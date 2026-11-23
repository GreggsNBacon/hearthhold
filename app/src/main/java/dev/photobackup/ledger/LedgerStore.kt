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
    fun observeCounts(): Flow<Map<BackupStatus, Int>>
    fun observeAll(): Flow<List<LedgerEntry>>
}
