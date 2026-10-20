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

enum class RestrictedUnlockStep {
    STEP_2A_TRIGGER_PROMPT,
    STEP_2B_ALLOW_IN_APP_INFO,
    STEP_2C_ENABLE_ACCESSIBILITY,
    COMPLETED
}

typealias RestrictedUnlockSubstep = RestrictedUnlockStep

data class SystemHealthStatus(
    val isAccessibilityServiceEnabled: Boolean = false,
    val isNotificationPermissionGranted: Boolean = true,
    val isBatteryOptimizationIgnored: Boolean = false,
    val isGoogleQuickSearchInstalled: Boolean = true,
    val isQuickSettingsTileAdded: Boolean = false,
    val hasVisitedAccessibilitySettings: Boolean = false,
    val hasVisitedAppInfoSettings: Boolean = false,
    val isLegacyPrototypeInstalled: Boolean = false
) {
    val hasVisitedAppInfo: Boolean
        get() = hasVisitedAppInfoSettings

    val isCoreReady: Boolean
        get() = isAccessibilityServiceEnabled && isNotificationPermissionGranted

    val isFullyOptimized: Boolean
        get() = isAccessibilityServiceEnabled &&
            isNotificationPermissionGranted &&
            isBatteryOptimizationIgnored

    /**
     * Resolves the active substep (`2A -> 2B -> 2C`) for unlocking Restricted Settings on Android 13+.
     */
    val currentRestrictedUnlockStep: RestrictedUnlockStep
        get() = SystemPermissionHelper.resolveRestrictedUnlockSubstep(
            isAccessibilityServiceEnabled = isAccessibilityServiceEnabled,
            hasVisitedAccessibilityList = hasVisitedAccessibilitySettings,
            hasVisitedAppInfo = hasVisitedAppInfoSettings
        )

    val activeRestrictedUnlockSubstep: RestrictedUnlockStep
        get() = currentRestrictedUnlockStep

    /**
     * On Android 13+ (API 33+), sideloaded APKs require unlocking "Allow restricted settings"
     * in App Info after first triggering the "App was denied access" prompt in Accessibility settings.
     */
    val isLikelyBlockedByRestrictedSettings: Boolean
        get() = !isAccessibilityServiceEnabled &&
            hasVisitedAccessibilitySettings &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

object SystemPermissionHelper {

    const val GOOGLE_QSB_PACKAGE = "com.google.android.googlequicksearchbox"
    const val LEGACY_PROTOTYPE_PACKAGE = "com.aistudio.catchsong.kxmpzq"

    fun resolveRestrictedUnlockSubstep(
        isAccessibilityServiceEnabled: Boolean,
        hasVisitedAccessibilityList: Boolean,
        hasVisitedAppInfo: Boolean
    ): RestrictedUnlockStep {
        return when {
            isAccessibilityServiceEnabled -> RestrictedUnlockStep.COMPLETED
            !hasVisitedAccessibilityList -> RestrictedUnlockStep.STEP_2A_TRIGGER_PROMPT
            !hasVisitedAppInfo -> RestrictedUnlockStep.STEP_2B_ALLOW_IN_APP_INFO
            else -> RestrictedUnlockStep.STEP_2C_ENABLE_ACCESSIBILITY
        }
    }

    fun evaluateSystemHealth(
        context: Context,
        isTileAddedFlag: Boolean = false,
        hasVisitedAccessibilityFlag: Boolean = false,
        hasVisitedAppInfoFlag: Boolean = false
    ): SystemHealthStatus {
        return SystemHealthStatus(
            isAccessibilityServiceEnabled = isAccessibilityServiceEnabled(context),
            isNotificationPermissionGranted = isNotificationPermissionGranted(context),
            isBatteryOptimizationIgnored = isBatteryOptimizationIgnored(context),
            isGoogleQuickSearchInstalled = isGoogleAppInstalled(context),
            isQuickSettingsTileAdded = isTileAddedFlag || SongCatchTileService.isTileListeningInSystem,
            hasVisitedAccessibilitySettings = hasVisitedAccessibilityFlag,
            hasVisitedAppInfoSettings = hasVisitedAppInfoFlag,
            isLegacyPrototypeInstalled = isLegacyPrototypeInstalled(context)
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
     * Checks whether the older prototype package (`com.aistudio.catchsong.kxmpzq`) is still installed
     * on the device, which would otherwise show a duplicate entry in Accessibility settings.
     */
    fun isLegacyPrototypeInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(LEGACY_PROTOTYPE_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds the `ACTION_DELETE` intent targeting the legacy prototype package (`com.aistudio.catchsong.kxmpzq`).
     */
    fun buildUninstallLegacyPrototypeIntent(): Intent {
        return Intent(Intent.ACTION_DELETE, Uri.parse("package:$LEGACY_PROTOTYPE_PACKAGE")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Launches the system package uninstaller for `com.aistudio.catchsong.kxmpzq`.
     */
    fun promptUninstallLegacyPrototype(context: Context) {
        try {
            context.startActivity(buildUninstallLegacyPrototypeIntent())
        } catch (_: Exception) {
            try {
                val appInfoIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", LEGACY_PROTOTYPE_PACKAGE, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(appInfoIntent)
            } catch (_: Exception) {
            }
        }
    }

    fun uninstallLegacyPrototype(context: Context) = promptUninstallLegacyPrototype(context)

    /**
     * Opens App Info so the user can allow restricted settings on Android 13+ or configure
     * unrestricted background battery usage.
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
