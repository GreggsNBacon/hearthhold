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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.photobackup.media.LocalMediaSource
import java.util.concurrent.TimeUnit

object BackupScheduler {
    const val TRIGGER = "pb_trigger"
    const val PERIODIC = "pb_periodic"
    const val NOW = "pb_now"
    const val KEY_CHARGING_ONLY = "chargingOnly"
    const val KEY_IS_TRIGGER = "isTrigger"

    private fun input(chargingOnly: Boolean, isTrigger: Boolean = false): Data =
        workDataOf(KEY_CHARGING_ONLY to chargingOnly, KEY_IS_TRIGGER to isTrigger)

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
    fun enqueueTrigger(context: Context, chargingOnly: Boolean, replace: Boolean = true) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(chargingOnly)
            .setTriggerContentMaxDelay(1, TimeUnit.MINUTES)
        for (uri in LocalMediaSource(context).contentUris()) constraints.addContentUriTrigger(uri, true)
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints.build())
            .setInputData(input(chargingOnly, isTrigger = true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        // Only the trigger itself may replace itself; other workers must not cancel a running trigger.
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(TRIGGER, policy, request)
    }

    /**
     * A running "Back up now" is kept; anything else (waiting in retry backoff, blocked, finished, none) is replaced,
     * so pressing the button always starts a run soon instead of being ignored.
     */
    internal fun nowPolicy(states: List<WorkInfo.State>): ExistingWorkPolicy =
        if (WorkInfo.State.RUNNING in states) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE

    /** User pressed "Back up now": unmetered only, no charging requirement. */
    fun runNow(context: Context, chargingOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setInputData(input(chargingOnly)) // only used for the re-enqueued trigger, not a constraint here
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        val wm = WorkManager.getInstance(context)
        // Called from the main thread: look up the current state without blocking, then enqueue.
        val infos = wm.getWorkInfosForUniqueWork(NOW)
        infos.addListener({
            val states = try {
                infos.get().map { it.state }
            } catch (_: Exception) {
                emptyList()
            }
            wm.enqueueUniqueWork(NOW, nowPolicy(states), request)
        }, Runnable::run)
    }
}
