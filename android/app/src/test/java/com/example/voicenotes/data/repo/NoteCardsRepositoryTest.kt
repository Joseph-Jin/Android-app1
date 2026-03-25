package com.example.voicenotes.data.repo

import com.example.voicenotes.data.local.NoteCardDao
import com.example.voicenotes.data.local.NoteCardEntity
import com.example.voicenotes.data.model.NoteStatus
import com.example.voicenotes.data.model.SentenceSlice
import com.example.voicenotes.data.model.TokenPhrase
import com.example.voicenotes.data.remote.NoteJobSnapshot
import com.example.voicenotes.data.remote.ReplacementSuggestionDto
import com.example.voicenotes.data.remote.SentenceSliceDto
import com.example.voicenotes.data.remote.TokenPhraseDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteCardsRepositoryTest {

    @Test
    fun createPendingCard_marksCardProcessingImmediately() = runTest {
        val repository = buildRepository()

        val card = repository.createPendingCard(audioPath = "/tmp/a.m4a")

        assertEquals(NoteStatus.PROCESSING, card.status)
    }

    @Test
    fun applyCompletedResult_updatesCleanTextAndSentences() = runTest {
        val repository = buildRepository()
        val card = repository.createPendingCard("/tmp/a.m4a")

        repository.applyCompletedResult(
            card.id,
            NoteJobSnapshot(
                jobId = "job-1",
                status = NoteStatus.COMPLETED.value,
                rawTranscript = "我们准备接入 Vibe Coding 做原型。",
                cleanText = "我们准备接入 Vibe Coding 做原型。",
                sentences = listOf(
                    SentenceSliceDto(
                        id = "sentence-1",
                        text = "我们准备接入 Vibe Coding 做原型。",
                        rawSourceSpan = "我们准备接入 Vibe Coding 做原型。",
                        tokens = listOf(
                            TokenPhraseDto(
                                id = "token-1",
                                text = "Vibe Coding",
                                startIndex = 6,
                                endIndex = 17,
                                isAsrSuspicious = true,
                                suggestions = listOf(
                                    ReplacementSuggestionDto(
                                        value = "Vibe Coding",
                                        reason = "brand name",
                                    )
                                ),
                            )
                        ),
                    )
                ),
            )
        )

        val updated = repository.observeCards().first().first()
        assertEquals("completed", updated.status.value)
        assertEquals("我们准备接入 Vibe Coding 做原型。", updated.cleanText)
        assertEquals("", updated.audioLocalPath)
        assertEquals(1, updated.sentences.size)
        assertEquals("Vibe Coding", updated.sentences.first().tokens.first().text)
        assertEquals("brand name", updated.sentences.first().tokens.first().suggestions.first().reason)
    }

    @Test
    fun applyFailedResult_keepsAudioPathForManualRetry() = runTest {
        val repository = buildRepository()
        val card = repository.createPendingCard("/tmp/a.m4a")

        repository.applyFailedResult(card.id, "network down")

        val updated = repository.observeCards().first().first()
        assertEquals("failed", updated.status.value)
        assertEquals("/tmp/a.m4a", updated.audioLocalPath)
        assertEquals("network down", updated.errorMessage)
    }

    @Test
    fun persistCardEdits_storesEditedSentencesAndCopyTextForFutureReloads() = runTest {
        val repository = buildRepository()
        val card = repository.createPendingCard("/tmp/a.m4a")
        repository.applyCompletedResult(
            card.id,
            NoteJobSnapshot(
                jobId = "job-1",
                status = NoteStatus.COMPLETED.value,
                rawTranscript = "今天我们主要聊 Web coding。",
                cleanText = "今天我们主要聊 Web coding。",
                sentences = listOf(
                    SentenceSliceDto(
                        id = "sentence-1",
                        text = "今天我们主要聊 Web coding。",
                        tokens = listOf(
                            TokenPhraseDto(
                                id = "token-1",
                                text = "今天",
                                startIndex = 0,
                                endIndex = 2,
                            ),
                            TokenPhraseDto(
                                id = "token-2",
                                text = "Web coding",
                                startIndex = 7,
                                endIndex = 17,
                                isAsrSuspicious = true,
                                suggestions = listOf(
                                    ReplacementSuggestionDto(
                                        value = "Vibe Coding",
                                        reason = "brand name",
                                    )
                                ),
                            ),
                        ),
                    )
                ),
            )
        )

        repository.persistCardEdits(
            cardId = card.id,
            sentences = listOf(
                SentenceSlice(
                    id = "sentence-1",
                    text = "今天我们主要聊 Vibe Coding。",
                    tokens = listOf(
                        TokenPhrase(
                            id = "token-1",
                            text = "今天",
                            startIndex = 0,
                            endIndex = 2,
                        ),
                        TokenPhrase(
                            id = "token-2",
                            text = "Vibe Coding",
                            startIndex = 8,
                            endIndex = 19,
                            isEdited = true,
                        ),
                    ),
                )
            ),
            copyText = "今天我们主要聊 Vibe Coding。",
        )

        val updated = repository.observeCards().first().first()
        assertEquals("今天我们主要聊 Vibe Coding。", updated.copyText)
        assertEquals("今天我们主要聊 Vibe Coding。", updated.sentences.first().text)
        assertEquals("Vibe Coding", updated.sentences.first().tokens.last().text)
        assertEquals(true, updated.sentences.first().tokens.last().isEdited)
    }

    private fun buildRepository(): NoteCardsRepository {
        return NoteCardsRepository(FakeNoteCardDao())
    }
}

private class FakeNoteCardDao : NoteCardDao {
    private val cards = MutableStateFlow<List<NoteCardEntity>>(emptyList())

    override fun observeCards(): Flow<List<NoteCardEntity>> = cards

    override suspend fun upsert(card: NoteCardEntity) {
        val updated = cards.value.toMutableList()
        val index = updated.indexOfFirst { it.id == card.id }
        if (index >= 0) {
            updated[index] = card
        } else {
            updated += card
        }
        cards.value = updated.sortedByDescending { it.createdAtEpochMillis }
    }

    override suspend fun getById(id: String): NoteCardEntity? {
        return cards.value.firstOrNull { it.id == id }
    }

    override suspend fun getNextProcessingCard(status: String): NoteCardEntity? {
        return cards.value.firstOrNull { it.status == status && it.remoteJobId == null }
    }

    override suspend fun deleteAll() {
        cards.value = emptyList()
    }
}
