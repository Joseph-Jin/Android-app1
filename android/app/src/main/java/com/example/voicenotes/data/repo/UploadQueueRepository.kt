package com.example.voicenotes.data.repo

import com.example.voicenotes.data.model.NoteCard
import com.example.voicenotes.data.model.NoteStatus
import com.example.voicenotes.data.remote.NoteJobSnapshot
import com.example.voicenotes.work.UploadWorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

sealed interface RetryFailedCardResult {
    data object Enqueued : RetryFailedCardResult
    data object MissingAudio : RetryFailedCardResult
    data object CardNotFound : RetryFailedCardResult
}

class UploadQueueRepository(
    private val noteCardsRepository: NoteCardsRepository,
    private val uploadWorkScheduler: UploadWorkScheduler,
) {
    fun observeQueuedCards(): Flow<List<NoteCard>> {
        return noteCardsRepository.observeCards().map { cards ->
            cards.filter { card ->
                card.status == NoteStatus.PROCESSING
            }
        }
    }

    suspend fun createPendingCardAndEnqueue(audioPath: String): NoteCard {
        val card = noteCardsRepository.createPendingCard(audioPath)
        uploadWorkScheduler.enqueueUploadQueue()
        return card
    }

    suspend fun reconcileProcessingCards() {
        if (
            noteCardsRepository.hasProcessingCards() &&
            !uploadWorkScheduler.hasRunningUploadQueueWork()
        ) {
            uploadWorkScheduler.enqueueUploadQueue(replaceExisting = true)
        }
    }

    suspend fun nextQueuedCard(excludedCardIds: Set<String> = emptySet()): NoteCard? {
        return noteCardsRepository.nextQueuedCard(excludedCardIds)
    }

    suspend fun markUploadStarted(
        cardId: String,
        remoteJobId: String,
    ) {
        noteCardsRepository.attachRemoteJobId(cardId, remoteJobId)
    }

    suspend fun markCompleted(
        cardId: String,
        result: NoteJobSnapshot,
    ) {
        noteCardsRepository.applyCompletedResult(cardId, result)
    }

    suspend fun markFailed(
        cardId: String,
        errorMessage: String,
    ) {
        noteCardsRepository.applyFailedResult(cardId, errorMessage)
    }

    suspend fun retryFailedCardAndEnqueue(cardId: String): RetryFailedCardResult {
        val card = noteCardsRepository.getCard(cardId) ?: return RetryFailedCardResult.CardNotFound
        if (card.audioLocalPath.isBlank() || !File(card.audioLocalPath).exists()) {
            noteCardsRepository.applyFailedResult(cardId, "本地录音已丢失，无法重试")
            return RetryFailedCardResult.MissingAudio
        }

        noteCardsRepository.retryFailedCard(cardId)
        uploadWorkScheduler.enqueueUploadQueue()
        return RetryFailedCardResult.Enqueued
    }
}
