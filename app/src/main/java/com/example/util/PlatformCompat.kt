package com.example.util

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.service.SongCatchTileService

/**
 * Runtime compatibility layer for Android 9 (API 28) through Android 16 (API 36) across AOSP and OEM builds.
 * Centralizes SDK version branches so callers avoid reflective or unguarded API invocations on older platforms.
 */
object PlatformCompat {

    const val ACTION_GOOGLE_MUSIC_SEARCH = "com.google.android.googlequicksearchbox.MUSIC_SEARCH"
    const val ACTION_GENERIC_MUSIC_SEARCH = "android.intent.action.MUSIC_SEARCH"
    private const val GLOBAL_ACTION_DISMISS_SHADE_API31 = 15

    /**
     * Collapses the Quick Settings panel without launching an Activity so the foreground media player
     * retains window focus and stays in the RESUMED lifecycle state.
     *
     * - API 31+: dispatches [AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE].
     * - API 28..30: broadcasts [Intent.ACTION_CLOSE_SYSTEM_DIALOGS], which is permitted on API <= 30.
     */
    fun dismissQuickSettingsShade(
        accessibilityService: AccessibilityService?,
        context: Context?,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (sdkInt >= Build.VERSION_CODES.S) {
            if (accessibilityService != null) {
                return try {
                    accessibilityService.performGlobalAction(GLOBAL_ACTION_DISMISS_SHADE_API31)
                } catch (_: Throwable) {
                    false
                }
            }
            return false
        }

        if (context != null) {
            return try {
                @Suppress("DEPRECATION")
                context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                true
            } catch (_: Throwable) {
                false
            }
        }
        return false
    }

    /**
     * Returns the expected parameter count for `IVoiceInteractionManagerService.showSessionFromSession`:
     * - API 34+: 4 parameters `(IBinder?, Bundle, Int, String?)`
     * - API 28..33: 3 parameters `(IBinder?, Bundle, Int)`
     */
    fun voiceInteractionParameterCount(sdkInt: Int = Build.VERSION.SDK_INT): Int {
        return if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 4 else 3
    }

    /**
     * Ordered attribution tags passed to `showSessionFromSession` on API 34+ across OEM SystemUI builds.
     */
    val voiceInteractionAttributionTags: List<String?> = listOf(
        "hyperOS_home",
        "circle_to_search",
        null
    )

    /**
     * Resolves physical screen dimensions `(widthPx, heightPx)` across API levels without hardcoded constants.
     */
    fun resolveScreenBounds(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Pair<Int, Int> {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager != null) {
                if (sdkInt >= Build.VERSION_CODES.R) {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        return bounds.width() to bounds.height()
                    }
                } else {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay?.getRealMetrics(metrics)
                    if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                        return metrics.widthPixels to metrics.heightPixels
                    }
                }
            }
        } catch (_: Throwable) {
            // Fall back to application resources displayMetrics below.
        }

        val dm = context.resources.displayMetrics
        val width = dm.widthPixels.takeIf { it > 0 } ?: 1080
        val height = dm.heightPixels.takeIf { it > 0 } ?: 2340
        return width to height
    }

    /**
     * Computes the exact `(x, y)` target coordinate for a stationary long-press on the system
     * navigation handle. Uses the `TYPE_NAVIGATION_BAR` window bounds when exposed by the
     * Accessibility hierarchy, or calculates the bottom handle position from display metrics.
     */
    fun computeNavigationHandleCenter(
        screenWidthPx: Int,
        screenHeightPx: Int,
        density: Float = 3.0f,
        navBarBounds: Rect? = null
    ): Pair<Float, Float> {
        if (navBarBounds != null && !navBarBounds.isEmpty && navBarBounds.width() > 0 && navBarBounds.height() > 0) {
            return navBarBounds.exactCenterX() to navBarBounds.exactCenterY()
        }
        val safeWidth = screenWidthPx.takeIf { it > 0 } ?: 1080
        val safeHeight = screenHeightPx.takeIf { it > 0 } ?: 2340
        val safeDensity = density.takeIf { it > 0f } ?: 2.75f
        val bottomOffsetPx = (6f * safeDensity).coerceIn(10f, 30f)
        val centerX = safeWidth / 2f
        val centerY = (safeHeight - bottomOffsetPx).coerceAtLeast(100f)
        return centerX to centerY
    }

    /**
     * Fallback intent for Android 9 through 12 devices that do not support Circle to Search.
     * Invoked only after both `Omni` session invocation and navigation-handle long-press fail
     * to open a `com.google.android.googlequicksearchbox` overlay window.
     */
    fun buildLegacyMusicSearchIntent(): Intent {
        return Intent(ACTION_GOOGLE_MUSIC_SEARCH).apply {
            setPackage("com.google.android.googlequicksearchbox")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
    }

    fun launchLegacyMusicSearchFallback(context: Context): Boolean {
        return try {
            context.startActivity(buildLegacyMusicSearchIntent())
            true
        } catch (_: Throwable) {
            try {
                val genericIntent = Intent(ACTION_GENERIC_MUSIC_SEARCH).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                context.startActivity(genericIntent)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Triggers a short confirmation haptic pulse across API 28 through 36.
     */
    fun triggerCaptureHapticPulse(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        return try {
            val vibrator: Vibrator? = if (sdkInt >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator?.hasVibrator() == true) {
                if (sdkInt >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    vibrator.vibrate(VibrationEffect.createOneShot(25L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks whether the native `StatusBarManager.requestAddTileService` dialog is supported (API 33+).
     */
    fun isTilePlacementRequestSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Requests adding the Quick Settings tile on API 33+, or returns [AddedTileOutcome.DISMISSED_OR_UNSUPPORTED]
     * on API 28..32 so the caller can display manual tile placement instructions.
     */
    fun requestAddQuickSettingsTile(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
        onResult: (AddedTileOutcome) -> Unit
    ) {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                if (statusBarManager != null) {
                    val componentName = ComponentName(context, SongCatchTileService::class.java)
                    val icon = Icon.createWithResource(context, R.drawable.ic_qs_catch_song)
                    statusBarManager.requestAddTileService(
                        componentName,
                        context.getString(R.string.tile_label_catch_song),
                        icon,
                        context.mainExecutor
                    ) { resultCode ->
                        val outcome = when (resultCode) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> AddedTileOutcome.ADDED
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> AddedTileOutcome.ALREADY_ADDED
                            else -> AddedTileOutcome.DISMISSED_OR_UNSUPPORTED
                        }
                        onResult(outcome)
                    }
                    return
                }
            } catch (_: Throwable) {
                // Fall through to unsupported outcome
            }
        }
        onResult(AddedTileOutcome.DISMISSED_OR_UNSUPPORTED)
    }

    /**
     * Evaluates notification permission status across API 28..36.
     */
    fun isNotificationPermissionGranted(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            try {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            } catch (_: Throwable) {
                true
            }
        }
    }

    /**
     * Launches an Activity and collapses the Quick Settings panel when Accessibility is not yet enabled:
     * - API 34+: uses `TileService.startActivityAndCollapse(PendingIntent)` with background start privilege.
     * - API 28..33: uses `TileService.startActivityAndCollapse(Intent)`.
     */
    @Suppress("DEPRECATION")
    fun startActivityAndCollapseFromTile(
        tileService: TileService,
        intent: Intent,
        requestCode: Int,
        sdkInt: Int = Build.VERSION.SDK_INT
    ) {
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            val pendingIntent = PendingIntent.getActivity(
                tileService,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                options.toBundle()
            )
            tileService.startActivityAndCollapse(pendingIntent)
        } else {
            tileService.startActivityAndCollapse(intent)
        }
    }
}
