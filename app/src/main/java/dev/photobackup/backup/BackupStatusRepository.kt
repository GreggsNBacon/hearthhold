package dev.photobackup.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.ledger.SecondCopyDao
import dev.photobackup.ledger.SecondStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class BackupStatusSnapshot(
    val lastRunMs: Long?,
    val lastResult: String?,
    val lastError: String?,
    val inProgress: Boolean,
    val done: Int,
    val total: Int,
    val etaSec: Long?,
    /** Free space on the drive as of the last connect (backup run or drive check); null if never seen. */
    val driveFreeBytes: Long? = null,
    /** Drive files being moved to their folders right now (0 = not moving). */
    val moving: Int = 0,
    /** The second backup location's status line (see [SecondStatusText]); null when it is off or has not run. */
    val secondStatus: String? = null,
)

/** The second location's status texts. */
object SecondStatusText {
    const val UP_TO_DATE = "Up to date"
    const val NOT_REACHABLE = "Not reachable"
    const val REENTER_PASSWORD = "Re-enter second location password"

    fun waiting(n: Int) = "$n waiting"
    fun failed(n: Int) = "$n failed"

    /**
     * null result = off or not run = no line. [waiting]: eligible ledger rows still without a verified second copy
     * ([SecondWaiting]). Failed items come first, then waiting, else up to date.
     */
    fun of(result: SecondResult?, waiting: Int): String? = when (result) {
        null -> null
        SecondResult.Unreachable -> NOT_REACHABLE
        SecondResult.AuthFailed -> REENTER_PASSWORD
        is SecondResult.Done -> when {
            result.failed > 0 -> failed(result.failed)
            waiting > 0 -> waiting(waiting)
            else -> UP_TO_DATE
        }
    }
}

/** How many items still need a second copy. */
object SecondWaiting {
    /**
     * Copy-eligible ledger rows (VERIFIED or REMOVED_FROM_PHONE, hash recorded, no bin request: the engine's rule)
     * without a VERIFIED [SecondCopy] of that hash. An UPLOADING row, with or without a `.part`, is waiting.
     */
    suspend fun count(ledger: LedgerStore, copies: SecondCopyDao): Int {
        val done = copies.byStatus(SecondStatus.VERIFIED).associateBy { it.mediaId }
        return (ledger.byStatus(BackupStatus.VERIFIED) + ledger.byStatus(BackupStatus.REMOVED_FROM_PHONE))
            .count { it.localSha256 != null && it.binRequestedAt == null && done[it.mediaId]?.sha256 != it.localSha256 }
    }
}

private val Context.statusDataStore: DataStore<Preferences> by preferencesDataStore(name = "backup_status")

/** Persists the last-run status so the Home screen can show it without the worker running. */
class BackupStatusRepository(private val dataStore: DataStore<Preferences>) {

    val status: Flow<BackupStatusSnapshot> = dataStore.data.map { read(it) }

    suspend fun update(transform: (BackupStatusSnapshot) -> BackupStatusSnapshot) {
        dataStore.edit { p ->
            val s = transform(read(p))
            p.putOrRemove(LAST_RUN, s.lastRunMs)
            p.putOrRemove(LAST_RESULT, s.lastResult)
            p.putOrRemove(LAST_ERROR, s.lastError)
            p[IN_PROGRESS] = s.inProgress
            p[DONE] = s.done
            p[TOTAL] = s.total
            p.putOrRemove(ETA, s.etaSec)
            p.putOrRemove(DRIVE_FREE, s.driveFreeBytes)
            p[MOVING] = s.moving
            p.putOrRemove(SECOND_STATUS, s.secondStatus)
        }
    }

    /**
     * Process start: a persisted "in progress" with no run holding [isRunning] is left over from a killed process
     * (OOM, force-stop). Clear it so the chip and "Back up now" don't stay stuck until the next run. The check runs
     * inside the edit, so a run that starts meanwhile (and takes its lock before writing) is never overwritten.
     */
    suspend fun clearStaleProgress(isRunning: () -> Boolean) = update { s ->
        if (s.inProgress && !isRunning()) s.copy(inProgress = false, done = 0, total = 0, etaSec = null, moving = 0) else s
    }

    suspend fun recordDriveFree(bytes: Long) = update { it.copy(driveFreeBytes = bytes) }

    private fun read(p: Preferences) = BackupStatusSnapshot(
        lastRunMs = p[LAST_RUN],
        lastResult = p[LAST_RESULT],
        lastError = p[LAST_ERROR],
        inProgress = p[IN_PROGRESS] ?: false,
        done = p[DONE] ?: 0,
        total = p[TOTAL] ?: 0,
        etaSec = p[ETA],
        driveFreeBytes = p[DRIVE_FREE],
        moving = p[MOVING] ?: 0,
        secondStatus = p[SECOND_STATUS],
    )

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    companion object {
        private val LAST_RUN = longPreferencesKey("last_run_ms")
        private val LAST_RESULT = stringPreferencesKey("last_result")
        private val LAST_ERROR = stringPreferencesKey("last_error")
        private val IN_PROGRESS = booleanPreferencesKey("in_progress")
        private val DONE = intPreferencesKey("done")
        private val TOTAL = intPreferencesKey("total")
        private val ETA = longPreferencesKey("eta_sec")
        private val DRIVE_FREE = longPreferencesKey("drive_free_bytes")
        private val MOVING = intPreferencesKey("moving")
        private val SECOND_STATUS = stringPreferencesKey("second_status")

        val defaultProvider: (Context) -> BackupStatusRepository = { ctx ->
            BackupStatusRepository(ctx.applicationContext.statusDataStore)
        }

        /** Process-wide access; tests replace it. */
        @Volatile var provider: (Context) -> BackupStatusRepository = defaultProvider

        fun get(context: Context): BackupStatusRepository = provider(context)
    }
}
