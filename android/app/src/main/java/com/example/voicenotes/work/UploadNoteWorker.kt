package com.example.voicenotes.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.CancellationException

class UploadNoteWorker(
    appContext: Context,
    params: WorkerParameters,
    private val processor: UploadQueueProcessor,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            resultForRetryableFailures(processor.processAllPendingCards())
        } catch (throwable: Throwable) {
            if (throwable is CancellationException || throwable is InterruptedException) {
                Result.retry()
            } else {
                throw throwable
            }
        }
    }

    internal companion object {
        fun resultForRetryableFailures(hasRetryableFailures: Boolean): Result {
            return if (hasRetryableFailures) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }
}
