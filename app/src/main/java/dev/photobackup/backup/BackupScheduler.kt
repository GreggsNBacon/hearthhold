package dev.photobackup.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.photobackup.media.LocalMediaSource
import java.util.concurrent.TimeUnit

object BackupScheduler {
    const val TRIGGER = "pb_trigger"
    const val PERIODIC = "pb_periodic"
    const val NOW = "pb_now"
    const val KEY_CHARGING_ONLY = "chargingOnly"

    private fun input(chargingOnly: Boolean): Data = workDataOf(KEY_CHARGING_ONLY to chargingOnly)

    fun scheduleAll(context: Context, chargingOnly: Boolean) {
        enqueueTrigger(context, chargingOnly)
        val periodic = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(chargingOnly)
                    .build()
            )
            .setInputData(input(chargingOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
    }

    /** Runs when new photos/videos appear. The worker re-enqueues it after every run. */
    fun enqueueTrigger(context: Context, chargingOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(chargingOnly)
            .setTriggerContentMaxDelay(1, TimeUnit.MINUTES)
        for (uri in LocalMediaSource(context).contentUris()) constraints.addContentUriTrigger(uri, true)
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints.build())
            .setInputData(input(chargingOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(TRIGGER, ExistingWorkPolicy.REPLACE, request)
    }

    /** User pressed "Back up now": unmetered only, no charging requirement. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }
}
