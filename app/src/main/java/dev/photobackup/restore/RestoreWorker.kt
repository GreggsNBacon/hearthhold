package dev.photobackup.restore

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.photobackup.backup.BackupLock
import dev.photobackup.backup.BackupNotifications
import dev.photobackup.backup.DrivePaths
import dev.photobackup.backup.LedgerDatabase
import dev.photobackup.backup.SetupIncompleteException
import dev.photobackup.settings.KeystoreCredentialCipher
import dev.photobackup.settings.SettingsRead
import dev.photobackup.settings.SettingsStore
import dev.photobackup.settings.SettingsUnreadableException
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import dev.photobackup.smb.SmbjRemoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

fun interface RestoreRunner {
    suspend fun run(selection: RestoreSelection, onProgress: (RestoreProgress) -> Unit): RestoreResult
}

/** Process-wide seams so tests can swap the engine. */
object RestoreFactory {
    /** The production engine (also for the Restore screen's [RestoreEngine.plan]). Throws when setup is unfinished. */
    suspend fun engine(context: Context): RestoreEngine {
        val app = context.applicationContext
        val read = SettingsStore(app, KeystoreCredentialCipher()).settingsResult.first()
        val s = (read as? SettingsRead.Ok)?.settings ?: throw SettingsUnreadableException()
        val smb = s.smb
        val destination = s.destination
        if (smb == null || destination.isNullOrBlank()) throw SetupIncompleteException()
        return RestoreEngine(
            ledger = LedgerDatabase.get(app).restoreDao(),
            phone = ResolverRestorePhone(app.contentResolver),
            root = DrivePaths.deviceRoot(destination, s.deviceName),
            openMain = { connected(smb) },
            openSecond = s.second?.smb?.let { cfg -> { connected(cfg) } },
            clock = System::currentTimeMillis,
        )
    }

    private suspend fun connected(cfg: SmbConfig): RemoteStore {
        val store = SmbjRemoteStore(cfg)
        try {
            store.connect()
        } catch (e: Exception) {
            runCatching { store.close() }
            throw e
        }
        return store
    }

    val defaultCreate: (Context) -> RestoreRunner = { ctx ->
        RestoreRunner { selection, onProgress -> engine(ctx).run(selection, onProgress) }
    }

    @Volatile var create: (Context) -> RestoreRunner = defaultCreate
}

/** The unique "restore" job: Wi-Fi (unmetered) only, one at a time. */
object RestoreScheduler {
    const val WORK = "restore"
    const val KEY_ALL = "all"
    const val KEY_GROUPS = "groups"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    private const val SEP = '\u001F'

    fun input(selection: RestoreSelection): Data = workDataOf(
        KEY_ALL to selection.everything,
        KEY_GROUPS to selection.groups.map { "${it.folder}$SEP${it.year ?: ""}" }.toTypedArray(),
    )

    fun selection(data: Data): RestoreSelection {
        if (data.getBoolean(KEY_ALL, false)) return RestoreSelection.EVERYTHING
        val groups = data.getStringArray(KEY_GROUPS).orEmpty().map {
            RestoreGroupKey(it.substringBefore(SEP), it.substringAfter(SEP, "").ifEmpty { null })
        }
        return RestoreSelection(groups.toSet())
    }

    /** Starts a restore unless one is already queued or running (that one is kept). */
    fun start(context: Context, selection: RestoreSelection) {
        val request = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setInputData(input(selection))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

/** The last restore's summary, for the Restore screen. */
data class SavedRestore(val result: RestoreResult, val finishedAtMs: Long)

/** Keeps the last [SavedRestore] in its own preferences file. Fixed texts only, never exception text. */
class RestoreResults(private val prefs: SharedPreferences) {
    fun load(): SavedRestore? = prefs.getString(KEY, null)?.let { decode(it) }

    fun save(result: RestoreResult, nowMs: Long = System.currentTimeMillis()) {
        val saved = SavedRestore(result, nowMs)
        prefs.edit().putString(KEY, encode(saved)).commit()
        latest.value = saved
    }

    fun clear() {
        prefs.edit().remove(KEY).commit()
        latest.value = null
    }

    companion object {
        private const val FILE = "restore_result"
        private const val KEY = "last"
        private val latest = MutableStateFlow<SavedRestore?>(null)

        fun get(context: Context) =
            RestoreResults(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

        /** Updated on every save in this process; read [load] once for a summary saved before. */
        val updates: StateFlow<SavedRestore?> = latest.asStateFlow()

        private fun encode(s: SavedRestore): String = JSONObject().apply {
            put("restored", s.result.restored)
            put("alreadyThere", s.result.alreadyThere)
            put("failed", JSONArray().apply {
                s.result.failed.forEach { put(JSONObject().put("name", it.name).put("reason", it.reason)) }
            })
            put("redirected", JSONArray(s.result.redirected))
            s.result.stopped?.let { put("stopped", it) }
            put("at", s.finishedAtMs)
        }.toString()

        private fun decode(text: String): SavedRestore? = try {
            val o = JSONObject(text)
            val failed = o.getJSONArray("failed").let { a ->
                (0 until a.length()).map { RestoreFailure(a.getJSONObject(it).getString("name"), a.getJSONObject(it).getString("reason")) }
            }
            val redirected = o.getJSONArray("redirected").let { a -> (0 until a.length()).map { a.getString(it) } }
            SavedRestore(
                RestoreResult(
                    restored = o.getInt("restored"),
                    alreadyThere = o.getInt("alreadyThere"),
                    failed = failed,
                    redirected = redirected,
                    stopped = if (o.has("stopped")) o.getString("stopped") else null,
                ),
                o.getLong("at"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Runs a restore as a foreground job ("Restoring 12 of 812", with Cancel) under [BackupLock], taken with tryLock: a
 * backup holding it means [BUSY], never a wait. Saves the summary to [RestoreResults] at the end, on cancel (finished
 * items are kept) and on failure. Logs nothing but a class name.
 */
class RestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val BUSY = "A backup is running — try again in a moment"
        /** Cancelled, or stopped by Android (e.g. Wi-Fi lost; WorkManager then runs it again). */
        const val CANCELLED = "Restore stopped — finished items kept"
        const val FAILED = "Restore failed — try again"
        const val SETUP = "Finish setup first"
        const val SETTINGS = "Couldn't read the settings — try again"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val cancel = try {
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        } catch (e: Exception) {
            null
        }
        return ForegroundInfo(
            BackupNotifications.RESTORE_ID,
            BackupNotifications.restoreProgress(applicationContext, done, total, cancel),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private suspend fun show(done: Int, total: Int) {
        try {
            setForeground(foregroundInfo(done, total))
        } catch (e: Exception) {
            if (e is CancellationException) throw e // foreground not allowed right now: keep working
        }
        try {
            setProgress(workDataOf(RestoreScheduler.KEY_DONE to done, RestoreScheduler.KEY_TOTAL to total))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    override suspend fun doWork(): Result {
        val results = RestoreResults.get(applicationContext)
        val selection = RestoreScheduler.selection(inputData)
        if (!BackupLock.mutex.tryLock()) {
            results.save(RestoreResult(stopped = BUSY))
            return Result.success()
        }
        var last = RestoreResult()
        try {
            show(0, 0)
            val runner = RestoreFactory.create(applicationContext)
            val updates = Channel<RestoreProgress>(Channel.CONFLATED)
            val result = coroutineScope {
                val consumer = launch {
                    var lastShown = 0L
                    for (p in updates) {
                        val now = System.currentTimeMillis()
                        if (now - lastShown < 1000 && p.done != p.total) continue
                        lastShown = now
                        show(p.done, p.total)
                    }
                }
                try {
                    runner.run(selection) { p ->
                        last = p.result
                        updates.trySend(p)
                    }
                } finally {
                    updates.close()
                    withContext(NonCancellable) { consumer.cancelAndJoin() }
                }
            }
            results.save(result)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { results.save(last.copy(stopped = CANCELLED)) }
            throw e
        } catch (_: SetupIncompleteException) {
            results.save(RestoreResult(stopped = SETUP))
        } catch (_: SettingsUnreadableException) {
            results.save(RestoreResult(stopped = SETTINGS))
        } catch (e: Exception) {
            // Class name only, never the message: it could carry connection details.
            runCatching { Log.w("Hearthhold", "restore failed: " + e.javaClass.simpleName) }
            results.save(last.copy(stopped = FAILED))
        } finally {
            BackupLock.mutex.unlock()
        }
        return Result.success()
    }
}
