package dev.photobackup.settings

import android.os.Build
import dev.photobackup.media.SourceFolders
import dev.photobackup.smb.SmbConfig

data class AppSettings(
    val smb: SmbConfig?,
    val destination: String?,
    val deviceName: String = Build.MODEL,
    val chargingOnly: Boolean = false,
    val freeUpAgeMonths: Int = 6,
    val folders: SourceFolders = SourceFolders(),
    val loopVideos: Boolean = true,
    val notifyOnComplete: Boolean = true,
    /** "Files at once": items the backup engine uploads in parallel, 1..4. More is faster but busier for the router. */
    val parallelUploads: Int = 1,
)
