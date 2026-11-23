package dev.photobackup.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.media.LocalMediaSource
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.KeystoreCredentialCipher
import dev.photobackup.settings.SettingsRead
import dev.photobackup.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

sealed interface StaleDecision {
    /** [waiting]: items waiting, or null when they could not be counted (no photo permission). */
    data class Warn(val days: Int, val waiting: Int?) : StaleDecision
    object None : StaleDecision
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Whether to warn that backups are stuck. [lastSuccessMs] is the last successful run (or the baseline standing in
 * for it). [waitingCount] null = unknown: warn on age alone; 0 = nothing to back up: never warn. At most one warning
 * a day; a warning older than the last success does not count (the success cleared it). Pure.
 */
fun staleDecision(nowMs: Long, lastSuccessMs: Long, lastWarnMs: Long?, waitingCount: Int?, days: Int): StaleDecision {
    if (waitingCount != null && waitingCount <= 0) return StaleDecision.None
    if (nowMs - lastSuccessMs < days * DAY_MS) return StaleDecision.None
    if (lastWarnMs != null && lastWarnMs >= lastSuccessMs && nowMs - lastWarnMs < DAY_MS) return StaleDecision.None
    return StaleDecision.Warn(days, waitingCount)
}

/** Seams for the stale check; tests replace them. */
object StaleCheckFactory {
    /** The settings, or null when they can't be read (the check then does nothing this time). */
    val defaultReadSettings: suspend (Context) -> AppSettings? = { ctx ->
        val r = SettingsStore(ctx.applicationContext, KeystoreCredentialCipher()).settingsResult.first()
        (r as? SettingsRead.Ok)?.settings
    }

    @Volatile var readSettings: suspend (Context) -> AppSettings? = defaultReadSettings

    /** Phone items still needing upload, or null when they can't be counted (no/partial photo permission, error). */
    val defaultWaiting: suspend (Context, AppSettings) -> Int? = { ctx, settings ->
        try {
            val ledger = LedgerDatabase.get(ctx.applicationContext).ledgerDao()
            LocalMediaSource(ctx.applicationContext).query(settings.folders)
                .count { LedgerRules.needsUpload(ledger.get(it.mediaId), it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    @Volatile var waiting: suspend (Context, AppSettings) -> Int? = defaultWaiting

    val defaultNowMs: () -> Long = { System.currentTimeMillis() }

    @Volatile var nowMs: () -> Long = defaultNowMs
}

/** Daily: warns when no run has succeeded for the configured days while items wait. Never logs anything. */
class StaleCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            val settings = StaleCheckFactory.readSettings(applicationContext) ?: return Result.success()
            if (settings.smb == null || settings.destination.isNullOrBlank()) return Result.success() // not set up
            val repo = BackupStatusRepository.get(applicationContext)
            val now = StaleCheckFactory.nowMs()
            val status = repo.status.first()
            // Never succeeded since install/upgrade: count from the first time this check ran.
            val baseline = status.staleBaselineMs ?: now.also { b -> repo.update { it.copy(staleBaselineMs = b) } }
            val lastSuccess = status.lastSuccessMs ?: baseline
            // Cheap checks first: only count (MediaStore) when a warning is possible at all.
            if (staleDecision(now, lastSuccess, status.lastStaleWarnMs, null, settings.staleWarnDays) !is StaleDecision.Warn) {
                return Result.success()
            }
            val waiting = StaleCheckFactory.waiting(applicationContext, settings)
            val d = staleDecision(now, lastSuccess, status.lastStaleWarnMs, waiting, settings.staleWarnDays)
            if (d is StaleDecision.Warn) {
                BackupNotifications.stale(
                    applicationContext,
                    if (d.waiting == null) BackupMessages.STALE_NO_COUNT else BackupMessages.stale(d.days, d.waiting),
                )
                // A run that succeeded meanwhile has cleared the warning: don't stamp a newer one over it.
                repo.update { if ((it.lastSuccessMs ?: 0L) > now) it else it.copy(lastStaleWarnMs = now) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Nothing to report: the next daily run tries again.
        }
        return Result.success()
    }
}
