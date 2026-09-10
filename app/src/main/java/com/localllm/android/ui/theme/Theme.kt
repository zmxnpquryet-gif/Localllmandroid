package com.localllm.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

fun getThemeColorScheme(
    themeName: String,
    isDark: Boolean
) = when (themeName.lowercase()) {
    "artistic", "artistic-flair", "artistic_flair" -> if (isDark) {
        darkColorScheme(
            primary = ArtisticPrimaryDark,
            onPrimary = ArtisticOnPrimaryDark,
            primaryContainer = ArtisticPrimaryContainerDark,
            onPrimaryContainer = ArtisticOnPrimaryContainerDark,
            secondary = ArtisticSecondaryDark,
            onSecondary = ArtisticOnSecondaryDark,
            secondaryContainer = ArtisticSecondaryContainerDark,
            onSecondaryContainer = ArtisticOnSecondaryContainerDark,
            background = ArtisticBgDark,
            onBackground = ArtisticTextDark,
            surface = ArtisticSurfaceDark,
            onSurface = ArtisticTextDark,
            surfaceVariant = ArtisticSurfaceVariantDark,
            onSurfaceVariant = ArtisticTextMutedDark,
            outline = ArtisticBorderDark
        )
    } else {
        lightColorScheme(
            primary = ArtisticPrimaryLight,
            onPrimary = ArtisticOnPrimaryLight,
            primaryContainer = ArtisticPrimaryContainerLight,
            onPrimaryContainer = ArtisticOnPrimaryContainerLight,
            secondary = ArtisticSecondaryLight,
            onSecondary = ArtisticOnPrimaryLight,
            secondaryContainer = ArtisticSecondaryContainerLight,
            onSecondaryContainer = ArtisticTextLight,
            background = ArtisticBgLight,
            onBackground = ArtisticTextLight,
            surface = ArtisticSurfaceLight,
            onSurface = ArtisticTextLight,
            surfaceVariant = ArtisticSurfaceVariantLight,
            onSurfaceVariant = ArtisticTextMutedLight,
            outline = ArtisticBorderLight
        )
    }

    "cyber" -> if (isDark) {
        darkColorScheme(
            primary = CyberGreen,
            onPrimary = Color.Black,
            primaryContainer = CyberSurfaceDark,
            onPrimaryContainer = CyberGreen,
            background = CyberBgDark,
            onBackground = Color(0xFFE2FBEF),
            surface = CyberSurfaceDark,
            onSurface = Color(0xFFE2FBEF),
            surfaceVariant = Color(0xFF142B21),
            onSurfaceVariant = Color(0xFF8DE2B8),
            outline = Color(0xFF1F4A38)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF008753),
            onPrimary = Color.White,
            background = Color(0xFFF2FBF6),
            surface = Color.White,
            outline = Color(0xFFBBE5D0)
        )
    }

    "obsidian" -> if (isDark) {
        darkColorScheme(
            primary = ObsidianPurple,
            onPrimary = Color.White,
            primaryContainer = ObsidianSurfaceDark,
            onPrimaryContainer = ObsidianPurple,
            background = ObsidianBgDark,
            onBackground = Color(0xFFF3E8FF),
            surface = ObsidianSurfaceDark,
            onSurface = Color(0xFFF3E8FF),
            surfaceVariant = Color(0xFF22163B),
            onSurfaceVariant = Color(0xFFC084FC),
            outline = Color(0xFF3B2361)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF7E22CE),
            onPrimary = Color.White,
            background = Color(0xFFFAF5FF),
            surface = Color.White,
            outline = Color(0xFFE9D5FF)
        )
    }

    "amber" -> if (isDark) {
        darkColorScheme(
            primary = AmberGold,
            onPrimary = Color.Black,
            primaryContainer = AmberSurfaceDark,
            onPrimaryContainer = AmberGold,
            background = AmberBgDark,
            onBackground = Color(0xFFFEF3C7),
            surface = AmberSurfaceDark,
            onSurface = Color(0xFFFEF3C7),
            surfaceVariant = Color(0xFF33240F),
            onSurfaceVariant = Color(0xFFFCD34D),
            outline = Color(0xFF553D19)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFFD97706),
            onPrimary = Color.White,
            background = Color(0xFFFFFBEB),
            surface = Color.White,
            outline = Color(0xFFFDE68A)
        )
    }

    "frost" -> if (isDark) {
        darkColorScheme(
            primary = ArcticBlue,
            onPrimary = Color.Black,
            primaryContainer = ArcticSurfaceDark,
            onPrimaryContainer = ArcticBlue,
            background = ArcticBgDark,
            onBackground = Color(0xFFE0F2FE),
            surface = ArcticSurfaceDark,
            onSurface = Color(0xFFE0F2FE),
            surfaceVariant = Color(0xFF162A40),
            onSurfaceVariant = Color(0xFF7DD3FC),
            outline = Color(0xFF234568)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0284C7),
            onPrimary = Color.White,
            background = Color(0xFFF0F9FF),
            surface = Color.White,
            outline = Color(0xFFBAE6FD)
        )
    }

    "chatgpt" -> if (isDark) {
        darkColorScheme(
            primary = OpenAiGreen,
            onPrimary = Color.White,
            primaryContainer = ChatGptDarkInput,
            onPrimaryContainer = ChatGptDarkText,
            secondary = OpenAiGreenLight,
            onSecondary = Color.Black,
            background = ChatGptDarkBg,
            onBackground = ChatGptDarkText,
            surface = ChatGptDarkSurface,
            onSurface = ChatGptDarkText,
            surfaceVariant = ChatGptDarkCard,
            onSurfaceVariant = ChatGptDarkTextMuted,
            outline = ChatGptDarkBorder
        )
    } else {
        lightColorScheme(
            primary = OpenAiGreenDark,
            onPrimary = Color.White,
            primaryContainer = ChatGptLightInput,
            onPrimaryContainer = ChatGptLightText,
            secondary = OpenAiGreen,
            onSecondary = Color.White,
            background = ChatGptLightBg,
            onBackground = ChatGptLightText,
            surface = ChatGptLightSurface,
            onSurface = ChatGptLightText,
            surfaceVariant = ChatGptLightCard,
            onSurfaceVariant = ChatGptLightTextMuted,
            outline = ChatGptLightBorder
        )
    }

    else -> {
        // Default theme: Artistic-Flair
        if (isDark) {
            darkColorScheme(
                primary = ArtisticPrimaryDark,
                onPrimary = ArtisticOnPrimaryDark,
                primaryContainer = ArtisticPrimaryContainerDark,
                onPrimaryContainer = ArtisticOnPrimaryContainerDark,
                secondary = ArtisticSecondaryDark,
                onSecondary = ArtisticOnSecondaryDark,
                secondaryContainer = ArtisticSecondaryContainerDark,
                onSecondaryContainer = ArtisticOnSecondaryContainerDark,
                background = ArtisticBgDark,
                onBackground = ArtisticTextDark,
                surface = ArtisticSurfaceDark,
                onSurface = ArtisticTextDark,
                surfaceVariant = ArtisticSurfaceVariantDark,
                onSurfaceVariant = ArtisticTextMutedDark,
                outline = ArtisticBorderDark
            )
        } else {
            lightColorScheme(
                primary = ArtisticPrimaryLight,
                onPrimary = ArtisticOnPrimaryLight,
                primaryContainer = ArtisticPrimaryContainerLight,
                onPrimaryContainer = ArtisticOnPrimaryContainerLight,
                secondary = ArtisticSecondaryLight,
                onSecondary = ArtisticOnPrimaryLight,
                secondaryContainer = ArtisticSecondaryContainerLight,
                onSecondaryContainer = ArtisticTextLight,
                background = ArtisticBgLight,
                onBackground = ArtisticTextLight,
                surface = ArtisticSurfaceLight,
                onSurface = ArtisticTextLight,
                surfaceVariant = ArtisticSurfaceVariantLight,
                onSurfaceVariant = ArtisticTextMutedLight,
                outline = ArtisticBorderLight
            )
        }
    }
}

@Composable
fun LocalLlmTheme(
    darkModePreference: String = "dark",
    themeColorName: String = "artistic",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (darkModePreference) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val colorScheme = getThemeColorScheme(themeColorName, isDark)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
