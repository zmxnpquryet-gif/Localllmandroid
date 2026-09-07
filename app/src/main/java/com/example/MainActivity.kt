package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.ui.MainViewModel
import com.example.ui.chat.ChatScreen
import com.example.ui.models.ModelManagerScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.LocalLlmTheme
import com.example.ui.voice.VoiceModeScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsState()
            val currentScreen by viewModel.currentScreen.collectAsState()

            LocalLlmTheme(
                darkModePreference = settings.darkModePreference,
                themeColorName = settings.themeColorName
            ) {
                Crossfade(targetState = currentScreen, label = "screenTransition") { screen ->
                    when (screen) {
                        "models" -> ModelManagerScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                        "settings" -> SettingsScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                        "voice_mode" -> VoiceModeScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                        else -> ChatScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
