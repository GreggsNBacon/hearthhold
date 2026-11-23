package dev.photobackup.ledger

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class SecondStatus { UPLOADING, VERIFIED, FAILED }

/** Stored on a VERIFIED second copy whose file was found missing or of the wrong size; the next run copies it again. */
const val SECOND_MISSING_ERROR = "Second copy missing — will copy again"

/** One row per item copied to the optional second backup location. Absent entirely while that feature is off. */
@Entity
data class SecondCopy(
    @PrimaryKey val mediaId: Long,
    val remotePath: String?,
    val partPath: String?,
    /** New second-drive path while a move is in flight (set before the rename, cleared after remotePath is updated). */
    val movingTo: String?,
    val status: SecondStatus,
    val sizeBytes: Long,
    val sha256: String?,
    val lastError: String?,
    val updatedAtMs: Long,
    /** When the health check last read this copy in full and judged it (null = never). */
    val lastCheckedAt: Long? = null,
)

@Dao
interface SecondCopyDao {
    @Query("SELECT * FROM SecondCopy WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): SecondCopy?

    @Query("SELECT * FROM SecondCopy")
    fun observeAll(): Flow<List<SecondCopy>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SecondCopy)

    @Query("SELECT * FROM SecondCopy")
    suspend fun all(): List<SecondCopy>

    @Query("SELECT * FROM SecondCopy WHERE partPath IS NOT NULL")
    suspend fun withPartPath(): List<SecondCopy>

    @Query("UPDATE SecondCopy SET lastError = :error, updatedAtMs = :nowMs WHERE mediaId = :mediaId")
    suspend fun setLastError(mediaId: Long, error: String?, nowMs: Long)

    @Query("SELECT * FROM SecondCopy WHERE status = :status")
    suspend fun byStatus(status: SecondStatus): List<SecondCopy>

    @Query("SELECT * FROM SecondCopy WHERE movingTo IS NOT NULL")
    suspend fun withMovingTo(): List<SecondCopy>

    @Query(
        "UPDATE SecondCopy SET movingTo = :target, updatedAtMs = :nowMs " +
            "WHERE mediaId = :mediaId AND movingTo IS NULL AND partPath IS NULL AND remotePath = :from"
    )
    suspend fun beginMoveCount(mediaId: Long, from: String, target: String, nowMs: Long): Int

    /** True when this call claimed the move; false when the row changed (or another move began) in the meantime. */
    suspend fun beginMove(mediaId: Long, from: String, target: String, nowMs: Long): Boolean =
        beginMoveCount(mediaId, from, target, nowMs) > 0

    @Query(
        "UPDATE SecondCopy SET remotePath = :remotePath, movingTo = :movingTo, lastError = :lastError, " +
            "updatedAtMs = :nowMs WHERE mediaId = :mediaId"
    )
    suspend fun endMove(mediaId: Long, remotePath: String?, movingTo: String?, lastError: String?, nowMs: Long)

    @Query(
        "UPDATE SecondCopy SET remotePath = :remotePath, movingTo = :movingTo, lastError = :lastError, " +
            "updatedAtMs = :nowMs WHERE mediaId = :mediaId AND movingTo = :expected"
    )
    suspend fun endMoveIfCount(
        mediaId: Long, expected: String, remotePath: String?, movingTo: String?, lastError: String?, nowMs: Long,
    ): Int

    /** [endMove] only while the row still records the move to [expected]; false = it changed meanwhile. */
    suspend fun endMoveIf(
        mediaId: Long, expected: String, remotePath: String?, movingTo: String?, lastError: String?, nowMs: Long,
    ): Boolean = endMoveIfCount(mediaId, expected, remotePath, movingTo, lastError, nowMs) > 0

    @Query(
        "UPDATE SecondCopy SET status = :to, lastError = :error, updatedAtMs = :nowMs " +
            "WHERE mediaId = :mediaId AND status = :from AND remotePath = :remotePath AND sha256 = :sha " +
            "AND movingTo IS NULL AND partPath IS NULL"
    )
    suspend fun demoteCount(
        mediaId: Long, remotePath: String, sha: String, from: SecondStatus, to: SecondStatus, error: String, nowMs: Long,
    ): Int

    /**
     * Marks a VERIFIED copy FAILED with [SECOND_MISSING_ERROR] (its file is gone or of the wrong size), so the next
     * run copies it again. Only while the row is still that same settled copy: VERIFIED at [remotePath] with [sha],
     * no move or `.part` in flight. False = it changed meanwhile, left alone. Never touches a drive file.
     */
    suspend fun demoteIfVerified(mediaId: Long, remotePath: String, sha: String, nowMs: Long): Boolean =
        demoteCount(mediaId, remotePath, sha, SecondStatus.VERIFIED, SecondStatus.FAILED, SECOND_MISSING_ERROR, nowMs) > 0

    /**
     * Up to [limit] copies the health check may read: VERIFIED, path recorded, no move or `.part` in flight, and of
     * the ledger row's current hash (no bin request). Never checked first, then oldest checked.
     */
    @Query(
        "SELECT c.* FROM SecondCopy c JOIN LedgerEntry e ON e.mediaId = c.mediaId " +
            "WHERE c.status = 'VERIFIED' AND c.remotePath IS NOT NULL AND c.sha256 IS NOT NULL " +
            "AND c.movingTo IS NULL AND c.partPath IS NULL AND c.sha256 = e.localSha256 AND e.binRequestedAt IS NULL " +
            "ORDER BY c.lastCheckedAt IS NOT NULL, c.lastCheckedAt, c.mediaId LIMIT :limit"
    )
    suspend fun healthBatch(limit: Int): List<SecondCopy>

    /** Sets lastCheckedAt only while the row is still that same settled VERIFIED copy. Never touches a drive file. */
    @Query(
        "UPDATE SecondCopy SET lastCheckedAt = :nowMs WHERE mediaId = :mediaId AND status = 'VERIFIED' " +
            "AND remotePath = :remotePath AND sha256 = :sha AND movingTo IS NULL AND partPath IS NULL"
    )
    suspend fun markCheckedCount(mediaId: Long, remotePath: String, sha: String, nowMs: Long): Int

    @Query(
        "UPDATE SecondCopy SET movingTo = NULL, status = :to, lastError = :error, updatedAtMs = :nowMs " +
            "WHERE mediaId = :mediaId AND movingTo = :expected AND remotePath IS :from"
    )
    suspend fun dropLostMoveCount(
        mediaId: Long, expected: String, from: String?, to: SecondStatus, error: String, nowMs: Long,
    ): Int

    /**
     * A move whose file is at neither path: the copy is lost. Clears movingTo and marks the row FAILED with
     * [SECOND_MISSING_ERROR] so the next copy pass makes it again, only while the row still records that move from
     * [from]. False = it changed meanwhile, left alone. Never touches a drive file.
     */
    suspend fun dropLostMove(mediaId: Long, expected: String, from: String?, nowMs: Long): Boolean =
        dropLostMoveCount(mediaId, expected, from, SecondStatus.FAILED, SECOND_MISSING_ERROR, nowMs) > 0

    /** Drops one row (a pending row of an item left waiting). Never touches a drive file. */
    @Query("DELETE FROM SecondCopy WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)

    @Query("DELETE FROM SecondCopy")
    suspend fun clear()
}
