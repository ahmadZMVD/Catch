package com.example.domain.interception

import com.example.util.PlatformCompat

/**
 * Lifecycle phases of the Circle to Search audio recognition pipeline:
 * - [IDLE]: Passive state with zero accessibility node traversal overhead.
 * - [AWAITING_SHADE_COLLAPSE]: Collapsing the Quick Settings panel and awaiting foreground app focus.
 * - [TRIGGERING_CIRCLE_TO_SEARCH]: Summoning the contextual search overlay and locating the audio search action.
 * - [LISTENING_FOR_SONG]: Enforcing the minimum audio sampling window and polling for a track match.
 * - [RESULT_STABILIZING]: Debouncing a matched song title and artist before committing to storage.
 */
enum class InterceptionPhase {
    IDLE,
    AWAITING_SHADE_COLLAPSE,
    TRIGGERING_CIRCLE_TO_SEARCH,
    LISTENING_FOR_SONG,
    RESULT_STABILIZING
}

/**
 * Lightweight, immutable representation of an `AccessibilityNodeInfo` subtree.
 * Decouples the extraction engine from Android framework classes for deterministic JVM testing.
 */
data class ParsedNodeSnapshot(
    val viewIdResourceName: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val isClickable: Boolean = false,
    val isVisibleToUser: Boolean = true,
    val isHeading: Boolean = false,
    val boundsCenterX: Int? = null,
    val boundsCenterY: Int? = null,
    val boundsWidthPx: Int? = null,
    val boundsHeightPx: Int? = null,
    val children: List<ParsedNodeSnapshot> = emptyList()
)

/**
 * Summary of an active `AccessibilityWindowInfo` layer on screen.
 */
data class WindowLayerSummary(
    val windowType: Int,
    val packageName: String,
    val boundsWidthPx: Int = 1080,
    val boundsHeightPx: Int = 0,
    val screenWidthPx: Int = 1080,
    val screenHeightPx: Int = 2340,
    val isFocused: Boolean = false,
    val isActive: Boolean = false,
    val isTrampolineActivity: Boolean = false,
    val containsQuickSettingsNodes: Boolean = false,
    val navBarCenterX: Float? = null,
    val navBarCenterY: Float? = null,
    val rootSnapshot: ParsedNodeSnapshot? = null
) {
    companion object {
        const val TYPE_APPLICATION = 1
        const val TYPE_SYSTEM = 3
        const val TYPE_STATUS_BAR = 2000
        const val TYPE_NAVIGATION_BAR = 2019
    }
}

/**
 * Snapshot of all visible `AccessibilityWindowInfo` layers on screen at a given instant.
 */
data class ScreenWindowEnvironment(
    val windows: List<WindowLayerSummary> = emptyList(),
    val screenWidthPx: Int = 1080,
    val screenHeightPx: Int = 2340,
    val screenDensity: Float = 3.0f
) {
    /**
     * Returns true when the Quick Settings / Notification Shade (`com.android.systemui`
     * or `TYPE_STATUS_BAR`) is still expanded and covering the screen.
     */
    val isQuickSettingsShadeVisible: Boolean
        get() = windows.any { win ->
            if (win.windowType == WindowLayerSummary.TYPE_NAVIGATION_BAR) {
                false
            } else if (win.windowType == WindowLayerSummary.TYPE_STATUS_BAR) {
                win.containsQuickSettingsNodes || win.boundsHeightPx > (win.screenHeightPx * 0.18f).toInt()
            } else if (win.packageName == "com.android.systemui") {
                win.containsQuickSettingsNodes || win.boundsHeightPx > (win.screenHeightPx * 0.25f).toInt()
            } else {
                false
            }
        }

    /**
     * Returns true when the Quick Settings shade is collapsed and an application window is visible.
     */
    val isShadeFullyCollapsedAndAppFocused: Boolean
        get() {
            if (isQuickSettingsShadeVisible) return false
            if (windows.any { it.isTrampolineActivity }) return false
            return windows.any { win ->
                win.windowType == WindowLayerSummary.TYPE_APPLICATION &&
                    win.packageName != "com.android.systemui" &&
                    !win.isTrampolineActivity
            }
        }

    /**
     * All active `com.google.android.googlequicksearchbox` window snapshots across all layers.
     */
    val allGoogleOverlaySnapshots: List<ParsedNodeSnapshot>
        get() = windows.mapNotNull { win ->
            if (win.packageName == GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE) {
                win.rootSnapshot
            } else {
                null
            }
        }

    val googleOverlaySnapshot: ParsedNodeSnapshot?
        get() = allGoogleOverlaySnapshots.firstOrNull()

    /**
     * True whenever `com.google.android.googlequicksearchbox` is present in `windows`.
     */
    val isGoogleOverlayVisible: Boolean
        get() = windows.any {
            it.packageName == GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE && it.rootSnapshot != null
        }

    /**
     * Exact center `(x, y)` of the `TYPE_NAVIGATION_BAR` window if present, or dynamically computed
     * from the current screen bounds and density via [PlatformCompat.computeNavigationHandleCenter].
     */
    val navigationHandleCenterPx: Pair<Float, Float>
        get() {
            val navWindow = windows.firstOrNull {
                it.windowType == WindowLayerSummary.TYPE_NAVIGATION_BAR &&
                    it.navBarCenterX != null &&
                    it.navBarCenterY != null
            }
            if (navWindow?.navBarCenterX != null && navWindow.navBarCenterY != null) {
                return navWindow.navBarCenterX to navWindow.navBarCenterY
            }
            return PlatformCompat.computeNavigationHandleCenter(
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                density = screenDensity,
                navBarBounds = null
            )
        }

    /**
     * Detects the foreground media application package (such as `com.instagram.android` or `org.telegram.messenger`).
     */
    val detectedForegroundAppPackage: String?
        get() = windows.firstOrNull {
            it.windowType == WindowLayerSummary.TYPE_APPLICATION &&
                it.packageName.isNotBlank() &&
                it.packageName != GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE &&
                it.packageName != "com.android.systemui" &&
                !it.isTrampolineActivity
        }?.packageName
}

/**
 * Result of running the 3-tier heuristic node extraction engine on a window snapshot.
 */
sealed class NodeParseOutcome {
    /** Window contains only intermediate listening prompts ("Listening...", "Play, sing, or hum a song") or non-music chrome. */
    data object TransientListeningOrEmpty : NodeParseOutcome()

    /** Circle to Search overlay is active and exposes a clickable audio search button. */
    data class CircleToSearchMusicButtonFound(
        val buttonLabel: String,
        val viewId: String?,
        val fallbackTapX: Int? = null,
        val fallbackTapY: Int? = null
    ) : NodeParseOutcome()

    /** Recognition explicitly failed ("No match found", "Couldn't find a match", "Can't identify song", "Try again"). */
    data class RecognitionFailed(val reason: String) : NodeParseOutcome()

    /** A recognized song title and artist were extracted from the Google Quick Search Box hierarchy. */
    data class SongMatched(val candidate: ExtractedSongCandidate) : NodeParseOutcome()
}

data class ExtractedSongCandidate(
    val songTitle: String,
    val artistName: String,
    val fullRawTitle: String,
    val confidenceTier: Int,
    val matchedByRule: String
) {
    val richnessScore: Int
        get() {
            var score = (4 - confidenceTier.coerceIn(1, 3)) * 100
            if (songTitle.isNotBlank()) score += 50 + songTitle.length.coerceAtMost(40)
            if (artistName.isNotBlank()) score += 80 + artistName.length.coerceAtMost(40)
            return score
        }
}

data class InterceptionSessionState(
    val phase: InterceptionPhase = InterceptionPhase.IDLE,
    val sessionId: Long = 0L,
    val armedAtEpochMillis: Long = 0L,
    val listeningStartedAtEpochMillis: Long = 0L,
    val currentAttempt: Int = 0,
    val sourceAppPackage: String? = null,
    val stabilizingCandidate: ExtractedSongCandidate? = null,
    val lastCapturedCandidate: ExtractedSongCandidate? = null,
    val lastFailureReason: String? = null,
    val hasClickedCircleToSearchMusicButton: Boolean = false
) {
    val isActiveWindow: Boolean
        get() = phase == InterceptionPhase.AWAITING_SHADE_COLLAPSE ||
            phase == InterceptionPhase.TRIGGERING_CIRCLE_TO_SEARCH ||
            phase == InterceptionPhase.LISTENING_FOR_SONG ||
            phase == InterceptionPhase.RESULT_STABILIZING

    val isConfirmedListeningForAudio: Boolean
        get() = phase == InterceptionPhase.LISTENING_FOR_SONG ||
            phase == InterceptionPhase.RESULT_STABILIZING
}

data class DiagnosticLogEntry(
    val timestampEpochMillis: Long = System.currentTimeMillis(),
    val phase: InterceptionPhase,
    val tag: String,
    val message: String
)

data class TreeSnapshotRecord(
    val timestampEpochMillis: Long = System.currentTimeMillis(),
    val packageName: String,
    val outcomeSummary: String,
    val nodeCount: Int,
    val serializedTreeJson: String
)
