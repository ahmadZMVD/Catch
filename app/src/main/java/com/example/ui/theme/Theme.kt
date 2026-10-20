package com.example.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.data.preferences.AppThemeMode

private val LightBoneMaterialScheme = lightColorScheme(
    primary = BoneAccentPrimary,
    onPrimary = BoneAccentOnPrimary,
    secondary = BoneAccentInteractive,
    onSecondary = Color.White,
    tertiary = BoneTextSecondary,
    background = BoneBackgroundPrimary,
    onBackground = BoneTextPrimary,
    surface = BoneCardSurface,
    onSurface = BoneTextPrimary,
    surfaceVariant = BoneIconBadgeSurface,
    onSurfaceVariant = BoneTextSecondary,
    outline = BoneBorderHairline,
    outlineVariant = BoneBorderHairline
)

private val DarkObsidianMaterialScheme = darkColorScheme(
    primary = ObsidianAccentPrimary,
    onPrimary = ObsidianAccentOnPrimary,
    secondary = ObsidianAccentInteractive,
    onSecondary = ObsidianBackgroundPrimary,
    tertiary = ObsidianTextSecondary,
    background = ObsidianBackgroundPrimary,
    onBackground = ObsidianTextPrimary,
    surface = ObsidianCardSurface,
    onSurface = ObsidianTextPrimary,
    surfaceVariant = ObsidianIconBadgeSurface,
    onSurfaceVariant = ObsidianTextSecondary,
    outline = ObsidianBorderHairline,
    outlineVariant = ObsidianBorderHairline
)

/**
 * Custom Bone / Obsidian Theme.
 * Explicitly disables Android 12+ dynamic wallpaper colors so the bespoke Apple-minimalist
 * Warm Bone (`#F9F6F0`) and Obsidian (`#111110`) palettes are strictly preserved.
 */
@Composable
fun CatchSongBoneTheme(
    themeMode: AppThemeMode = AppThemeMode.LIGHT_BONE,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        AppThemeMode.LIGHT_BONE -> false
        AppThemeMode.DARK_OBSIDIAN -> true
        AppThemeMode.SYSTEM_AUTO -> systemDark
    }

    val targetTokens = if (useDark) DarkObsidianTokens else LightBoneTokens
    val colorSpring = spring<Color>(stiffness = Spring.StiffnessMediumLow)

    val animatedBg by animateColorAsState(targetTokens.backgroundPrimary, colorSpring, label = "bg")
    val animatedCard by animateColorAsState(targetTokens.cardSurface, colorSpring, label = "card")
    val animatedGlass by animateColorAsState(targetTokens.surfaceGlass, colorSpring, label = "glass")
    val animatedBadge by animateColorAsState(targetTokens.iconBadgeSurface, colorSpring, label = "badge")
    val animatedTextPrimary by animateColorAsState(targetTokens.textPrimary, colorSpring, label = "text1")
    val animatedTextSecondary by animateColorAsState(targetTokens.textSecondary, colorSpring, label = "text2")
    val animatedTextTertiary by animateColorAsState(targetTokens.textTertiary, colorSpring, label = "text3")
    val animatedBorder by animateColorAsState(targetTokens.borderHairline, colorSpring, label = "border")
    val animatedAccent by animateColorAsState(targetTokens.accentPrimary, colorSpring, label = "accent")
    val animatedOnAccent by animateColorAsState(targetTokens.accentOnPrimary, colorSpring, label = "onAccent")
    val animatedInteractive by animateColorAsState(targetTokens.accentInteractive, colorSpring, label = "inter")

    val animatedTokens = targetTokens.copy(
        backgroundPrimary = animatedBg,
        cardSurface = animatedCard,
        surfaceGlass = animatedGlass,
        iconBadgeSurface = animatedBadge,
        textPrimary = animatedTextPrimary,
        textSecondary = animatedTextSecondary,
        textTertiary = animatedTextTertiary,
        borderHairline = animatedBorder,
        accentPrimary = animatedAccent,
        accentOnPrimary = animatedOnAccent,
        accentInteractive = animatedInteractive
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !useDark
                controller.isAppearanceLightNavigationBars = !useDark
            }
        }
    }

    val materialScheme = if (useDark) DarkObsidianMaterialScheme else LightBoneMaterialScheme

    CompositionLocalProvider(LocalBoneTokens provides animatedTokens) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = Typography,
            content = content
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    CatchSongBoneTheme(
        themeMode = if (darkTheme) AppThemeMode.DARK_OBSIDIAN else AppThemeMode.LIGHT_BONE,
        content = content
    )
}
