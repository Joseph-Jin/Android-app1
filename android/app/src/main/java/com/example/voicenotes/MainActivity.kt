package com.example.voicenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.example.voicenotes.ui.home.HomeScreen
import com.example.voicenotes.ui.home.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as VoiceNotesApplication).appContainer
        val homeViewModel = ViewModelProvider(
            this,
            HomeViewModel.Factory(
                noteCardsRepository = appContainer.noteCardsRepository,
                uploadQueueRepository = appContainer.uploadQueueRepository,
            ),
        )[HomeViewModel::class.java]

        setContent {
            HomeScreen(viewModel = homeViewModel)
        }
    }
}
