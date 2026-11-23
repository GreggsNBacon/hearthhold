package dev.photobackup.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** The phone item a drive-only row is re-keyed to after a verified restore (or one found already on the phone). */
data class RestoredItem(
    val mediaId: Long,
    val localUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
)

/** Ledger access used by restore; [RestoreDao] is the Room implementation. */
interface RestoreLedger {
    /**
     * Drive-only rows restore may bring back: REMOVED_FROM_PHONE with a recorded hash and drive path, no bin request
     * (so never BINNED), no move or `.part` in flight.
     */
    suspend fun candidates(): List<LedgerEntry>

    suspend fun secondCopy(mediaId: Long): SecondCopy?

    suspend fun hasRow(mediaId: Long): Boolean

    /**
     * In one transaction: the row [oldId] becomes a VERIFIED row keyed by [item] (same drive path and hash, the phone
     * item's id, uri, name, size and date modified; everything else kept), and its FolderAssignment and SecondCopy move
     * with it. Only while [oldId] is still a candidate at [remotePath] with [sha], and no other row has [item]'s id.
     * False = nothing changed. Never touches a drive file.
     */
    suspend fun rekey(oldId: Long, remotePath: String, sha: String, item: RestoredItem, nowMs: Long): Boolean
}

@Dao
abstract class RestoreDao : RestoreLedger {
    @Query(
        "SELECT * FROM LedgerEntry WHERE status = 'REMOVED_FROM_PHONE' AND localSha256 IS NOT NULL " +
            "AND remotePath IS NOT NULL AND binRequestedAt IS NULL AND movingTo IS NULL AND partPath IS NULL"
    )
    abstract override suspend fun candidates(): List<LedgerEntry>

    @Query("SELECT * FROM SecondCopy WHERE mediaId = :mediaId")
    abstract override suspend fun secondCopy(mediaId: Long): SecondCopy?

    @Query("SELECT COUNT(*) FROM LedgerEntry WHERE mediaId = :mediaId")
    abstract suspend fun rowCount(mediaId: Long): Int

    override suspend fun hasRow(mediaId: Long): Boolean = rowCount(mediaId) > 0

    @Query(
        "SELECT * FROM LedgerEntry WHERE mediaId = :mediaId AND status = 'REMOVED_FROM_PHONE' " +
            "AND remotePath = :remotePath AND localSha256 = :sha AND binRequestedAt IS NULL " +
            "AND movingTo IS NULL AND partPath IS NULL"
    )
    abstract suspend fun candidateRow(mediaId: Long, remotePath: String, sha: String): LedgerEntry?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEntry(e: LedgerEntry)

    @Query("DELETE FROM LedgerEntry WHERE mediaId = :mediaId")
    abstract suspend fun deleteEntry(mediaId: Long)

    @Query("SELECT * FROM FolderAssignment WHERE mediaId = :mediaId")
    abstract suspend fun assignment(mediaId: Long): FolderAssignment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAssignment(a: FolderAssignment)

    @Query("DELETE FROM FolderAssignment WHERE mediaId = :mediaId")
    abstract suspend fun deleteAssignment(mediaId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSecondCopy(c: SecondCopy)

    @Query("DELETE FROM SecondCopy WHERE mediaId = :mediaId")
    abstract suspend fun deleteSecondCopy(mediaId: Long)

    @Transaction
    override suspend fun rekey(oldId: Long, remotePath: String, sha: String, item: RestoredItem, nowMs: Long): Boolean {
        val old = candidateRow(oldId, remotePath, sha) ?: return false
        if (item.mediaId != oldId && rowCount(item.mediaId) > 0) return false
        val folder = assignment(oldId)
        val copy = secondCopy(oldId)
        deleteEntry(oldId)
        deleteAssignment(oldId)
        deleteSecondCopy(oldId)
        insertEntry(
            old.copy(
                mediaId = item.mediaId,
                localUri = item.localUri,
                displayName = item.displayName,
                sizeBytes = item.sizeBytes,
                dateModifiedSec = item.dateModifiedSec,
                status = BackupStatus.VERIFIED,
                updatedAtMs = nowMs,
            )
        )
        folder?.let { insertAssignment(it.copy(mediaId = item.mediaId)) }
        copy?.let { insertSecondCopy(it.copy(mediaId = item.mediaId)) }
        return true
    }
}
