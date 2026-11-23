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
)
