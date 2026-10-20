package com.example.domain.interception

import com.example.data.local.CapturedSongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * State coordinator for the Circle to Search audio recognition pipeline:
 * 1. Remains completely passive when [InterceptionPhase.IDLE] is active.
 * 2. Enforces a `3,200ms` minimum audio sampling window ([minAudioSamplingWindowMs]) after the
 *    Circle to Search audio button is clicked so transient UI states never trigger premature dismissal.
 * 3. Gates explicit "No match" failure detection until `t >= 5,500ms` ([explicitFailureMinElapsedMs]).
 * 4. Debounces matched song candidates for `250ms` ([stabilizationWindowMs]) before committing to Room
 *    and dismissing the Google overlay.
 */
class InterceptionStateMachine(
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val stabilizationWindowMs: Long = 250L,
    private val defaultTimeoutMs: Long = 17_500L,
    private val minAudioSamplingWindowMs: Long = MIN_AUDIO_SAMPLING_WINDOW_MS,
    private val explicitFailureMinElapsedMs: Long = EXPLICIT_FAILURE_MIN_ELAPSED_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    interface Listener {
        fun onClickCircleToSearchMusicButton(
            viewId: String?,
            label: String,
            fallbackTapX: Int?,
            fallbackTapY: Int?
        )

        fun onSongCapturedAndStabilized(
            candidate: ExtractedSongCandidate,
            triggerMode: String,
            sourceAppPackage: String?
        )

        fun onDismissGoogleOverlayRequested()

        fun onRecognitionFailedOrTimedOut(
            reason: String,
            wasExplicitFailure: Boolean
        )

        fun onSessionResetToIdle() {}
    }

    @Volatile
    var listener: Listener? = null

    private val sessionCounter = AtomicLong(0L)
    private val stateLock = Any()

    private val _sessionState = MutableStateFlow(InterceptionSessionState())
    val sessionState: StateFlow<InterceptionSessionState> = _sessionState.asStateFlow()

    private val _diagnosticLogs = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val diagnosticLogs: StateFlow<List<DiagnosticLogEntry>> = _diagnosticLogs.asStateFlow()

    private val _treeSnapshots = MutableStateFlow<List<TreeSnapshotRecord>>(emptyList())
    val treeSnapshots: StateFlow<List<TreeSnapshotRecord>> = _treeSnapshots.asStateFlow()

    private var watchdogJob: Job? = null
    private var stabilizationJob: Job? = null
    private var activeTriggerMode: String = CapturedSongEntity.CAPTURE_MODE_CIRCLE_TO_SEARCH

    fun beginHandshakeSession(
        initialPhase: InterceptionPhase,
        sourceAppPackage: String? = null,
        timeoutMs: Long = defaultTimeoutMs,
        enableInternalWatchdog: Boolean = false
    ): Long? {
        val now = clock()
        var newSessionId = 0L

        synchronized(stateLock) {
            val current = _sessionState.value
            if (current.isActiveWindow && (now - current.armedAtEpochMillis) < 250L) {
                appendLogLocked(current.phase, "DEBOUNCE", "Ignored duplicate trigger within 250ms")
                return null
            }

            cancelJobsLocked()
            newSessionId = sessionCounter.incrementAndGet()
            activeTriggerMode = CapturedSongEntity.CAPTURE_MODE_CIRCLE_TO_SEARCH

            val listeningStart = if (initialPhase == InterceptionPhase.LISTENING_FOR_SONG) now else 0L
            _sessionState.value = InterceptionSessionState(
                phase = initialPhase,
                sessionId = newSessionId,
                armedAtEpochMillis = now,
                listeningStartedAtEpochMillis = listeningStart,
                currentAttempt = if (initialPhase == InterceptionPhase.TRIGGERING_CIRCLE_TO_SEARCH) 1 else 0,
                sourceAppPackage = sourceAppPackage,
                stabilizingCandidate = null,
                lastCapturedCandidate = current.lastCapturedCandidate,
                lastFailureReason = null,
                hasClickedCircleToSearchMusicButton = false
            )

            appendLogLocked(
                initialPhase,
                "SESSION_START",
                "Started session #$newSessionId in $initialPhase (source=${sourceAppPackage ?: "foreground"}, timeout=${timeoutMs}ms)"
            )

            if (enableInternalWatchdog) {
                val capturedSessionId = newSessionId
                watchdogJob = externalScope.launch {
                    delay(timeoutMs.coerceIn(3_000L, 35_000L))
                    onWatchdogTimeout(capturedSessionId)
                }
            }
        }
        return newSessionId
    }

    fun markTriggeringCircleToSearch(
        sessionId: Long,
        attemptNumber: Int,
        detectedSourcePkg: String? = null
    ): Boolean {
        synchronized(stateLock) {
            val current = _sessionState.value
            if (current.sessionId != sessionId || !current.isActiveWindow) return false
            if (current.phase == InterceptionPhase.RESULT_STABILIZING) return false

            val updatedSource = current.sourceAppPackage ?: detectedSourcePkg
            _sessionState.value = current.copy(
                phase = InterceptionPhase.TRIGGERING_CIRCLE_TO_SEARCH,
                currentAttempt = attemptNumber,
                sourceAppPackage = updatedSource
            )
            appendLogLocked(
                InterceptionPhase.TRIGGERING_CIRCLE_TO_SEARCH,
                "CTS_TRIGGER",
                "Circle to Search trigger attempt #$attemptNumber (source=${updatedSource ?: "foreground"})"
            )
            return true
        }
    }

    fun confirmCircleToSearchMusicListeningActive(
        sessionId: Long,
        buttonLabel: String = "Search a song"
    ): Boolean {
        val now = clock()
        synchronized(stateLock) {
            val current = _sessionState.value
            if (current.sessionId != sessionId || !current.isActiveWindow) return false
            if (current.phase == InterceptionPhase.RESULT_STABILIZING) return true

            _sessionState.value = current.copy(
                phase = InterceptionPhase.LISTENING_FOR_SONG,
                listeningStartedAtEpochMillis = if (current.listeningStartedAtEpochMillis > 0L) {
                    current.listeningStartedAtEpochMillis
                } else {
                    now
                },
                hasClickedCircleToSearchMusicButton = true
            )
            appendLogLocked(
                InterceptionPhase.LISTENING_FOR_SONG,
                "CTS_LISTENING",
                "Audio search active ('$buttonLabel'); entering ${minAudioSamplingWindowMs}ms audio sampling window"
            )
            return true
        }
    }

    fun markResultStabilizing(
        sessionId: Long,
        candidate: ExtractedSongCandidate
    ): Boolean {
        synchronized(stateLock) {
            val current = _sessionState.value
            if (current.sessionId != sessionId || !current.isActiveWindow) return false

            val existing = current.stabilizingCandidate
            val chosen = if (existing == null || candidate.richnessScore >= existing.richnessScore) {
                candidate
            } else {
                existing
            }

            _sessionState.value = current.copy(
                phase = InterceptionPhase.RESULT_STABILIZING,
                stabilizingCandidate = chosen
            )
            appendLogLocked(
                InterceptionPhase.RESULT_STABILIZING,
                "STABILIZING",
                "Stabilizing [${chosen.songTitle} - ${chosen.artistName}] for ${stabilizationWindowMs}ms"
            )
            return true
        }
    }

    fun commitStabilizedSongMatch(
        sessionId: Long,
        candidate: ExtractedSongCandidate
    ): Boolean {
        val modeToReport: String
        val sourcePkg: String?

        synchronized(stateLock) {
            val current = _sessionState.value
            if (current.sessionId != sessionId || !current.isActiveWindow) return false

            cancelJobsLocked()
            modeToReport = activeTriggerMode
            sourcePkg = current.sourceAppPackage

            _sessionState.value = current.copy(
                phase = InterceptionPhase.IDLE,
                stabilizingCandidate = null,
                lastCapturedCandidate = candidate,
                lastFailureReason = null,
                hasClickedCircleToSearchMusicButton = false
            )
            appendLogLocked(
                InterceptionPhase.IDLE,
                "CAPTURED",
                "Captured [${candidate.songTitle} - ${candidate.artistName}] (source=${sourcePkg ?: "foreground"}); dismissing overlay"
            )
        }

        listener?.onSongCapturedAndStabilized(
            candidate = candidate,
            triggerMode = modeToReport,
            sourceAppPackage = sourcePkg
        )
        listener?.onDismissGoogleOverlayRequested()
        listener?.onSessionResetToIdle()
        return true
    }

    fun reportExplicitNoMatchAfterGracePeriod(
        sessionId: Long,
        reason: String
    ) {
        synchronized(stateLock) {
            val current = _sessionState.value
            if (current.sessionId != sessionId || !current.isActiveWindow) return

            cancelJobsLocked()
            _sessionState.value = current.copy(
                phase = InterceptionPhase.IDLE,
                stabilizingCandidate = null,
                lastFailureReason = reason,
                hasClickedCircleToSearchMusicButton = false
            )
            appendLogLocked(
                InterceptionPhase.IDLE,
                "NO_MATCH",
                "Google reported no match after sampling window ('$reason'); dismissing overlay"
            )
        }

        listener?.onDismissGoogleOverlayRequested()
        listener?.onRecognitionFailedOrTimedOut(reason = reason, wasExplicitFailure = false)
        listener?.onSessionResetToIdle()
    }

    fun abortHandshakeOnTriggerFailure(
        sessionId: Long,
        reason: String,
        notifyExplicitFailure: Boolean = false
    ) {
        var shouldNotify = false
        synchronized(stateLock) {
            val current = _sessionState.value
            if (current.sessionId != sessionId || !current.isActiveWindow) return

            cancelJobsLocked()
            _sessionState.value = current.copy(
                phase = InterceptionPhase.IDLE,
                stabilizingCandidate = null,
                lastFailureReason = reason,
                hasClickedCircleToSearchMusicButton = false
            )
            appendLogLocked(InterceptionPhase.IDLE, "HANDSHAKE_ABORT", reason)
            shouldNotify = true
        }

        if (shouldNotify) {
            listener?.onRecognitionFailedOrTimedOut(
                reason = reason,
                wasExplicitFailure = notifyExplicitFailure
            )
            listener?.onSessionResetToIdle()
        }
    }

    fun forceResetSessionToIdle(sessionId: Long) {
        var didReset = false
        synchronized(stateLock) {
            val current = _sessionState.value
            if (current.sessionId == sessionId && current.phase != InterceptionPhase.IDLE) {
                cancelJobsLocked()
                _sessionState.value = current.copy(
                    phase = InterceptionPhase.IDLE,
                    stabilizingCandidate = null,
                    hasClickedCircleToSearchMusicButton = false
                )
                appendLogLocked(
                    InterceptionPhase.IDLE,
                    "FINALLY_IDLE_SYNC",
                    "Guaranteed cleanup reset session #$sessionId to IDLE"
                )
                didReset = true
            }
        }
        if (didReset) {
            listener?.onSessionResetToIdle()
        }
    }

    fun armInterception(
        triggerMode: String = CapturedSongEntity.CAPTURE_MODE_CIRCLE_TO_SEARCH,
        sourceAppPackage: String? = null,
        timeoutMs: Long = defaultTimeoutMs
    ): Boolean {
        val sessionId = beginHandshakeSession(
            initialPhase = InterceptionPhase.TRIGGERING_CIRCLE_TO_SEARCH,
            sourceAppPackage = sourceAppPackage,
            timeoutMs = timeoutMs,
            enableInternalWatchdog = true
        ) ?: return false
        synchronized(stateLock) {
            activeTriggerMode = triggerMode
        }
        return sessionId > 0L
    }

    fun cancelInterception(reason: String = "Cancelled by user") {
        var wasActive = false
        synchronized(stateLock) {
            val current = _sessionState.value
            if (!current.isActiveWindow) return
            wasActive = true
            cancelJobsLocked()
            _sessionState.value = current.copy(
                phase = InterceptionPhase.IDLE,
                stabilizingCandidate = null,
                lastFailureReason = reason,
                hasClickedCircleToSearchMusicButton = false
            )
            appendLogLocked(InterceptionPhase.IDLE, "CANCELLED", reason)
        }
        if (wasActive) {
            listener?.onSessionResetToIdle()
        }
    }

    fun onAccessibilityTreeSnapshot(
        rootSnapshot: ParsedNodeSnapshot?,
        recordTreeSnapshot: Boolean = false,
        detectedSourceAppPackage: String? = null,
        screenWidthPx: Int = 1080,
        screenHeightPx: Int = 2340
    ) {
        val currentSnapshot = _sessionState.value
        if (!currentSnapshot.isActiveWindow || rootSnapshot == null) {
            return
        }
        if (currentSnapshot.phase == InterceptionPhase.AWAITING_SHADE_COLLAPSE) {
            return
        }

        val now = clock()
        val totalElapsed = (now - currentSnapshot.armedAtEpochMillis).coerceAtLeast(0L)
        val listenElapsed = if (currentSnapshot.listeningStartedAtEpochMillis > 0L) {
            (now - currentSnapshot.listeningStartedAtEpochMillis).coerceAtLeast(0L)
        } else {
            totalElapsed
        }

        val allowCtsButton = !currentSnapshot.hasClickedCircleToSearchMusicButton &&
            currentSnapshot.phase != InterceptionPhase.RESULT_STABILIZING

        val allowFailure = currentSnapshot.phase == InterceptionPhase.LISTENING_FOR_SONG &&
            listenElapsed >= minAudioSamplingWindowMs &&
            totalElapsed >= explicitFailureMinElapsedMs

        val outcome = GoogleMusicNodeParser.parseTree(
            root = rootSnapshot,
            allowCircleToSearchButtonDetection = allowCtsButton,
            allowFailureDetection = allowFailure,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        if (recordTreeSnapshot) {
            val (nodeCount, json) = GoogleMusicNodeParser.serializeTreeToJson(rootSnapshot)
            val summary = when (outcome) {
                is NodeParseOutcome.SongMatched ->
                    "MATCHED: ${outcome.candidate.songTitle} (${outcome.candidate.artistName})"
                is NodeParseOutcome.CircleToSearchMusicButtonFound ->
                    "CTS_MUSIC_BTN: ${outcome.buttonLabel}"
                is NodeParseOutcome.RecognitionFailed ->
                    "FAILED: ${outcome.reason}"
                is NodeParseOutcome.TransientListeningOrEmpty ->
                    "LISTENING / TRANSIENT"
            }
            val record = TreeSnapshotRecord(
                timestampEpochMillis = now,
                packageName = rootSnapshot.packageName ?: GoogleMusicNodeParser.GOOGLE_QSB_PACKAGE,
                outcomeSummary = summary,
                nodeCount = nodeCount,
                serializedTreeJson = json
            )
            _treeSnapshots.value = (listOf(record) + _treeSnapshots.value).take(15)
        }

        when (outcome) {
            is NodeParseOutcome.TransientListeningOrEmpty -> Unit

            is NodeParseOutcome.CircleToSearchMusicButtonFound -> {
                var shouldClick = false
                synchronized(stateLock) {
                    val state = _sessionState.value
                    if (state.isActiveWindow &&
                        !state.hasClickedCircleToSearchMusicButton &&
                        state.phase != InterceptionPhase.RESULT_STABILIZING
                    ) {
                        _sessionState.value = state.copy(
                            phase = InterceptionPhase.LISTENING_FOR_SONG,
                            listeningStartedAtEpochMillis = if (state.listeningStartedAtEpochMillis > 0L) {
                                state.listeningStartedAtEpochMillis
                            } else {
                                now
                            },
                            sourceAppPackage = state.sourceAppPackage ?: detectedSourceAppPackage,
                            hasClickedCircleToSearchMusicButton = true
                        )
                        shouldClick = true
                        appendLogLocked(
                            InterceptionPhase.LISTENING_FOR_SONG,
                            "CTS_BUTTON_CLICK",
                            "Clicking Circle to Search music button (${outcome.buttonLabel}, id=${outcome.viewId ?: "pos"})"
                        )
                    }
                }
                if (shouldClick) {
                    listener?.onClickCircleToSearchMusicButton(
                        viewId = outcome.viewId,
                        label = outcome.buttonLabel,
                        fallbackTapX = outcome.fallbackTapX,
                        fallbackTapY = outcome.fallbackTapY
                    )
                }
            }

            is NodeParseOutcome.RecognitionFailed -> {
                if (listenElapsed >= minAudioSamplingWindowMs && totalElapsed >= explicitFailureMinElapsedMs) {
                    reportExplicitNoMatchAfterGracePeriod(
                        sessionId = currentSnapshot.sessionId,
                        reason = outcome.reason
                    )
                }
            }

            is NodeParseOutcome.SongMatched -> {
                onCandidateMatched(outcome.candidate, detectedSourceAppPackage)
            }
        }
    }

    private fun onCandidateMatched(
        candidate: ExtractedSongCandidate,
        detectedSourceAppPackage: String?
    ) {
        synchronized(stateLock) {
            val current = _sessionState.value
            if (!current.isActiveWindow) return

            val existing = current.stabilizingCandidate
            if (existing != null && candidate.richnessScore <= existing.richnessScore) {
                return
            }

            val updatedSource = current.sourceAppPackage ?: detectedSourceAppPackage
            _sessionState.value = current.copy(
                phase = InterceptionPhase.RESULT_STABILIZING,
                sourceAppPackage = updatedSource,
                stabilizingCandidate = candidate
            )
            appendLogLocked(
                InterceptionPhase.RESULT_STABILIZING,
                "CANDIDATE_FOUND",
                "Candidate found [${candidate.songTitle} - ${candidate.artistName}] via ${candidate.matchedByRule}; stabilizing for ${stabilizationWindowMs}ms"
            )

            stabilizationJob?.cancel()
            val capturedSessionId = current.sessionId
            stabilizationJob = externalScope.launch {
                delay(stabilizationWindowMs.coerceAtLeast(10L))
                finalizeStabilizedCapture(capturedSessionId)
            }
        }
    }

    private fun finalizeStabilizedCapture(expectedSessionId: Long) {
        val capturedWinner: ExtractedSongCandidate
        val modeToReport: String
        val sourcePkg: String?

        synchronized(stateLock) {
            val current = _sessionState.value
            if (current.sessionId != expectedSessionId ||
                current.phase != InterceptionPhase.RESULT_STABILIZING ||
                current.stabilizingCandidate == null
            ) {
                return
            }

            val winner = current.stabilizingCandidate
            cancelJobsLocked()

            capturedWinner = winner
            modeToReport = activeTriggerMode
            sourcePkg = current.sourceAppPackage

            _sessionState.value = current.copy(
                phase = InterceptionPhase.IDLE,
                stabilizingCandidate = null,
                lastCapturedCandidate = winner,
                lastFailureReason = null,
                hasClickedCircleToSearchMusicButton = false
            )
            appendLogLocked(
                InterceptionPhase.IDLE,
                "CAPTURED",
                "Captured [${winner.songTitle} - ${winner.artistName}] (raw='${winner.fullRawTitle}'); dismissing Circle to Search overlay"
            )
        }

        listener?.onSongCapturedAndStabilized(
            candidate = capturedWinner,
            triggerMode = modeToReport,
            sourceAppPackage = sourcePkg
        )
        listener?.onDismissGoogleOverlayRequested()
        listener?.onSessionResetToIdle()
    }

    internal fun onWatchdogTimeout(expectedSessionId: Long) {
        var timedOut = false
        val timeoutReason = "Listening window timed out before a song was matched"

        synchronized(stateLock) {
            val current = _sessionState.value
            if (current.sessionId != expectedSessionId || !current.isActiveWindow) return

            if (current.phase == InterceptionPhase.RESULT_STABILIZING && current.stabilizingCandidate != null) {
                finalizeStabilizedCapture(expectedSessionId)
                return
            }

            cancelJobsLocked()
            _sessionState.value = current.copy(
                phase = InterceptionPhase.IDLE,
                stabilizingCandidate = null,
                lastFailureReason = timeoutReason,
                hasClickedCircleToSearchMusicButton = false
            )
            appendLogLocked(InterceptionPhase.IDLE, "WATCHDOG_TIMEOUT", timeoutReason)
            timedOut = true
        }

        if (timedOut) {
            listener?.onDismissGoogleOverlayRequested()
            listener?.onRecognitionFailedOrTimedOut(
                reason = timeoutReason,
                wasExplicitFailure = false
            )
            listener?.onSessionResetToIdle()
        }
    }

    fun recordExternalLog(tag: String, message: String) {
        synchronized(stateLock) {
            appendLogLocked(_sessionState.value.phase, tag, message)
        }
    }

    fun clearDiagnosticLogs() {
        synchronized(stateLock) {
            _diagnosticLogs.value = emptyList()
            _treeSnapshots.value = emptyList()
        }
    }

    private fun cancelJobsLocked() {
        watchdogJob?.cancel()
        watchdogJob = null
        stabilizationJob?.cancel()
        stabilizationJob = null
    }

    private fun appendLogLocked(phase: InterceptionPhase, tag: String, message: String) {
        val entry = DiagnosticLogEntry(
            timestampEpochMillis = clock(),
            phase = phase,
            tag = tag,
            message = message
        )
        _diagnosticLogs.value = (listOf(entry) + _diagnosticLogs.value).take(60)
    }

    companion object {
        const val MIN_AUDIO_SAMPLING_WINDOW_MS = 3_200L
        const val EXPLICIT_FAILURE_MIN_ELAPSED_MS = 5_500L

        val sharedInstance: InterceptionStateMachine by lazy {
            InterceptionStateMachine()
        }
    }
}
