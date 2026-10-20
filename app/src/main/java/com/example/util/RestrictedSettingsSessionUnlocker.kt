package com.example.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

enum class SessionUnlockOutcome {
    SESSION_COMMITTED,
    SOURCE_APK_UNAVAILABLE,
    FAILED
}

data class SessionUnlockResult(
    val sessionCreated: Boolean,
    val outcome: SessionUnlockOutcome,
    val sessionId: Int = -1,
    val bytesStreamed: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Layer 1 Restricted Settings Unlock Engine:
 * Uses `PackageInstaller.SessionParams(MODE_FULL_INSTALL)` with
 * `setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)` (`2`) on Android 13+ (API 33+)
 * to re-stage the installed APK through a store-attributed installation session.
 */
object RestrictedSettingsSessionUnlocker {

    const val ACTION_SESSION_UNLOCK_STATUS = "io.github.ahmadzmvd.catchapp.ACTION_SESSION_UNLOCK_STATUS"
    const val PACKAGE_SOURCE_STORE_VALUE = 2

    @Volatile
    var lastObservedStatus: Int? = null

    /**
     * Builds a `PackageInstaller.SessionParams` configured with `PACKAGE_SOURCE_STORE` on API 33+
     * and `setRequestUpdateOwnership(true)` on API 34+.
     */
    fun createStoreSessionParams(
        packageName: String,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): PackageInstaller.SessionParams {
        return PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
        }
    }

    /**
     * Opens a `PackageInstaller.Session`, streams the installed base APK (`applicationInfo.sourceDir`),
     * and commits the session via [SessionUnlockReceiver].
     */
    fun triggerStoreSessionSelfUpgrade(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): SessionUnlockOutcome {
        return executeStoreSessionSelfUpgrade(context, sdkInt).outcome
    }

    /**
     * Detailed execution helper returning [SessionUnlockResult] for diagnostics and unit tests.
     */
    fun executeStoreSessionSelfUpgrade(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): SessionUnlockResult {
        val packageInstaller = context.packageManager.packageInstaller
        val params = createStoreSessionParams(context.packageName, sdkInt)
        var session: PackageInstaller.Session? = null
        return try {
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            val sourcePath = context.applicationInfo?.sourceDir
            val apkFile = if (!sourcePath.isNullOrBlank()) File(sourcePath) else null
            var totalBytes = 0L

            if (apkFile != null && apkFile.isFile && apkFile.canRead() && apkFile.length() > 0L) {
                session.openWrite("base.apk", 0, apkFile.length()).use { outStream ->
                    apkFile.inputStream().use { inStream ->
                        totalBytes = inStream.copyTo(outStream)
                    }
                    session.fsync(outStream)
                }
            }

            val callbackIntent = Intent(context, SessionUnlockReceiver::class.java).apply {
                action = ACTION_SESSION_UNLOCK_STATUS
                setPackage(context.packageName)
            }
            val pendingFlags = if (sdkInt >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                pendingFlags
            )
            session.commit(pendingIntent.intentSender)
            session.close()
            SessionUnlockResult(
                sessionCreated = true,
                outcome = SessionUnlockOutcome.SESSION_COMMITTED,
                sessionId = sessionId,
                bytesStreamed = totalBytes
            )
        } catch (t: Throwable) {
            try {
                session?.abandon()
            } catch (_: Throwable) {
            }
            SessionUnlockResult(
                sessionCreated = false,
                outcome = SessionUnlockOutcome.FAILED,
                errorMessage = t.message ?: t.javaClass.simpleName
            )
        }
    }
}

/**
 * Receives `PackageInstaller` session callbacks and launches the user confirmation prompt
 * (`STATUS_PENDING_USER_ACTION`) when requested by the system package installer.
 */
class SessionUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        RestrictedSettingsSessionUnlocker.lastObservedStatus = status

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            }
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmIntent)
                } catch (_: Exception) {
                }
            }
        }
    }
}
