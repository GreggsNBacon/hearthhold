package dev.photobackup.settings

import android.os.Build
import dev.photobackup.backup.OvernightWindow
import dev.photobackup.media.SourceFolders
import dev.photobackup.review.DEFAULT_GESTURES
import dev.photobackup.review.ReviewAction
import dev.photobackup.review.Swipe
import dev.photobackup.smb.SmbConfig

/** The optional second backup location: its own drive login plus its own destination folder. */
data class SecondLocation(val smb: SmbConfig, val destination: String)

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
    /** Swipe review gestures; null = No action. At least one direction is not null. */
    val gestures: Map<Swipe, ReviewAction?> = DEFAULT_GESTURES,
    /** Second backup location; null = off (the app behaves as without the feature). */
    val second: SecondLocation? = null,
    /** With a second location: Free up only deletes phone files verified on both drives. */
    val freeUpNeedsBoth: Boolean = true,
    /** "Warn if no backup for N days", 1..30: how long backups may stay stuck with items waiting before a warning. */
    val staleWarnDays: Int = 3,
) {
    /** The active window, or null when "Only back up overnight" is off. */
    fun overnightWindow(): OvernightWindow? =
        if (overnightOnly) OvernightWindow(overnightStartHour, overnightEndHour) else null
}
