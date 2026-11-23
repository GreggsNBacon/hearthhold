package dev.photobackup.ledger

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class StatusCount(val status: BackupStatus, val n: Int)

@Dao
interface LedgerDao {
    @Upsert suspend fun upsert(e: LedgerEntry)

    @Query("SELECT * FROM LedgerEntry WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): LedgerEntry?

    @Query("SELECT * FROM LedgerEntry WHERE status = :s")
    suspend fun byStatus(s: BackupStatus): List<LedgerEntry>

    @Query("SELECT * FROM LedgerEntry WHERE partPath IS NOT NULL")
    suspend fun withPartPath(): List<LedgerEntry>

    @Query("DELETE FROM LedgerEntry WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)

    @Query("SELECT status AS status, COUNT(*) AS n FROM LedgerEntry GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    fun observeCounts(): Flow<Map<BackupStatus, Int>> =
        observeStatusCounts().map { rows -> rows.associate { it.status to it.n } }

    @Query("SELECT * FROM LedgerEntry ORDER BY dateTakenMs DESC")
    fun observeAll(): Flow<List<LedgerEntry>>
}
