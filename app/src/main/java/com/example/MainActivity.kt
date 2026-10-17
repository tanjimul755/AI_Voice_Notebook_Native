package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.VoiceNoteViewModel
import com.example.ui.VoiceNotebookScreen
import com.example.ui.theme.MyApplicationTheme

/**
 * Main application entry activity.
 * Configures edge-to-edge window insets and bootstraps the VoiceNotebookScreen.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: VoiceNoteViewModel by viewModels {
        VoiceNoteViewModel.provideFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                VoiceNotebookScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.example.reminder.ReminderRinger.stopRinging()
    }
}
