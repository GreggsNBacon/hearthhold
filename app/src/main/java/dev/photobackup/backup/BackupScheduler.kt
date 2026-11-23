package dev.photobackup.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
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
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object BackupScheduler {
    const val TRIGGER = "pb_trigger"
    const val PERIODIC = "pb_periodic"
    const val NOW = "pb_now"
    const val NIGHT = "pb_night"
    const val KEY_CHARGING_ONLY = "chargingOnly"
    const val KEY_IS_TRIGGER = "isTrigger"

    /** "Back up now": runs even outside the overnight window. */
    const val KEY_MANUAL = "manual"

    /** The once-a-night job at the window start; it re-schedules itself for the next night. */
    const val KEY_IS_NIGHT = "isNight"

    private fun input(chargingOnly: Boolean, isTrigger: Boolean = false, manual: Boolean = false, night: Boolean = false) =
        workDataOf(KEY_CHARGING_ONLY to chargingOnly, KEY_IS_TRIGGER to isTrigger, KEY_MANUAL to manual, KEY_IS_NIGHT to night)

    /** [night]: the overnight window, or null when "Only back up overnight" is off (cancels the night job). */
    fun scheduleAll(
        context: Context,
        chargingOnly: Boolean,
        night: OvernightWindow? = null,
        now: LocalTime = LocalTime.now(),
    ) {
        // Inside the window already: run soon rather than waiting a day.
        enqueueNight(context, chargingOnly, night, night?.let { BackupWindow.nextWindowStartDelay(now, it.start, it.end) })
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

    /**
     * Schedules the one-off night run [delay] from now (replacing any earlier one), or cancels it when [night] is
     * null. A run outside the window still exits at once in the worker, so a late start is harmless.
     */
    fun enqueueNight(context: Context, chargingOnly: Boolean, night: OvernightWindow?, delay: Duration?) {
        val wm = WorkManager.getInstance(context)
        if (night == null || delay == null) {
            wm.cancelUniqueWork(NIGHT)
            return
        }
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(chargingOnly)
                    .build()
            )
            .setInputData(input(chargingOnly, night = true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(NIGHT, ExistingWorkPolicy.REPLACE, request)
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
            .setInputData(input(chargingOnly, manual = true)) // charging flag: only for the re-enqueued trigger
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
