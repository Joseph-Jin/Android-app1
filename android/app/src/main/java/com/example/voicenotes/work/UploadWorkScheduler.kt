package com.example.voicenotes.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface UploadWorkScheduler {
    fun enqueueUploadQueue(replaceExisting: Boolean = false)

    suspend fun hasRunningUploadQueueWork(): Boolean
}

class WorkManagerUploadWorkScheduler(
    context: Context,
) : UploadWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueueUploadQueue(replaceExisting: Boolean) {
        val request = OneTimeWorkRequestBuilder<UploadNoteWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            )
            .addTag(UNIQUE_WORK_NAME)
            .build()
        val policy = if (replaceExisting) {
            ExistingWorkPolicy.REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        }
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
    }

    override suspend fun hasRunningUploadQueueWork(): Boolean {
        return workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
            .get()
            .any { it.state == WorkInfo.State.RUNNING }
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "upload-note-queue"
    }
}
