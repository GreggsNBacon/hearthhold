package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.smb.RemoteEntry
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Locale

enum class MoveOutcome { MOVED, FAILED, SKIPPED }

/** What a failed [DriveMover.move] records on the item's row (main ledger or second copy list). */
interface MoveRecorder {
    /** The move failed before any rename was tried (folder, no free name): record the error only. */
    suspend fun failed()

    /**
     * The rename to [target] (already recorded in movingTo) was tried and failed: point the row back at its old path,
     * clear movingTo when [stillAtFrom] (a stat confirmed the file is still there), else keep [target] for the next
     * run's recovery, and record the error.
     */
    suspend fun abandoned(target: String, stillAtFrom: Boolean)
}

/** [MoveRecorder] for a main ledger row: targeted writes only (setLastError / endMove). */
class LedgerMoveRecorder(
    private val ledger: LedgerStore,
    private val mediaId: Long,
    private val from: String,
    private val error: String,
    private val clock: () -> Long,
) : MoveRecorder {
    override suspend fun failed() = ledger.setLastError(mediaId, error, clock())
    override suspend fun abandoned(target: String, stillAtFrom: Boolean) = ledger.endMove(
        mediaId, remotePath = from, movingTo = if (stillAtFrom) null else target, lastError = error, nowMs = clock(),
    )
}

/** What [DriveMover.pickName] found. */
sealed interface NamePick {
    /** A free name, reserved (name and `.part`) with its folder created; the caller releases it when done. */
    class Free(val path: String) : NamePick

    /** An existing file of the same size that the caller's check accepted (nothing reserved). */
    class Existing(val path: String) : NamePick

    /** The caller's check asked to stop (nothing reserved). */
    data object Aborted : NamePick

    /** No free name within the clash limit. */
    data object NoName : NamePick
}

/** The answer of [DriveMover.pickName]'s check on an existing same-size file. */
enum class NameCheck { ADOPT, NEXT, ABORT }

/**
 * The one drive move mechanism (folder moves, bin moves, restores, second-location folder moves) and the name
 * reservations it shares with uploads. One instance per drive (store) per run.
 *
 * A target name (and its `.part`) is reserved under [nameLock] while the free-name check runs, so two moves or uploads
 * in the same folder can never pick the same name. Names are kept lowercase: SMB names are case-insensitive.
 */
class DriveMover(private val store: RemoteStore) {
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

    private class Claim(val claimed: Boolean, val found: RemoteEntry?)

    /** Under [nameLock]: reserves [candidate] when neither it nor its `.part` exists on the drive or is reserved. */
    private suspend fun tryClaim(candidate: String): Claim = nameLock.withLock {
        if (isReserved(candidate)) return@withLock Claim(false, null)
        val found = store.stat(candidate)
        if (found != null || store.stat(RemotePathPlanner.partName(candidate)) != null) {
            return@withLock Claim(false, found)
        }
        reserve(candidate)
        Claim(true, null)
    }

    private fun candidate(dir: String, name: String, n: Int) =
        "$dir\\" + if (n == 0) name else RemotePathPlanner.clashName(name, n)

    /** First name in [dir] (clash scheme) that exists neither on the drive (nor its `.part`) nor in [reserved]. */
    suspend fun reserveFreeName(dir: String, name: String): String? {
        for (n in 0..MAX_CLASH) {
            val candidate = candidate(dir, name, n)
            if (tryClaim(candidate).claimed) return candidate
        }
        return null
    }

    /**
     * The upload name pick: walks the clash scheme in [dir]; the first name free on the drive and not reserved is
     * reserved and [dir] created (the reservation is released again if that fails). An existing file of [sizeBytes] on
     * the way is offered to [check] (run outside [nameLock]; it may hash): ADOPT returns it, ABORT stops, NEXT walks
     * on. Never overwrites anything. IOExceptions propagate.
     */
    suspend fun pickName(
        dir: String,
        name: String,
        sizeBytes: Long,
        check: suspend (existing: String) -> NameCheck,
    ): NamePick {
        for (n in 0..MAX_CLASH) {
            val candidate = candidate(dir, name, n)
            val claim = tryClaim(candidate)
            if (claim.claimed) {
                try {
                    store.mkdirs(dir)
                } catch (e: Throwable) {
                    release(candidate)
                    throw e
                }
                return NamePick.Free(candidate)
            }
            val st = claim.found
            if (st != null && !st.isDirectory && st.sizeBytes == sizeBytes) {
                when (check(candidate)) {
                    NameCheck.ADOPT -> return NamePick.Existing(candidate)
                    NameCheck.ABORT -> return NamePick.Aborted
                    NameCheck.NEXT -> {}
                }
            }
        }
        return NamePick.NoName
    }

    /**
     * mkdirs, reserve a free name (clash scheme, never an existing name), [begin] (a conditional write that records
     * movingTo; false = the row changed meanwhile, skip it untouched), rename, then [finish] (records the new path).
     * On failure the file stays where it was: movingTo is cleared only once a stat confirms the file is still at
     * [from] (otherwise the next run's recovery settles it), and [recorder] records the error. Every write is targeted,
     * so a concurrent change to another column is never undone. A [RemoteException] propagates (the caller stops); the
     * row then keeps movingTo for recovery. A false [finish] counts as failed (the row changed under the move; recovery
     * or the next attempt settles it).
     */
    suspend fun move(
        from: String,
        dir: String,
        name: String,
        recorder: MoveRecorder,
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
                recorder.failed()
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
                recorder.failed()
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
            recorder.abandoned(target!!, stillThere)
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
