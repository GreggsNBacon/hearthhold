package dev.photobackup.backup

import android.util.Log
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.SecondLocation
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** What the second engine needs for one run: the fresh [settings], its [second] location and its own [store]. */
class SecondEnv(
    val settings: AppSettings,
    val second: SecondLocation,
    val store: RemoteStore,
    /** A connected main-drive store, or null when it can't be had. Asked at most once per run by the engine. */
    val mainStore: suspend () -> RemoteStore?,
)

/**
 * Runs the second backup step after the main run, inside the same [BackupLock] hold. Owns the second store and any
 * main-drive store it had to open itself, and closes them in finally. A failure here never fails the main run.
 * Nothing is logged but a class name.
 */
class SecondStep(
    private val readSettings: suspend () -> AppSettings,
    private val openStore: (SmbConfig) -> RemoteStore,
    private val runEngine: suspend (SecondEnv) -> SecondResult,
) {
    /**
     * Runs [main] (the main engine), then the second step. Cancellation, window close and setup-incomplete come out
     * of [main] as exceptions and skip the second step. A main result of Stopped (unreachable, auth) still runs it.
     * [mainStore] is the main engine's store; reused only after a Done run (its session is then still open).
     */
    suspend fun around(mainSmb: SmbConfig, mainStore: RemoteStore, main: suspend () -> BackupResult): BackupResult {
        val r = main()
        coroutineContext.ensureActive()
        val second = runSecond(mainSmb, mainStore, reuseMain = r is BackupResult.Done) ?: return r
        return when (r) {
            is BackupResult.Done -> r.copy(second = second)
            is BackupResult.Stopped -> r.copy(second = second)
        }
    }

    private suspend fun runSecond(mainSmb: SmbConfig, mainStore: RemoteStore, reuseMain: Boolean): SecondResult? {
        // Re-read: Remove or edit in Settings may have happened since the run began. Off (or unreadable) = skip.
        val settings = try {
            readSettings()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        val second = settings.second ?: return null
        var secondStore: RemoteStore? = null
        var lazyMain: RemoteStore? = null
        try {
            val store = openStore(second.smb).also { secondStore = it }
            val mainProvider: suspend () -> RemoteStore? = {
                if (reuseMain) mainStore else {
                    val s = openStore(mainSmb)
                    try {
                        s.connect()
                        lazyMain = s
                        s
                    } catch (e: Throwable) {
                        runCatching { s.close() }
                        if (e is IOException) null else throw e
                    }
                }
            }
            return runEngine(SecondEnv(settings, second, store, mainProvider))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Class name only, never the message: it could carry connection details.
            runCatching { Log.w("Hearthhold", "second backup failed: " + e.javaClass.simpleName) }
            return SecondResult.Unreachable
        } finally {
            runCatching { secondStore?.close() }
            runCatching { lazyMain?.close() }
        }
    }
}
