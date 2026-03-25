package com.example.voicenotes.work

import com.example.voicenotes.data.model.NoteCard
import com.example.voicenotes.data.remote.NoteJobSnapshot
import com.example.voicenotes.data.remote.UploadNoteRequest
import com.example.voicenotes.data.remote.VoiceNotesApi
import com.example.voicenotes.data.repo.UploadQueueRepository
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import retrofit2.HttpException

class UploadQueueProcessor(
    private val uploadQueueRepository: UploadQueueRepository,
    private val api: VoiceNotesApi,
    private val pollIntervalMillis: Long = 500L,
    private val maxPollAttempts: Int = 60,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun processAllPendingCards(): Boolean {
        val skippedRetryableCardIds = mutableSetOf<String>()
        var sawRetryableFailure = false

        while (true) {
            val card = uploadQueueRepository.nextQueuedCard(skippedRetryableCardIds) ?: return sawRetryableFailure
            when (processCard(card)) {
                CardProcessingOutcome.Completed,
                CardProcessingOutcome.TerminalFailure -> Unit

                CardProcessingOutcome.RetryableFailure -> {
                    sawRetryableFailure = true
                    skippedRetryableCardIds += card.id
                }
            }
        }
    }

    private suspend fun processCard(card: NoteCard): CardProcessingOutcome {
        val uploadRequest = if (card.remoteJobId == null) {
            try {
                card.toUploadRequest()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException || throwable is InterruptedException) {
                    throw throwable
                }
                markFailed(
                    card = card,
                    errorMessage = throwable.message ?: "Upload failed",
                )
                return CardProcessingOutcome.TerminalFailure
            }
        } else {
            null
        }

        return try {
            val jobId = card.remoteJobId ?: submitCard(card, uploadRequest!!)
            when (val pollResult = pollUntilFinished(jobId)) {
                is PollResult.Finished -> {
                    when (pollResult.snapshot.status.lowercase()) {
                        "completed" -> markCompletedAndCleanup(card, pollResult.snapshot)
                        "failed" -> markFailed(
                            card = card,
                            errorMessage = pollResult.snapshot.errorMessage ?: "Upload failed",
                        )
                        else -> markFailed(
                            card = card,
                            errorMessage = "Unexpected job status: ${pollResult.snapshot.status}",
                        )
                    }
                    CardProcessingOutcome.Completed
                }

                PollResult.TimedOut -> CardProcessingOutcome.RetryableFailure
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException || throwable is InterruptedException) {
                throw throwable
            }
            if (throwable.isRetryableUploadFailure()) {
                CardProcessingOutcome.RetryableFailure
            } else {
                markFailed(
                    card = card,
                    errorMessage = throwable.message ?: "Upload failed",
                )
                CardProcessingOutcome.TerminalFailure
            }
        }
    }

    private suspend fun submitCard(
        card: NoteCard,
        request: UploadNoteRequest,
    ): String {
        val submitted = api.createNoteJob(request)
        uploadQueueRepository.markUploadStarted(card.id, submitted.jobId)
        return submitted.jobId
    }

    private suspend fun markCompletedAndCleanup(
        card: NoteCard,
        snapshot: NoteJobSnapshot,
    ) {
        uploadQueueRepository.markCompleted(card.id, snapshot)
        cleanupLocalAudio(card.audioLocalPath)
    }

    private suspend fun markFailed(
        card: NoteCard,
        errorMessage: String,
    ) {
        uploadQueueRepository.markFailed(card.id, errorMessage)
    }

    private suspend fun pollUntilFinished(jobId: String): PollResult {
        repeat(maxPollAttempts) { attempt ->
            val snapshot = api.getNoteJob(jobId)
            when (snapshot.status.lowercase()) {
                "processing" -> {
                    if (attempt < maxPollAttempts - 1) {
                        sleeper(pollIntervalMillis)
                    } else {
                        return PollResult.TimedOut
                    }
                }
                else -> return PollResult.Finished(snapshot)
            }
        }
        return PollResult.TimedOut
    }

    private fun NoteCard.toUploadRequest(): UploadNoteRequest {
        val audioFile = File(audioLocalPath)
        return UploadNoteRequest(
            cardId = id,
            fileName = audioFile.name.ifBlank { "$id.m4a" },
            mimeType = "audio/mp4",
            audioBytes = audioFile.readBytes(),
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    private fun cleanupLocalAudio(audioLocalPath: String) {
        if (audioLocalPath.isBlank()) {
            return
        }
        File(audioLocalPath).delete()
    }

    private fun Throwable.isRetryableUploadFailure(): Boolean {
        return when (this) {
            is HttpException -> code() == 408 || code() == 425 || code() == 429 || code() in 500..599
            is IOException -> true
            else -> false
        }
    }

    private sealed interface PollResult {
        data class Finished(val snapshot: NoteJobSnapshot) : PollResult
        data object TimedOut : PollResult
    }

    private enum class CardProcessingOutcome {
        Completed,
        RetryableFailure,
        TerminalFailure,
    }
}
