package dev.photobackup.ledger

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class LedgerEntry(
    @PrimaryKey val mediaId: Long,
    val localUri: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val dateTakenMs: Long,
    val dateModifiedSec: Long,
    val isVideo: Boolean,
    val localSha256: String?,
    val remotePath: String?,
    val partPath: String?,
    val status: BackupStatus,
    val lastError: String?,
    val updatedAtMs: Long,
    /** New drive path while a move is in flight (set before the rename, cleared after remotePath is updated). */
    val movingTo: String? = null,
    /** When review asked for this item to go to the bin (before the move on the drive). */
    val binRequestedAt: Long? = null,
    /** Where the file sits in the drive's .Bin folder. */
    val binPath: String? = null,
    /** When the file landed in the bin; auto-empty counts from here. */
    val binnedAt: Long? = null,
)
