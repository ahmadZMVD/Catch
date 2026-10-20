package com.example.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.CapturedSongEntity
import com.example.util.PlatformCompat

/**
 * Dispatches heads-up confirmation notifications and haptic click pulses
 * when a song is recognized and saved to the local Room database.
 */
object SongNotificationDispatcher {

    const val CHANNEL_ID_CONFIRMATIONS = "song_captured_confirmations"
    const val ACTION_DOWNLOAD_SONG = "com.example.action.NOTIFICATION_DOWNLOAD_SONG"
    const val EXTRA_RAW_SONG_QUERY = "extra_raw_song_query"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID_CONFIRMATIONS)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_CONFIRMATIONS,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_desc)
                    enableVibration(true)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * Triggers a short tactile confirmation pulse on song capture across API 28..36.
     */
    fun triggerCaptureHapticPulse(context: Context) {
        PlatformCompat.triggerCaptureHapticPulse(context)
    }

    /**
     * Builds the confirmation notification for a captured song.
     */
    fun buildCapturedSongNotification(
        context: Context,
        song: CapturedSongEntity,
        notificationId: Int = (song.id.toInt().takeIf { it != 0 } ?: System.currentTimeMillis().toInt())
    ): Notification {
        ensureNotificationChannel(context)

        val formattedBody = if (song.artistName.isNotBlank()) {
            "${song.displayTitle} - ${song.artistName}"
        } else {
            song.displayTitle
        }

        val downloadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DOWNLOAD_SONG
            putExtra(EXTRA_RAW_SONG_QUERY, song.searchQueryBase)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val downloadPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId xor 0x1A2B,
            downloadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val viewListIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val viewListPendingIntent = PendingIntent.getActivity(
            context,
            notificationId xor 0x3C4D,
            viewListIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID_CONFIRMATIONS)
            .setSmallIcon(R.drawable.ic_qs_catch_song)
            .setContentTitle(context.getString(R.string.notification_title_saved))
            .setContentText(formattedBody)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$formattedBody\nTap \"Download (Search)\" to search \"$formattedBody دانلود\" in your browser.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(viewListPendingIntent)
            .addAction(
                R.drawable.ic_qs_catch_song,
                context.getString(R.string.notification_action_download),
                downloadPendingIntent
            )
            .addAction(
                0,
                context.getString(R.string.notification_action_view_list),
                viewListPendingIntent
            )
            .build()
    }

    fun dispatchCapturedSongNotification(context: Context, song: CapturedSongEntity) {
        triggerCaptureHapticPulse(context)

        if (!PlatformCompat.isNotificationPermissionGranted(context)) return

        val notificationId = (song.id.toInt().takeIf { it != 0 } ?: (System.currentTimeMillis() and 0x7FFFFFFF).toInt())
        val notification = buildCapturedSongNotification(context, song, notificationId)
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission revoked between check and notify
        }
    }

    fun dispatchNoMatchNotification(context: Context, reason: String) {
        if (!PlatformCompat.isNotificationPermissionGranted(context)) return
        ensureNotificationChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CONFIRMATIONS)
            .setSmallIcon(R.drawable.ic_qs_catch_song)
            .setContentTitle(context.getString(R.string.notification_title_no_match))
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(0x7001, notification)
        } catch (_: SecurityException) {
            // Ignore when notification permission is not granted
        }
    }
}
