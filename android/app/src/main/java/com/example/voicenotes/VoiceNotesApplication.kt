package com.example.voicenotes

import android.app.Application
import androidx.work.Configuration
import com.example.voicenotes.app.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VoiceNotesApplication : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val appContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            appContainer.uploadQueueRepository.reconcileProcessingCards()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = appContainer.workManagerConfiguration
}
