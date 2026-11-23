package dev.photobackup.settings

import android.os.Build
import dev.photobackup.backup.OvernightWindow
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
    /** "Only back up overnight": automatic runs only between these hours (0..23, may span midnight). */
    val overnightOnly: Boolean = false,
    val overnightStartHour: Int = 1,
    val overnightEndHour: Int = 6,
    /** Folder rules: normalised phone relative path (e.g. "Pictures/WhatsApp/") to drive folder name. */
    val folderRules: Map<String, String> = emptyMap(),
) {
    /** The active window, or null when "Only back up overnight" is off. */
    fun overnightWindow(): OvernightWindow? =
        if (overnightOnly) OvernightWindow(overnightStartHour, overnightEndHour) else null
}
