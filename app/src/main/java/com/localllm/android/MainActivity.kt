package com.localllm.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel
import com.localllm.android.ui.api.ApiServerScreen
import com.localllm.android.ui.chat.ChatScreen
import com.localllm.android.ui.models.ModelManagerScreen
import com.localllm.android.ui.settings.SettingsScreen
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.ui.voice.VoiceModeScreen
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsState()
            val currentScreen by viewModel.currentScreen.collectAsState()

            BackHandler(enabled = currentScreen != AppScreen.CHAT) {
                viewModel.navigateTo(AppScreen.CHAT)
            }

            val currentLocale = remember(settings.languagePreference) {
                when (settings.languagePreference) {
                    "ko" -> Locale.KOREAN
                    "en" -> Locale.ENGLISH
                    else -> Locale.getDefault()
                }
            }

            val baseContext = LocalContext.current
            val localizedContext = remember(currentLocale, baseContext) {
                val config = Configuration(baseContext.resources.configuration).apply {
                    setLocale(currentLocale)
                }
                baseContext.createConfigurationContext(config)
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                // The localized wrapper above is not an Activity, so the registry owner
                // lookup via LocalContext would fail (launch crash). Pin the real one.
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalConfiguration provides localizedContext.resources.configuration
            ) {
                GlassTheme(
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
}
