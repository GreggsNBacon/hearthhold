package dev.photobackup.freeup

import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.media.PhoneMedia
import java.time.Instant
import java.time.ZoneId

object SuggestionRule {
    /** Verified, unchanged-since-backup items older than [ageMonths] calendar months, biggest first. */
    fun suggest(
        entries: List<LedgerEntry>,
        phone: Map<Long, PhoneMedia>,
        nowMs: Long,
        ageMonths: Int,
        zone: ZoneId,
    ): List<LedgerEntry> {
        val cutoff = Instant.ofEpochMilli(nowMs).atZone(zone).minusMonths(ageMonths.toLong()).toInstant().toEpochMilli()
        return entries
            .filter { e ->
                val p = phone[e.mediaId]
                // A row with a drive move in flight is never offered (its drive path is not settled).
                e.movingTo == null && LedgerRules.isSafeToRemove(e, p) && itemDateMs(e, p) < cutoff
            }
            .sortedByDescending { it.sizeBytes }
    }

    private fun itemDateMs(e: LedgerEntry, p: PhoneMedia?): Long = when {
        e.dateTakenMs > 0 -> e.dateTakenMs
        p != null && p.dateTakenMs > 0 -> p.dateTakenMs
        p != null -> p.dateAddedSec * 1000
        else -> e.dateTakenMs
    }
}
