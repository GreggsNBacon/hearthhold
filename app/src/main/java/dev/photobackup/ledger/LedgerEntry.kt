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
    /**
     * When review's Junk asked for this item to go to the drive bin. Set together with REMOVED_FROM_PHONE; kept while
     * BINNED; cleared by restore. A row with it set is a bin row: hidden from the Gallery, counts, Free up, folder
     * reconcile, pinning and rule counts.
     */
    val binRequestedAt: Long? = null,
    /**
     * The ORIGINAL drive path, where restore puts the file back (clash-safe). Recorded in the same write that sets
     * movingTo when the bin move begins. [remotePath] is always the file's real current location (inside
     * `<destination>\<device>\.Bin\` once binned).
     */
    val binPath: String? = null,
    /** When the row became BINNED; auto-empty deletes the file once this is more than 30 days ago. */
    val binnedAt: Long? = null,
    /** When the health check last read this row's drive file in full and judged it (null = never). */
    val lastCheckedAt: Long? = null,
)
