package com.example.service

import android.app.Activity
import android.os.Build
import android.os.Bundle

/**
 * Legacy fallback zero-UI activity kept only for manifest compatibility;
 * `SongCatchTileService` now collapses the Quick Settings shade directly via
 * `AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` without launching any Activity.
 */
class TileTriggerTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        val service = CatchAccessibilityService.instance
        if (service != null) {
            service.startCatchSessionFromTile()
        } else {
            val sourcePkg = intent?.getStringExtra(EXTRA_SOURCE_PACKAGE)
            CircleToSearchInternalAudioEngine.sharedInstance.requestStartFromTile(
                sourceAppPackage = sourcePkg
            )
        }

        finish()
        isTrampolineCurrentlyActive = false
    }

    companion object {
        const val EXTRA_SOURCE_PACKAGE = "extra_source_package"

        @Volatile
        var isTrampolineCurrentlyActive: Boolean = false
    }
}
