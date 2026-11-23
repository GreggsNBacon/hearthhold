package dev.photobackup.backup

import dev.photobackup.smb.RemoteException

sealed interface BackupResult {
    /** [bytesUploaded]: total size of the files uploaded (not adopted) this run. */
    data class Done(val uploaded: Int, val skipped: Int, val failed: Int, val bytesUploaded: Long = 0) : BackupResult
    data class Stopped(val reason: RemoteException) : BackupResult
}
