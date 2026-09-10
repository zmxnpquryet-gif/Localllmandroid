package com.localllm.android

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
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel
import com.localllm.android.ui.api.ApiServerScreen
import com.localllm.android.ui.chat.ChatScreen
import com.localllm.android.ui.models.ModelManagerScreen
import com.localllm.android.ui.settings.SettingsScreen
import com.localllm.android.ui.theme.LocalLlmTheme
import com.localllm.android.ui.voice.VoiceModeScreen

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
                        AppScreen.MODELS -> ModelManagerScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                        AppScreen.SETTINGS -> SettingsScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                        AppScreen.VOICE_MODE -> VoiceModeScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                        AppScreen.API_MODE -> ApiServerScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                        AppScreen.CHAT -> ChatScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
