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
        "UPDATE LedgerEntry SET status = 'REMOVED_FROM_PHONE', updatedAtMs = :nowMs, " +
            "binRequestedAt = :binRequestedAt " +
            "WHERE mediaId = :mediaId AND status = 'VERIFIED' AND movingTo IS NULL"
    )
    suspend fun markRemovedIfSettledCount(mediaId: Long, nowMs: Long, binRequestedAt: Long? = null): Int

    override suspend fun markRemovedIfSettled(mediaId: Long, nowMs: Long, binRequestedAt: Long?): Boolean =
        markRemovedIfSettledCount(mediaId, nowMs, binRequestedAt) > 0

    @Query("UPDATE LedgerEntry SET lastError = :error, updatedAtMs = :nowMs WHERE mediaId = :mediaId")
    override suspend fun setLastError(mediaId: Long, error: String?, nowMs: Long)

    @Query(
        "UPDATE LedgerEntry SET movingTo = :target, updatedAtMs = :nowMs " +
            "WHERE mediaId = :mediaId AND movingTo IS NULL AND partPath IS NULL AND remotePath = :from"
    )
    suspend fun beginMoveCount(mediaId: Long, from: String, target: String, nowMs: Long): Int

    override suspend fun beginMove(mediaId: Long, from: String, target: String, nowMs: Long): Boolean =
        beginMoveCount(mediaId, from, target, nowMs) > 0

    @Query(
        "UPDATE LedgerEntry SET remotePath = :remotePath, movingTo = :movingTo, lastError = :lastError, " +
            "updatedAtMs = :nowMs WHERE mediaId = :mediaId"
    )
    override suspend fun endMove(mediaId: Long, remotePath: String?, movingTo: String?, lastError: String?, nowMs: Long)

    @Query("SELECT * FROM LedgerEntry WHERE status = 'REMOVED_FROM_PHONE' AND binRequestedAt IS NOT NULL")
    override suspend fun withBinRequest(): List<LedgerEntry>

    @Query(
        "UPDATE LedgerEntry SET movingTo = :target, binPath = :from, updatedAtMs = :nowMs " +
            "WHERE mediaId = :mediaId AND status = 'REMOVED_FROM_PHONE' AND binRequestedAt IS NOT NULL " +
            "AND movingTo IS NULL AND partPath IS NULL AND remotePath = :from"
    )
    suspend fun beginBinMoveCount(mediaId: Long, from: String, target: String, nowMs: Long): Int

    override suspend fun beginBinMove(mediaId: Long, from: String, target: String, nowMs: Long): Boolean =
        beginBinMoveCount(mediaId, from, target, nowMs) > 0

    @Query(
        "UPDATE LedgerEntry SET status = 'BINNED', binnedAt = :nowMs, lastError = NULL, updatedAtMs = :nowMs " +
            "WHERE mediaId = :mediaId AND status = 'REMOVED_FROM_PHONE' AND binRequestedAt IS NOT NULL " +
            "AND binPath IS NOT NULL AND movingTo IS NULL AND remotePath = :path"
    )
    suspend fun markBinnedCount(mediaId: Long, path: String, nowMs: Long): Int

    override suspend fun markBinned(mediaId: Long, path: String, nowMs: Long): Boolean =
        markBinnedCount(mediaId, path, nowMs) > 0

    @Query(
        "UPDATE LedgerEntry SET status = 'REMOVED_FROM_PHONE', remotePath = :restoredPath, movingTo = NULL, " +
            "lastError = NULL, binRequestedAt = NULL, binPath = NULL, binnedAt = NULL, updatedAtMs = :nowMs " +
            "WHERE mediaId = :mediaId AND status = 'BINNED' AND movingTo = :restoredPath"
    )
    suspend fun finishRestoreCount(mediaId: Long, restoredPath: String, nowMs: Long): Int

    override suspend fun finishRestore(mediaId: Long, restoredPath: String, nowMs: Long): Boolean =
        finishRestoreCount(mediaId, restoredPath, nowMs) > 0

    @Query(
        "UPDATE LedgerEntry SET binRequestedAt = NULL, binPath = NULL, lastError = NULL, updatedAtMs = :nowMs " +
            "WHERE mediaId = :mediaId AND status = 'REMOVED_FROM_PHONE' AND binRequestedAt IS NOT NULL " +
            "AND movingTo IS NULL"
    )
    suspend fun cancelBinRequestCount(mediaId: Long, nowMs: Long): Int

    override suspend fun cancelBinRequest(mediaId: Long, nowMs: Long): Boolean = cancelBinRequestCount(mediaId, nowMs) > 0

    @Query(
        "DELETE FROM LedgerEntry WHERE mediaId = :mediaId AND status = 'BINNED' AND movingTo IS NULL " +
            "AND remotePath = :remotePath"
    )
    suspend fun deleteBinnedCount(mediaId: Long, remotePath: String): Int

    override suspend fun deleteBinned(mediaId: Long, remotePath: String): Boolean =
        deleteBinnedCount(mediaId, remotePath) > 0

    @Query(
        "SELECT status AS status, COUNT(*) AS n FROM LedgerEntry " +
            "WHERE binRequestedAt IS NULL AND status != 'BINNED' GROUP BY status"
    )
    fun observeStatusCounts(): Flow<List<StatusCount>>

    override fun observeCounts(): Flow<Map<BackupStatus, Int>> =
        observeStatusCounts().map { rows -> rows.associate { it.status to it.n } }

    // ---- Health check. Every write is conditional: only while the row is still the same settled copy. ----

    /**
     * Up to [limit] rows whose main drive file the health check may read: VERIFIED or REMOVED_FROM_PHONE, path and
     * hash recorded, no bin request, no move or `.part` in flight. Never checked first, then oldest checked.
     */
    @Query(
        "SELECT * FROM LedgerEntry WHERE status IN ('VERIFIED', 'REMOVED_FROM_PHONE') AND binRequestedAt IS NULL " +
            "AND movingTo IS NULL AND partPath IS NULL AND remotePath IS NOT NULL AND localSha256 IS NOT NULL " +
            "ORDER BY lastCheckedAt IS NOT NULL, lastCheckedAt, mediaId LIMIT :limit"
    )
    suspend fun healthBatch(limit: Int): List<LedgerEntry>

    /**
     * Sets lastCheckedAt and lastError ([clearError]: only that exact text is cleared, other errors stay; or
     * [setError]: replaces it) only while the row is still the settled copy at [remotePath] with [sha].
     */
    @Query(
        "UPDATE LedgerEntry SET lastCheckedAt = :nowMs, updatedAtMs = :nowMs, " +
            "lastError = CASE WHEN :setError IS NOT NULL THEN :setError " +
            "WHEN lastError = :clearError THEN NULL ELSE lastError END " +
            "WHERE mediaId = :mediaId AND remotePath = :remotePath AND localSha256 = :sha " +
            "AND movingTo IS NULL AND partPath IS NULL AND binRequestedAt IS NULL " +
            "AND status IN ('VERIFIED', 'REMOVED_FROM_PHONE')"
    )
    suspend fun markCheckedCount(
        mediaId: Long, remotePath: String, sha: String, clearError: String?, setError: String?, nowMs: Long,
    ): Int

    /**
     * Repoints a repaired row at [newPath] (lastError cleared: healthy again; lastCheckedAt = [nowMs]) only while it
     * is still the settled copy at [oldPath] with [sha]. False = it changed meanwhile, left alone.
     */
    @Query(
        "UPDATE LedgerEntry SET remotePath = :newPath, lastError = NULL, lastCheckedAt = :nowMs, updatedAtMs = :nowMs " +
            "WHERE mediaId = :mediaId AND remotePath = :oldPath AND localSha256 = :sha " +
            "AND movingTo IS NULL AND partPath IS NULL AND binRequestedAt IS NULL " +
            "AND status IN ('VERIFIED', 'REMOVED_FROM_PHONE')"
    )
    suspend fun repointCount(mediaId: Long, oldPath: String, newPath: String, sha: String, nowMs: Long): Int

    suspend fun repoint(mediaId: Long, oldPath: String, newPath: String, sha: String, nowMs: Long): Boolean =
        repointCount(mediaId, oldPath, newPath, sha, nowMs) > 0

    @Query("SELECT COUNT(*) FROM LedgerEntry WHERE lastError = :error")
    suspend fun countWithError(error: String): Int

    @Query("SELECT * FROM LedgerEntry ORDER BY dateTakenMs DESC")
    override fun observeAll(): Flow<List<LedgerEntry>>
}
