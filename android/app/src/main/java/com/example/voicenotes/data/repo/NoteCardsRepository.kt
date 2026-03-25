package com.example.voicenotes.data.repo

import com.example.voicenotes.data.local.NoteCardDao
import com.example.voicenotes.data.local.NoteCardEntity
import com.example.voicenotes.data.model.NoteCard
import com.example.voicenotes.data.model.NoteStatus
import com.example.voicenotes.data.model.SentenceSlice
import com.example.voicenotes.data.model.TokenPhrase
import com.example.voicenotes.data.remote.NoteJobSnapshot
import com.example.voicenotes.data.remote.ReplacementSuggestionDto
import com.example.voicenotes.data.remote.SentenceSliceDto
import com.example.voicenotes.data.remote.TokenPhraseDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

class NoteCardsRepository(
    private val noteCardDao: NoteCardDao,
    private val gson: Gson = Gson(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sentenceListType = object : TypeToken<List<SentenceSlice>>() {}.type

    fun observeCards(): Flow<List<NoteCard>> {
        return noteCardDao.observeCards().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun createPendingCard(audioPath: String): NoteCard {
        val entity = NoteCardEntity(
            id = UUID.randomUUID().toString(),
            createdAtEpochMillis = clock(),
            status = NoteStatus.PROCESSING.value,
            audioLocalPath = audioPath,
        )
        noteCardDao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun hasProcessingCards(): Boolean {
        return noteCardDao.getNextProcessingCard(NoteStatus.PROCESSING.value) != null
    }

    suspend fun applyCompletedResult(
        cardId: String,
        cleanText: String,
        sentences: List<String>,
    ) {
        applyCompletedResult(
            cardId = cardId,
            result = NoteJobSnapshot(
                jobId = cardId,
                status = NoteStatus.COMPLETED.value,
                cleanText = cleanText,
                sentences = sentences.map { sentenceText ->
                    SentenceSliceDto(
                        id = UUID.randomUUID().toString(),
                        text = sentenceText,
                    )
                },
            )
        )
    }

    suspend fun applyCompletedResult(
        cardId: String,
        result: NoteJobSnapshot,
    ) {
        val current = requireNotNull(noteCardDao.getById(cardId)) { "Card not found: $cardId" }
        noteCardDao.upsert(
            current.copy(
                status = NoteStatus.COMPLETED.value,
                audioLocalPath = "",
                rawTranscript = result.rawTranscript ?: current.rawTranscript,
                cleanText = result.cleanText,
                sentencesJson = serializeSentences(result.sentences.map { it.toDomain() }),
                copyText = result.cleanText ?: result.rawTranscript ?: current.copyText,
                errorMessage = null,
            )
        )
    }

    suspend fun applyFailedResult(
        cardId: String,
        errorMessage: String,
    ) {
        val current = requireNotNull(noteCardDao.getById(cardId)) { "Card not found: $cardId" }
        noteCardDao.upsert(
            current.copy(
                status = NoteStatus.FAILED.value,
                errorMessage = errorMessage,
            )
        )
    }

    suspend fun retryFailedCard(cardId: String) {
        val current = requireNotNull(noteCardDao.getById(cardId)) { "Card not found: $cardId" }
        noteCardDao.upsert(
            current.copy(
                status = NoteStatus.PROCESSING.value,
                errorMessage = null,
                remoteJobId = null,
            )
        )
    }

    suspend fun getCard(cardId: String): NoteCard? {
        return noteCardDao.getById(cardId)?.toDomain()
    }

    suspend fun attachRemoteJobId(
        cardId: String,
        remoteJobId: String,
    ) {
        val current = requireNotNull(noteCardDao.getById(cardId)) { "Card not found: $cardId" }
        noteCardDao.upsert(
            current.copy(
                remoteJobId = remoteJobId,
            )
        )
    }

    suspend fun persistCardEdits(
        cardId: String,
        sentences: List<SentenceSlice>,
        copyText: String,
    ) {
        val current = requireNotNull(noteCardDao.getById(cardId)) { "Card not found: $cardId" }
        noteCardDao.upsert(
            current.copy(
                sentencesJson = serializeSentences(sentences),
                copyText = copyText,
            )
        )
    }

    suspend fun nextQueuedCard(excludedCardIds: Set<String> = emptySet()): NoteCard? {
        return noteCardDao.observeCards()
            .first()
            .asSequence()
            .map { it.toDomain() }
            .sortedBy { it.createdAtEpochMillis }
            .firstOrNull { card ->
                card.status == NoteStatus.PROCESSING && card.id !in excludedCardIds
            }
    }

    suspend fun clearAllCards() {
        val audioPaths = noteCardDao.observeCards()
            .first()
            .map(NoteCardEntity::audioLocalPath)
            .filter(String::isNotBlank)
            .distinct()

        audioPaths.forEach { path ->
            File(path).delete()
        }
        noteCardDao.deleteAll()
    }

    private fun NoteCardEntity.toDomain(): NoteCard {
        return NoteCard(
            id = id,
            createdAtEpochMillis = createdAtEpochMillis,
            status = NoteStatus.fromValue(status),
            audioLocalPath = audioLocalPath,
            rawTranscript = rawTranscript,
            cleanText = cleanText,
            sentences = deserializeSentences(sentencesJson),
            copyText = copyText ?: cleanText ?: rawTranscript,
            errorMessage = errorMessage,
            remoteJobId = remoteJobId,
        )
    }

    private fun SentenceSliceDto.toDomain(): SentenceSlice {
        return SentenceSlice(
            id = id,
            text = text,
            tokens = tokens.map { it.toDomain() },
            rawSourceSpan = rawSourceSpan,
        )
    }

    private fun TokenPhraseDto.toDomain(): TokenPhrase {
        return TokenPhrase(
            id = id,
            text = text,
            startIndex = startIndex,
            endIndex = endIndex,
            isAsrSuspicious = isAsrSuspicious,
            isEdited = false,
            suggestions = suggestions.map { it.toDomain() },
        )
    }

    private fun ReplacementSuggestionDto.toDomain(): com.example.voicenotes.data.model.ReplacementSuggestion {
        return com.example.voicenotes.data.model.ReplacementSuggestion(
            value = value,
            reason = reason,
        )
    }

    private fun serializeSentences(sentences: List<SentenceSlice>): String {
        return gson.toJson(sentences, sentenceListType)
    }

    private fun deserializeSentences(json: String): List<SentenceSlice> {
        if (json.isBlank()) {
            return emptyList()
        }
        return gson.fromJson(json, sentenceListType)
    }
}
