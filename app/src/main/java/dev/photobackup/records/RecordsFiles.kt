package dev.photobackup.records

import dev.photobackup.settings.AppSettings
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The DB files of an import, all in the app's databases folder:
 * - [STAGING]: the imported DB while it is being checked, migrated and cleaned. Never used by the app.
 * - [READY]: staging after it passed everything, renamed in one step (the commit point).
 * - [LIVE]: `ledger.db`. A [READY] file replaces it the next time the ledger DB is opened in a fresh process,
 *   before anything has it open ([promoteIfReady]).
 * A crash at any point leaves either the old DB or the new one, never a mix.
 */
object RecordsFiles {
    const val LIVE = "ledger.db"
    const val STAGING = "ledger-import.staging"
    const val READY = "ledger-import.db"

    private val SIDECARS = listOf("-wal", "-shm", "-journal")

    /** The DB an import replaced (one kept), with its own WAL and journal: a manual fallback, never opened by the app. */
    const val BACKUP = "ledger.db.before-import"

    /** Left by [promoteIfReady]: the first start after an import re-schedules backups ([rescheduleIfImported]). */
    const val IMPORTED_MARKER = "ledger-import.done"

    fun importedMarker(dir: File) = File(dir, IMPORTED_MARKER)

    /**
     * If an import is ready, makes it `ledger.db`. The old DB moves to [BACKUP] together with its WAL and journal
     * (a WAL left beside `ledger.db` would be replayed onto the new file). A crash at any step just repeats this
     * next time: until the final rename, [READY] is still there. Only call while nothing has `ledger.db` open.
     * True when it promoted.
     */
    fun promoteIfReady(dir: File): Boolean {
        val ready = File(dir, READY)
        if (!ready.isFile) return false
        sync(ready)
        importedMarker(dir).writeText("1")
        val live = File(dir, LIVE)
        if (live.exists()) {
            for (s in listOf("") + SIDECARS) deleteOrThrow(File(dir, BACKUP + s))
            for (s in listOf("-wal", "-journal")) {
                val f = File(dir, LIVE + s)
                if (f.exists()) move(f, File(dir, BACKUP + s))
            }
            deleteOrThrow(File(dir, "$LIVE-shm"))
            move(live, File(dir, BACKUP))
        } else {
            for (s in SIDECARS) deleteOrThrow(File(dir, LIVE + s))
        }
        move(ready, live)
        for (s in SIDECARS) File(dir, READY + s).delete()
        return true
    }

    /**
     * The first start after an import: background work is scheduled again from the restored settings (a fresh install
     * has none; enqueues made just before the restart can be lost). Runs once; kept for the next start when the
     * settings can't be read. Without a complete setup the marker is dropped (setup schedules). True when scheduled.
     */
    suspend fun rescheduleIfImported(
        dir: File,
        readSettings: suspend () -> AppSettings,
        schedule: (AppSettings) -> Unit,
    ): Boolean {
        val marker = importedMarker(dir)
        if (!marker.exists()) return false
        val s = try {
            readSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        val complete = s.smb != null && !s.destination.isNullOrBlank()
        if (complete) schedule(s)
        marker.delete()
        return complete
    }

    /**
     * Staging (closed, fully written) becomes [READY]. Refuses when a non-empty WAL is left beside it: its last
     * changes would not be in the file. Throws [IOException] on any failure; then nothing is ready.
     */
    fun commit(dir: File) {
        val staging = File(dir, STAGING)
        if (!staging.isFile) throw IOException("no staging file")
        val wal = File(dir, "$STAGING-wal")
        if (wal.exists() && wal.length() > 0) throw IOException("staging not checkpointed")
        for (s in SIDECARS) deleteOrThrow(File(dir, STAGING + s))
        sync(staging)
        move(staging, File(dir, READY))
    }

    /** Removes a staging DB and its sidecars (cancelled, rejected or left by a crash). Never touches [LIVE]. */
    fun clearStaging(dir: File) {
        File(dir, STAGING).delete()
        for (s in SIDECARS) File(dir, STAGING + s).delete()
    }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Flushes [f] to storage before it is renamed into place (a power cut must not leave a truncated DB). */
    private fun sync(f: File) {
        RandomAccessFile(f, "rw").use { it.fd.sync() }
    }

    private fun deleteOrThrow(f: File) {
        if (f.exists() && !f.delete()) throw IOException("could not delete")
    }
}
