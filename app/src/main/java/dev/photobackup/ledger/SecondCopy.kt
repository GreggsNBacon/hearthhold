package dev.photobackup.ledger

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class SecondStatus { UPLOADING, VERIFIED, FAILED }

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

    @Query("DELETE FROM SecondCopy")
    suspend fun clear()
}
