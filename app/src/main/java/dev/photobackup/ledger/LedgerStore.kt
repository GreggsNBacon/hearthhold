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
    fun observeCounts(): Flow<Map<BackupStatus, Int>>
    fun observeAll(): Flow<List<LedgerEntry>>
}
