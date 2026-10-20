package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.CatchViewModel
import com.example.ui.SongHistoryScreen
import com.example.ui.theme.CatchSongBoneTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CatchViewModel by viewModels {
        CatchViewModel.provideFactory(application as CatchApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            CatchSongBoneTheme(themeMode = uiState.preferences.themeMode) {
                SongHistoryScreen(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETUP_WIZARD, false) == true) {
            viewModel.setSetupCardExpanded(true)
        }
    }

    companion object {
        const val EXTRA_OPEN_SETUP_WIZARD = "extra_open_setup_wizard"
    }
}
