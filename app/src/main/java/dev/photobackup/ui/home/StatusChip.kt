package dev.photobackup.ui.home

import dev.photobackup.backup.BackupMessages
import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.backup.OvernightWindow
import dev.photobackup.freeup.formatSize
import dev.photobackup.media.MediaAccess
import java.time.LocalTime

enum class ChipKind { OK, BUSY, WAITING, PROBLEM }

/** The compact status chip in the Gallery's top bar. */
data class ChipState(val text: String, val kind: ChipKind)

/**
 * One short line for the top bar, most important first: a run in progress, the drive not reachable, an unreadable
 * password, missing (or partial) photo access, the last run's error (shortened), waiting for the overnight window,
 * files waiting, failed files, never backed up yet, a drive check still running ("Checking…", never a premature
 * "Up to date"), else "Up to date".
 * [window] is the active overnight window (null when off); [now] is local wall-clock time; [photoAccess] is null
 * while unknown. Pure.
 */
fun statusChip(
    status: BackupStatusSnapshot?,
    reach: Reachability,
    waiting: Int,
    failed: Int,
    needsPassword: Boolean,
    window: OvernightWindow?,
    now: LocalTime,
    photoAccess: MediaAccess? = MediaAccess.FULL,
): ChipState = when {
    status == null -> ChipState("Checking…", ChipKind.BUSY)
    status.inProgress ->
        ChipState(if (status.total > 0) "Backing up ${status.done} of ${status.total}" else "Backing up…", ChipKind.BUSY)
    reach == Reachability.NO -> ChipState("Drive not reachable", ChipKind.PROBLEM)
    needsPassword -> ChipState("Re-enter drive password", ChipKind.PROBLEM)
    photoAccess == MediaAccess.NONE -> ChipState(BackupMessages.PERMISSION, ChipKind.PROBLEM)
    photoAccess == MediaAccess.PARTIAL -> ChipState(BackupMessages.PERMISSION_ALL, ChipKind.PROBLEM)
    status.lastError != null -> ChipState(shortError(status.lastError), ChipKind.PROBLEM)
    waiting > 0 && window != null && !window.contains(now) ->
        ChipState("Waiting for overnight window", ChipKind.WAITING)
    waiting > 0 -> ChipState("$waiting waiting", ChipKind.WAITING)
    failed > 0 -> ChipState("$failed failed", ChipKind.PROBLEM)
    // No run has finished yet: nothing is known to be on the drive.
    status.lastRunMs == null -> ChipState("Not backed up yet", ChipKind.WAITING)
    reach == Reachability.CHECKING -> ChipState("Checking…", ChipKind.BUSY)
    else -> ChipState("Up to date", ChipKind.OK)
}

private const val CHIP_ERROR_CHARS = 24

/** First line of [error], cut to [CHIP_ERROR_CHARS] characters plus "…" when longer. */
private fun shortError(error: String): String {
    val line = error.lineSequence().first().trim()
    return if (line.length <= CHIP_ERROR_CHARS) line else line.take(CHIP_ERROR_CHARS).trimEnd() + "…"
}

/**
 * The status sheet's drive-limits lines (spec "Drive limits": show free space and FAT32-flagged files): free space
 * from the last connect, and how many files are too big for a FAT32 drive. Pure.
 */
fun driveLimitLines(freeBytes: Long?, tooBig: Int): List<String> = listOfNotNull(
    freeBytes?.let { "${formatSize(it.coerceAtLeast(0))} free on the drive" },
    when {
        tooBig == 1 -> "1 file is too big for this drive's format (FAT32, 4 GB limit)"
        tooBig > 1 -> "$tooBig files are too big for this drive's format (FAT32, 4 GB limit)"
        else -> null
    },
)
