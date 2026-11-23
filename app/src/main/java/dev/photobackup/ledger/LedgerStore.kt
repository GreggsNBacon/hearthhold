package dev.photobackup.ledger

import kotlinx.coroutines.flow.Flow

/** Ledger access used by the backup engine. [LedgerDao] is the Room implementation. */
interface LedgerStore {
    suspend fun upsert(e: LedgerEntry)
    suspend fun get(mediaId: Long): LedgerEntry?
    suspend fun byStatus(s: BackupStatus): List<LedgerEntry>
    suspend fun withPartPath(): List<LedgerEntry>
    suspend fun withMovingTo(): List<LedgerEntry>
    suspend fun delete(mediaId: Long)

    /**
     * Atomically sets the row REMOVED_FROM_PHONE only if it is still VERIFIED with no drive move in flight (movingTo
     * null), setting binRequestedAt to [binRequestedAt] in the same write (review's Junk). Returns whether it changed.
     * A read-then-upsert could drop a movingTo marker set in between.
     */
    suspend fun markRemovedIfSettled(mediaId: Long, nowMs: Long, binRequestedAt: Long? = null): Boolean

    /**
     * The targeted writes below change only the columns they name, so a concurrent write to another column (e.g.
     * Free up's [markRemovedIfSettled]) is never undone by a whole-row upsert of a row read earlier.
     */
    suspend fun setLastError(mediaId: Long, error: String?, nowMs: Long)

    /**
     * Records a drive move about to happen: sets movingTo to [target] only if no move is recorded and the row still
     * points at [from]. Returns whether it changed; false means the row changed meanwhile and must not be moved.
     */
    suspend fun beginMove(mediaId: Long, from: String, target: String, nowMs: Long): Boolean

    /** Sets remotePath, movingTo and lastError only (the end of a move, or its recovery). */
    suspend fun endMove(mediaId: Long, remotePath: String?, movingTo: String?, lastError: String?, nowMs: Long)

    // ---- Drive bin (review's Junk). Every write is conditional, like the moves above. ----

    /** REMOVED_FROM_PHONE rows with binRequestedAt set: Junk still waiting to be moved into the bin. */
    suspend fun withBinRequest(): List<LedgerEntry>

    /**
     * Records a bin move about to happen: sets movingTo to [target] and binPath to [from] (the original path, for
     * restore) in one write, only if the row is a waiting bin request still at [from] with no move or `.part`.
     */
    suspend fun beginBinMove(mediaId: Long, from: String, target: String, nowMs: Long): Boolean

    /**
     * Marks a moved bin request BINNED with binnedAt = [nowMs], only if it is still REMOVED_FROM_PHONE with
     * binRequestedAt and binPath set, no move in flight, and remotePath = [path].
     */
    suspend fun markBinned(mediaId: Long, path: String, nowMs: Long): Boolean

    /**
     * The end of a restore, in one write: only for a BINNED row whose movingTo is [restoredPath], sets
     * REMOVED_FROM_PHONE, remotePath = [restoredPath], and clears movingTo, lastError and the bin columns.
     */
    suspend fun finishRestore(mediaId: Long, restoredPath: String, nowMs: Long): Boolean

    /** Drops a bin request not yet moved (REMOVED_FROM_PHONE, no move in flight): clears binRequestedAt and binPath. */
    suspend fun cancelBinRequest(mediaId: Long, nowMs: Long): Boolean

    /** Deletes the row only if it is BINNED at [remotePath] with no move in flight (emptying the bin). */
    suspend fun deleteBinned(mediaId: Long, remotePath: String): Boolean

    /** Rows per status, leaving out bin rows (binRequestedAt set, or BINNED): they are neither backed up nor waiting. */
    fun observeCounts(): Flow<Map<BackupStatus, Int>>
    fun observeAll(): Flow<List<LedgerEntry>>
}
