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
     * null). Returns whether it changed. A read-then-upsert could drop a movingTo marker set in between.
     */
    suspend fun markRemovedIfSettled(mediaId: Long, nowMs: Long): Boolean
    fun observeCounts(): Flow<Map<BackupStatus, Int>>
    fun observeAll(): Flow<List<LedgerEntry>>
}
