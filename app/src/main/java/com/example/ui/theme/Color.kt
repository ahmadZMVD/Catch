package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light Mode: Warm Bone / Alabaster Ivory
val BoneBackgroundPrimary = Color(0xFFF9F6F0)
val BoneCardSurface = Color(0xFFF1EDE4)
val BoneSurfaceGlass = Color(0xE6F9F6F0)
val BoneIconBadgeSurface = Color(0xFFE8E3D8)
val BoneTextPrimary = Color(0xFF141413)
val BoneTextSecondary = Color(0xFF6E6A63)
val BoneTextTertiary = Color(0xFF9E9A91)
val BoneBorderHairline = Color(0xFFE2DDD3)
val BoneAccentPrimary = Color(0xFF1C1C1E)
val BoneAccentOnPrimary = Color(0xFFF9F6F0)
val BoneAccentInteractive = Color(0xFFC85A32)
val BoneStatusReady = Color(0xFF2E6F40)
val BoneStatusReadyBg = Color(0xFFE5EFE7)

// Dark Mode: Obsidian Warm Slate
val ObsidianBackgroundPrimary = Color(0xFF111110)
val ObsidianCardSurface = Color(0xFF1C1C1A)
val ObsidianSurfaceGlass = Color(0xE6111110)
val ObsidianIconBadgeSurface = Color(0xFF262623)
val ObsidianTextPrimary = Color(0xFFF5F2EB)
val ObsidianTextSecondary = Color(0xFF9A968E)
val ObsidianTextTertiary = Color(0xFF636059)
val ObsidianBorderHairline = Color(0xFF2A2926)
val ObsidianAccentPrimary = Color(0xFFF5F2EB)
val ObsidianAccentOnPrimary = Color(0xFF111110)
val ObsidianAccentInteractive = Color(0xFFE07A5F)
val ObsidianStatusReady = Color(0xFF68B67E)
val ObsidianStatusReadyBg = Color(0xFF1A281E)

@Immutable
data class BoneDesignTokens(
    val isDark: Boolean,
    val backgroundPrimary: Color,
    val cardSurface: Color,
    val surfaceGlass: Color,
    val iconBadgeSurface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val borderHairline: Color,
    val accentPrimary: Color,
    val accentOnPrimary: Color,
    val accentInteractive: Color,
    val statusReady: Color,
    val statusReadyBg: Color
)

val LightBoneTokens = BoneDesignTokens(
    isDark = false,
    backgroundPrimary = BoneBackgroundPrimary,
    cardSurface = BoneCardSurface,
    surfaceGlass = BoneSurfaceGlass,
    iconBadgeSurface = BoneIconBadgeSurface,
    textPrimary = BoneTextPrimary,
    textSecondary = BoneTextSecondary,
    textTertiary = BoneTextTertiary,
    borderHairline = BoneBorderHairline,
    accentPrimary = BoneAccentPrimary,
    accentOnPrimary = BoneAccentOnPrimary,
    accentInteractive = BoneAccentInteractive,
    statusReady = BoneStatusReady,
    statusReadyBg = BoneStatusReadyBg
)

val DarkObsidianTokens = BoneDesignTokens(
    isDark = true,
    backgroundPrimary = ObsidianBackgroundPrimary,
    cardSurface = ObsidianCardSurface,
    surfaceGlass = ObsidianSurfaceGlass,
    iconBadgeSurface = ObsidianIconBadgeSurface,
    textPrimary = ObsidianTextPrimary,
    textSecondary = ObsidianTextSecondary,
    textTertiary = ObsidianTextTertiary,
    borderHairline = ObsidianBorderHairline,
    accentPrimary = ObsidianAccentPrimary,
    accentOnPrimary = ObsidianAccentOnPrimary,
    accentInteractive = ObsidianAccentInteractive,
    statusReady = ObsidianStatusReady,
    statusReadyBg = ObsidianStatusReadyBg
)

val LocalBoneTokens = staticCompositionLocalOf { LightBoneTokens }
