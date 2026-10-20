package com.example.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.example.util.PersianDownloadBrowserLauncher

/**
 * Handles the "Download (Search)" action button directly from the confirmation notification banner,
 * launching `[Song Name] \u062F\u0627\u0646\u0644\u0648\u062F` in the user's default browser and dismissing the notification.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != SongNotificationDispatcher.ACTION_DOWNLOAD_SONG) return
        val rawQuery = intent.getStringExtra(SongNotificationDispatcher.EXTRA_RAW_SONG_QUERY).orEmpty()
        val notificationId = intent.getIntExtra(SongNotificationDispatcher.EXTRA_NOTIFICATION_ID, -1)

        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        if (rawQuery.isNotBlank()) {
            PersianDownloadBrowserLauncher.launchPersianDownloadSearch(context, rawQuery)
        }
    }
}
