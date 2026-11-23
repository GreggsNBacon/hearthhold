package dev.photobackup.backup

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object BackupNotifications {
    const val CHANNEL_PROGRESS = "backup_progress"
    const val CHANNEL_ERRORS = "backup_errors"
    const val PROGRESS_ID = 1001
    const val ERROR_ID = 1002

    private fun manager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels(context: Context) {
        val nm = manager(context)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Backup progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ERRORS, "Backup problems", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun progress(context: Context, done: Int, total: Int, etaSec: Long?): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up $done of $total")
            .setContentText(etaSec?.let { "About ${formatEta(it)} left" })
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    /** Posts only when POST_NOTIFICATIONS is granted; never throws. */
    fun error(context: Context, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            ensureChannels(context)
            val n = NotificationCompat.Builder(context, CHANNEL_ERRORS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Photo Backup")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            manager(context).notify(ERROR_ID, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    fun clearError(context: Context) = manager(context).cancel(ERROR_ID)

    private fun formatEta(sec: Long): String = when {
        sec < 60 -> "$sec s"
        sec < 3600 -> "${(sec + 59) / 60} min"
        else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    }
}
