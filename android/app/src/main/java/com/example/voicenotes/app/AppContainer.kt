package com.example.voicenotes.app

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.voicenotes.BuildConfig
import com.example.voicenotes.data.local.AppDatabase
import com.example.voicenotes.data.remote.RetrofitVoiceNotesApi
import com.example.voicenotes.data.remote.VoiceNotesApi
import com.example.voicenotes.data.repo.NoteCardsRepository
import com.example.voicenotes.data.repo.UploadQueueRepository
import com.example.voicenotes.work.UploadQueueProcessor
import com.example.voicenotes.work.UploadNoteWorker
import com.example.voicenotes.work.UploadWorkScheduler
import com.example.voicenotes.work.WorkManagerUploadWorkScheduler

class AppContainer(
    context: Context,
    private val voiceNotesApiBaseUrl: String = BuildConfig.VOICE_NOTES_API_BASE_URL,
) {
    val applicationContext: Context = context.applicationContext
    val appDatabase: AppDatabase by lazy { AppDatabase.getInstance(applicationContext) }
    val noteCardsRepository: NoteCardsRepository by lazy {
        NoteCardsRepository(appDatabase.noteCardDao())
    }
    val uploadWorkScheduler: UploadWorkScheduler by lazy {
        WorkManagerUploadWorkScheduler(applicationContext)
    }
    val uploadQueueRepository: UploadQueueRepository by lazy {
        UploadQueueRepository(noteCardsRepository, uploadWorkScheduler)
    }
    val voiceNotesApi: VoiceNotesApi by lazy { RetrofitVoiceNotesApi(voiceNotesApiBaseUrl) }
    val workerFactory: WorkerFactory by lazy { AppWorkerFactory(this) }
    val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }
}

private class AppWorkerFactory(
    private val appContainer: AppContainer,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return when (workerClassName) {
            UploadNoteWorker::class.java.name -> UploadNoteWorker(
                appContext = appContext,
                params = workerParameters,
                processor = UploadQueueProcessor(
                    uploadQueueRepository = appContainer.uploadQueueRepository,
                    api = appContainer.voiceNotesApi,
                ),
            )
            else -> null
        }
    }
}
