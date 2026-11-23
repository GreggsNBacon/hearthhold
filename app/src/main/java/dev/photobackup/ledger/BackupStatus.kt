package dev.photobackup.ledger

/** BINNED: moved into the drive's .Bin folder by review; not backed up, not waiting, hidden from most lists. */
enum class BackupStatus { PENDING, UPLOADING, VERIFIED, FAILED, REMOVED_FROM_PHONE, BINNED }
