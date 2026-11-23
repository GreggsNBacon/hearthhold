package dev.photobackup.backup

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.photobackup.MainActivity
import dev.photobackup.R
import dev.photobackup.freeup.formatSize

object BackupNotifications {
    const val CHANNEL_PROGRESS = "backup_progress"
    const val CHANNEL_ERRORS = "backup_errors"
    const val CHANNEL_DONE = "backup_done"
    const val PROGRESS_ID = 1001
    const val ERROR_ID = 1002
    const val DONE_ID = 1003
    private const val REQ_REAUTH = 2001
    private const val REQ_OPEN = 2002

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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Backup finished", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun progress(context: Context, done: Int, total: Int, etaSec: Long?, moving: Int = 0): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_hearthhold)
            .setContentTitle(if (moving > 0) BackupMessages.moving(moving) else "Backing up $done of $total")
            .setContentText(if (moving > 0) null else etaSec?.let { "About ${formatEta(it)} left" })
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    /**
     * Posts only when POST_NOTIFICATIONS is granted; never throws. With [reauth] (wrong username or password),
     * tapping it opens the app straight to the "Re-enter drive password" dialog.
     */
    fun error(context: Context, text: String, reauth: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            ensureChannels(context)
            val n = NotificationCompat.Builder(context, CHANNEL_ERRORS)
                .setSmallIcon(R.drawable.ic_stat_hearthhold)
                .setContentTitle("Hearthhold")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .apply { if (reauth) setContentIntent(reauthIntent(context)) }
                .build()
            manager(context).notify(ERROR_ID, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    /** "Backup complete: N files backed up (size)". Quiet channel; posts only with POST_NOTIFICATIONS; never throws. */
    fun complete(context: Context, files: Int, bytes: Long) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            ensureChannels(context)
            val n = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_hearthhold)
                .setContentTitle("Backup complete")
                .setContentText(completeText(files, bytes))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
            manager(context).notify(DONE_ID, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    fun completeText(files: Int, bytes: Long): String =
        "$files ${if (files == 1) "file" else "files"} backed up (${formatSize(bytes)})"

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, REQ_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun clearError(context: Context) = manager(context).cancel(ERROR_ID)

    private fun reauthIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REAUTH, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, REQ_REAUTH, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun formatEta(sec: Long): String = when {
        sec < 60 -> "$sec s"
        sec < 3600 -> "${(sec + 59) / 60} min"
        else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    }
}
