package dev.photobackup.ledger

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class StatusCount(val status: BackupStatus, val n: Int)

@Dao
interface LedgerDao : LedgerStore {
    @Upsert override suspend fun upsert(e: LedgerEntry)

    @Query("SELECT * FROM LedgerEntry WHERE mediaId = :mediaId")
    override suspend fun get(mediaId: Long): LedgerEntry?

    @Query("SELECT * FROM LedgerEntry WHERE status = :s")
    override suspend fun byStatus(s: BackupStatus): List<LedgerEntry>

    @Query("SELECT * FROM LedgerEntry WHERE partPath IS NOT NULL")
    override suspend fun withPartPath(): List<LedgerEntry>

    @Query("SELECT * FROM LedgerEntry WHERE movingTo IS NOT NULL")
    override suspend fun withMovingTo(): List<LedgerEntry>

    @Query("DELETE FROM LedgerEntry WHERE mediaId = :mediaId")
    override suspend fun delete(mediaId: Long)

    @Query(
        "UPDATE LedgerEntry SET status = 'REMOVED_FROM_PHONE', updatedAtMs = :nowMs " +
            "WHERE mediaId = :mediaId AND status = 'VERIFIED' AND movingTo IS NULL"
    )
    suspend fun markRemovedIfSettledCount(mediaId: Long, nowMs: Long): Int

    override suspend fun markRemovedIfSettled(mediaId: Long, nowMs: Long): Boolean =
        markRemovedIfSettledCount(mediaId, nowMs) > 0

    @Query("SELECT status AS status, COUNT(*) AS n FROM LedgerEntry GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    override fun observeCounts(): Flow<Map<BackupStatus, Int>> =
        observeStatusCounts().map { rows -> rows.associate { it.status to it.n } }

    @Query("SELECT * FROM LedgerEntry ORDER BY dateTakenMs DESC")
    override fun observeAll(): Flow<List<LedgerEntry>>
}
