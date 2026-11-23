package dev.photobackup.backup

import dev.photobackup.smb.RemoteException

sealed interface BackupResult {
    data class Done(val uploaded: Int, val skipped: Int, val failed: Int) : BackupResult
    data class Stopped(val reason: RemoteException) : BackupResult
}
