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
)

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
        }
    }

    private fun read(p: Preferences) = BackupStatusSnapshot(
        lastRunMs = p[LAST_RUN],
        lastResult = p[LAST_RESULT],
        lastError = p[LAST_ERROR],
        inProgress = p[IN_PROGRESS] ?: false,
        done = p[DONE] ?: 0,
        total = p[TOTAL] ?: 0,
        etaSec = p[ETA],
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

        val defaultProvider: (Context) -> BackupStatusRepository = { ctx ->
            BackupStatusRepository(ctx.applicationContext.statusDataStore)
        }

        /** Process-wide access; tests replace it. */
        @Volatile var provider: (Context) -> BackupStatusRepository = defaultProvider

        fun get(context: Context): BackupStatusRepository = provider(context)
    }
}
