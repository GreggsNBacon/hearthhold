package dev.photobackup.backup

import java.time.Duration
import java.time.LocalTime

/** "Only back up overnight": automatic runs happen only from [startHour]:00 until [endHour]:00 (may span midnight). */
data class OvernightWindow(val startHour: Int, val endHour: Int) {
    val start: LocalTime get() = LocalTime.of(startHour, 0)
    val end: LocalTime get() = LocalTime.of(endHour, 0)

    /** e.g. "1:00–6:00" or "23:00–5:00". */
    val label: String get() = "$startHour:00–$endHour:00"

    fun contains(now: LocalTime) = BackupWindow.isInWindow(now, start, end)
}

/** Pure time arithmetic for the overnight window. Local wall-clock time; a DST change shifts one night by an hour. */
object BackupWindow {
    private val DAY: Duration = Duration.ofDays(1)

    /** Start inclusive, end exclusive. start > end spans midnight; start == end means all day. */
    fun isInWindow(now: LocalTime, start: LocalTime, end: LocalTime): Boolean = when {
        start == end -> true
        start < end -> now >= start && now < end
        else -> now >= start || now < end
    }

    /** Zero when already inside the window (run now), else the time until the window next opens. */
    fun nextWindowStartDelay(now: LocalTime, start: LocalTime, end: LocalTime): Duration =
        if (isInWindow(now, start, end)) Duration.ZERO else untilNextStart(now, start)

    /** Time until [start] next comes round, strictly in the future (exactly at [start] means tomorrow). */
    fun untilNextStart(now: LocalTime, start: LocalTime): Duration {
        val d = Duration.between(now, start)
        return if (d.isNegative || d.isZero) d.plus(DAY) else d
    }

    fun waitingMessage(w: OvernightWindow) = "Waiting for overnight window (${w.label})"
}
