package dev.photobackup.ui.home

import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.backup.OvernightWindow
import java.time.LocalTime

enum class ChipKind { OK, BUSY, WAITING, PROBLEM }

/** The compact status chip in the Gallery's top bar. */
data class ChipState(val text: String, val kind: ChipKind)

/**
 * One short line for the top bar, most important first: a run in progress, the drive not reachable, a run error
 * or unreadable password, waiting for the overnight window, files waiting, failed files, else "Up to date".
 * [window] is the active overnight window (null when off); [now] is local wall-clock time. Pure.
 */
fun statusChip(
    status: BackupStatusSnapshot?,
    reach: Reachability,
    waiting: Int,
    failed: Int,
    needsPassword: Boolean,
    window: OvernightWindow?,
    now: LocalTime,
): ChipState = when {
    status == null -> ChipState("Checking…", ChipKind.BUSY)
    status.inProgress ->
        ChipState(if (status.total > 0) "Backing up ${status.done} of ${status.total}" else "Backing up…", ChipKind.BUSY)
    reach == Reachability.NO -> ChipState("Drive not reachable", ChipKind.PROBLEM)
    status.lastError != null || needsPassword -> ChipState("Backup problem", ChipKind.PROBLEM)
    waiting > 0 && window != null && !window.contains(now) ->
        ChipState("Waiting for overnight window", ChipKind.WAITING)
    waiting > 0 -> ChipState("$waiting waiting", ChipKind.WAITING)
    failed > 0 -> ChipState("$failed failed", ChipKind.PROBLEM)
    else -> ChipState("Up to date", ChipKind.OK)
}
