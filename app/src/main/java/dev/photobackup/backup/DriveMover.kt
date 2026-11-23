package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Locale

enum class MoveOutcome { MOVED, FAILED, SKIPPED }

/**
 * The one drive move mechanism (folder moves, bin moves, restores) and the name reservations it shares with uploads.
 *
 * A target name (and its `.part`) is reserved under [nameLock] while the free-name check runs, so two moves or uploads
 * in the same folder can never pick the same name. Names are kept lowercase: SMB names are case-insensitive.
 */
class DriveMover(
    private val store: RemoteStore,
    private val ledger: LedgerStore,
    private val clock: () -> Long,
) {
    companion object {
        private const val MAX_CLASH = 10_000
    }

    /** Target names (and their `.part`) held by in-flight items. Guard with `synchronized(reserved)`. */
    private val reserved = mutableSetOf<String>()

    /** Serialises "is this name free?" checks with the reservation that follows. */
    val nameLock = Mutex()

    private fun key(path: String) = path.lowercase(Locale.ROOT)

    fun isReserved(path: String) = synchronized(reserved) {
        key(path) in reserved || key(RemotePathPlanner.partName(path)) in reserved
    }

    fun reserve(path: String) = synchronized(reserved) {
        reserved += key(path)
        reserved += key(RemotePathPlanner.partName(path))
    }

    /** Keeps a path (only, not its `.part`) reserved, e.g. one still recorded in movingTo. */
    fun reserveExact(path: String) = synchronized(reserved) { reserved += key(path) }

    fun release(path: String) = synchronized(reserved) {
        reserved.remove(key(path))
        reserved.remove(key(RemotePathPlanner.partName(path)))
    }

    /** First name in [dir] (clash scheme) that exists neither on the drive (nor its `.part`) nor in [reserved]. */
    suspend fun reserveFreeName(dir: String, name: String): String? {
        for (n in 0..MAX_CLASH) {
            val candidate = "$dir\\" + if (n == 0) name else RemotePathPlanner.clashName(name, n)
            val part = RemotePathPlanner.partName(candidate)
            val claimed = nameLock.withLock {
                if (isReserved(candidate) || store.stat(candidate) != null || store.stat(part) != null) {
                    return@withLock false
                }
                reserve(candidate)
                true
            }
            if (claimed) return candidate
        }
        return null
    }

    /**
     * mkdirs, reserve a free name (clash scheme, never an existing name), [begin] (a conditional ledger write that
     * records movingTo; false = the row changed meanwhile, skip it untouched), rename, then [finish] (records the new
     * path). On failure the file stays where it was: movingTo is cleared only once a stat confirms the file is still at
     * [from] (otherwise the next run's recovery settles it), and the row gets [error]. Every ledger write is targeted,
     * so a concurrent change to another column is never undone. A [RemoteException] propagates (the caller stops); the
     * row then keeps movingTo for recovery. A false [finish] counts as failed (the row changed under the move; recovery
     * or the next attempt settles it).
     */
    suspend fun move(
        row: LedgerEntry,
        from: String,
        dir: String,
        name: String,
        error: String,
        begin: suspend (target: String) -> Boolean,
        finish: suspend (target: String) -> Boolean,
    ): MoveOutcome {
        var target: String? = null
        var renameTried = false
        var keepReserved = false
        try {
            store.mkdirs(dir)
            target = reserveFreeName(dir, name)
            if (target == null) {
                ledger.setLastError(row.mediaId, error, clock())
                return MoveOutcome.FAILED
            }
            if (!begin(target)) return MoveOutcome.SKIPPED
            renameTried = true
            store.rename(from, target)
            if (!finish(target)) {
                keepReserved = true
                return MoveOutcome.FAILED
            }
            return MoveOutcome.MOVED
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            if (!renameTried) {
                ledger.setLastError(row.mediaId, error, clock())
                return MoveOutcome.FAILED
            }
            val stillThere = try {
                store.stat(from)?.isDirectory == false
            } catch (s: IOException) {
                if (s is RemoteException) throw s
                false
            }
            // Still recorded in movingTo: keep the name reserved for the rest of the run, as recovery does.
            if (!stillThere) keepReserved = true
            ledger.endMove(
                row.mediaId,
                remotePath = from,
                movingTo = if (stillThere) null else target,
                lastError = error,
                nowMs = clock(),
            )
            return MoveOutcome.FAILED
        } finally {
            if (!keepReserved) target?.let { release(it) }
        }
    }
}

/** What emptying one bin row did. */
enum class EmptyOutcome { DELETED, REFUSED, FAILED }

/**
 * The only code that deletes drive files besides the engine's own `.part` / failed copies: emptying the bin.
 * Deletes a file only when the row is BINNED, settled (no move or `.part`), and its remotePath is inside the CURRENT
 * `<root>\.Bin\` ([DrivePaths.isInBin]); anything else is refused and left alone, file and row. A file already
 * missing loses only its ledger row. The ledger row (and its hand-picked folder) goes only after the file is gone.
 */
object BinFiles {
    const val AUTO_EMPTY_AFTER_MS = 30L * 24 * 60 * 60 * 1000
    const val MISMATCH_ERROR = "The file in the bin has changed on the drive — left as it is"

    /** Auto-empty: BINNED for strictly more than 30 days. */
    fun isExpired(e: LedgerEntry, nowMs: Long): Boolean {
        val at = e.binnedAt ?: return false
        return nowMs - at > AUTO_EMPTY_AFTER_MS
    }

    /**
     * Auto-empty takes only cleanly BINNED rows: expired, settled, and with no error (a failed restore or a changed
     * file is left for the owner to decide on the Bin screen).
     */
    fun isAutoEmptyCandidate(e: LedgerEntry, nowMs: Long): Boolean =
        e.status == BackupStatus.BINNED && e.movingTo == null && e.partPath == null && e.lastError == null &&
            isExpired(e, nowMs)

    fun mayDelete(e: LedgerEntry, root: String): Boolean =
        e.status == BackupStatus.BINNED && e.movingTo == null && e.partPath == null &&
            DrivePaths.isInBin(e.remotePath, root)

    /**
     * A [RemoteException] propagates; any other IOException fails this row only (file and row stay). A file whose size
     * is not the row's (the name may now hold another file, e.g. after a hand delete and a later bin move) is refused:
     * file and row stay, the row gets [MISMATCH_ERROR].
     */
    suspend fun empty(
        e: LedgerEntry,
        root: String,
        store: RemoteStore,
        ledger: LedgerStore,
        nowMs: Long,
        clearAssignment: suspend (Long) -> Unit,
    ): EmptyOutcome {
        if (!mayDelete(e, root)) return EmptyOutcome.REFUSED
        val path = e.remotePath!!
        try {
            val st = store.stat(path)
            if (st != null) {
                if (st.isDirectory) return EmptyOutcome.REFUSED
                if (st.sizeBytes != e.sizeBytes) {
                    ledger.setLastError(e.mediaId, MISMATCH_ERROR, nowMs)
                    return EmptyOutcome.REFUSED
                }
                store.delete(path)
            }
        } catch (x: IOException) {
            if (x is RemoteException) throw x
            return EmptyOutcome.FAILED
        }
        if (!ledger.deleteBinned(e.mediaId, path)) return EmptyOutcome.FAILED
        clearAssignment(e.mediaId)
        return EmptyOutcome.DELETED
    }
}
