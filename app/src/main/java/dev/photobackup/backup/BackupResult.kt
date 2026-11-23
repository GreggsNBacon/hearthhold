package dev.photobackup.backup

import dev.photobackup.smb.RemoteException

sealed interface BackupResult {
    /**
     * [bytesUploaded]: total size of the files uploaded (not adopted) this run. [driveFreeBytes]: the drive's free
     * space at connect minus what this run uploaded (null when unknown, e.g. from a test runner). [noRoom]: how
     * many of the failed items were bigger than the drive's free space (counted in [failed] too).
     */
    data class Done(
        val uploaded: Int,
        val skipped: Int,
        val failed: Int,
        val bytesUploaded: Long = 0,
        val driveFreeBytes: Long? = null,
        val noRoom: Int = 0,
    ) : BackupResult
    data class Stopped(val reason: RemoteException) : BackupResult
}
