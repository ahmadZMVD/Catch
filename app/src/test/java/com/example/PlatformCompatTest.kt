package com.example

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.CapturedSongEntity
import com.example.data.preferences.AppThemeMode
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.preferences.catchSongDataStore
import com.example.data.repository.SongRepository
import com.example.domain.interception.ExtractedSongCandidate
import com.example.domain.interception.GoogleMusicNodeParser
import com.example.domain.interception.InterceptionPhase
import com.example.domain.interception.InterceptionStateMachine
import com.example.domain.interception.ParsedNodeSnapshot
import com.example.domain.interception.ScreenWindowEnvironment
import com.example.domain.interception.WindowLayerSummary
import com.example.service.CatchAccessibilityService
import com.example.service.CircleToSearchInternalAudioEngine
import com.example.service.HiddenApiHelper
import com.example.service.SongCatchTileService
import com.example.ui.CatchViewModel
import com.example.ui.SongHistoryScreen
import com.example.ui.theme.CatchSongBoneTheme
import com.example.util.AddedTileOutcome
import com.example.util.PlatformCompat
import com.example.util.RestrictedSettingsSessionUnlocker
import com.example.util.RestrictedUnlockStep
import com.example.util.SystemPermissionHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxxhdpi")
class PlatformCompatTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: CatchApplication
    private lateinit var inMemoryDb: AppDatabase
    private lateinit var repository: SongRepository
    private lateinit var prefsRepository: UserPreferencesRepository
    private lateinit var stateMachine: InterceptionStateMachine
    private lateinit var viewModel: CatchViewModel

    /**
     * Foreground video player activity that records whether `onPause()`
     * is ever invoked when the user taps the Quick Settings Tile.
     */
    class ForegroundVideoPlaybackTestActivity : Activity() {
        var onPauseCallCount: Int = 0
            private set
        var isVideoPlaying: Boolean = false
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            isVideoPlaying = true
        }

        override fun onResume() {
            super.onResume()
            isVideoPlaying = true
        }

        override fun onPause() {
            onPauseCallCount += 1
            isVideoPlaying = false
            super.onPause()
        }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        inMemoryDb = AppDatabase.createInMemory(app)
        repository = SongRepository(inMemoryDb.capturedSongDao())
        prefsRepository = UserPreferencesRepository(app.catchSongDataStore)
        stateMachine = InterceptionStateMachine(
            stabilizationWindowMs = 60L,
            defaultTimeoutMs = 17_500L
        )

        stateMachine.listener = object : InterceptionStateMachine.Listener {
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
                runBlocking {
                    repository.recordCapturedSong(
                        fullRawTitle = candidate.fullRawTitle,
                        songTitle = candidate.songTitle,
                        artistName = candidate.artistName,
                        captureMode = triggerMode,
                        sourceAppPackage = sourceAppPackage,
                        mergeDuplicates = false
                    )
                }
            }

            override fun onDismissGoogleOverlayRequested() {}
            override fun onRecognitionFailedOrTimedOut(reason: String, wasExplicitFailure: Boolean) {}
        }

        viewModel = CatchViewModel(
            appContext = app,
            songRepository = repository,
            preferencesRepository = prefsRepository,
            stateMachine = stateMachine
        )
    }

    @After
    fun tearDown() {
        CircleToSearchInternalAudioEngine.sharedInstance.cancelActivePipeline("test tearDown")
        CatchAccessibilityService.activeInstance = null
        CircleToSearchInternalAudioEngine.sharedInstance.platformBridge = null
        SongCatchTileService.activeTileInstance = null
        SongCatchTileService.lastLaunchedActivityIntentForTest = null
        HiddenApiHelper.testOmniInvoker = null
        inMemoryDb.close()
    }

    /**
     * Zero-pause Quick Settings tile verification:
     * Hosts [ForegroundVideoPlaybackTestActivity] in the `RESUMED` state, triggers `SongCatchTileService.onClick()`,
     * and asserts:
     * 1. `Shadows.shadowOf(application).nextStartedActivity` is `null` (zero Activity launch).
     * 2. `ForegroundVideoPlaybackTestActivity.onPauseCallCount == 0` across the entire 10-second capture lifecycle.
     * 3. `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` (constant `15`) is invoked to collapse the shade.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `tile click uses GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE and never pauses foreground video activity over 10s lifecycle`() = runTest {
        val videoController = Robolectric.buildActivity(ForegroundVideoPlaybackTestActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
        val videoActivity = videoController.get()

        val shadowApp = Shadows.shadowOf(app)
        while (shadowApp.nextStartedActivity != null) {
            // Drain initial setup intent
        }

        assertEquals(0, videoActivity.onPauseCallCount)
        assertTrue(videoActivity.isVideoPlaying)

        var globalActionDismissShadeCount = 0
        var omniTriggeredCount = 0
        var musicButtonClickedCount = 0
        var overlayDismissedCount = 0

        val testEngine = CircleToSearchInternalAudioEngine(
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
            stabilizationDebounceMs = 250L
        )

        val bridge = object : CircleToSearchInternalAudioEngine.PlatformBridge {
            override fun dismissNotificationShadeViaGlobalAction(): Boolean {
                globalActionDismissShadeCount += 1
                return true
            }

            override fun triggerCircleToSearchOmni(): Boolean {
                omniTriggeredCount += 1
                return true
            }

            override fun inspectWindowEnvironment(): ScreenWindowEnvironment {
                val now = testScheduler.currentTime
                val instagramLayer = WindowLayerSummary(
                    windowType = WindowLayerSummary.TYPE_APPLICATION,
                    packageName = "com.instagram.android",
                    boundsHeightPx = 2340
                )
                return when {
                    now < 300L -> ScreenWindowEnvironment(
                        windows = listOf(
                            WindowLayerSummary(
                                windowType = WindowLayerSummary.TYPE_STATUS_BAR,
                                packageName = "com.android.systemui",
                                boundsHeightPx = 2340,
                                containsQuickSettingsNodes = true
                            ),
                            instagramLayer
                        )
                    )
                    now < 900L -> ScreenWindowEnvironment(
                        windows = listOf(instagramLayer)
                    )
                    now < 1_700L -> ScreenWindowEnvironment(
                        windows = listOf(
                            instagramLayer,
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
                                        )
                                    )
                                )
                            )
                        )
                    )
                    now < 6_200L -> ScreenWindowEnvironment(
                        windows = listOf(
                            instagramLayer,
                            WindowLayerSummary(
                                windowType = WindowLayerSummary.TYPE_SYSTEM,
                                packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                boundsHeightPx = 1000,
                                rootSnapshot = ParsedNodeSnapshot(
                                    packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                    children = listOf(
                                        ParsedNodeSnapshot(text = "Listening..."),
                                        ParsedNodeSnapshot(text = "Play, sing, or hum a song")
                                    )
                                )
                            )
                        )
                    )
                    else -> ScreenWindowEnvironment(
                        windows = listOf(
                            instagramLayer,
                            WindowLayerSummary(
                                windowType = WindowLayerSummary.TYPE_SYSTEM,
                                packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                boundsHeightPx = 1000,
                                rootSnapshot = ParsedNodeSnapshot(
                                    packageName = GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                                    children = listOf(
                                        ParsedNodeSnapshot(
                                            viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_title",
                                            text = "Midnight City"
                                        ),
                                        ParsedNodeSnapshot(
                                            viewIdResourceName = "com.google.android.googlequicksearchbox:id/sound_search_result_artist",
                                            text = "M83"
                                        )
                                    )
                                )
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
            ): Boolean {
                musicButtonClickedCount += 1
                return true
            }

            override fun dismissGoogleOverlayImmediately() {
                overlayDismissedCount += 1
            }
        }

        CircleToSearchInternalAudioEngine.sharedInstance.platformBridge = bridge
        testEngine.platformBridge = bridge

        val tileService = Robolectric.setupService(SongCatchTileService::class.java)
        tileService.onClick()

        val nextStarted = shadowApp.nextStartedActivity
        assertNull(
            "TileService.onClick() MUST NOT launch any Activity when CatchAccessibilityService/bridge is active",
            nextStarted
        )
        assertNull(SongCatchTileService.lastLaunchedActivityIntentForTest)

        CircleToSearchInternalAudioEngine.sharedInstance.cancelActivePipeline("switch to virtual clock")

        testEngine.requestStartFromTile(sourceAppPackage = "com.instagram.android")
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(
            "Foreground video activity MUST NEVER receive onPause() over the entire 10-second capture lifecycle",
            0,
            videoActivity.onPauseCallCount
        )
        assertTrue("Foreground video must remain playing", videoActivity.isVideoPlaying)
        assertNull("No Activity may be started at any point during the 10-second lifecycle", shadowApp.nextStartedActivity)

        assertTrue(
            "GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE must be invoked to collapse the shade natively",
            globalActionDismissShadeCount >= 1
        )
        assertEquals(1, musicButtonClickedCount)
        assertEquals(InterceptionPhase.IDLE, stateMachine.sessionState.value.phase)
    }

    /**
     * Multi-API compatibility verification across Android 9 (API 28) through Android 16 (API 36):
     * Verifies all SDK-gated branches in `PlatformCompat` and `HiddenApiHelper`.
     */
    @Test
    @Suppress("DEPRECATION")
    fun `multi-API compatibility layer branches cleanly across API 28, 30, 31, 33, 34, and 36`() {
        val shadowApp = Shadows.shadowOf(app)

        // 1. Shade dismissal on API 28..30 uses ACTION_CLOSE_SYSTEM_DIALOGS broadcast
        val dismissedApi28 = PlatformCompat.dismissQuickSettingsShade(
            accessibilityService = null,
            context = app,
            sdkInt = Build.VERSION_CODES.P
        )
        assertTrue(dismissedApi28)
        assertTrue(
            shadowApp.broadcastIntents.any { it.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS }
        )

        // 2. VoiceInteraction showSessionFromSession parameter count: 3 args on API 28..33, 4 args on API 34+
        assertEquals(3, PlatformCompat.voiceInteractionParameterCount(Build.VERSION_CODES.P))
        assertEquals(3, PlatformCompat.voiceInteractionParameterCount(Build.VERSION_CODES.R))
        assertEquals(3, PlatformCompat.voiceInteractionParameterCount(Build.VERSION_CODES.TIRAMISU))
        assertEquals(4, PlatformCompat.voiceInteractionParameterCount(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertEquals(4, PlatformCompat.voiceInteractionParameterCount(36))

        // 3. StatusBarManager tile placement request availability: false on API 28..32, true on API 33+
        assertFalse(PlatformCompat.isTilePlacementRequestSupported(Build.VERSION_CODES.P))
        assertFalse(PlatformCompat.isTilePlacementRequestSupported(Build.VERSION_CODES.S_V2))
        assertTrue(PlatformCompat.isTilePlacementRequestSupported(Build.VERSION_CODES.TIRAMISU))
        assertTrue(PlatformCompat.isTilePlacementRequestSupported(36))

        var api29TileOutcome: AddedTileOutcome? = null
        PlatformCompat.requestAddQuickSettingsTile(app, sdkInt = Build.VERSION_CODES.Q) { outcome ->
            api29TileOutcome = outcome
        }
        assertEquals(AddedTileOutcome.DISMISSED_OR_UNSUPPORTED, api29TileOutcome)

        // 4. Legacy MUSIC_SEARCH intent builder targets Google QSB with NEW_TASK | NO_ANIMATION
        val legacyIntent = PlatformCompat.buildLegacyMusicSearchIntent()
        assertEquals(PlatformCompat.ACTION_GOOGLE_MUSIC_SEARCH, legacyIntent.action)
        assertEquals("com.google.android.googlequicksearchbox", legacyIntent.`package`)

        // 5. Dynamic navigation handle coordinates across compact phone, tall phone, and tablet
        val (compactX, compactY) = PlatformCompat.computeNavigationHandleCenter(
            screenWidthPx = 720,
            screenHeightPx = 1560,
            density = 2.0f,
            navBarBounds = null
        )
        assertEquals(360f, compactX, 0.5f)
        assertTrue(compactY in 1530f..1550f)

        val explicitNavRect = Rect(0, 2304, 1080, 2340)
        val (rectX, rectY) = PlatformCompat.computeNavigationHandleCenter(
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            density = 3.0f,
            navBarBounds = explicitNavRect
        )
        assertEquals(540f, rectX, 0.5f)
        assertEquals(2322f, rectY, 0.5f)

        val (tabletX, tabletY) = PlatformCompat.computeNavigationHandleCenter(
            screenWidthPx = 1600,
            screenHeightPx = 2560,
            density = 2.0f,
            navBarBounds = null
        )
        assertEquals(800f, tabletX, 0.5f)
        assertTrue(tabletY in 2530f..2550f)

        // 6. Haptic pulse and notification permission checks across API 28 and 36
        PlatformCompat.triggerCaptureHapticPulse(app, sdkInt = Build.VERSION_CODES.P)
        PlatformCompat.triggerCaptureHapticPulse(app, sdkInt = 36)
        assertTrue(PlatformCompat.isNotificationPermissionGranted(app, sdkInt = Build.VERSION_CODES.P))
    }

    /**
     * UI and branding verification when Accessibility is enabled:
     * Verifies on the Compose hierarchy (`360dp x 780dp`):
     * 1. App title is `"Catch"` (`R.string.app_name == "Catch"`).
     * 2. `"Ready to Catch"` is absent from the Home Screen (`onAllNodesWithText("Ready to Catch")` is empty).
     * 3. Top header has `WindowInsets.safeDrawing` + `>= 20.dp` extra top clearance.
     * 4. Tapping Settings (`open_settings_button`) opens the Settings bottom sheet with `"Bone Light"`,
     *    `"Obsidian Dark"`, and `"System"` theme pills.
     * 5. The bottom `"Catch Now"` button (`catch_now_button`) is a `56.dp` circular button (`width == height == 56.dp`).
     */
    @Test
    fun `home screen displays Catch branding, omits Ready to Catch, enforces top cutout clearance, and renders 56dp circular button`() {
        val songs = runBlocking {
            val s1 = repository.recordCapturedSong(
                fullRawTitle = "\u06AF\u0648\u0647\u0631 \u06A9\u0645\u06CC\u0627\u0628 - \u0645\u062D\u0633\u0646 \u0686\u0627\u0648\u0634\u06CC",
                songTitle = "\u06AF\u0648\u0647\u0631 \u06A9\u0645\u06CC\u0627\u0628",
                artistName = "\u0645\u062D\u0633\u0646 \u0686\u0627\u0648\u0634\u06CC",
                captureMode = CapturedSongEntity.CAPTURE_MODE_CIRCLE_TO_SEARCH,
                sourceAppPackage = "com.instagram.android",
                mergeDuplicates = false
            )
            val s2 = repository.recordCapturedSong(
                fullRawTitle = "Blinding Lights - The Weeknd",
                songTitle = "Blinding Lights",
                artistName = "The Weeknd",
                captureMode = CapturedSongEntity.CAPTURE_MODE_CIRCLE_TO_SEARCH,
                sourceAppPackage = "org.telegram.messenger",
                mergeDuplicates = false
            )
            listOf(s2, s1)
        }

        val initialState = viewModel.uiState.value.copy(
            songs = songs,
            totalCount = songs.size,
            healthStatus = viewModel.uiState.value.healthStatus.copy(
                isAccessibilityServiceEnabled = true
            )
        )

        composeTestRule.setContent {
            CatchSongBoneTheme(themeMode = AppThemeMode.LIGHT_BONE) {
                Box(modifier = Modifier.size(width = 360.dp, height = 780.dp)) {
                    SongHistoryScreen(
                        uiState = initialState,
                        viewModel = viewModel,
                        simulatedTopCutoutInset = 38.dp,
                        simulatedBottomNavInset = 16.dp
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // 1. Verify app name resource and header title are "Catch"
        assertEquals("Catch", app.getString(R.string.app_name))
        assertEquals("Catch", app.getString(R.string.tile_label_catch_song))
        assertEquals("Catch", app.getString(R.string.accessibility_service_label))
        composeTestRule.onNodeWithTag("app_header_title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Catch").assertIsDisplayed()

        // 2. Verify "Ready to Catch" does NOT exist anywhere in the UI tree
        val readyToCatchNodes = composeTestRule.onAllNodesWithText("Ready to Catch", substring = true)
            .fetchSemanticsNodes()
        assertTrue(
            "'Ready to Catch' must be completely removed from the Home Screen",
            readyToCatchNodes.isEmpty()
        )

        // 3. Verify Top Header row clears the 38.dp cutout + >=20.dp extra top margin (>= 54.dp)
        val headerRowNode = composeTestRule.onNodeWithTag("top_header_optical_row").fetchSemanticsNode()
        val density = composeTestRule.density
        val headerTopDp = with(density) { headerRowNode.boundsInRoot.top.toDp() }
        assertTrue(
            "Top header row ($headerTopDp) must have >= 54.dp top clearance (38.dp cutout + >=20.dp extra buffer)",
            headerTopDp >= 54.dp
        )

        // 4. Verify 56.dp circular "Catch Now" button geometry (width == height == 56.dp)
        val catchBtnNode = composeTestRule.onNodeWithTag("catch_now_button").fetchSemanticsNode()
        val btnWidthDp = with(density) { catchBtnNode.boundsInRoot.width.toDp() }
        val btnHeightDp = with(density) { catchBtnNode.boundsInRoot.height.toDp() }
        val btnBottomDp = with(density) { catchBtnNode.boundsInRoot.bottom.toDp() }

        assertEquals(56f, btnWidthDp.value, 1.5f)
        assertEquals(56f, btnHeightDp.value, 1.5f)
        assertTrue(
            "Bottom 56.dp circular button ($btnBottomDp) must sit cleanly above the bottom gesture bar (<= 752.dp)",
            btnBottomDp <= 752.dp
        )

        // 5. Verify Settings Gear opens Settings bottom sheet with Theme Switcher
        composeTestRule.onNodeWithTag("open_settings_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_theme_selector").assertIsDisplayed()
        composeTestRule.onNodeWithTag("theme_pill_LIGHT_BONE").assertIsDisplayed()
        composeTestRule.onNodeWithTag("theme_pill_DARK_OBSIDIAN").assertIsDisplayed()
        composeTestRule.onNodeWithTag("theme_pill_SYSTEM_AUTO").assertIsDisplayed()
    }

    /**
     * Verifies Android 13-16 Restricted Settings unlock flow (`Step 2A -> 2B -> 2C`),
     * `PackageInstaller` Store Session self-upgrade unlocker, Legacy Prototype (`com.aistudio.catchsong.kxmpzq`)
     * warning card, and hiding of `catch_now_button` while Accessibility is disabled.
     */
    @Test
    fun `restricted settings unlock wizard, store session unlocker, legacy prototype detector, and floating button visibility work end-to-end`() {
        // 1. Verify 2A -> 2B -> 2C substep progression logic
        assertEquals(
            RestrictedUnlockStep.STEP_2A_TRIGGER_PROMPT,
            SystemPermissionHelper.resolveRestrictedUnlockSubstep(
                isAccessibilityServiceEnabled = false,
                hasVisitedAccessibilityList = false,
                hasVisitedAppInfo = false
            )
        )
        assertEquals(
            RestrictedUnlockStep.STEP_2B_ALLOW_IN_APP_INFO,
            SystemPermissionHelper.resolveRestrictedUnlockSubstep(
                isAccessibilityServiceEnabled = false,
                hasVisitedAccessibilityList = true,
                hasVisitedAppInfo = false
            )
        )
        assertEquals(
            RestrictedUnlockStep.STEP_2C_ENABLE_ACCESSIBILITY,
            SystemPermissionHelper.resolveRestrictedUnlockSubstep(
                isAccessibilityServiceEnabled = false,
                hasVisitedAccessibilityList = true,
                hasVisitedAppInfo = true
            )
        )
        assertEquals(
            RestrictedUnlockStep.COMPLETED,
            SystemPermissionHelper.resolveRestrictedUnlockSubstep(
                isAccessibilityServiceEnabled = true,
                hasVisitedAccessibilityList = true,
                hasVisitedAppInfo = true
            )
        )

        // 2. Verify Store Session params and self-upgrade execution
        val params = RestrictedSettingsSessionUnlocker.createStoreSessionParams(app.packageName, sdkInt = 36)
        assertNotNull(params)
        val unlockResult = RestrictedSettingsSessionUnlocker.executeStoreSessionSelfUpgrade(app, sdkInt = 36)
        assertNotNull(unlockResult.outcome)

        // 3. Verify legacy prototype uninstaller intent targets com.aistudio.catchsong.kxmpzq
        val uninstallIntent = SystemPermissionHelper.buildUninstallLegacyPrototypeIntent()
        assertEquals(Intent.ACTION_DELETE, uninstallIntent.action)
        assertEquals("package:com.aistudio.catchsong.kxmpzq", uninstallIntent.dataString)

        // 4. Render Home Screen while Accessibility is disabled & legacy prototype is detected
        val setupState = viewModel.uiState.value.copy(
            isSetupCardExpanded = true,
            healthStatus = viewModel.uiState.value.healthStatus.copy(
                isAccessibilityServiceEnabled = false,
                isLegacyPrototypeInstalled = true
            )
        )

        composeTestRule.setContent {
            CatchSongBoneTheme(themeMode = AppThemeMode.LIGHT_BONE) {
                Box(modifier = Modifier.size(width = 360.dp, height = 780.dp)) {
                    SongHistoryScreen(
                        uiState = setupState,
                        viewModel = viewModel,
                        simulatedTopCutoutInset = 38.dp,
                        simulatedBottomNavInset = 16.dp
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Verify catch_now_button is hidden while Accessibility is disabled so it never overlaps the Setup Wizard card
        assertTrue(
            "Floating catch_now_button must be hidden while Accessibility is disabled",
            composeTestRule.onAllNodesWithTag("catch_now_button").fetchSemanticsNodes().isEmpty()
        )

        // Verify legacy prototype warning card and Restricted Settings 2A -> 2B -> 2C buttons are present
        composeTestRule.onNodeWithTag("legacy_prototype_warning_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("uninstall_legacy_prototype_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("restricted_settings_unlock_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("step_2a_accessibility_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_app_info_restricted_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("step_2c_enable_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("store_session_auto_unlock_button").assertIsDisplayed()
    }

    /**
     * Multi-form-factor viewport (`411dp x 891dp` @ `1.3x` font scale and `800dp x 1280dp` tablet) verification.
     */
    @Test
    fun `responsive layout adapts cleanly across 411x891 at 1_3x font scale and 800x1280 tablet viewports`() {
        val state = viewModel.uiState.value.copy(
            healthStatus = viewModel.uiState.value.healthStatus.copy(
                isAccessibilityServiceEnabled = true
            )
        )
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2.625f, fontScale = 1.3f)
            ) {
                CatchSongBoneTheme(themeMode = AppThemeMode.DARK_OBSIDIAN) {
                    Box(modifier = Modifier.size(width = 411.dp, height = 891.dp)) {
                        SongHistoryScreen(
                            uiState = state,
                            viewModel = viewModel,
                            simulatedTopCutoutInset = 38.dp,
                            simulatedBottomNavInset = 16.dp
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // Verify "Ready to Catch" is absent
        assertTrue(
            composeTestRule.onAllNodesWithText("Ready to Catch", substring = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )

        val titleNode = composeTestRule.onNodeWithTag("app_header_title").fetchSemanticsNode()
        val settingsNode = composeTestRule.onNodeWithTag("open_settings_button").fetchSemanticsNode()
        assertTrue(
            "Title right edge (${titleNode.boundsInRoot.right}) must not collide with Settings Gear (${settingsNode.boundsInRoot.left})",
            titleNode.boundsInRoot.right <= settingsNode.boundsInRoot.left + 2f
        )

        val catchBtnNode = composeTestRule.onNodeWithTag("catch_now_button").fetchSemanticsNode()
        val btnW = catchBtnNode.boundsInRoot.width / 2.625f
        val btnH = catchBtnNode.boundsInRoot.height / 2.625f
        assertEquals(56f, btnW, 2.0f)
        assertEquals(56f, btnH, 2.0f)
    }
}
