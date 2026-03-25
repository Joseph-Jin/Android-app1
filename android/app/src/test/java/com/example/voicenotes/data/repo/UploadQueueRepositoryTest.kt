package com.example.voicenotes.data.repo

import com.example.voicenotes.data.local.NoteCardDao
import com.example.voicenotes.data.local.NoteCardEntity
import com.example.voicenotes.data.model.NoteStatus
import com.example.voicenotes.work.UploadWorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class UploadQueueRepositoryTest {

    @Test
    fun createPendingCardAndEnqueuesQueueWork() = runTest {
        val scheduler = FakeUploadWorkScheduler()
        val repository = UploadQueueRepository(
            noteCardsRepository = NoteCardsRepository(UploadQueueRepositoryFakeNoteCardDao()),
            uploadWorkScheduler = scheduler,
        )

        val card = repository.createPendingCardAndEnqueue("/tmp/a.m4a")

        assertEquals(1, scheduler.enqueueCalls)
        assertEquals(false, scheduler.replaceExistingCalls.single())
        assertEquals("/tmp/a.m4a", card.audioLocalPath)
    }

    @Test
    fun reconcileProcessingCardsReEnqueuesQueueWorkWhenProcessingCardsExist() = runTest {
        val scheduler = FakeUploadWorkScheduler()
        val repository = UploadQueueRepository(
            noteCardsRepository = NoteCardsRepository(
                UploadQueueRepositoryFakeNoteCardDao(
                    initialCards = listOf(
                        NoteCardEntity(
                            id = "card-1",
                            createdAtEpochMillis = 1L,
                            status = "processing",
                            audioLocalPath = "/tmp/a.m4a",
                        ),
                    )
                )
            ),
            uploadWorkScheduler = scheduler,
        )

        repository.reconcileProcessingCards()

        assertEquals(1, scheduler.enqueueCalls)
        assertEquals(true, scheduler.replaceExistingCalls.single())
    }

    @Test
    fun reconcileProcessingCardsSkipsWhenNothingIsProcessing() = runTest {
        val scheduler = FakeUploadWorkScheduler()
        val repository = UploadQueueRepository(
            noteCardsRepository = NoteCardsRepository(
                UploadQueueRepositoryFakeNoteCardDao(
                    initialCards = listOf(
                        NoteCardEntity(
                            id = "card-1",
                            createdAtEpochMillis = 1L,
                            status = "completed",
                            audioLocalPath = "/tmp/a.m4a",
                        ),
                    )
                )
            ),
            uploadWorkScheduler = scheduler,
        )

        repository.reconcileProcessingCards()

        assertEquals(0, scheduler.enqueueCalls)
        assertEquals(emptyList<Boolean>(), scheduler.replaceExistingCalls)
    }

    @Test
    fun reconcileProcessingCardsSkipsWhenQueueWorkIsAlreadyRunning() = runTest {
        val scheduler = FakeUploadWorkScheduler(running = true)
        val repository = UploadQueueRepository(
            noteCardsRepository = NoteCardsRepository(
                UploadQueueRepositoryFakeNoteCardDao(
                    initialCards = listOf(
                        NoteCardEntity(
                            id = "card-1",
                            createdAtEpochMillis = 1L,
                            status = "processing",
                            audioLocalPath = "/tmp/a.m4a",
                        ),
                    )
                )
            ),
            uploadWorkScheduler = scheduler,
        )

        repository.reconcileProcessingCards()

        assertEquals(0, scheduler.enqueueCalls)
        assertEquals(emptyList<Boolean>(), scheduler.replaceExistingCalls)
    }

    @Test
    fun retryFailedCardAndEnqueue_resetsFailedCardAndSchedulesWork() = runTest {
        val scheduler = FakeUploadWorkScheduler()
        val audioFile = File.createTempFile("retry-failed-card", ".m4a")
        val noteCardsRepository = NoteCardsRepository(
            UploadQueueRepositoryFakeNoteCardDao(
                initialCards = listOf(
                    NoteCardEntity(
                        id = "card-1",
                        createdAtEpochMillis = 1L,
                        status = "failed",
                        audioLocalPath = audioFile.absolutePath,
                        errorMessage = "network down",
                        remoteJobId = "job-123",
                    ),
                )
            )
        )
        val repository = UploadQueueRepository(
            noteCardsRepository = noteCardsRepository,
            uploadWorkScheduler = scheduler,
        )

        val result = repository.retryFailedCardAndEnqueue("card-1")
        val updated = noteCardsRepository.observeCards().first().first()

        assertEquals(RetryFailedCardResult.Enqueued, result)
        assertEquals(NoteStatus.PROCESSING, updated.status)
        assertEquals(audioFile.absolutePath, updated.audioLocalPath)
        assertNull(updated.errorMessage)
        assertNull(updated.remoteJobId)
        assertEquals(1, scheduler.enqueueCalls)
    }

    @Test
    fun retryFailedCardAndEnqueue_returnsMissingAudioWhenFileWasRemoved() = runTest {
        val scheduler = FakeUploadWorkScheduler()
        val noteCardsRepository = NoteCardsRepository(
            UploadQueueRepositoryFakeNoteCardDao(
                initialCards = listOf(
                    NoteCardEntity(
                        id = "card-1",
                        createdAtEpochMillis = 1L,
                        status = "failed",
                        audioLocalPath = "/tmp/definitely-missing-retry.m4a",
                        errorMessage = "network down",
                    ),
                )
            )
        )
        val repository = UploadQueueRepository(
            noteCardsRepository = noteCardsRepository,
            uploadWorkScheduler = scheduler,
        )

        val result = repository.retryFailedCardAndEnqueue("card-1")
        val updated = noteCardsRepository.observeCards().first().first()

        assertEquals(RetryFailedCardResult.MissingAudio, result)
        assertEquals(NoteStatus.FAILED, updated.status)
        assertEquals("本地录音已丢失，无法重试", updated.errorMessage)
        assertEquals(0, scheduler.enqueueCalls)
    }
}

private class FakeUploadWorkScheduler : UploadWorkScheduler {
    constructor(running: Boolean = false) {
        this.running = running
    }

    var enqueueCalls: Int = 0
    val replaceExistingCalls = mutableListOf<Boolean>()
    var running: Boolean = false

    override fun enqueueUploadQueue(replaceExisting: Boolean) {
        enqueueCalls += 1
        replaceExistingCalls += replaceExisting
    }

    override suspend fun hasRunningUploadQueueWork(): Boolean {
        return running
    }
}

private class UploadQueueRepositoryFakeNoteCardDao(
    initialCards: List<NoteCardEntity> = emptyList(),
) : NoteCardDao {
    private val cards = MutableStateFlow(initialCards)

    override fun observeCards(): Flow<List<NoteCardEntity>> = cards

    override suspend fun upsert(card: NoteCardEntity) {
        val updated = cards.value.toMutableList()
        val index = updated.indexOfFirst { it.id == card.id }
        if (index >= 0) {
            updated[index] = card
        } else {
            updated += card
        }
        cards.value = updated
    }

    override suspend fun getById(id: String): NoteCardEntity? {
        return cards.value.firstOrNull { it.id == id }
    }

    override suspend fun getNextProcessingCard(status: String): NoteCardEntity? {
        return cards.value.firstOrNull { it.status == status }
    }

    override suspend fun deleteAll() {
        cards.value = emptyList()
    }
}
