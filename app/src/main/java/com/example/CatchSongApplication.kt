package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.local.CapturedSongEntity
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.preferences.catchSongDataStore
import com.example.data.repository.SongRepository
import com.example.domain.interception.ExtractedSongCandidate
import com.example.domain.interception.InterceptionStateMachine
import com.example.notification.SongNotificationDispatcher
import com.example.service.GoogleMusicAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Application singleton coordinating the Room Database, DataStore Preferences,
 * InterceptionStateMachine, Confirmation Notifications, and Accessibility Service callbacks.
 */
class CatchSongApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val songRepository: SongRepository by lazy {
        SongRepository(database.capturedSongDao())
    }

    val preferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(applicationContext.catchSongDataStore)
    }

    @Volatile
    var mergeDuplicateCapturesCached: Boolean = false
        private set

    @Volatile
    var isTreeDebuggerEnabledCached: Boolean = true
        private set

    override fun onCreate() {
        super.onCreate()
        SongNotificationDispatcher.ensureNotificationChannel(this)

        applicationScope.launch {
            preferencesRepository.preferencesFlow.collectLatest { prefs ->
                mergeDuplicateCapturesCached = prefs.mergeDuplicateCaptures
                isTreeDebuggerEnabledCached = prefs.treeDebuggerEnabled
            }
        }

        wireInterceptionStateMachine()
    }

    private fun wireInterceptionStateMachine() {
        val stateMachine = InterceptionStateMachine.sharedInstance
        stateMachine.listener = object : InterceptionStateMachine.Listener {

            override fun onClickCircleToSearchMusicButton(
                viewId: String?,
                label: String,
                fallbackTapX: Int?,
                fallbackTapY: Int?
            ) {
                GoogleMusicAccessibilityService.activeInstance?.clickMusicSearchButton(
                    viewId = viewId,
                    label = label,
                    fallbackTapX = fallbackTapX,
                    fallbackTapY = fallbackTapY
                )
            }

            override fun onSongCapturedAndStabilized(
                candidate: ExtractedSongCandidate,
                triggerMode: String,
                sourceAppPackage: String?
            ) {
                applicationScope.launch {
                    val savedEntity = songRepository.recordCapturedSong(
                        fullRawTitle = candidate.fullRawTitle,
                        songTitle = candidate.songTitle,
                        artistName = candidate.artistName,
                        captureMode = CapturedSongEntity.CAPTURE_MODE_CIRCLE_TO_SEARCH,
                        sourceAppPackage = sourceAppPackage,
                        mergeDuplicates = mergeDuplicateCapturesCached
                    )
                    SongNotificationDispatcher.dispatchCapturedSongNotification(
                        context = this@CatchSongApplication,
                        song = savedEntity
                    )
                }
            }

            override fun onDismissGoogleOverlayRequested() {
                GoogleMusicAccessibilityService.activeInstance?.dismissGoogleOverlayImmediately()
            }

            override fun onRecognitionFailedOrTimedOut(
                reason: String,
                wasExplicitFailure: Boolean
            ) {
                if (wasExplicitFailure) {
                    SongNotificationDispatcher.dispatchNoMatchNotification(
                        context = this@CatchSongApplication,
                        reason = reason
                    )
                }
            }

            override fun onSessionResetToIdle() {
                com.example.service.SongCatchTileService.requestTileSync(this@CatchSongApplication)
            }
        }
    }
}
