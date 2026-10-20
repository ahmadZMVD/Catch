package com.example

import com.example.domain.interception.ExtractedSongCandidate
import com.example.domain.interception.GoogleMusicNodeParser
import com.example.domain.interception.InterceptionPhase
import com.example.domain.interception.InterceptionStateMachine
import com.example.domain.interception.ParsedNodeSnapshot
import com.example.domain.interception.ScreenWindowEnvironment
import com.example.domain.interception.WindowLayerSummary
import com.example.service.CircleToSearchInternalAudioEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class InterceptionStateMachineStressTest {

    /**
     * Multi-second Circle to Search lifecycle test:
     * Simulates an 8-second real-time timeline over a foreground video player (`com.instagram.android`):
     * - `t = 0ms..450ms`: `TYPE_STATUS_BAR` (`com.android.systemui` Quick Settings panel) is closing.
     * - `t = 450ms..1,100ms`: Foreground window is `com.instagram.android`.
     * - `t = 1,100ms..1,900ms`: Second window `com.google.android.googlequicksearchbox` appears in `windows`
     *   containing the Circle to Search bar and the clickable audio search button (`id/music_search_button`).
     * - `t = 1,900ms..6,400ms` (4.5 seconds of listening): Window shows `"Listening..."` and
     *   `"Play, sing, or hum a song"` (plus an intermediate `"Try again"` label during the `3,200ms` grace period).
     * - `t = 6,400ms`: Window updates to `title = "Blinding Lights"`, `artist = "The Weeknd"`.
     */
    @Test
    fun `multi-second Circle to Search lifecycle over Instagram never closes prematurely and captures Blinding Lights after 4_5s of listening`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val capturedCandidates = mutableListOf<ExtractedSongCandidate>()
        val capturedSourcePackages = mutableListOf<String?>()
        val failureReasons = mutableListOf<String>()
        var dismissOverlayCalls = 0
        var dismissShadeGlobalActionCalls = 0
        var musicButtonClickCount = 0
        var tileSyncCalls = 0
        var prematurelyClosedDuringListening = false
        var prematureFailureFiredDuringListening = false

        val stateMachine = InterceptionStateMachine(
            externalScope = backgroundScope,
            stabilizationWindowMs = 250L,
            defaultTimeoutMs = 17_500L,
            minAudioSamplingWindowMs = 3_200L,
            explicitFailureMinElapsedMs = 5_500L,
            clock = { testScheduler.currentTime }
        )

        stateMachine.listener = object : InterceptionStateMachine.Listener {
            override fun onClickCircleToSearchMusicButton(
                viewId: String?,
                label: String,
                fallbackTapX: Int?,
                fallbackTapY: Int?
            ) {
            }

            override fun onSongCapturedAndStabilized(
                candidate: ExtractedSongCandidate,
                triggerMode: String,
                sourceAppPackage: String?
            ) {
                capturedCandidates.add(candidate)
                capturedSourcePackages.add(sourceAppPackage)
            }

            override fun onDismissGoogleOverlayRequested() {
                dismissOverlayCalls += 1
                if (testScheduler.currentTime < 6_400L) {
                    prematurelyClosedDuringListening = true
                }
            }

            override fun onRecognitionFailedOrTimedOut(reason: String, wasExplicitFailure: Boolean) {
                failureReasons.add(reason)
                if (testScheduler.currentTime < 6_400L) {
                    prematureFailureFiredDuringListening = true
                }
            }
        }

        val engine = CircleToSearchInternalAudioEngine(
            stateMachine = stateMachine,
            engineScope = backgroundScope,
            shadeCollapseMaxWaitMs = 1_200L,
            shadePollIntervalMs = 50L,
            postCollapseSettleBufferMs = 380L,
            omniWaitTimeoutMs = 600L,
            overlayAppearTimeoutMs = 1_100L,
            overlayPollIntervalMs = 60L,
            ctsBarAnimationSettleMs = 350L,
            minAudioSamplingWindowMs = 3_200L,
            explicitFailureMinElapsedMs = 5_500L,
            resultScrapePollIntervalMs = 150L,
            windowDisappearanceThresholdMs = 450L,
            stabilizationDebounceMs = 250L,
            maxTriggerAttempts = 2,
            retryBackoffMs = 220L,
            hardWatchdogTimeoutMs = 17_500L
        )

        engine.platformBridge = object : CircleToSearchInternalAudioEngine.PlatformBridge {
            override fun dismissNotificationShadeViaGlobalAction(): Boolean {
                dismissShadeGlobalActionCalls += 1
                return true
            }

            override fun triggerCircleToSearchOmni(): Boolean {
                return true
            }

            override fun inspectWindowEnvironment(): ScreenWindowEnvironment {
                val now = testScheduler.currentTime
                val instagramWindow = WindowLayerSummary(
                    windowType = WindowLayerSummary.TYPE_APPLICATION,
                    packageName = "com.instagram.android",
                    boundsHeightPx = 2340
                )
                val navBarWindow = WindowLayerSummary(
                    windowType = WindowLayerSummary.TYPE_NAVIGATION_BAR,
                    packageName = "com.android.systemui",
                    boundsHeightPx = 36,
                    navBarCenterX = 540f,
                    navBarCenterY = 2322f
                )

                return when {
                    // t = 0ms..450ms: TYPE_STATUS_BAR (Quick Settings panel) is still closing
                    now < 450L -> ScreenWindowEnvironment(
                        windows = listOf(
                            WindowLayerSummary(
                                windowType = WindowLayerSummary.TYPE_STATUS_BAR,
                                packageName = "com.android.systemui",
                                boundsHeightPx = 2340,
                                containsQuickSettingsNodes = true
                            ),
                            instagramWindow,
                            navBarWindow
                        )
                    )

                    // t = 450ms..1,100ms: Foreground window is com.instagram.android
                    now < 1_100L -> ScreenWindowEnvironment(
                        windows = listOf(instagramWindow, navBarWindow)
                    )

                    // t = 1,100ms..1,900ms: Second window com.google.android.googlequicksearchbox appears
                    now < 1_900L -> ScreenWindowEnvironment(
                        windows = listOf(
                            instagramWindow,
                            WindowLayerSummary(
                                windowType = WindowLayerSummary.TYPE_SYSTEM,
                                packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                boundsHeightPx = 900,
                                rootSnapshot = ParsedNodeSnapshot(
                                    packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                    children = listOf(
                                        ParsedNodeSnapshot(text = "Search anything on your screen"),
                                        ParsedNodeSnapshot(
                                            viewIdResourceName = "com.google.android.googlequicksearchbox:id/music_search_button",
                                            contentDescription = "Search a song",
                                            className = "android.widget.ImageView",
                                            isClickable = true,
                                            boundsCenterX = 910,
                                            boundsCenterY = 2110,
                                            boundsWidthPx = 132,
                                            boundsHeightPx = 132
                                        )
                                    )
                                )
                            ),
                            navBarWindow
                        )
                    )

                    // t = 1,900ms..6,400ms (4.5 seconds of listening): Window shows "Listening..." and "Play, sing, or hum a song"
                    now < 6_400L -> {
                        val listeningChildren = mutableListOf(
                            ParsedNodeSnapshot(
                                viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_prompt",
                                text = "Listening..."
                            ),
                            ParsedNodeSnapshot(
                                text = "Play, sing, or hum a song"
                            )
                        )
                        // Inject a transient "Try again" node at t = 2,500ms..3,500ms during the 3,200ms grace period
                        if (now in 2_500L..3_500L) {
                            listeningChildren.add(ParsedNodeSnapshot(text = "Try again"))
                        }

                        ScreenWindowEnvironment(
                            windows = listOf(
                                instagramWindow,
                                WindowLayerSummary(
                                    windowType = WindowLayerSummary.TYPE_SYSTEM,
                                    packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                    boundsHeightPx = 1050,
                                    rootSnapshot = ParsedNodeSnapshot(
                                        packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                        children = listeningChildren
                                    )
                                ),
                                navBarWindow
                            )
                        )
                    }

                    // t >= 6,400ms: Window updates to title = "Blinding Lights", artist = "The Weeknd"
                    else -> ScreenWindowEnvironment(
                        windows = listOf(
                            instagramWindow,
                            WindowLayerSummary(
                                windowType = WindowLayerSummary.TYPE_SYSTEM,
                                packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                boundsHeightPx = 1050,
                                rootSnapshot = ParsedNodeSnapshot(
                                    packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                    children = listOf(
                                        ParsedNodeSnapshot(
                                            viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_title",
                                            text = "Blinding Lights"
                                        ),
                                        ParsedNodeSnapshot(
                                            viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_artist",
                                            text = "The Weeknd"
                                        )
                                    )
                                )
                            ),
                            navBarWindow
                        )
                    )
                }
            }

            override fun dispatchCircleToSearchTrigger(attempt: Int, holdDurationMs: Long): Boolean {
                return true
            }

            override fun clickMusicSearchButton(
                viewId: String?,
                label: String,
                fallbackTapX: Int?,
                fallbackTapY: Int?
            ): Boolean {
                musicButtonClickCount += 1
                return true
            }

            override fun dismissGoogleOverlayImmediately() {
                dismissOverlayCalls += 1
            }

            override fun syncQuickSettingsTileState() {
                tileSyncCalls += 1
            }
        }

        val started = engine.requestStartFromTile(sourceAppPackage = "com.instagram.android")
        assertTrue(started)

        // Advance through the listening window up to t = 6,350ms
        advanceTimeBy(6_350L)
        runCurrent()

        assertEquals(1, dismissShadeGlobalActionCalls)
        assertFalse(
            "Overlay MUST NOT be closed during the 4.5s listening window",
            prematurelyClosedDuringListening
        )
        assertFalse(
            "Failure notification MUST NOT fire during the 4.5s listening window",
            prematureFailureFiredDuringListening
        )
        assertEquals(0, dismissOverlayCalls)
        assertTrue(failureReasons.isEmpty())

        assertEquals(
            "Audio search button must be clicked exactly once",
            1,
            musicButtonClickCount
        )
        assertEquals(InterceptionPhase.LISTENING_FOR_SONG, stateMachine.sessionState.value.phase)
        assertTrue(stateMachine.sessionState.value.isConfirmedListeningForAudio)

        // Advance past t = 6,400ms + 250ms stabilization (to t = 7,000ms)
        advanceTimeBy(650L)
        runCurrent()

        assertEquals(1, capturedCandidates.size)
        val captured = capturedCandidates.first()
        assertEquals("Blinding Lights", captured.songTitle)
        assertEquals("The Weeknd", captured.artistName)
        assertEquals("Blinding Lights - The Weeknd", captured.fullRawTitle)
        assertEquals("com.instagram.android", capturedSourcePackages.first())
        assertTrue("GLOBAL_ACTION_BACK must be dispatched on capture", dismissOverlayCalls >= 1)
        assertEquals(InterceptionPhase.IDLE, stateMachine.sessionState.value.phase)
        assertFalse(stateMachine.sessionState.value.isConfirmedListeningForAudio)
        assertTrue("Tile sync must be invoked in finally block", tileSyncCalls >= 1)
    }

    /**
     * Watchdog and failure recovery test across 5 failure modes:
     * - Mode A: Circle to Search never opens (`Omni` + 2 gesture attempts + legacy fallback all fail).
     * - Mode B: Circle to Search opens, but the audio search button is missing.
     * - Mode C: User manually swipes away the Google overlay mid-listening (`windows` loses Google QSB for `>= 450ms`).
     * - Mode D: Google listens for the full `17.5s` without matching a song (`withTimeout(17_500L)`).
     * - Mode E: Rapid double-tap on the Quick Settings Tile (`t = 0ms` and `t = 400ms`).
     *
     * Verifies that in 100% of cases, the state resets to `InterceptionPhase.IDLE` within `<= 18,000ms`
     * and never hangs in `"Listening..."`.
     */
    @Test
    fun `watchdog and failure recovery resets Listening state across all 5 failure modes`() = runTest {
        // MODE A: Circle to Search never opens
        run {
            val sm = InterceptionStateMachine(
                externalScope = backgroundScope,
                clock = { testScheduler.currentTime }
            )
            var tileSynced = false
            val gestureAttempts = AtomicInteger(0)
            val engine = CircleToSearchInternalAudioEngine(
                stateMachine = sm,
                engineScope = backgroundScope,
                maxTriggerAttempts = 2
            )
            engine.platformBridge = object : CircleToSearchInternalAudioEngine.PlatformBridge {
                override fun triggerCircleToSearchOmni(): Boolean = false
                override fun inspectWindowEnvironment(): ScreenWindowEnvironment = ScreenWindowEnvironment(
                    windows = listOf(
                        WindowLayerSummary(
                            windowType = WindowLayerSummary.TYPE_APPLICATION,
                            packageName = "org.telegram.messenger",
                            boundsHeightPx = 2340
                        )
                    )
                )
                override fun dispatchCircleToSearchTrigger(attempt: Int, holdDurationMs: Long): Boolean {
                    gestureAttempts.incrementAndGet()
                    return true
                }
                override fun clickMusicSearchButton(
                    viewId: String?,
                    label: String,
                    fallbackTapX: Int?,
                    fallbackTapY: Int?
                ): Boolean = false
                override fun dismissGoogleOverlayImmediately() {}
                override fun syncQuickSettingsTileState() {
                    tileSynced = true
                }
            }

            engine.requestStartFromInAppButton(sourceAppPackage = "org.telegram.messenger")
            advanceTimeBy(5_000L)
            runCurrent()

            assertEquals(2, gestureAttempts.get())
            assertEquals("Mode A must reset to IDLE", InterceptionPhase.IDLE, sm.sessionState.value.phase)
            assertFalse(sm.sessionState.value.isConfirmedListeningForAudio)
            assertTrue(tileSynced)
        }

        // MODE B: Circle to Search opens, but audio search button is missing
        run {
            val sm = InterceptionStateMachine(
                externalScope = backgroundScope,
                clock = { testScheduler.currentTime }
            )
            var tileSynced = false
            val engine = CircleToSearchInternalAudioEngine(
                stateMachine = sm,
                engineScope = backgroundScope
            )
            engine.platformBridge = object : CircleToSearchInternalAudioEngine.PlatformBridge {
                override fun triggerCircleToSearchOmni(): Boolean = true
                override fun inspectWindowEnvironment(): ScreenWindowEnvironment = ScreenWindowEnvironment(
                    windows = listOf(
                        WindowLayerSummary(
                            windowType = WindowLayerSummary.TYPE_SYSTEM,
                            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                            boundsHeightPx = 800,
                            rootSnapshot = ParsedNodeSnapshot(
                                packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                children = listOf(ParsedNodeSnapshot(text = "Translate"))
                            )
                        )
                    )
                )
                override fun dispatchCircleToSearchTrigger(attempt: Int, holdDurationMs: Long): Boolean = true
                override fun clickMusicSearchButton(
                    viewId: String?,
                    label: String,
                    fallbackTapX: Int?,
                    fallbackTapY: Int?
                ): Boolean = false
                override fun dismissGoogleOverlayImmediately() {}
                override fun syncQuickSettingsTileState() {
                    tileSynced = true
                }
            }

            engine.requestStartFromInAppButton()
            advanceTimeBy(4_000L)
            runCurrent()

            assertEquals("Mode B must reset to IDLE", InterceptionPhase.IDLE, sm.sessionState.value.phase)
            assertFalse(sm.sessionState.value.isConfirmedListeningForAudio)
            assertTrue(tileSynced)
        }

        // MODE C: User manually swipes away the Google overlay mid-listening
        run {
            val sm = InterceptionStateMachine(
                externalScope = backgroundScope,
                clock = { testScheduler.currentTime }
            )
            var tileSynced = false
            val startTime = testScheduler.currentTime
            val engine = CircleToSearchInternalAudioEngine(
                stateMachine = sm,
                engineScope = backgroundScope,
                windowDisappearanceThresholdMs = 450L
            )
            engine.platformBridge = object : CircleToSearchInternalAudioEngine.PlatformBridge {
                override fun triggerCircleToSearchOmni(): Boolean = true
                override fun inspectWindowEnvironment(): ScreenWindowEnvironment {
                    val elapsed = testScheduler.currentTime - startTime
                    // Overlay visible for first 2,500ms, then user swipes it away
                    return if (elapsed < 2_500L) {
                        ScreenWindowEnvironment(
                            windows = listOf(
                                WindowLayerSummary(
                                    windowType = WindowLayerSummary.TYPE_SYSTEM,
                                    packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                    boundsHeightPx = 900,
                                    rootSnapshot = ParsedNodeSnapshot(
                                        packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                        children = listOf(
                                            ParsedNodeSnapshot(
                                                viewIdResourceName = "com.google.android.googlequicksearchbox:id/music_search_button",
                                                contentDescription = "Search a song",
                                                isClickable = true
                                            ),
                                            ParsedNodeSnapshot(text = "Listening...")
                                        )
                                    )
                                )
                            )
                        )
                    } else {
                        ScreenWindowEnvironment(
                            windows = listOf(
                                WindowLayerSummary(
                                    windowType = WindowLayerSummary.TYPE_APPLICATION,
                                    packageName = "com.instagram.android",
                                    boundsHeightPx = 2340
                                )
                            )
                        )
                    }
                }
                override fun dispatchCircleToSearchTrigger(attempt: Int, holdDurationMs: Long): Boolean = true
                override fun clickMusicSearchButton(
                    viewId: String?,
                    label: String,
                    fallbackTapX: Int?,
                    fallbackTapY: Int?
                ): Boolean = true
                override fun dismissGoogleOverlayImmediately() {}
                override fun syncQuickSettingsTileState() {
                    tileSynced = true
                }
            }

            engine.requestStartFromInAppButton()
            advanceTimeBy(3_500L)
            runCurrent()

            assertEquals(
                "Mode C must detect overlay disappearance within 450ms and reset to IDLE",
                InterceptionPhase.IDLE,
                sm.sessionState.value.phase
            )
            assertFalse(sm.sessionState.value.isConfirmedListeningForAudio)
            assertTrue(tileSynced)
        }

        // MODE D: Google listens for full 17.5s without matching a song (hard watchdog timeout)
        run {
            val sm = InterceptionStateMachine(
                externalScope = backgroundScope,
                clock = { testScheduler.currentTime }
            )
            var tileSynced = false
            val engine = CircleToSearchInternalAudioEngine(
                stateMachine = sm,
                engineScope = backgroundScope,
                hardWatchdogTimeoutMs = 17_500L
            )
            engine.platformBridge = object : CircleToSearchInternalAudioEngine.PlatformBridge {
                override fun triggerCircleToSearchOmni(): Boolean = true
                override fun inspectWindowEnvironment(): ScreenWindowEnvironment = ScreenWindowEnvironment(
                    windows = listOf(
                        WindowLayerSummary(
                            windowType = WindowLayerSummary.TYPE_SYSTEM,
                            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                            boundsHeightPx = 900,
                            rootSnapshot = ParsedNodeSnapshot(
                                packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                children = listOf(
                                    ParsedNodeSnapshot(
                                        viewIdResourceName = "com.google.android.googlequicksearchbox:id/music_search_button",
                                        contentDescription = "Search a song",
                                        isClickable = true
                                    ),
                                    ParsedNodeSnapshot(text = "Listening...")
                                )
                            )
                        )
                    )
                )
                override fun dispatchCircleToSearchTrigger(attempt: Int, holdDurationMs: Long): Boolean = true
                override fun clickMusicSearchButton(
                    viewId: String?,
                    label: String,
                    fallbackTapX: Int?,
                    fallbackTapY: Int?
                ): Boolean = true
                override fun dismissGoogleOverlayImmediately() {}
                override fun syncQuickSettingsTileState() {
                    tileSynced = true
                }
            }

            engine.requestStartFromInAppButton()
            advanceTimeBy(18_000L)
            runCurrent()

            assertEquals(
                "Mode D must hard-timeout via withTimeout(17_500L) and reset to IDLE",
                InterceptionPhase.IDLE,
                sm.sessionState.value.phase
            )
            assertFalse(sm.sessionState.value.isConfirmedListeningForAudio)
            assertTrue(tileSynced)
        }

        // MODE E: Rapid double-tap on the Quick Settings Tile (t = 0ms and t = 400ms)
        run {
            val sm = InterceptionStateMachine(
                externalScope = backgroundScope,
                clock = { testScheduler.currentTime }
            )
            var tileSynced = false
            val engine = CircleToSearchInternalAudioEngine(
                stateMachine = sm,
                engineScope = backgroundScope,
                hardWatchdogTimeoutMs = 17_500L
            )
            engine.platformBridge = object : CircleToSearchInternalAudioEngine.PlatformBridge {
                override fun triggerCircleToSearchOmni(): Boolean = true
                override fun inspectWindowEnvironment(): ScreenWindowEnvironment = ScreenWindowEnvironment(
                    windows = listOf(
                        WindowLayerSummary(
                            windowType = WindowLayerSummary.TYPE_APPLICATION,
                            packageName = "com.instagram.android",
                            boundsHeightPx = 2340
                        ),
                        WindowLayerSummary(
                            windowType = WindowLayerSummary.TYPE_SYSTEM,
                            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                            boundsHeightPx = 900,
                            rootSnapshot = ParsedNodeSnapshot(
                                packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                children = listOf(
                                    ParsedNodeSnapshot(
                                        viewIdResourceName = "com.google.android.googlequicksearchbox:id/music_search_button",
                                        contentDescription = "Search a song",
                                        isClickable = true
                                    ),
                                    ParsedNodeSnapshot(text = "Listening...")
                                )
                            )
                        )
                    )
                )
                override fun dispatchCircleToSearchTrigger(attempt: Int, holdDurationMs: Long): Boolean = true
                override fun clickMusicSearchButton(
                    viewId: String?,
                    label: String,
                    fallbackTapX: Int?,
                    fallbackTapY: Int?
                ): Boolean = true
                override fun dismissGoogleOverlayImmediately() {}
                override fun syncQuickSettingsTileState() {
                    tileSynced = true
                }
            }

            engine.requestStartFromTile(sourceAppPackage = "com.instagram.android")
            advanceTimeBy(400L)
            runCurrent()
            // Second tap at t = 400ms cancels previous job and starts clean session
            engine.requestStartFromTile(sourceAppPackage = "com.instagram.android")
            advanceTimeBy(18_000L)
            runCurrent()

            assertEquals(
                "Mode E must cleanly cancel previous coroutine and reset to IDLE within <= 18,000ms",
                InterceptionPhase.IDLE,
                sm.sessionState.value.phase
            )
            assertFalse(sm.sessionState.value.isConfirmedListeningForAudio)
            assertTrue(tileSynced)
        }
    }

    @Test
    fun `legacy Android 9 through 12 fallback triggers MUSIC_SEARCH intent when Omni and navigation gesture do not open overlay`() = runTest {
        val sm = InterceptionStateMachine(
            externalScope = backgroundScope,
            clock = { testScheduler.currentTime }
        )
        var legacyFallbackCalled = false
        var legacyOverlayOpened = false
        var capturedCandidate: ExtractedSongCandidate? = null

        sm.listener = object : InterceptionStateMachine.Listener {
            override fun onClickCircleToSearchMusicButton(
                viewId: String?,
                label: String,
                fallbackTapX: Int?,
                fallbackTapY: Int?
            ) {}

            override fun onSongCapturedAndStabilized(
                candidate: ExtractedSongCandidate,
                triggerMode: String,
                sourceAppPackage: String?
            ) {
                capturedCandidate = candidate
            }

            override fun onDismissGoogleOverlayRequested() {}
            override fun onRecognitionFailedOrTimedOut(reason: String, wasExplicitFailure: Boolean) {}
        }

        val engine = CircleToSearchInternalAudioEngine(
            stateMachine = sm,
            engineScope = backgroundScope,
            maxTriggerAttempts = 2
        )

        engine.platformBridge = object : CircleToSearchInternalAudioEngine.PlatformBridge {
            override fun triggerCircleToSearchOmni(): Boolean = false
            override fun dispatchCircleToSearchTrigger(attempt: Int, holdDurationMs: Long): Boolean = false
            override fun launchLegacyMusicSearchFallback(): Boolean {
                legacyFallbackCalled = true
                legacyOverlayOpened = true
                return true
            }
            override fun inspectWindowEnvironment(): ScreenWindowEnvironment {
                return if (!legacyOverlayOpened) {
                    ScreenWindowEnvironment(
                        windows = listOf(
                            WindowLayerSummary(
                                windowType = WindowLayerSummary.TYPE_APPLICATION,
                                packageName = "com.spotify.music",
                                boundsHeightPx = 1920
                            )
                        ),
                        screenWidthPx = 1080,
                        screenHeightPx = 1920
                    )
                } else {
                    ScreenWindowEnvironment(
                        windows = listOf(
                            WindowLayerSummary(
                                windowType = WindowLayerSummary.TYPE_SYSTEM,
                                packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                boundsHeightPx = 1000,
                                rootSnapshot = ParsedNodeSnapshot(
                                    packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                    children = listOf(
                                        ParsedNodeSnapshot(
                                            viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_title",
                                            text = "Veridis Quo"
                                        ),
                                        ParsedNodeSnapshot(
                                            viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_artist",
                                            text = "Daft Punk"
                                        )
                                    )
                                )
                            )
                        ),
                        screenWidthPx = 1080,
                        screenHeightPx = 1920
                    )
                }
            }
            override fun clickMusicSearchButton(
                viewId: String?,
                label: String,
                fallbackTapX: Int?,
                fallbackTapY: Int?
            ): Boolean = true
            override fun dismissGoogleOverlayImmediately() {}
        }

        engine.requestStartFromInAppButton(sourceAppPackage = "com.spotify.music")
        advanceTimeBy(6_000L)
        runCurrent()

        assertTrue("Legacy MUSIC_SEARCH fallback must be invoked when CTS is unavailable", legacyFallbackCalled)
        assertNotNull("Legacy fallback must still capture the matched song", capturedCandidate)
        assertEquals("Veridis Quo", capturedCandidate?.songTitle)
        assertEquals("Daft Punk", capturedCandidate?.artistName)
        assertEquals(InterceptionPhase.IDLE, sm.sessionState.value.phase)
    }

    @Test
    fun `partial-to-complete result stabilization selects enriched title and artist over early title-only frame`() = runTest {
        val captured = mutableListOf<ExtractedSongCandidate>()
        val sm = InterceptionStateMachine(
            externalScope = backgroundScope,
            stabilizationWindowMs = 250L,
            clock = { testScheduler.currentTime }
        )
        sm.listener = object : InterceptionStateMachine.Listener {
            override fun onClickCircleToSearchMusicButton(
                viewId: String?,
                label: String,
                fallbackTapX: Int?,
                fallbackTapY: Int?
            ) {}

            override fun onSongCapturedAndStabilized(
                candidate: ExtractedSongCandidate,
                triggerMode: String,
                sourceAppPackage: String?
            ) {
                captured.add(candidate)
            }

            override fun onDismissGoogleOverlayRequested() {}
            override fun onRecognitionFailedOrTimedOut(reason: String, wasExplicitFailure: Boolean) {}
        }

        sm.armInterception()

        val partialTree = ParsedNodeSnapshot(
            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
            children = listOf(
                ParsedNodeSnapshot(
                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_title",
                    text = "Starboy"
                )
            )
        )
        sm.onAccessibilityTreeSnapshot(partialTree)

        advanceTimeBy(80L)
        runCurrent()
        assertTrue(captured.isEmpty())

        val completeTree = ParsedNodeSnapshot(
            packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
            children = listOf(
                ParsedNodeSnapshot(
                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_title",
                    text = "Starboy"
                ),
                ParsedNodeSnapshot(
                    viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_artist",
                    text = "The Weeknd"
                )
            )
        )
        sm.onAccessibilityTreeSnapshot(completeTree)

        advanceTimeBy(300L)
        runCurrent()

        assertEquals(1, captured.size)
        assertEquals("Starboy", captured.first().songTitle)
        assertEquals("The Weeknd", captured.first().artistName)
        assertEquals("Starboy - The Weeknd", captured.first().fullRawTitle)
        assertNull(sm.sessionState.value.lastFailureReason)
    }
}
