package com.example.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.CatchApplication
import com.example.MainActivity
import com.example.R
import com.example.domain.interception.InterceptionPhase
import com.example.domain.interception.InterceptionSessionState
import com.example.domain.interception.InterceptionStateMachine
import com.example.util.SystemPermissionHelper
import com.example.util.PlatformCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Quick Settings Tile Service (`Catch`).
 *
 * When [CatchAccessibilityService.instance] (or a [CircleToSearchInternalAudioEngine.PlatformBridge])
 * is active, [onClick] triggers [CatchAccessibilityService.startCatchSessionFromTile] directly without
 * starting an Activity. The shade is dismissed via [PlatformCompat.dismissQuickSettingsShade] so the
 * foreground media player retains focus and never receives `onPause()`.
 *
 * When the Accessibility Service is not yet enabled, [onClick] opens [MainActivity] via
 * [PlatformCompat.startActivityAndCollapseFromTile] to guide the user through permission setup.
 */
class SongCatchTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObservationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        activeTileInstance = this
    }

    override fun onTileAdded() {
        super.onTileAdded()
        isTileListeningInSystem = true
        (applicationContext as? CatchApplication)?.let { app ->
            serviceScope.launch {
                app.preferencesRepository.setTileAddedToQuickSettings(true)
            }
        }
        syncTileVisualState()
    }

    override fun onStartListening() {
        super.onStartListening()
        activeTileInstance = this
        isTileListeningInSystem = true
        syncTileVisualState()

        stateObservationJob?.cancel()
        stateObservationJob = serviceScope.launch {
            InterceptionStateMachine.sharedInstance.sessionState.collectLatest { session ->
                updateTileForSession(session)
            }
        }
    }

    override fun onStopListening() {
        stateObservationJob?.cancel()
        stateObservationJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        triggerTileClickAction(this)
    }

    fun performTileClick() {
        triggerTileClickAction(this)
    }

    private fun collapseAndStartSetupActivity(intent: Intent, requestCode: Int) {
        lastLaunchedActivityIntentForTest = intent
        PlatformCompat.startActivityAndCollapseFromTile(
            tileService = this,
            intent = intent,
            requestCode = requestCode
        )
    }

    fun syncTileVisualState() {
        updateTileForSession(InterceptionStateMachine.sharedInstance.sessionState.value)
    }

    private fun updateTileForSession(session: InterceptionSessionState) {
        val tile = try {
            qsTile
        } catch (_: Exception) {
            null
        } ?: return

        val isA11yReady = SystemPermissionHelper.isAccessibilityServiceEnabled(applicationContext) ||
            CatchAccessibilityService.instance != null ||
            CircleToSearchInternalAudioEngine.sharedInstance.platformBridge != null

        tile.label = getString(R.string.tile_label_catch_song)

        when {
            session.isConfirmedListeningForAudio -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.tile_subtitle_listening)
                }
            }
            session.phase == InterceptionPhase.AWAITING_SHADE_COLLAPSE ||
                session.phase == InterceptionPhase.TRIGGERING_CIRCLE_TO_SEARCH -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Starting..."
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (isA11yReady) {
                        getString(R.string.tile_subtitle_ready)
                    } else {
                        getString(R.string.tile_subtitle_setup)
                    }
                }
            }
        }
        try {
            tile.updateTile()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        if (activeTileInstance == this) {
            activeTileInstance = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var isTileListeningInSystem: Boolean = false

        @Volatile
        var activeTileInstance: SongCatchTileService? = null

        @Volatile
        var lastLaunchedActivityIntentForTest: Intent? = null

        /**
         * Dispatches the tile click action without launching an Activity when the Accessibility
         * Service is connected, or opens [MainActivity] when permission setup is required.
         */
        fun triggerTileClickAction(tileService: SongCatchTileService? = null) {
            val service = CatchAccessibilityService.instance
            if (service != null) {
                service.startCatchSessionFromTile()
                tileService?.syncTileVisualState()
                return
            }

            val bridge = CircleToSearchInternalAudioEngine.sharedInstance.platformBridge
            if (bridge != null) {
                CircleToSearchInternalAudioEngine.sharedInstance.requestStartFromTile()
                tileService?.syncTileVisualState()
                return
            }

            val context = tileService?.applicationContext ?: return
            val setupIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_SETUP_WIZARD, true)
            }
            tileService.collapseAndStartSetupActivity(setupIntent, requestCode = 1000)
        }

        /**
         * Synchronizes the Quick Settings Tile state whenever a session transitions back to `IDLE`.
         */
        fun requestTileSync(context: Context?) {
            activeTileInstance?.syncTileVisualState()
            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    requestListeningState(
                        context,
                        ComponentName(context, SongCatchTileService::class.java)
                    )
                }
            }
        }
    }
}
