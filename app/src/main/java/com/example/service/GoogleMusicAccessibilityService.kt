package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.CatchSongApplication
import com.example.domain.interception.GoogleMusicNodeParser
import com.example.domain.interception.InterceptionStateMachine
import com.example.domain.interception.ParsedNodeSnapshot
import com.example.domain.interception.ScreenWindowEnvironment
import com.example.domain.interception.WindowLayerSummary
import com.example.util.PlatformCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

typealias CatchAccessibilityService = GoogleMusicAccessibilityService

/**
 * AccessibilityService implementation for [CircleToSearchInternalAudioEngine.PlatformBridge].
 *
 * - Collapses the Quick Settings shade via [PlatformCompat.dismissQuickSettingsShade] without
 *   launching an Activity so the foreground media player retains focus.
 * - Inspects all interactive windows (`accessibilityService.windows`) via
 *   `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` so overlays rendered above third-party apps are detected.
 * - Summons Circle to Search via [HiddenApiHelper.triggerCircleToSearchOmni], cascading to a
 *   stationary `650ms` long-press gesture on the navigation handle and [PlatformCompat.launchLegacyMusicSearchFallback]
 *   on pre-Circle-to-Search devices.
 * - Locates and clicks the audio search button inside the Google overlay and dismisses the
 *   overlay via `GLOBAL_ACTION_BACK` once a track is matched.
 */
class GoogleMusicAccessibilityService :
    AccessibilityService(),
    CircleToSearchInternalAudioEngine.PlatformBridge {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var lastNonGoogleForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        CircleToSearchInternalAudioEngine.sharedInstance.platformBridge = this

        runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        }

        InterceptionStateMachine.sharedInstance.recordExternalLog(
            "A11Y_SERVICE",
            "CatchAccessibilityService connected with FLAG_RETRIEVE_INTERACTIVE_WINDOWS enabled"
        )
    }

    /**
     * Entrypoint invoked by [SongCatchTileService.onClick] when this AccessibilityService is active.
     */
    fun startCatchSessionFromTile(timeoutSeconds: Int = 18): Boolean {
        val sourcePkg = detectCurrentForegroundAppPackage()
        return CircleToSearchInternalAudioEngine.sharedInstance.requestStartFromTile(
            sourceAppPackage = sourcePkg,
            timeoutSeconds = timeoutSeconds
        )
    }

    fun triggerFromQuickSettingsTile(timeoutSeconds: Int = 18): Boolean {
        return startCatchSessionFromTile(timeoutSeconds)
    }

    /**
     * Entrypoint invoked by the in-app circular "Catch Now" button.
     */
    fun startCatchSessionFromApp(timeoutSeconds: Int = 18): Boolean {
        val sourcePkg = detectCurrentForegroundAppPackage()
        return CircleToSearchInternalAudioEngine.sharedInstance.requestStartFromInAppButton(
            sourceAppPackage = sourcePkg,
            timeoutSeconds = timeoutSeconds
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val stateMachine = InterceptionStateMachine.sharedInstance
        val currentSession = stateMachine.sessionState.value

        if (!currentSession.isActiveWindow) {
            return
        }

        val eventPkg = event?.packageName?.toString()
        if (eventPkg != null &&
            eventPkg != GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE &&
            eventPkg != packageName &&
            !eventPkg.contains("systemui", ignoreCase = true)
        ) {
            lastNonGoogleForegroundPackage = eventPkg
        }

        val app = applicationContext as? CatchSongApplication
        val recordSnapshots = app?.isTreeDebuggerEnabledCached ?: true

        try {
            val windowList = windows
            for (win in windowList) {
                val winRoot = try {
                    win.root
                } catch (_: Exception) {
                    null
                } ?: continue
                if (winRoot.packageName?.toString() == GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE) {
                    val snapshot = convertNodeTreeSafely(winRoot)
                    if (snapshot != null && recordSnapshots) {
                        if (CircleToSearchInternalAudioEngine.sharedInstance.platformBridge == null) {
                            stateMachine.onAccessibilityTreeSnapshot(
                                rootSnapshot = snapshot,
                                recordTreeSnapshot = true
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun dismissNotificationShadeViaGlobalAction(): Boolean {
        dismissShadeGlobalActionCount += 1
        val dismissed = PlatformCompat.dismissQuickSettingsShade(
            accessibilityService = this,
            context = applicationContext
        )
        if (dismissed) return true
        return try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Exception) {
            false
        }
    }

    override fun triggerCircleToSearchOmni(): Boolean {
        return HiddenApiHelper.triggerCircleToSearchOmni()
    }

    override fun launchLegacyMusicSearchFallback(): Boolean {
        return PlatformCompat.launchLegacyMusicSearchFallback(applicationContext)
    }

    override fun inspectWindowEnvironment(): ScreenWindowEnvironment {
        val (screenW, screenH) = PlatformCompat.resolveScreenBounds(applicationContext)
        val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 3.0f
        val summaries = mutableListOf<WindowLayerSummary>()

        try {
            val liveWindows = windows
            val rect = Rect()
            for (win in liveWindows) {
                win.getBoundsInScreen(rect)
                val widthPx = rect.width().coerceAtLeast(0).takeIf { it > 0 } ?: screenW
                val heightPx = rect.height().coerceAtLeast(0)
                val root = try {
                    win.root
                } catch (_: Exception) {
                    null
                }
                val pkg = root?.packageName?.toString().orEmpty()

                val isBottomNavBarWindow = pkg == "com.android.systemui" &&
                    heightPx in 10..(screenH * 0.12f).toInt() &&
                    rect.bottom >= (screenH - 40)

                val winType = when {
                    isBottomNavBarWindow -> WindowLayerSummary.TYPE_NAVIGATION_BAR
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION -> WindowLayerSummary.TYPE_APPLICATION
                    win.type == AccessibilityWindowInfo.TYPE_SYSTEM -> {
                        if (pkg == "com.android.systemui" && heightPx > screenH * 0.20f) {
                            WindowLayerSummary.TYPE_STATUS_BAR
                        } else {
                            WindowLayerSummary.TYPE_SYSTEM
                        }
                    }
                    else -> WindowLayerSummary.TYPE_SYSTEM
                }

                val isShadeQuickSettings = pkg == "com.android.systemui" &&
                    !isBottomNavBarWindow &&
                    heightPx > screenH * 0.20f

                val googleSnapshot = if (pkg == GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE && root != null) {
                    convertNodeTreeSafely(root)
                } else {
                    null
                }

                summaries.add(
                    WindowLayerSummary(
                        windowType = winType,
                        packageName = pkg,
                        boundsWidthPx = widthPx,
                        boundsHeightPx = heightPx,
                        screenWidthPx = screenW,
                        screenHeightPx = screenH,
                        containsQuickSettingsNodes = isShadeQuickSettings,
                        isTrampolineActivity = TileTriggerTrampolineActivity.isTrampolineCurrentlyActive && pkg == packageName,
                        navBarCenterX = if (isBottomNavBarWindow && !rect.isEmpty) rect.exactCenterX() else null,
                        navBarCenterY = if (isBottomNavBarWindow && !rect.isEmpty) rect.exactCenterY() else null,
                        rootSnapshot = googleSnapshot
                    )
                )
            }
        } catch (_: Exception) {
        }

        if (summaries.isEmpty()) {
            val activeRoot = try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            }
            if (activeRoot != null) {
                val pkg = activeRoot.packageName?.toString().orEmpty()
                val rect = Rect()
                activeRoot.getBoundsInScreen(rect)
                summaries.add(
                    WindowLayerSummary(
                        windowType = if (pkg == "com.android.systemui") {
                            WindowLayerSummary.TYPE_STATUS_BAR
                        } else {
                            WindowLayerSummary.TYPE_APPLICATION
                        },
                        packageName = pkg,
                        boundsWidthPx = rect.width().takeIf { it > 0 } ?: screenW,
                        boundsHeightPx = rect.height().coerceAtLeast(screenH),
                        screenWidthPx = screenW,
                        screenHeightPx = screenH,
                        containsQuickSettingsNodes = pkg == "com.android.systemui",
                        isTrampolineActivity = TileTriggerTrampolineActivity.isTrampolineCurrentlyActive,
                        rootSnapshot = if (pkg == GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE) {
                            convertNodeTreeSafely(activeRoot)
                        } else {
                            null
                        }
                    )
                )
            }
        }

        return ScreenWindowEnvironment(
            windows = summaries,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            screenDensity = density
        )
    }

    override fun dispatchCircleToSearchTrigger(attempt: Int, holdDurationMs: Long): Boolean {
        return try {
            val env = inspectWindowEnvironment()
            val (centerX, baseNavBarY) = env.navigationHandleCenterPx
            val adjustedY = when (attempt) {
                1 -> baseNavBarY
                else -> (env.screenHeightPx - (5f * env.screenDensity).coerceIn(10f, 24f)).coerceAtLeast(100f)
            }

            val path = Path().apply {
                moveTo(centerX, adjustedY)
            }
            val stroke = GestureDescription.StrokeDescription(
                path,
                0L,
                holdDurationMs.coerceIn(500L, 1_000L)
            )
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    override fun clickMusicSearchButton(
        viewId: String?,
        label: String,
        fallbackTapX: Int?,
        fallbackTapY: Int?
    ): Boolean {
        val clickedViaNode = clickCircleToSearchMusicButton(viewId, label)
        if (clickedViaNode) return true

        if (fallbackTapX != null && fallbackTapY != null && fallbackTapX > 0 && fallbackTapY > 0) {
            return dispatchCoordinateTap(fallbackTapX.toFloat(), fallbackTapY.toFloat())
        }
        return false
    }

    fun clickCircleToSearchMusicButton(targetViewId: String?, targetLabel: String): Boolean {
        val rootsToSearch = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (win in windows) {
                val r = try {
                    win.root
                } catch (_: Exception) {
                    null
                } ?: continue
                if (r.packageName?.toString() == GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE) {
                    rootsToSearch.add(r)
                }
            }
            val active = try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            }
            if (active != null &&
                active.packageName?.toString() == GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE &&
                rootsToSearch.none { it == active }
            ) {
                rootsToSearch.add(active)
            }
        } catch (_: Exception) {
        }

        val (screenW, screenH) = PlatformCompat.resolveScreenBounds(applicationContext)
        val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 3.0f
        val rect = Rect()

        for (root in rootsToSearch) {
            if (findAndClickMusicNodeRecursive(
                    node = root,
                    targetViewId = targetViewId,
                    targetLabel = targetLabel,
                    scratchRect = rect,
                    screenW = screenW,
                    screenH = screenH,
                    density = density,
                    allowGeometricFallback = false,
                    depth = 0
                )
            ) {
                return true
            }
        }

        for (root in rootsToSearch) {
            if (findAndClickMusicNodeRecursive(
                    node = root,
                    targetViewId = targetViewId,
                    targetLabel = targetLabel,
                    scratchRect = rect,
                    screenW = screenW,
                    screenH = screenH,
                    density = density,
                    allowGeometricFallback = true,
                    depth = 0
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun findAndClickMusicNodeRecursive(
        node: AccessibilityNodeInfo,
        targetViewId: String?,
        targetLabel: String,
        scratchRect: Rect,
        screenW: Int,
        screenH: Int,
        density: Float,
        allowGeometricFallback: Boolean,
        depth: Int
    ): Boolean {
        if (depth > GoogleMusicNodeParser.MAX_TRAVERSAL_DEPTH) return false
        try {
            val viewId = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val className = node.className?.toString().orEmpty()
            val combinedLower = "$text $desc".lowercase(Locale.ROOT).trim()

            val matchesExplicit = (targetViewId != null && viewId.equals(targetViewId, ignoreCase = true)) ||
                (targetLabel.isNotBlank() &&
                    !targetLabel.startsWith("Geometric") &&
                    !targetLabel.startsWith("Positional") &&
                    combinedLower.contains(targetLabel.lowercase(Locale.ROOT))) ||
                combinedLower.contains("search a song") ||
                combinedLower.contains("find a song") ||
                combinedLower.contains("identify song") ||
                combinedLower.contains("identify music") ||
                combinedLower.contains("music search") ||
                combinedLower.contains("\u062C\u0633\u062A\u062C\u0648\u06CC \u0622\u0647\u0646\u06AF") ||
                combinedLower == "music" ||
                combinedLower == "song" ||
                combinedLower == "hum" ||
                combinedLower == "audio" ||
                combinedLower == "\u0622\u0647\u0646\u06AF" ||
                combinedLower == "\u0645\u0648\u0632\u06CC\u06A9" ||
                viewId.contains("music", ignoreCase = true) ||
                viewId.contains("song", ignoreCase = true) ||
                viewId.contains("audio", ignoreCase = true) ||
                viewId.contains("sound", ignoreCase = true) ||
                viewId.contains("hum", ignoreCase = true)

            var matchesGeometry = false
            if (!matchesExplicit && allowGeometricFallback && node.isClickable) {
                node.getBoundsInScreen(scratchRect)
                val minSizePx = (24f * density).toInt()
                val maxSizePx = (80f * density).toInt()
                val w = scratchRect.width()
                val h = scratchRect.height()
                val cx = scratchRect.centerX()
                val cy = scratchRect.centerY()
                val isExcluded = combinedLower.contains("translate") ||
                    combinedLower.contains("close") ||
                    combinedLower.contains("homework") ||
                    viewId.contains("translate", ignoreCase = true) ||
                    viewId.contains("close", ignoreCase = true)

                val isIconClass = className.contains("ImageView", ignoreCase = true) ||
                    className.contains("ImageButton", ignoreCase = true) ||
                    className.contains("Button", ignoreCase = true) ||
                    className.contains("FrameLayout", ignoreCase = true) ||
                    className.contains("View", ignoreCase = true)

                if (!isExcluded &&
                    isIconClass &&
                    cx > (screenW * 0.55f) &&
                    cy > (screenH * 0.65f) &&
                    w in minSizePx..maxSizePx &&
                    h in minSizePx..maxSizePx
                ) {
                    matchesGeometry = true
                }
            }

            if (matchesExplicit || matchesGeometry) {
                // Walk up to the nearest clickable parent (up to 2 parent levels)
                var clickableTarget: AccessibilityNodeInfo? = node
                var hop = 0
                while (clickableTarget != null && !clickableTarget.isClickable && hop < 2) {
                    clickableTarget = clickableTarget.parent
                    hop++
                }
                val nodeToClick = clickableTarget ?: node
                if (nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                nodeToClick.getBoundsInScreen(scratchRect)
                if (!scratchRect.isEmpty) {
                    return dispatchCoordinateTap(
                        scratchRect.exactCenterX(),
                        scratchRect.exactCenterY()
                    )
                }
            }

            val count = node.childCount.coerceIn(0, 50)
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                if (findAndClickMusicNodeRecursive(
                        node = child,
                        targetViewId = targetViewId,
                        targetLabel = targetLabel,
                        scratchRect = scratchRect,
                        screenW = screenW,
                        screenH = screenH,
                        density = density,
                        allowGeometricFallback = allowGeometricFallback,
                        depth = depth + 1
                    )
                ) {
                    return true
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun dispatchCoordinateTap(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun convertNodeTreeSafely(
        root: AccessibilityNodeInfo,
        depth: Int = 0,
        counter: IntArray = IntArray(1),
        scratchRect: Rect = Rect()
    ): ParsedNodeSnapshot? {
        if (depth > GoogleMusicNodeParser.MAX_TRAVERSAL_DEPTH) return null
        if (counter[0] >= GoogleMusicNodeParser.MAX_TRAVERSAL_NODES) return null
        counter[0] += 1

        return try {
            root.getBoundsInScreen(scratchRect)
            val centerX = if (!scratchRect.isEmpty) scratchRect.centerX() else null
            val centerY = if (!scratchRect.isEmpty) scratchRect.centerY() else null
            val widthPx = if (!scratchRect.isEmpty) scratchRect.width() else null
            val heightPx = if (!scratchRect.isEmpty) scratchRect.height() else null

            val childCount = root.childCount.coerceIn(0, 60)
            val children = ArrayList<ParsedNodeSnapshot>(childCount)
            for (i in 0 until childCount) {
                if (counter[0] >= GoogleMusicNodeParser.MAX_TRAVERSAL_NODES) break
                val child = try {
                    root.getChild(i)
                } catch (_: Exception) {
                    null
                } ?: continue
                val childSnapshot = convertNodeTreeSafely(child, depth + 1, counter, scratchRect)
                if (childSnapshot != null) {
                    children.add(childSnapshot)
                }
            }

            val isHeadingCompat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                root.isHeading
            } else {
                false
            }

            ParsedNodeSnapshot(
                viewIdResourceName = root.viewIdResourceName,
                className = root.className?.toString(),
                text = root.text?.toString(),
                contentDescription = root.contentDescription?.toString(),
                isClickable = root.isClickable,
                isVisibleToUser = root.isVisibleToUser,
                isHeading = isHeadingCompat,
                packageName = root.packageName?.toString(),
                boundsCenterX = centerX,
                boundsCenterY = centerY,
                boundsWidthPx = widthPx,
                boundsHeightPx = heightPx,
                children = children
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun dismissGoogleOverlayImmediately() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Exception) {
        }

        serviceScope.launch {
            delay(180L)
            try {
                val env = inspectWindowEnvironment()
                if (env.isGoogleOverlayVisible) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun syncQuickSettingsTileState() {
        runCatching {
            SongCatchTileService.requestTileSync(applicationContext)
        }
    }

    fun detectCurrentForegroundAppPackage(): String? {
        val detected = inspectWindowEnvironment().detectedForegroundAppPackage
        if (detected != null) {
            lastNonGoogleForegroundPackage = detected
            return detected
        }
        return lastNonGoogleForegroundPackage
    }

    override fun onInterrupt() {
        CircleToSearchInternalAudioEngine.sharedInstance.cancelActivePipeline("AccessibilityService interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (activeInstance == this) {
            activeInstance = null
        }
        if (CircleToSearchInternalAudioEngine.sharedInstance.platformBridge == this) {
            CircleToSearchInternalAudioEngine.sharedInstance.platformBridge = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeInstance == this) {
            activeInstance = null
        }
        if (CircleToSearchInternalAudioEngine.sharedInstance.platformBridge == this) {
            CircleToSearchInternalAudioEngine.sharedInstance.platformBridge = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var activeInstance: GoogleMusicAccessibilityService? = null

        val instance: GoogleMusicAccessibilityService?
            get() = activeInstance

        @Volatile
        var dismissShadeGlobalActionCount: Int = 0
    }
}
