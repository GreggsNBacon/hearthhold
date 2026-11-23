package dev.photobackup.freeup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class SuggestionRuleTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 11, 22).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun entry(id: Long, taken: Long, size: Long = 100, status: BackupStatus = BackupStatus.VERIFIED) =
        LedgerEntry(id, "content://media/$id", "f$id.jpg", "DCIM/", size, taken, 10, false, "h", "r\\f$id.jpg",
            null, status, null, 0)

    private fun phone(e: LedgerEntry, size: Long = e.sizeBytes) =
        PhoneMedia(e.mediaId, e.localUri, e.displayName, e.relativePath, size, e.dateTakenMs, 0, e.dateModifiedSec,
            false, "image/jpeg")

    private fun run(
        entries: List<LedgerEntry>,
        phoneMap: Map<Long, PhoneMedia> = entries.associate { it.mediaId to phone(it) },
    ) = SuggestionRule.suggest(entries, phoneMap, now, 6, zone).map { it.mediaId }

    @Test fun onlyOlderThanSixMonthsAndVerified() {
        val old = entry(1, ms(2026, 5, 21)); val recent = entry(2, ms(2026, 5, 23))
        val pending = entry(3, ms(2026, 1, 1), status = BackupStatus.PENDING)
        assertEquals(listOf(1L), run(listOf(old, recent, pending)))
    }

    @Test fun sortedBiggestFirst() {
        val a = entry(1, ms(2025, 1, 1), 10); val b = entry(2, ms(2025, 1, 1), 300); val c = entry(3, ms(2025, 1, 1), 50)
        assertEquals(listOf(2L, 3L, 1L), run(listOf(a, b, c)))
    }

    @Test fun excludesEditedSinceBackup() {
        val a = entry(1, ms(2025, 1, 1)); val b = entry(2, ms(2025, 1, 1))
        val map = mapOf(1L to phone(a), 2L to phone(b, size = 999))
        assertEquals(listOf(1L), run(listOf(a, b), map))
    }

    @Test fun excludesItemsGoneFromPhone() {
        val a = entry(1, ms(2025, 1, 1))
        assertEquals(emptyList<Long>(), run(listOf(a), emptyMap()))
    }

    @Test fun fallsBackToDateAddedWhenNoDateTaken() {
        val old = entry(1, 0); val recent = entry(2, 0)
        val map = mapOf(
            1L to phone(old).copy(dateAddedSec = ms(2025, 1, 1) / 1000),
            2L to phone(recent).copy(dateAddedSec = ms(2026, 11, 1) / 1000),
        )
        assertEquals(listOf(1L), run(listOf(old, recent), map))
    }
}
