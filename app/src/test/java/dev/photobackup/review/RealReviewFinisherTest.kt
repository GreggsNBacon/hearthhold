package dev.photobackup.review

import dev.photobackup.backup.Hashing
import dev.photobackup.backup.LocalOpener
import dev.photobackup.backup.MemoryLedger
import dev.photobackup.freeup.FreeUpRepository
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.ledger.ReviewDecision
import dev.photobackup.ledger.ReviewDecisionDao
import dev.photobackup.ledger.ReviewKeep
import dev.photobackup.ledger.ReviewKeepDao
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

private class FinisherDecisions : ReviewDecisionDao {
    val rows = LinkedHashMap<Long, ReviewDecision>()
    override suspend fun upsert(d: ReviewDecision) { rows[d.mediaId] = d }
    override suspend fun delete(mediaId: Long) { rows.remove(mediaId) }
    override suspend fun lastDecided() = rows.values.maxWithOrNull(compareBy({ it.decidedAt }, { it.mediaId }))
    override suspend fun all() = rows.values.sortedWith(compareBy({ it.decidedAt }, { it.mediaId }))
    override suspend fun clear(ids: List<Long>) { ids.forEach { rows.remove(it) } }
}

private class FinisherKeeps : ReviewKeepDao {
    val rows = LinkedHashMap<Long, ReviewKeep>()
    override suspend fun upsert(k: ReviewKeep) { rows[k.mediaId] = k }
    override suspend fun get(mediaId: Long) = rows[mediaId]
    override suspend fun all() = rows.values.toList()
    override suspend fun since(cutoffMs: Long) = rows.values.filter { it.keptAt >= cutoffMs }
    override suspend fun clear(ids: List<Long>) { ids.forEach { rows.remove(it) } }
}

class RealReviewFinisherTest {
    private val now = 1_800_000_000_000L
    private val ledger = MemoryLedger()
    private val decisions = FinisherDecisions()
    private val keeps = FinisherKeeps()
    private val store = FakeRemoteStore()
    private val phone = LinkedHashMap<Long, PhoneMedia>()
    private val phoneBytes = HashMap<Long, ByteArray>()
    private var storeAvailable = true
    private val requests = mutableListOf<List<Long>>()
    private var runs = 0

    private fun data(id: Long, size: Int = 5) = ByteArray(size) { (id + it).toByte() }

    private fun finisher(l: LedgerStore = ledger) = RealReviewFinisher(
        ledger = l,
        decisions = decisions,
        keeps = keeps,
        repo = FreeUpRepository(l, clock = { now }),
        openStore = { if (storeAvailable) store else null },
        queryPhone = { phone.toMap() },
        opener = LocalOpener { m -> phoneBytes[m.mediaId]?.let { ByteArrayInputStream(it) } },
        makeDeleteRequest = { items -> requests += items.map { it.mediaId }; "request" },
        runNow = { runs++ },
        clock = { now },
    )

    /** A VERIFIED, backed-up photo present on the phone and on the drive, with a decision. */
    private suspend fun photo(id: Long, action: ReviewAction, size: Int = 5): LedgerEntry {
        val bytes = data(id, size)
        val e = LedgerEntry(id, "content://media/$id", "f$id.jpg", "DCIM/", size.toLong(), 1000, 10, false,
            Hashing.sha256(ByteArrayInputStream(bytes)), "r\\f$id.jpg", null, BackupStatus.VERIFIED, null, 0)
        ledger.upsert(e)
        store.files[e.remotePath!!] = bytes
        phone[id] = PhoneMedia(id, e.localUri, e.displayName, e.relativePath, e.sizeBytes, e.dateTakenMs, 0,
            e.dateModifiedSec, false, "image/jpeg")
        phoneBytes[id] = bytes
        decisions.upsert(ReviewDecision(id, action, id))
        return e
    }

    @Test fun offlineDriveReturnsOfflineAndWritesNothing() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.KEEP)
        store.failOn = { op, _ -> if (op == "connect") RemoteException.Unreachable() else null }
        val before = ledger.rows.value
        assertEquals(PrepareResult.Offline, finisher().prepare(decisions.all()))
        assertEquals(before, ledger.rows.value)
        assertEquals(2, decisions.rows.size)
        assertTrue(keeps.rows.isEmpty())
        assertTrue(requests.isEmpty())
        assertEquals(0, runs)
    }

    @Test fun noDriveConfiguredIsOffline() = runBlocking {
        photo(1, ReviewAction.DRIVE_ONLY)
        storeAvailable = false
        assertEquals(PrepareResult.Offline, finisher().prepare(decisions.all()))
        assertEquals(1, decisions.rows.size)
    }

    @Test fun cancelKeepsEverything() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.DRIVE_ONLY)
        photo(3, ReviewAction.KEEP)
        val before = ledger.rows.value
        val r = finisher().prepare(decisions.all())
        assertTrue(r is PrepareResult.Ready)
        // The user cancels the Android dialog: apply() is never called.
        assertEquals(before, ledger.rows.value)
        assertEquals(setOf(1L, 2L, 3L), decisions.rows.keys)
        assertTrue(keeps.rows.isEmpty())
        assertEquals(0, runs)
    }

    @Test fun oneDeleteRequestForRowsThatPassed() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.DRIVE_ONLY)
        photo(3, ReviewAction.KEEP)
        val r = finisher().prepare(decisions.all()) as PrepareResult.Ready
        assertEquals(listOf(listOf(1L, 2L)), requests)
        assertEquals("request", r.request)
        assertEquals(listOf(1L, 2L), r.passed.map { it.mediaId })
        assertEquals(listOf(3L), r.keeps.map { it.mediaId })
        assertTrue(r.failed.isEmpty())
    }

    @Test fun partialRecheckFailureIsListedAndItsDecisionKept() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.DRIVE_ONLY)
        photo(3, ReviewAction.JUNK)
        store.files["r\\f2.jpg"] = ByteArray(5) { 99 } // drive copy damaged
        phoneBytes[3] = ByteArray(5) { 42 } // phone file changed
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        assertEquals(listOf(1L), r.passed.map { it.mediaId })
        assertEquals(
            listOf(FailedRow(2, FreeUpRepository.DAMAGED), FailedRow(3, FreeUpRepository.PHONE_CHANGED)),
            r.failed,
        )
        assertEquals(listOf(listOf(1L)), requests)
        val done = f.apply(r) as ApplyResult.Done
        assertEquals(1, done.junkCount)
        assertEquals(setOf(2L, 3L), decisions.rows.keys) // failed rows keep their decisions
        assertEquals(BackupStatus.FAILED, ledger.get(2)!!.status)
        assertEquals(BackupStatus.FAILED, ledger.get(3)!!.status)
    }

    @Test fun junkGetsBinRequestedAtOnlyAfterConfirm() = runBlocking {
        photo(1, ReviewAction.JUNK, size = 7)
        photo(2, ReviewAction.DRIVE_ONLY, size = 11)
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        assertNull(ledger.get(1)!!.binRequestedAt)
        assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
        val done = f.apply(r) as ApplyResult.Done
        assertEquals(ApplyResult.Done(freedBytes = 18, junkCount = 1, keptCount = 0), done)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, ledger.get(1)!!.status)
        assertEquals(now, ledger.get(1)!!.binRequestedAt)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, ledger.get(2)!!.status)
        assertNull(ledger.get(2)!!.binRequestedAt)
        assertTrue(decisions.rows.isEmpty())
    }

    @Test fun keepsRecordedAfterConfirm() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.KEEP)
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        assertTrue(keeps.rows.isEmpty())
        val done = f.apply(r) as ApplyResult.Done
        assertEquals(1, done.keptCount)
        assertEquals(ReviewKeep(2, now), keeps.rows[2])
        assertEquals(BackupStatus.VERIFIED, ledger.get(2)!!.status) // keep never touches the ledger
        assertTrue(decisions.rows.isEmpty())
    }

    @Test fun keepOnlyIsAppliedWithoutTheDialog() = runBlocking {
        photo(1, ReviewAction.KEEP)
        photo(2, ReviewAction.KEEP)
        val r = finisher().prepare(decisions.all())
        assertEquals(PrepareResult.NothingToDelete(keepOnlyApplied = true, keptCount = 2), r)
        assertEquals(setOf(1L, 2L), keeps.rows.keys)
        assertTrue(decisions.rows.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test fun runNowCalledOnceAfterApply() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.JUNK)
        photo(3, ReviewAction.DRIVE_ONLY)
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        assertEquals(0, runs)
        f.apply(r)
        assertEquals(1, runs)
    }

    @Test fun rowGoneFromThePhoneIsListedAndItsDecisionCleared() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.DRIVE_ONLY)
        phone.remove(2L)
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        assertEquals(listOf(FailedRow(2, RealReviewFinisher.GONE_FROM_PHONE)), r.failed)
        assertEquals(setOf(1L, 2L), decisions.rows.keys) // nothing changes before confirm
        f.apply(r)
        assertTrue(decisions.rows.isEmpty())
        assertEquals(BackupStatus.VERIFIED, ledger.get(2)!!.status)
    }

    @Test fun allGoneFromThePhoneClearsDecisionsWithoutDialog() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.KEEP)
        phone.remove(1L)
        val r = finisher().prepare(decisions.all())
        assertEquals(
            PrepareResult.NothingToDelete(true, 1, listOf(FailedRow(1, RealReviewFinisher.GONE_FROM_PHONE))), r,
        )
        assertTrue(decisions.rows.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test fun rowMovedByABackupRunMeanwhileKeepsItsDecisionAndIsCounted() = runBlocking {
        photo(1, ReviewAction.JUNK, size = 7)
        photo(2, ReviewAction.JUNK, size = 11)
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        ledger.upsert(ledger.get(2)!!.copy(movingTo = "elsewhere\\f2.jpg")) // a backup run starts a move
        val done = f.apply(r) as ApplyResult.Done
        assertEquals(ApplyResult.Done(freedBytes = 7, junkCount = 1, keptCount = 0, leftAlone = 1), done)
        assertEquals(setOf(2L), decisions.rows.keys)
        val two = ledger.get(2)!!
        assertEquals(BackupStatus.VERIFIED, two.status)
        assertNull(two.binRequestedAt)
        assertEquals("elsewhere\\f2.jpg", two.movingTo)
        assertEquals(1, runs)
    }

    @Test fun junkIsMarkedWithOneConditionalWrite() = runBlocking {
        photo(1, ReviewAction.JUNK)
        val writes = mutableListOf<String>()
        val spy = object : LedgerStore by ledger {
            override suspend fun upsert(e: LedgerEntry) { writes += "upsert"; ledger.upsert(e) }
            override suspend fun markRemovedIfSettled(mediaId: Long, nowMs: Long, binRequestedAt: Long?): Boolean {
                writes += "mark $mediaId $binRequestedAt"
                return ledger.markRemovedIfSettled(mediaId, nowMs, binRequestedAt)
            }
        }
        val f = finisher(spy)
        f.apply(f.prepare(decisions.all()) as PrepareResult.Ready)
        assertEquals(listOf("mark 1 $now"), writes)
    }
}
