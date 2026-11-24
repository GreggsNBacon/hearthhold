package dev.photobackup.records

import java.io.File
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

    /**
     * If an import is ready, makes it `ledger.db`. The old DB's sidecar files go first (a leftover WAL would be
     * replayed onto the new file); a crash after that and before the rename just repeats this next time. Only call
     * while nothing has `ledger.db` open. True when it promoted.
     */
    fun promoteIfReady(dir: File): Boolean {
        val ready = File(dir, READY)
        if (!ready.isFile) return false
        for (s in SIDECARS) deleteOrThrow(File(dir, LIVE + s))
        Files.move(ready.toPath(), File(dir, LIVE).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        for (s in SIDECARS) File(dir, READY + s).delete()
        return true
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
        Files.move(staging.toPath(), File(dir, READY).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Removes a staging DB and its sidecars (cancelled, rejected or left by a crash). Never touches [LIVE]. */
    fun clearStaging(dir: File) {
        File(dir, STAGING).delete()
        for (s in SIDECARS) File(dir, STAGING + s).delete()
    }

    private fun deleteOrThrow(f: File) {
        if (f.exists() && !f.delete()) throw IOException("could not delete")
    }
}
