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
)
