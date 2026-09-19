package com.localllm.android.ui.glass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.localllm.android.ui.theme.AmberBgDark
import com.localllm.android.ui.theme.AmberGold
import com.localllm.android.ui.theme.AmberSurfaceDark
import com.localllm.android.ui.theme.ArcticBgDark
import com.localllm.android.ui.theme.ArcticBlue
import com.localllm.android.ui.theme.ArcticSurfaceDark
import com.localllm.android.ui.theme.ArtisticBgDark
import com.localllm.android.ui.theme.ArtisticBgLight
import com.localllm.android.ui.theme.ArtisticBorderDark
import com.localllm.android.ui.theme.ArtisticBorderLight
import com.localllm.android.ui.theme.ArtisticOnPrimaryContainerDark
import com.localllm.android.ui.theme.ArtisticOnPrimaryContainerLight
import com.localllm.android.ui.theme.ArtisticOnPrimaryDark
import com.localllm.android.ui.theme.ArtisticOnPrimaryLight
import com.localllm.android.ui.theme.ArtisticOnSecondaryContainerDark
import com.localllm.android.ui.theme.ArtisticPrimaryContainerDark
import com.localllm.android.ui.theme.ArtisticPrimaryContainerLight
import com.localllm.android.ui.theme.ArtisticPrimaryDark
import com.localllm.android.ui.theme.ArtisticPrimaryLight
import com.localllm.android.ui.theme.ArtisticSecondaryContainerDark
import com.localllm.android.ui.theme.ArtisticSecondaryContainerLight
import com.localllm.android.ui.theme.ArtisticSecondaryDark
import com.localllm.android.ui.theme.ArtisticOnSecondaryDark
import com.localllm.android.ui.theme.ArtisticSecondaryLight
import com.localllm.android.ui.theme.ArtisticSurfaceDark
import com.localllm.android.ui.theme.ArtisticSurfaceLight
import com.localllm.android.ui.theme.ArtisticSurfaceVariantDark
import com.localllm.android.ui.theme.ArtisticSurfaceVariantLight
import com.localllm.android.ui.theme.ArtisticTextDark
import com.localllm.android.ui.theme.ArtisticTextLight
import com.localllm.android.ui.theme.ArtisticTextMutedDark
import com.localllm.android.ui.theme.ArtisticTextMutedLight
import com.localllm.android.ui.theme.ChatGptDarkBg
import com.localllm.android.ui.theme.ChatGptDarkBorder
import com.localllm.android.ui.theme.ChatGptDarkCard
import com.localllm.android.ui.theme.ChatGptDarkInput
import com.localllm.android.ui.theme.ChatGptDarkSurface
import com.localllm.android.ui.theme.ChatGptDarkText
import com.localllm.android.ui.theme.ChatGptDarkTextMuted
import com.localllm.android.ui.theme.ChatGptLightBg
import com.localllm.android.ui.theme.ChatGptLightBorder
import com.localllm.android.ui.theme.ChatGptLightCard
import com.localllm.android.ui.theme.ChatGptLightInput
import com.localllm.android.ui.theme.ChatGptLightSurface
import com.localllm.android.ui.theme.ChatGptLightText
import com.localllm.android.ui.theme.ChatGptLightTextMuted
import com.localllm.android.ui.theme.CyberBgDark
import com.localllm.android.ui.theme.CyberGreen
import com.localllm.android.ui.theme.CyberSurfaceDark
import com.localllm.android.ui.theme.LiquidBgDark
import com.localllm.android.ui.theme.LiquidBgLight
import com.localllm.android.ui.theme.LiquidBorderDark
import com.localllm.android.ui.theme.LiquidBorderLight
import com.localllm.android.ui.theme.LiquidOnPrimaryContainerDark
import com.localllm.android.ui.theme.LiquidOnPrimaryContainerLight
import com.localllm.android.ui.theme.LiquidOnPrimaryDark
import com.localllm.android.ui.theme.LiquidOnPrimaryLight
import com.localllm.android.ui.theme.LiquidPrimaryContainerDark
import com.localllm.android.ui.theme.LiquidPrimaryContainerLight
import com.localllm.android.ui.theme.LiquidPrimaryDark
import com.localllm.android.ui.theme.LiquidPrimaryLight
import com.localllm.android.ui.theme.LiquidSecondaryDark
import com.localllm.android.ui.theme.LiquidSurfaceDark
import com.localllm.android.ui.theme.LiquidSurfaceLight
import com.localllm.android.ui.theme.LiquidSurfaceVariantDark
import com.localllm.android.ui.theme.LiquidSurfaceVariantLight
import com.localllm.android.ui.theme.LiquidTertiaryDark
import com.localllm.android.ui.theme.LiquidTextDark
import com.localllm.android.ui.theme.LiquidTextLight
import com.localllm.android.ui.theme.LiquidTextMutedDark
import com.localllm.android.ui.theme.LiquidTextMutedLight
import com.localllm.android.ui.theme.ObsidianBgDark
import com.localllm.android.ui.theme.ObsidianPurple
import com.localllm.android.ui.theme.ObsidianSurfaceDark
import com.localllm.android.ui.theme.OpenAiGreen
import com.localllm.android.ui.theme.OpenAiGreenDark
import com.localllm.android.ui.theme.OpenAiGreenLight

/**
 * First-party design tokens. Same palette values as before, zero Material types:
 * screens read [GlassTheme.colors]/[GlassTheme.type] instead of MaterialTheme.
 */
data class GlassColors(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val outline: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color
)

data class GlassType(
    val headlineMedium: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle
)

private fun glassType() = GlassType(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

private val baselineTertiaryDark = Color(0xFFCCC2DC)
private val baselineTertiaryLight = Color(0xFF7D5260)
private val baselineErrorDark = Color(0xFFF2B8B8)
private val baselineOnErrorDark = Color(0xFF601410)
private val baselineErrorContainerDark = Color(0xFF8C1D18)
private val baselineOnErrorContainerDark = Color(0xFFF9DEDC)
private val baselineErrorLight = Color(0xFFBA1A1A)
private val baselineOnErrorLight = Color(0xFFFFFFFF)
private val baselineErrorContainerLight = Color(0xFFFFDAD6)
private val baselineOnErrorContainerLight = Color(0xFF410002)

fun glassColors(themeName: String, dark: Boolean): GlassColors {
    return when (themeName.lowercase()) {
        "liquid", "glass" -> if (dark) {
            GlassColors(LiquidBgDark, LiquidTextDark, LiquidSurfaceDark, LiquidTextDark,
                LiquidSurfaceVariantDark, LiquidTextMutedDark, LiquidPrimaryDark, LiquidOnPrimaryDark,
                LiquidPrimaryContainerDark, LiquidOnPrimaryContainerDark, LiquidSecondaryDark,
                LiquidOnPrimaryDark, LiquidSurfaceVariantDark, LiquidTextDark, LiquidTertiaryDark,
                LiquidBorderDark, baselineErrorDark, baselineErrorContainerDark, baselineOnErrorContainerDark, Color(0xFF633B48), Color(0xFFFFD9E2))
        } else {
            GlassColors(LiquidBgLight, LiquidTextLight, LiquidSurfaceLight, LiquidTextLight,
                LiquidSurfaceVariantLight, LiquidTextMutedLight, LiquidPrimaryLight, LiquidOnPrimaryLight,
                LiquidPrimaryContainerLight, LiquidOnPrimaryContainerLight, LiquidPrimaryLight,
                LiquidOnPrimaryLight, LiquidSurfaceVariantLight, LiquidTextLight, Color(0xFF0D9488),
                LiquidBorderLight, baselineErrorLight, baselineErrorContainerLight, baselineOnErrorContainerLight, Color(0xFFFFD9E2), Color(0xFF633B48))
        }
        "cyber" -> if (dark) {
            GlassColors(CyberBgDark, Color(0xFFE2FBEF), CyberSurfaceDark, Color(0xFFE2FBEF),
                Color(0xFF142B21), Color(0xFF8DE2B8), CyberGreen, Color.Black,
                CyberSurfaceDark, CyberGreen, CyberGreen, Color.Black,
                Color(0xFF142B21), Color(0xFFE2FBEF), baselineTertiaryDark,
                Color(0xFF1F4A38), baselineErrorDark, baselineErrorContainerDark, baselineOnErrorContainerDark, Color(0xFF633B48), Color(0xFFFFD9E2))
        } else {
            GlassColors(Color(0xFFF2FBF6), Color(0xFF0B1F16), Color.White, Color(0xFF0B1F16),
                Color(0xFFDDF2E6), Color(0xFF0B5C3B), Color(0xFF008753), Color.White,
                Color(0xFFDDF2E6), Color(0xFF008753), Color(0xFF008753), Color.White,
                Color(0xFFDDF2E6), Color(0xFF0B1F16), baselineTertiaryLight,
                Color(0xFFBBE5D0), baselineErrorLight, baselineErrorContainerLight, baselineOnErrorContainerLight, Color(0xFFFFD9E2), Color(0xFF633B48))
        }
        "obsidian" -> if (dark) {
            GlassColors(ObsidianBgDark, Color(0xFFF3E8FF), ObsidianSurfaceDark, Color(0xFFF3E8FF),
                Color(0xFF22163B), Color(0xFFC084FC), ObsidianPurple, Color.White,
                ObsidianSurfaceDark, ObsidianPurple, ObsidianPurple, Color.White,
                Color(0xFF22163B), Color(0xFFF3E8FF), baselineTertiaryDark,
                Color(0xFF3B2361), baselineErrorDark, baselineErrorContainerDark, baselineOnErrorContainerDark, Color(0xFF633B48), Color(0xFFFFD9E2))
        } else {
            GlassColors(Color(0xFFFAF5FF), Color(0xFF1D1030), Color.White, Color(0xFF1D1030),
                Color(0xFFF1E4FF), Color(0xFF5B21B6), Color(0xFF7E22CE), Color.White,
                Color(0xFFF1E4FF), Color(0xFF7E22CE), Color(0xFF7E22CE), Color.White,
                Color(0xFFF1E4FF), Color(0xFF1D1030), baselineTertiaryLight,
                Color(0xFFE9D5FF), baselineErrorLight, baselineErrorContainerLight, baselineOnErrorContainerLight, Color(0xFFFFD9E2), Color(0xFF633B48))
        }
        "amber" -> if (dark) {
            GlassColors(AmberBgDark, Color(0xFFFEF3C7), AmberSurfaceDark, Color(0xFFFEF3C7),
                Color(0xFF33240F), Color(0xFFFCD34D), AmberGold, Color.Black,
                AmberSurfaceDark, AmberGold, AmberGold, Color.Black,
                Color(0xFF33240F), Color(0xFFFEF3C7), baselineTertiaryDark,
                Color(0xFF553D19), baselineErrorDark, baselineErrorContainerDark, baselineOnErrorContainerDark, Color(0xFF633B48), Color(0xFFFFD9E2))
        } else {
            GlassColors(Color(0xFFFFFBEB), Color(0xFF241A08), Color.White, Color(0xFF241A08),
                Color(0xFFFDF0D5), Color(0xFF92400E), Color(0xFFD97706), Color.White,
                Color(0xFFFDF0D5), Color(0xFFD97706), Color(0xFFD97706), Color.White,
                Color(0xFFFDF0D5), Color(0xFF241A08), baselineTertiaryLight,
                Color(0xFFFDE68A), baselineErrorLight, baselineErrorContainerLight, baselineOnErrorContainerLight, Color(0xFFFFD9E2), Color(0xFF633B48))
        }
        "frost" -> if (dark) {
            GlassColors(ArcticBgDark, Color(0xFFE0F2FE), ArcticSurfaceDark, Color(0xFFE0F2FE),
                Color(0xFF162A40), Color(0xFF7DD3FC), ArcticBlue, Color.Black,
                ArcticSurfaceDark, ArcticBlue, ArcticBlue, Color.Black,
                Color(0xFF162A40), Color(0xFFE0F2FE), baselineTertiaryDark,
                Color(0xFF234568), baselineErrorDark, baselineErrorContainerDark, baselineOnErrorContainerDark, Color(0xFF633B48), Color(0xFFFFD9E2))
        } else {
            GlassColors(Color(0xFFF0F9FF), Color(0xFF082A3D), Color.White, Color(0xFF082A3D),
                Color(0xFFDDF1FE), Color(0xFF075985), Color(0xFF0284C7), Color.White,
                Color(0xFFDDF1FE), Color(0xFF0284C7), Color(0xFF0284C7), Color.White,
                Color(0xFFDDF1FE), Color(0xFF082A3D), baselineTertiaryLight,
                Color(0xFFBAE6FD), baselineErrorLight, baselineErrorContainerLight, baselineOnErrorContainerLight, Color(0xFFFFD9E2), Color(0xFF633B48))
        }
        "chatgpt" -> if (dark) {
            GlassColors(ChatGptDarkBg, ChatGptDarkText, ChatGptDarkSurface, ChatGptDarkText,
                ChatGptDarkCard, ChatGptDarkTextMuted, OpenAiGreen, Color.White,
                ChatGptDarkInput, ChatGptDarkText, OpenAiGreenLight, Color.Black,
                ChatGptDarkCard, ChatGptDarkText, baselineTertiaryDark,
                ChatGptDarkBorder, baselineErrorDark, baselineErrorContainerDark, baselineOnErrorContainerDark, Color(0xFF633B48), Color(0xFFFFD9E2))
        } else {
            GlassColors(ChatGptLightBg, ChatGptLightText, ChatGptLightSurface, ChatGptLightText,
                ChatGptLightCard, ChatGptLightTextMuted, OpenAiGreenDark, Color.White,
                ChatGptLightInput, ChatGptLightText, OpenAiGreen, Color.White,
                ChatGptLightCard, ChatGptLightText, baselineTertiaryLight,
                ChatGptLightBorder, baselineErrorLight, baselineErrorContainerLight, baselineOnErrorContainerLight, Color(0xFFFFD9E2), Color(0xFF633B48))
        }
        else -> if (dark) {
            GlassColors(ArtisticBgDark, ArtisticTextDark, ArtisticSurfaceDark, ArtisticTextDark,
                ArtisticSurfaceVariantDark, ArtisticTextMutedDark, ArtisticPrimaryDark, ArtisticOnPrimaryDark,
                ArtisticPrimaryContainerDark, ArtisticOnPrimaryContainerDark, ArtisticSecondaryDark, ArtisticOnSecondaryDark,
                ArtisticSecondaryContainerDark, ArtisticOnSecondaryContainerDark, baselineTertiaryDark,
                ArtisticBorderDark, baselineErrorDark, baselineErrorContainerDark, baselineOnErrorContainerDark, Color(0xFF633B48), Color(0xFFFFD9E2))
        } else {
            GlassColors(ArtisticBgLight, ArtisticTextLight, ArtisticSurfaceLight, ArtisticTextLight,
                ArtisticSurfaceVariantLight, ArtisticTextMutedLight, ArtisticPrimaryLight, ArtisticOnPrimaryLight,
                ArtisticPrimaryContainerLight, ArtisticOnPrimaryContainerLight, ArtisticSecondaryLight, ArtisticOnPrimaryLight,
                ArtisticSecondaryContainerLight, ArtisticTextLight, baselineTertiaryLight,
                ArtisticBorderLight, baselineErrorLight, baselineErrorContainerLight, baselineOnErrorContainerLight, Color(0xFFFFD9E2), Color(0xFF633B48))
        }
    }
}

private val LocalGlassColors = staticCompositionLocalOf<GlassColors> {
    error("GlassTheme not installed")
}
private val LocalGlassType = staticCompositionLocalOf { glassType() }

object GlassTheme {
    val colors: GlassColors @Composable get() = LocalGlassColors.current
    val type: GlassType @Composable get() = LocalGlassType.current
}

@Composable
fun GlassTheme(
    darkModePreference: String = "dark",
    themeColorName: String = "artistic",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (darkModePreference) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val colors = glassColors(themeColorName, dark)
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalGlassType provides glassType(),
        LocalGContentColor provides colors.onSurface,
        content = content
    )
}
