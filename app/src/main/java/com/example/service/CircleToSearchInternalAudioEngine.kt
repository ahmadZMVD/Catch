package com.example.service

import com.example.domain.interception.ExtractedSongCandidate
import com.example.domain.interception.GoogleMusicNodeParser
import com.example.domain.interception.InterceptionPhase
import com.example.domain.interception.InterceptionStateMachine
import com.example.domain.interception.NodeParseOutcome
import com.example.domain.interception.ScreenWindowEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Coordinates the Circle to Search internal audio recognition pipeline:
 *
 * - `awaitShadeCollapsed`: Dismisses the Quick Settings panel via `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE`
 *   (or `ACTION_CLOSE_SYSTEM_DIALOGS` on API 28..30) without starting an Activity, polling
 *   `accessibilityService.windows` until the foreground application holds focus.
 * - `summonContextualSearch`: Invokes `IVoiceInteractionManagerService.showSessionFromSession` (`Omni`),
 *   cascading to a stationary long-press gesture on the navigation handle (`TYPE_NAVIGATION_BAR`) and a
 *   legacy `MUSIC_SEARCH` intent fallback on pre-Circle-to-Search Android 9..12 devices.
 * - `clickAudioSearchAction`: Locates the audio search button inside `com.google.android.googlequicksearchbox`
 *   and dispatches a single click action.
 * - `awaitTrackMatch`: Enforces a `3,200ms` minimum audio sampling window ([minAudioSamplingWindowMs])
 *   before evaluating failure states, stabilizes matched song candidates for `250ms`, dismisses the
 *   Google overlay via `GLOBAL_ACTION_BACK`, and resets state to [InterceptionPhase.IDLE] inside a
 *   guaranteed `finally` block.
 */
class CircleToSearchInternalAudioEngine(
    private val stateMachine: InterceptionStateMachine = InterceptionStateMachine.sharedInstance,
    private val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val shadeCollapseMaxWaitMs: Long = 1_200L,
    private val shadePollIntervalMs: Long = 50L,
    private val postCollapseSettleBufferMs: Long = 380L,
    private val omniWaitTimeoutMs: Long = 600L,
    private val overlayAppearTimeoutMs: Long = 1_100L,
    private val overlayPollIntervalMs: Long = 60L,
    private val ctsBarAnimationSettleMs: Long = 350L,
    private val minAudioSamplingWindowMs: Long = InterceptionStateMachine.MIN_AUDIO_SAMPLING_WINDOW_MS,
    private val explicitFailureMinElapsedMs: Long = InterceptionStateMachine.EXPLICIT_FAILURE_MIN_ELAPSED_MS,
    private val resultScrapePollIntervalMs: Long = 150L,
    private val windowDisappearanceThresholdMs: Long = 450L,
    private val stabilizationDebounceMs: Long = 250L,
    private val maxTriggerAttempts: Int = 2,
    private val retryBackoffMs: Long = 220L,
    private val hardWatchdogTimeoutMs: Long = 17_500L
) {

    interface PlatformBridge {
        fun dismissNotificationShadeViaGlobalAction(): Boolean = true
        fun triggerCircleToSearchOmni(): Boolean = false
        fun inspectWindowEnvironment(): ScreenWindowEnvironment
        fun dispatchCircleToSearchTrigger(attempt: Int, holdDurationMs: Long = 650L): Boolean
        fun launchLegacyMusicSearchFallback(): Boolean = false
        fun clickMusicSearchButton(
            viewId: String?,
            label: String,
            fallbackTapX: Int?,
            fallbackTapY: Int?
        ): Boolean
        fun dismissGoogleOverlayImmediately()
        fun syncQuickSettingsTileState() {}
    }

    @Volatile
    var platformBridge: PlatformBridge? = null

    private val jobLock = Any()
    private var activeHandshakeJob: Job? = null

    fun requestStartFromTile(
        sourceAppPackage: String? = null,
        timeoutSeconds: Int = 18
    ): Boolean {
        return startUnifiedPipeline(
            waitForQuickSettingsShadeCollapse = true,
            sourceAppPackage = sourceAppPackage,
            timeoutSeconds = timeoutSeconds
        )
    }

    fun requestStartFromInAppButton(
        sourceAppPackage: String? = null,
        timeoutSeconds: Int = 18
    ): Boolean {
        return startUnifiedPipeline(
            waitForQuickSettingsShadeCollapse = false,
            sourceAppPackage = sourceAppPackage,
            timeoutSeconds = timeoutSeconds
        )
    }

    fun cancelActivePipeline(reason: String = "Cancelled by user") {
        synchronized(jobLock) {
            activeHandshakeJob?.cancel()
            activeHandshakeJob = null
        }
        stateMachine.cancelInterception(reason)
        (platformBridge ?: GoogleMusicAccessibilityService.activeInstance)?.syncQuickSettingsTileState()
    }

    private fun startUnifiedPipeline(
        waitForQuickSettingsShadeCollapse: Boolean,
        sourceAppPackage: String?,
        timeoutSeconds: Int
    ): Boolean {
        val initialPhase = if (waitForQuickSettingsShadeCollapse) {
            InterceptionPhase.AWAITING_SHADE_COLLAPSE
        } else {
            InterceptionPhase.TRIGGERING_CIRCLE_TO_SEARCH
        }

        val watchdogMs = if (timeoutSeconds in 8..30 && timeoutSeconds != 18) {
            timeoutSeconds * 1_000L
        } else {
            hardWatchdogTimeoutMs
        }

        val sessionId = stateMachine.beginHandshakeSession(
            initialPhase = initialPhase,
            sourceAppPackage = sourceAppPackage,
            timeoutMs = watchdogMs,
            enableInternalWatchdog = false
        ) ?: return false

        synchronized(jobLock) {
            activeHandshakeJob?.cancel()
            activeHandshakeJob = engineScope.launch {
                try {
                    withTimeout(watchdogMs) {
                        runRecognitionPipeline(
                            sessionId = sessionId,
                            fromTile = waitForQuickSettingsShadeCollapse
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    stateMachine.onWatchdogTimeout(sessionId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    stateMachine.recordExternalLog(
                        "PIPELINE_ERR",
                        "Unhandled exception in recognition pipeline: ${e.javaClass.simpleName}: ${e.message}"
                    )
                } finally {
                    resetStateToIdleAndSyncTile(sessionId)
                }
            }
        }
        return true
    }

    internal suspend fun runRecognitionPipeline(
        sessionId: Long,
        fromTile: Boolean
    ) {
        val bridge = platformBridge ?: GoogleMusicAccessibilityService.activeInstance
        if (bridge == null) {
            stateMachine.abortHandshakeOnTriggerFailure(
                sessionId = sessionId,
                reason = "Accessibility Service is not connected. Enable Catch in Settings.",
                notifyExplicitFailure = false
            )
            return
        }

        var elapsedTotalMs = 0L

        if (fromTile) {
            bridge.dismissNotificationShadeViaGlobalAction()
            val shadeElapsed = awaitShadeCollapsed(bridge, sessionId)
            if (shadeElapsed < 0L) {
                stateMachine.abortHandshakeOnTriggerFailure(
                    sessionId = sessionId,
                    reason = "Quick Settings shade did not collapse within ${shadeCollapseMaxWaitMs}ms.",
                    notifyExplicitFailure = false
                )
                return
            }
            elapsedTotalMs += shadeElapsed

            delay(postCollapseSettleBufferMs)
            elapsedTotalMs += postCollapseSettleBufferMs

            if (!isSessionStillActive(sessionId)) return
        }

        val initialEnv = bridge.inspectWindowEnvironment()
        val detectedAppPkg = initialEnv.detectedForegroundAppPackage

        val summonOutcome = summonContextualSearch(
            bridge = bridge,
            sessionId = sessionId,
            initiallyVisible = initialEnv.isGoogleOverlayVisible,
            detectedAppPkg = detectedAppPkg
        )
        elapsedTotalMs += summonOutcome.second

        if (!summonOutcome.first || !isSessionStillActive(sessionId)) {
            stateMachine.abortHandshakeOnTriggerFailure(
                sessionId = sessionId,
                reason = "Circle to Search overlay did not appear. Ensure Circle to Search and Navigation Bar are enabled in system settings.",
                notifyExplicitFailure = false
            )
            return
        }

        if (ctsBarAnimationSettleMs > 0L) {
            delay(ctsBarAnimationSettleMs)
            elapsedTotalMs += ctsBarAnimationSettleMs
        }
        if (!isSessionStillActive(sessionId)) return

        val clickOutcome = clickAudioSearchAction(bridge, sessionId)
        if (!clickOutcome.first) {
            stateMachine.abortHandshakeOnTriggerFailure(
                sessionId = sessionId,
                reason = "Could not locate or click the audio search button inside Circle to Search.",
                notifyExplicitFailure = false
            )
            return
        }
        elapsedTotalMs += clickOutcome.second

        awaitTrackMatch(
            bridge = bridge,
            sessionId = sessionId,
            elapsedBeforeListeningMs = elapsedTotalMs
        )
    }

    internal suspend fun awaitShadeCollapsed(
        bridge: PlatformBridge,
        sessionId: Long
    ): Long {
        var elapsed = 0L
        while (elapsed <= shadeCollapseMaxWaitMs) {
            if (!isSessionStillActive(sessionId)) return -1L
            val env = bridge.inspectWindowEnvironment()
            if (env.isShadeFullyCollapsedAndAppFocused) {
                stateMachine.recordExternalLog(
                    "SHADE_GATE",
                    "Quick Settings shade collapsed after ${elapsed}ms; waiting ${postCollapseSettleBufferMs}ms settle buffer"
                )
                return elapsed
            }
            delay(shadePollIntervalMs)
            elapsed += shadePollIntervalMs
        }
        return -1L
    }

    private suspend fun summonContextualSearch(
        bridge: PlatformBridge,
        sessionId: Long,
        initiallyVisible: Boolean,
        detectedAppPkg: String?
    ): Pair<Boolean, Long> {
        var elapsed = 0L
        var overlayOpened = initiallyVisible

        if (!overlayOpened) {
            val omniInvoked = bridge.triggerCircleToSearchOmni()
            if (omniInvoked) {
                stateMachine.markTriggeringCircleToSearch(
                    sessionId = sessionId,
                    attemptNumber = 1,
                    detectedSourcePkg = detectedAppPkg
                )
                val waitedOmni = pollForGoogleOverlayToAppear(bridge, sessionId, omniWaitTimeoutMs)
                if (waitedOmni >= 0L) {
                    overlayOpened = true
                    elapsed += waitedOmni
                } else {
                    elapsed += omniWaitTimeoutMs
                }
            }
        }

        if (!overlayOpened) {
            val holdDurations = longArrayOf(650L, 750L, 820L)
            for (attempt in 1..maxTriggerAttempts) {
                if (!isSessionStillActive(sessionId)) return false to elapsed

                if (bridge.inspectWindowEnvironment().isGoogleOverlayVisible) {
                    overlayOpened = true
                    break
                }

                val transitioned = stateMachine.markTriggeringCircleToSearch(
                    sessionId = sessionId,
                    attemptNumber = attempt,
                    detectedSourcePkg = detectedAppPkg
                )
                if (!transitioned) return false to elapsed

                val holdMs = holdDurations[(attempt - 1).coerceIn(0, holdDurations.lastIndex)]
                bridge.dispatchCircleToSearchTrigger(attempt = attempt, holdDurationMs = holdMs)

                val waitedGesture = pollForGoogleOverlayToAppear(bridge, sessionId, overlayAppearTimeoutMs)
                if (waitedGesture >= 0L) {
                    overlayOpened = true
                    elapsed += waitedGesture
                    break
                }
                elapsed += overlayAppearTimeoutMs

                if (attempt < maxTriggerAttempts && isSessionStillActive(sessionId)) {
                    delay(retryBackoffMs)
                    elapsed += retryBackoffMs
                }
            }
        }

        if (!overlayOpened && isSessionStillActive(sessionId)) {
            if (bridge.launchLegacyMusicSearchFallback()) {
                val waitedLegacy = pollForGoogleOverlayToAppear(bridge, sessionId, overlayAppearTimeoutMs)
                if (waitedLegacy >= 0L) {
                    overlayOpened = true
                    elapsed += waitedLegacy
                } else {
                    elapsed += overlayAppearTimeoutMs
                }
            }
        }

        return overlayOpened to elapsed
    }

    private suspend fun pollForGoogleOverlayToAppear(
        bridge: PlatformBridge,
        sessionId: Long,
        timeoutMs: Long
    ): Long {
        var elapsed = 0L
        while (elapsed <= timeoutMs) {
            if (!isSessionStillActive(sessionId)) return -1L
            val env = bridge.inspectWindowEnvironment()
            if (env.isGoogleOverlayVisible) {
                return elapsed
            }
            delay(overlayPollIntervalMs)
            elapsed += overlayPollIntervalMs
        }
        return -1L
    }

    private suspend fun clickAudioSearchAction(
        bridge: PlatformBridge,
        sessionId: Long
    ): Pair<Boolean, Long> {
        var elapsed = 0L
        val maxSearchMs = 720L

        while (elapsed <= maxSearchMs) {
            if (!isSessionStillActive(sessionId)) return false to elapsed
            val env = bridge.inspectWindowEnvironment()
            val googleRoots = env.allGoogleOverlaySnapshots

            if (googleRoots.isNotEmpty()) {
                val parseOutcome = GoogleMusicNodeParser.parseMultipleWindows(
                    roots = googleRoots,
                    allowCircleToSearchButtonDetection = true,
                    allowFailureDetection = false,
                    screenWidthPx = env.screenWidthPx,
                    screenHeightPx = env.screenHeightPx
                )

                when (parseOutcome) {
                    is NodeParseOutcome.CircleToSearchMusicButtonFound -> {
                        val clicked = bridge.clickMusicSearchButton(
                            viewId = parseOutcome.viewId,
                            label = parseOutcome.buttonLabel,
                            fallbackTapX = parseOutcome.fallbackTapX,
                            fallbackTapY = parseOutcome.fallbackTapY
                        )
                        if (clicked) {
                            stateMachine.confirmCircleToSearchMusicListeningActive(
                                sessionId = sessionId,
                                buttonLabel = parseOutcome.buttonLabel
                            )
                            return true to elapsed
                        }
                    }

                    is NodeParseOutcome.SongMatched -> {
                        stateMachine.confirmCircleToSearchMusicListeningActive(
                            sessionId = sessionId,
                            buttonLabel = "Direct Match"
                        )
                        return true to elapsed
                    }

                    else -> {
                        if (googleRoots.any { GoogleMusicNodeParser.isAudioListeningUiActive(it) }) {
                            stateMachine.confirmCircleToSearchMusicListeningActive(
                                sessionId = sessionId,
                                buttonLabel = "Audio Listener Already Active"
                            )
                            return true to elapsed
                        }
                    }
                }
            }

            delay(overlayPollIntervalMs)
            elapsed += overlayPollIntervalMs
        }

        val finalEnv = bridge.inspectWindowEnvironment()
        if (finalEnv.isGoogleOverlayVisible) {
            val clickedFallback = bridge.clickMusicSearchButton(
                viewId = null,
                label = "Search a song",
                fallbackTapX = (finalEnv.screenWidthPx * 0.84f).toInt(),
                fallbackTapY = (finalEnv.screenHeightPx * 0.90f).toInt()
            )
            if (clickedFallback) {
                stateMachine.confirmCircleToSearchMusicListeningActive(
                    sessionId = sessionId,
                    buttonLabel = "Coordinate Fallback Music Tap"
                )
                return true to elapsed
            }
        }

        return false to elapsed
    }

    private suspend fun awaitTrackMatch(
        bridge: PlatformBridge,
        sessionId: Long,
        elapsedBeforeListeningMs: Long
    ) {
        var listeningElapsedMs = 0L
        var missingOverlayAccumulatedMs = 0L

        while (isSessionStillActive(sessionId)) {
            val env = bridge.inspectWindowEnvironment()
            val googleRoots = env.allGoogleOverlaySnapshots

            if (googleRoots.isEmpty()) {
                missingOverlayAccumulatedMs += resultScrapePollIntervalMs
                if (missingOverlayAccumulatedMs >= windowDisappearanceThresholdMs) {
                    stateMachine.recordExternalLog(
                        "WINDOW_CLOSED",
                        "Google overlay disappeared for ${missingOverlayAccumulatedMs}ms; resetting to IDLE"
                    )
                    return
                }
            } else {
                missingOverlayAccumulatedMs = 0L

                val isPastMinSamplingWindow = listeningElapsedMs >= minAudioSamplingWindowMs
                val isPastExplicitFailureGate = isPastMinSamplingWindow &&
                    (elapsedBeforeListeningMs + listeningElapsedMs) >= explicitFailureMinElapsedMs

                val parseOutcome = GoogleMusicNodeParser.parseMultipleWindows(
                    roots = googleRoots,
                    allowCircleToSearchButtonDetection = false,
                    allowFailureDetection = isPastExplicitFailureGate,
                    screenWidthPx = env.screenWidthPx,
                    screenHeightPx = env.screenHeightPx
                )

                when (parseOutcome) {
                    is NodeParseOutcome.SongMatched -> {
                        var stabilizedCandidate: ExtractedSongCandidate = parseOutcome.candidate
                        stateMachine.markResultStabilizing(sessionId, stabilizedCandidate)

                        if (stabilizationDebounceMs > 0L) {
                            delay(stabilizationDebounceMs)
                            if (!isSessionStillActive(sessionId)) return

                            val recheckEnv = bridge.inspectWindowEnvironment()
                            val recheckOutcome = GoogleMusicNodeParser.parseMultipleWindows(
                                roots = recheckEnv.allGoogleOverlaySnapshots,
                                allowCircleToSearchButtonDetection = false,
                                allowFailureDetection = false,
                                screenWidthPx = recheckEnv.screenWidthPx,
                                screenHeightPx = recheckEnv.screenHeightPx
                            )
                            if (recheckOutcome is NodeParseOutcome.SongMatched &&
                                recheckOutcome.candidate.richnessScore >= stabilizedCandidate.richnessScore
                            ) {
                                stabilizedCandidate = recheckOutcome.candidate
                            }
                        }

                        stateMachine.commitStabilizedSongMatch(sessionId, stabilizedCandidate)
                        return
                    }

                    is NodeParseOutcome.RecognitionFailed -> {
                        stateMachine.reportExplicitNoMatchAfterGracePeriod(
                            sessionId = sessionId,
                            reason = parseOutcome.reason
                        )
                        return
                    }

                    is NodeParseOutcome.CircleToSearchMusicButtonFound,
                    is NodeParseOutcome.TransientListeningOrEmpty -> Unit
                }
            }

            delay(resultScrapePollIntervalMs)
            listeningElapsedMs += resultScrapePollIntervalMs
        }
    }

    private fun resetStateToIdleAndSyncTile(sessionId: Long) {
        stateMachine.forceResetSessionToIdle(sessionId)
        (platformBridge ?: GoogleMusicAccessibilityService.activeInstance)?.syncQuickSettingsTileState()
    }

    private fun isSessionStillActive(sessionId: Long): Boolean {
        val current = stateMachine.sessionState.value
        return current.sessionId == sessionId && current.isActiveWindow
    }

    companion object {
        val sharedInstance: CircleToSearchInternalAudioEngine by lazy {
            CircleToSearchInternalAudioEngine()
        }
    }
}
