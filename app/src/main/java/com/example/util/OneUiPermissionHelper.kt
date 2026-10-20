package com.example.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.service.GoogleMusicAccessibilityService
import com.example.service.SongCatchTileService

data class SystemHealthStatus(
    val isAccessibilityServiceEnabled: Boolean = false,
    val isNotificationPermissionGranted: Boolean = true,
    val isBatteryOptimizationIgnored: Boolean = false,
    val isGoogleQuickSearchInstalled: Boolean = true,
    val isQuickSettingsTileAdded: Boolean = false,
    val hasVisitedAccessibilitySettings: Boolean = false
) {
    val isCoreReady: Boolean
        get() = isAccessibilityServiceEnabled && isNotificationPermissionGranted

    val isFullyOptimized: Boolean
        get() = isAccessibilityServiceEnabled &&
            isNotificationPermissionGranted &&
            isBatteryOptimizationIgnored

    /**
     * On Android 13+ (API 33+), sideloaded APKs require unlocking "Allow restricted settings"
     * in App Info before the Accessibility toggle becomes interactive.
     */
    val isLikelyBlockedByRestrictedSettings: Boolean
        get() = !isAccessibilityServiceEnabled &&
            hasVisitedAccessibilitySettings &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

object OneUiPermissionHelper {

    const val GOOGLE_QSB_PACKAGE = "com.google.android.googlequicksearchbox"

    fun evaluateSystemHealth(
        context: Context,
        isTileAddedFlag: Boolean = false,
        hasVisitedAccessibilityFlag: Boolean = false
    ): SystemHealthStatus {
        return SystemHealthStatus(
            isAccessibilityServiceEnabled = isAccessibilityServiceEnabled(context),
            isNotificationPermissionGranted = isNotificationPermissionGranted(context),
            isBatteryOptimizationIgnored = isBatteryOptimizationIgnored(context),
            isGoogleQuickSearchInstalled = isGoogleAppInstalled(context),
            isQuickSettingsTileAdded = isTileAddedFlag || SongCatchTileService.isTileListeningInSystem,
            hasVisitedAccessibilitySettings = hasVisitedAccessibilityFlag
        )
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (GoogleMusicAccessibilityService.activeInstance != null) return true
        return try {
            val expectedComponent = ComponentName(context, GoogleMusicAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            enabledServices.split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it.packageName == expectedComponent.packageName && it.className == expectedComponent.className }
        } catch (_: Exception) {
            false
        }
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        return PlatformCompat.isNotificationPermissionGranted(context)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun isGoogleAppInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(GOOGLE_QSB_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens App Info so the user can allow restricted settings on Android 13+ or configure
     * unrestricted background battery usage across OEMs.
     */
    fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAccessibilitySettings(context)
        }
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
                openAppDetailsSettings(context)
            }
        }
    }

    /**
     * Prompts the user with the system dialog to add the Catch Quick Settings tile on Android 13+ (API 33+),
     * or returns [AddedTileOutcome.DISMISSED_OR_UNSUPPORTED] on API 28..32.
     */
    fun requestAddQuickSettingsTile(
        context: Context,
        onResult: (AddedTileOutcome) -> Unit
    ) {
        PlatformCompat.requestAddQuickSettingsTile(
            context = context,
            sdkInt = Build.VERSION.SDK_INT,
            onResult = onResult
        )
    }

    /**
     * Maps known media source packages to human-readable labels for the UI badge.
     */
    fun formatSourceAppName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "System Audio"
        val lower = packageName.lowercase()
        return when {
            lower.contains("instagram") -> "Instagram"
            lower.contains("telegram") || lower.contains("nekogram") -> "Telegram"
            lower.contains("musically") || lower.contains("tiktok") || lower.contains("trill") -> "TikTok"
            lower.contains("youtube") -> "YouTube"
            lower.contains("spotify") -> "Spotify"
            lower.contains("soundcloud") -> "SoundCloud"
            lower.contains("twitter") || lower.contains("com.twitter") -> "X / Twitter"
            lower.contains("chrome") || lower.contains("sbrowser") || lower.contains("firefox") -> "Browser"
            else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }
}

enum class AddedTileOutcome {
    ADDED,
    ALREADY_ADDED,
    DISMISSED_OR_UNSUPPORTED
}
