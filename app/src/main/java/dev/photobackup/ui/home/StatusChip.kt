package dev.photobackup.ui.home

import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.backup.OvernightWindow
import java.time.LocalTime

enum class ChipKind { OK, BUSY, WAITING, PROBLEM }

/** The compact status chip in the Gallery's top bar. */
data class ChipState(val text: String, val kind: ChipKind)

/**
 * One short line for the top bar, most important first: a run in progress, the drive not reachable, an unreadable
 * password, the last run's error (shortened), waiting for the overnight window, files waiting, failed files, a drive
 * check still running ("Checking…", never a premature "Up to date"), else "Up to date".
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
    needsPassword -> ChipState("Re-enter drive password", ChipKind.PROBLEM)
    status.lastError != null -> ChipState(shortError(status.lastError), ChipKind.PROBLEM)
    waiting > 0 && window != null && !window.contains(now) ->
        ChipState("Waiting for overnight window", ChipKind.WAITING)
    waiting > 0 -> ChipState("$waiting waiting", ChipKind.WAITING)
    failed > 0 -> ChipState("$failed failed", ChipKind.PROBLEM)
    reach == Reachability.CHECKING -> ChipState("Checking…", ChipKind.BUSY)
    else -> ChipState("Up to date", ChipKind.OK)
}

private const val CHIP_ERROR_CHARS = 24

/** First line of [error], cut to [CHIP_ERROR_CHARS] characters plus "…" when longer. */
private fun shortError(error: String): String {
    val line = error.lineSequence().first().trim()
    return if (line.length <= CHIP_ERROR_CHARS) line else line.take(CHIP_ERROR_CHARS).trimEnd() + "…"
}
