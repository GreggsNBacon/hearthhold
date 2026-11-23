package dev.photobackup.bin

import dev.photobackup.backup.BackupLock
import dev.photobackup.backup.BinFiles
import dev.photobackup.backup.DriveMover
import dev.photobackup.backup.DrivePaths
import dev.photobackup.backup.LedgerMoveRecorder
import dev.photobackup.backup.EmptyOutcome
import dev.photobackup.backup.MoveOutcome
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignmentStore
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException

/** The outcome of a Bin screen action. Never an exception. */
sealed interface BinResult {
    /** [count] items restored / deleted; [failedCount] refused or failed (left as they were). */
    data class Done(val count: Int, val failedCount: Int) : BinResult

    /** A backup run holds [BackupLock]: "A backup is running — try again in a moment". */
    data object Busy : BinResult

    /** The drive is not reachable or not set up (any IOException, including RemoteException). */
    data object Offline : BinResult
}

/**
 * The drive bin (review's Junk): list, restore, empty. Drive work runs under [lock] ([BackupLock] in production, so
 * it never races a backup run) on a freshly opened and connected store, and reuses the engine's [DriveMover] and
 * [BinFiles] rules: restore is a clash-safe movingTo move back to binPath (never a download), and empty deletes only
 * BINNED files inside the CURRENT `<dest>\<device>\.Bin\`. Database errors count the item as failed; nothing throws
 * to the caller except cancellation.
 */
class BinRepository(
    private val ledger: LedgerStore,
    private val assignments: FolderAssignmentStore?,
    /** Opens a store for the configured drive, or null if none is configured. */
    private val openStore: suspend () -> RemoteStore?,
    /** (destination, device name) of the current settings, or null if not set up. */
    private val root: suspend () -> Pair<String?, String>?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val lock: Mutex = BackupLock.mutex,
) {
    companion object {
        const val RESTORE_ERROR = "Couldn't restore from the bin"
    }

    /** BINNED rows plus Junk still waiting to be moved (the Bin screen shows those as "Moving to bin…"). */
    fun list(): Flow<List<LedgerEntry>> = ledger.observeAll().map { rows ->
        rows.filter { it.status == BackupStatus.BINNED || isWaiting(it) }
    }

    /** Puts each item back where it was (clash: `name (n).ext`), as drive-only. */
    suspend fun restore(ids: List<Long>): BinResult = withDrive { store, deviceRoot ->
        val mover = DriveMover(store)
        tally(ids) { id -> restoreOne(id, store, mover, deviceRoot) }
    }

    /** Deletes the given bin items for good (drive file, then ledger row and hand-picked folder). */
    suspend fun empty(ids: List<Long>): BinResult = withDrive { store, deviceRoot ->
        tally(ids) { id -> emptyOne(ledger.get(id), store, deviceRoot) }
    }

    /** Deletes every BINNED item for good. Junk still waiting to be moved is left alone. */
    suspend fun emptyAll(): BinResult = withDrive { store, deviceRoot ->
        val rows = try {
            ledger.byStatus(BackupStatus.BINNED).sortedBy { it.mediaId }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withDrive BinResult.Done(0, 1) // the list itself could not be read
        }
        val byId = rows.associateBy { it.mediaId }
        tally(rows.map { it.mediaId }) { id -> emptyOne(byId[id], store, deviceRoot) }
    }

    private fun isWaiting(e: LedgerEntry) = e.status == BackupStatus.REMOVED_FROM_PHONE && e.binRequestedAt != null

    /**
     * Lock (or Busy), open + connect (or Offline), run [block]. Any exception outside the per-item tally (drive,
     * settings, credentials, close) → Offline. A drive drop part-way through returns Offline even though earlier
     * items in the batch were already restored or deleted; [list] shows the true state.
     */
    private suspend fun withDrive(block: suspend (RemoteStore, String) -> BinResult): BinResult {
        if (!lock.tryLock()) return BinResult.Busy
        try {
            return withContext(Dispatchers.IO) {
                try {
                    val r = root() ?: return@withContext BinResult.Offline
                    val deviceRoot = DrivePaths.deviceRoot(r.first, r.second)
                    val store = openStore() ?: return@withContext BinResult.Offline
                    store.use {
                        it.connect()
                        block(it, deviceRoot)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // IOException (RemoteException too) from the drive, or anything from settings, credentials or
                    // closing the store. Never shown or logged: exception text can carry server paths.
                    BinResult.Offline
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** Runs [one] per id; true = done. A database error fails that item only; IOExceptions propagate (Offline). */
    private suspend fun tally(ids: List<Long>, one: suspend (Long) -> Boolean): BinResult {
        var done = 0
        var failed = 0
        for (id in ids) {
            val ok = try {
                one(id)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            }
            if (ok) done++ else failed++
        }
        return BinResult.Done(done, failed)
    }

    private suspend fun restoreOne(id: Long, store: RemoteStore, mover: DriveMover, deviceRoot: String): Boolean {
        var row = ledger.get(id) ?: return false
        if (row.movingTo != null || row.partPath != null) return false
        if (isWaiting(row)) {
            val path = row.remotePath
            if (!DrivePaths.isInBin(path, deviceRoot) || row.binPath == null) {
                // Not moved yet (or never will be: old layout, other destination): just drop the request.
                return ledger.cancelBinRequest(id, clock())
            }
            // Moved, but the BINNED mark was lost: mark it, then restore as usual.
            if (!ledger.markBinned(id, path!!, clock())) return false
            row = ledger.get(id) ?: return false
        }
        if (row.status != BackupStatus.BINNED) return false
        val from = row.remotePath ?: return false
        val original = row.binPath ?: return false
        // Only this row's own file goes back: the bin name may now hold another file (hand delete + name reuse).
        val st = store.stat(from)
        if (st == null || st.isDirectory) return false
        if (st.sizeBytes != row.sizeBytes) {
            ledger.setLastError(id, BinFiles.MISMATCH_ERROR, clock())
            return false
        }
        val cut = original.lastIndexOf('\\')
        if (cut <= 0 || cut == original.length - 1) return false
        val outcome = mover.move(
            from, original.substring(0, cut), original.substring(cut + 1),
            LedgerMoveRecorder(ledger, id, from, RESTORE_ERROR, clock),
            begin = { target -> ledger.beginMove(id, from, target, clock()) },
            finish = { target -> ledger.finishRestore(id, target, clock()) },
        )
        return outcome == MoveOutcome.MOVED
    }

    private suspend fun emptyOne(row: LedgerEntry?, store: RemoteStore, deviceRoot: String): Boolean {
        row ?: return false
        return BinFiles.empty(row, deviceRoot, store, ledger, clock()) { assignments?.clear(it) } == EmptyOutcome.DELETED
    }
}
