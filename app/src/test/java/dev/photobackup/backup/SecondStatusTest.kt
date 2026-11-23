package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.ledger.SecondStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondStatusTest {
    @Test fun textCases() {
        assertNull(SecondStatusText.of(null, 0))
        assertEquals("Up to date", SecondStatusText.of(SecondResult.Done(5, 1, 0, 0), 0))
        assertEquals("3 waiting", SecondStatusText.of(SecondResult.Done(0, 0, 0, 3), 3))
        assertEquals("Not reachable", SecondStatusText.of(SecondResult.Unreachable, 9))
        assertEquals("Re-enter second location password", SecondStatusText.of(SecondResult.AuthFailed, 9))
        assertEquals("2 failed", SecondStatusText.of(SecondResult.Done(0, 0, 2, 0), 2))
    }

    @Test fun failedBeatsWaitingAndWaitingCountComesFromTheLedger() {
        assertEquals("1 failed", SecondStatusText.of(SecondResult.Done(0, 0, 1, 4), 5))
        // Done says nothing waiting, but the ledger still has rows without a verified copy (e.g. new files this run).
        assertEquals("2 waiting", SecondStatusText.of(SecondResult.Done(1, 0, 0, 0), 2))
    }

    private fun row(id: Long, status: BackupStatus = BackupStatus.VERIFIED, sha: String? = "h$id", bin: Long? = null) =
        LedgerEntry(id, "u$id", "n", "DCIM/", 1, 0, 0, false, sha, "r\\$id", null, status, null, 0, binRequestedAt = bin)

    private fun copy(id: Long, status: SecondStatus, sha: String? = "h$id", part: String? = null) =
        SecondCopy(id, "s\\$id", part, null, status, 1, sha, null, 0)

    @Test fun waitingCountsEligibleRowsWithoutAVerifiedCopy() = runBlocking {
        val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        ledger.upsert(row(1)) // verified copy: not waiting
        copies.upsert(copy(1, SecondStatus.VERIFIED))
        ledger.upsert(row(2)) // no SecondCopy row: waiting
        ledger.upsert(row(3, BackupStatus.REMOVED_FROM_PHONE)) // UPLOADING with a part: waiting, not copied
        copies.upsert(copy(3, SecondStatus.UPLOADING, part = "s\\3.part"))
        ledger.upsert(row(4)) // FAILED copy: waiting
        copies.upsert(copy(4, SecondStatus.FAILED))
        ledger.upsert(row(5, bin = 9)) // bin request: not eligible
        ledger.upsert(row(6, sha = null)) // no hash: not eligible
        ledger.upsert(row(7, BackupStatus.PENDING)) // not on the main drive yet: not eligible
        ledger.upsert(row(8)) // edited on main: the copy's hash is stale
        copies.upsert(copy(8, SecondStatus.VERIFIED, sha = "old"))
        assertEquals(4, SecondWaiting.count(ledger, copies)) // 2, 3, 4, 8
    }
}
