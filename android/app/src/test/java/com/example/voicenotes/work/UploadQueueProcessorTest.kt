package com.example.voicenotes.work

import com.example.voicenotes.data.local.NoteCardDao
import com.example.voicenotes.data.local.NoteCardEntity
import com.example.voicenotes.data.remote.NoteJobSnapshot
import com.example.voicenotes.data.remote.SentenceSliceDto
import com.example.voicenotes.data.remote.UploadNoteRequest
import com.example.voicenotes.data.remote.UploadNoteResponse
import com.example.voicenotes.data.remote.VoiceNotesApi
import com.example.voicenotes.data.repo.NoteCardsRepository
import com.example.voicenotes.data.repo.UploadQueueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File

class UploadQueueProcessorTest {

    @Test
    fun resumesPollingWhenRemoteJobIdAlreadyExists() = runTest {
        val dao = UploadQueueProcessorFakeNoteCardDao(
            initialCards = listOf(
                NoteCardEntity(
                    id = "card-1",
                    createdAtEpochMillis = 1L,
                    status = "processing",
                    audioLocalPath = "/tmp/a.m4a",
                    remoteJobId = "job-123",
                )
            )
        )
        val repository = NoteCardsRepository(dao)
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = repository,
            uploadWorkScheduler = object : UploadWorkScheduler {
                override fun enqueueUploadQueue(replaceExisting: Boolean) = Unit

                override suspend fun hasRunningUploadQueueWork(): Boolean = false
            }
        )
        val api = FakeVoiceNotesApi()
        val processor = UploadQueueProcessor(
            uploadQueueRepository = uploadQueueRepository,
            api = api,
            pollIntervalMillis = 0L,
            sleeper = {},
        )

        processor.processAllPendingCards()

        assertEquals(0, api.createCalls)
        assertEquals(listOf("job-123"), api.pollRequests)
        val updated = repository.observeCards().first().first()
        assertEquals("completed", updated.status.value)
        assertEquals("finished", updated.cleanText)
    }

    @Test
    fun transientHttpFailureLeavesCardProcessingAndContinuesQueue() = runTest {
        val dao = UploadQueueProcessorFakeNoteCardDao(
            initialCards = listOf(
                NoteCardEntity(
                    id = "card-1",
                    createdAtEpochMillis = 1L,
                    status = "processing",
                    audioLocalPath = tempAudioPath("transient-card-1"),
                ),
                NoteCardEntity(
                    id = "card-2",
                    createdAtEpochMillis = 2L,
                    status = "processing",
                    audioLocalPath = tempAudioPath("transient-card-2"),
                ),
            )
        )
        val repository = NoteCardsRepository(dao)
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = repository,
            uploadWorkScheduler = object : UploadWorkScheduler {
                override fun enqueueUploadQueue(replaceExisting: Boolean) = Unit

                override suspend fun hasRunningUploadQueueWork(): Boolean = false
            }
        )
        val api = object : VoiceNotesApi {
            override suspend fun createNoteJob(request: UploadNoteRequest): UploadNoteResponse {
                return when (request.cardId) {
                    "card-1" -> throw HttpException(
                        Response.error<Any>(
                            503,
                            "service unavailable".toResponseBody("text/plain".toMediaType()),
                        )
                    )

                    "card-2" -> UploadNoteResponse(jobId = "job-2")
                    else -> error("unexpected card id: ${request.cardId}")
                }
            }

            override suspend fun getNoteJob(jobId: String): NoteJobSnapshot {
                return completedSnapshot(jobId)
            }
        }
        val processor = UploadQueueProcessor(
            uploadQueueRepository = uploadQueueRepository,
            api = api,
            pollIntervalMillis = 0L,
            sleeper = {},
        )

        val sawRetryableFailure = processor.processAllPendingCards()

        val cards = repository.observeCards().first()
        val firstCard = cards.first { it.id == "card-1" }
        val secondCard = cards.first { it.id == "card-2" }
        assertTrue(sawRetryableFailure)
        assertEquals("processing", firstCard.status.value)
        assertNull(firstCard.errorMessage)
        assertEquals("completed", secondCard.status.value)
        assertNull(firstCard.remoteJobId)
        assertEquals("job-2", secondCard.remoteJobId)
    }

    @Test
    fun neverFinishingRemoteJobDoesNotBlockLaterCards() = runTest {
        val dao = UploadQueueProcessorFakeNoteCardDao(
            initialCards = listOf(
                NoteCardEntity(
                    id = "card-1",
                    createdAtEpochMillis = 1L,
                    status = "processing",
                    audioLocalPath = "/tmp/a.m4a",
                    remoteJobId = "job-1",
                ),
                NoteCardEntity(
                    id = "card-2",
                    createdAtEpochMillis = 2L,
                    status = "processing",
                    audioLocalPath = tempAudioPath("stuck-card-2"),
                ),
            )
        )
        val repository = NoteCardsRepository(dao)
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = repository,
            uploadWorkScheduler = object : UploadWorkScheduler {
                override fun enqueueUploadQueue(replaceExisting: Boolean) = Unit

                override suspend fun hasRunningUploadQueueWork(): Boolean = false
            }
        )
        val api = object : VoiceNotesApi {
            override suspend fun createNoteJob(request: UploadNoteRequest): UploadNoteResponse {
                return UploadNoteResponse(jobId = "job-2")
            }

            override suspend fun getNoteJob(jobId: String): NoteJobSnapshot {
                return when (jobId) {
                    "job-1" -> processingSnapshot(jobId)
                    "job-2" -> completedSnapshot(jobId)
                    else -> error("unexpected job id: $jobId")
                }
            }
        }
        val processor = UploadQueueProcessor(
            uploadQueueRepository = uploadQueueRepository,
            api = api,
            pollIntervalMillis = 0L,
            sleeper = {},
        )

        withTimeout(1_000L) {
            processor.processAllPendingCards()
        }

        val cards = repository.observeCards().first()
        val firstCard = cards.first { it.id == "card-1" }
        val secondCard = cards.first { it.id == "card-2" }
        assertEquals("processing", firstCard.status.value)
        assertNull(firstCard.errorMessage)
        assertEquals("completed", secondCard.status.value)
        assertEquals("job-1", firstCard.remoteJobId)
        assertEquals("job-2", secondCard.remoteJobId)
    }

    @Test
    fun defaultPollingWindow_handlesRealBackendLatencyBeforeRetrying() = runTest {
        val dao = UploadQueueProcessorFakeNoteCardDao(
            initialCards = listOf(
                NoteCardEntity(
                    id = "card-1",
                    createdAtEpochMillis = 1L,
                    status = "processing",
                    audioLocalPath = tempAudioPath("slow-backend-card"),
                )
            )
        )
        val repository = NoteCardsRepository(dao)
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = repository,
            uploadWorkScheduler = object : UploadWorkScheduler {
                override fun enqueueUploadQueue(replaceExisting: Boolean) = Unit

                override suspend fun hasRunningUploadQueueWork(): Boolean = false
            }
        )
        var pollCount = 0
        val api = object : VoiceNotesApi {
            override suspend fun createNoteJob(request: UploadNoteRequest): UploadNoteResponse {
                return UploadNoteResponse(jobId = "job-1")
            }

            override suspend fun getNoteJob(jobId: String): NoteJobSnapshot {
                pollCount += 1
                return if (pollCount < 25) {
                    processingSnapshot(jobId)
                } else {
                    completedSnapshot(jobId)
                }
            }
        }
        val processor = UploadQueueProcessor(
            uploadQueueRepository = uploadQueueRepository,
            api = api,
            pollIntervalMillis = 0L,
            sleeper = {},
        )

        val sawRetryableFailure = processor.processAllPendingCards()

        val updated = repository.observeCards().first().first()
        assertFalse(sawRetryableFailure)
        assertEquals("completed", updated.status.value)
        assertEquals("finished", updated.cleanText)
    }

    @Test
    fun cancellationDoesNotMarkCardFailed() = runTest {
        val dao = UploadQueueProcessorFakeNoteCardDao(
            initialCards = listOf(
                NoteCardEntity(
                    id = "card-1",
                    createdAtEpochMillis = 1L,
                    status = "processing",
                    audioLocalPath = "/tmp/a.m4a",
                    remoteJobId = "job-123",
                )
            )
        )
        val repository = NoteCardsRepository(dao)
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = repository,
            uploadWorkScheduler = object : UploadWorkScheduler {
                override fun enqueueUploadQueue(replaceExisting: Boolean) = Unit

                override suspend fun hasRunningUploadQueueWork(): Boolean = false
            }
        )
        val api = object : VoiceNotesApi {
            override suspend fun createNoteJob(request: UploadNoteRequest) =
                error("should not upload again")

            override suspend fun getNoteJob(jobId: String): NoteJobSnapshot {
                throw CancellationException("stopped")
            }
        }
        val processor = UploadQueueProcessor(
            uploadQueueRepository = uploadQueueRepository,
            api = api,
            pollIntervalMillis = 0L,
            sleeper = {},
        )

        try {
            processor.processAllPendingCards()
            fail("expected cancellation to escape")
        } catch (expected: CancellationException) {
            // Expected: WorkManager should be able to retry/resume the work.
        }

        val updated = repository.observeCards().first().first()
        assertEquals("processing", updated.status.value)
        assertEquals("job-123", updated.remoteJobId)
    }

    @Test
    fun completedUpload_deletesLocalAudioAndClearsStoredPath() = runTest {
        val audioPath = tempAudioPath("cleanup-completed")
        val dao = UploadQueueProcessorFakeNoteCardDao(
            initialCards = listOf(
                NoteCardEntity(
                    id = "card-1",
                    createdAtEpochMillis = 1L,
                    status = "processing",
                    audioLocalPath = audioPath,
                )
            )
        )
        val repository = NoteCardsRepository(dao)
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = repository,
            uploadWorkScheduler = object : UploadWorkScheduler {
                override fun enqueueUploadQueue(replaceExisting: Boolean) = Unit

                override suspend fun hasRunningUploadQueueWork(): Boolean = false
            }
        )
        val api = object : VoiceNotesApi {
            override suspend fun createNoteJob(request: UploadNoteRequest): UploadNoteResponse {
                return UploadNoteResponse(jobId = "job-1")
            }

            override suspend fun getNoteJob(jobId: String): NoteJobSnapshot {
                return completedSnapshot(jobId)
            }
        }
        val processor = UploadQueueProcessor(
            uploadQueueRepository = uploadQueueRepository,
            api = api,
            pollIntervalMillis = 0L,
            sleeper = {},
        )

        processor.processAllPendingCards()

        val updated = repository.observeCards().first().first()
        assertEquals("completed", updated.status.value)
        assertEquals("", updated.audioLocalPath)
        assertFalse(File(audioPath).exists())
    }

    @Test
    fun terminalFailure_keepsLocalAudioForManualRetry() = runTest {
        val audioPath = tempAudioPath("cleanup-failed")
        val dao = UploadQueueProcessorFakeNoteCardDao(
            initialCards = listOf(
                NoteCardEntity(
                    id = "card-1",
                    createdAtEpochMillis = 1L,
                    status = "processing",
                    audioLocalPath = audioPath,
                )
            )
        )
        val repository = NoteCardsRepository(dao)
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = repository,
            uploadWorkScheduler = object : UploadWorkScheduler {
                override fun enqueueUploadQueue(replaceExisting: Boolean) = Unit

                override suspend fun hasRunningUploadQueueWork(): Boolean = false
            }
        )
        val api = object : VoiceNotesApi {
            override suspend fun createNoteJob(request: UploadNoteRequest): UploadNoteResponse {
                throw IllegalStateException("bad request")
            }

            override suspend fun getNoteJob(jobId: String): NoteJobSnapshot {
                error("should not poll after terminal failure")
            }
        }
        val processor = UploadQueueProcessor(
            uploadQueueRepository = uploadQueueRepository,
            api = api,
            pollIntervalMillis = 0L,
            sleeper = {},
        )

        processor.processAllPendingCards()

        val updated = repository.observeCards().first().first()
        assertEquals("failed", updated.status.value)
        assertEquals(audioPath, updated.audioLocalPath)
        assertEquals("bad request", updated.errorMessage)
        assertTrue(File(audioPath).exists())
    }
}

private fun completedSnapshot(jobId: String): NoteJobSnapshot {
    return NoteJobSnapshot(
        jobId = jobId,
        status = "completed",
        cleanText = "finished",
        rawTranscript = "original",
        sentences = listOf(
            SentenceSliceDto(
                id = "sentence-1",
                text = "finished",
            )
        ),
    )
}

private fun processingSnapshot(jobId: String): NoteJobSnapshot {
    return NoteJobSnapshot(
        jobId = jobId,
        status = "processing",
        rawTranscript = "original",
    )
}

private fun tempAudioPath(prefix: String): String {
    val file = File.createTempFile("voicenotes-$prefix-", ".m4a")
    file.writeBytes("fake-audio".toByteArray())
    file.deleteOnExit()
    return file.absolutePath
}

private class FakeVoiceNotesApi : VoiceNotesApi {
    var createCalls: Int = 0
    val pollRequests: MutableList<String> = mutableListOf()

    override suspend fun createNoteJob(request: UploadNoteRequest) =
        error("should not upload again")

    override suspend fun getNoteJob(jobId: String): NoteJobSnapshot {
        pollRequests += jobId
        return completedSnapshot(jobId)
    }
}

private class UploadQueueProcessorFakeNoteCardDao(
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
