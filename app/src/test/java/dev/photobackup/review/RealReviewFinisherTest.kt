package dev.photobackup.review

import dev.photobackup.backup.Hashing
import dev.photobackup.backup.LocalOpener
import dev.photobackup.backup.MemoryLedger
import dev.photobackup.backup.MemorySecondCopies
import dev.photobackup.freeup.SecondGate
import dev.photobackup.freeup.SecondGateOpen
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.smb.RemoteStore
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
    /** On the phone but outside the configured backup folders: absent from [phone], present for [phoneExists]. */
    private val elsewhere = mutableSetOf<Long>()
    private var existsFails = false
    private val existsAsked = mutableListOf<Set<Long>>()

    private val phoneExists: suspend (Collection<Long>) -> Set<Long> = { ids ->
        existsAsked += ids.toSet()
        if (existsFails) throw SecurityException("No photo access")
        ids.filter { it in phone || it in elsewhere }.toSet()
    }

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
        phoneExists = phoneExists,
        openSecondGate = { secondGate() },
    )

    private var secondGate: suspend () -> SecondGateOpen = { SecondGateOpen.Off }

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

    /** I2: a Drive only row whose phone copy is already gone is applied at prepare, outside the dialog. */
    @Test fun driveOnlyRowAlreadyGoneFromThePhoneIsAppliedAtPrepare() = runBlocking {
        photo(1, ReviewAction.JUNK)
        val two = photo(2, ReviewAction.DRIVE_ONLY)
        phone.remove(2L)
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        assertTrue(r.failed.isEmpty())
        assertEquals(listOf(ReviewDecision(2, ReviewAction.DRIVE_ONLY, 2)), r.alreadyGone)
        assertEquals(listOf(listOf(1L)), requests) // the gone row is not in the delete request
        assertEquals(setOf(1L), decisions.rows.keys)
        assertEquals(two.copy(status = BackupStatus.REMOVED_FROM_PHONE, updatedAtMs = now), ledger.get(2))
        val done = f.apply(r) as ApplyResult.Done
        assertEquals(1, done.junkCount)
        assertTrue(decisions.rows.isEmpty())
    }

    /** I2 / T5: Junk whose phone copy is gone (deleted, intent not recorded) still goes to the drive bin. */
    @Test fun junkAlreadyGoneFromThePhoneIsBinRequestedWithoutDialog() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.KEEP)
        phone.remove(1L)
        val r = finisher().prepare(decisions.all())
        assertEquals(
            PrepareResult.NothingToDelete(
                true, 1, emptyList(), alreadyGone = listOf(ReviewDecision(1, ReviewAction.JUNK, 1)),
            ),
            r,
        )
        val one = ledger.get(1)!!
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, one.status)
        assertEquals(now, one.binRequestedAt)
        assertTrue(decisions.rows.isEmpty())
        assertTrue(requests.isEmpty())
    }

    /** NEW-1: still on the phone but outside the backup folders (unticked / moved): never marked, decision kept. */
    @Test fun rowOutsideTheBackupFoldersButStillOnThePhoneIsNotMarked() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.DRIVE_ONLY)
        photo(3, ReviewAction.JUNK)
        phone.remove(1L); elsewhere += 1L // folder unticked
        phone.remove(2L); elsewhere += 2L // file moved to another folder
        phone.remove(3L) // really gone
        val before1 = ledger.get(1); val before2 = ledger.get(2)
        val r = finisher().prepare(decisions.all())
        assertEquals(
            PrepareResult.NothingToDelete(
                false, 0,
                listOf(
                    FailedRow(1, RealReviewFinisher.NOT_IN_FOLDERS),
                    FailedRow(2, RealReviewFinisher.NOT_IN_FOLDERS),
                ),
                alreadyGone = listOf(ReviewDecision(3, ReviewAction.JUNK, 3)),
            ),
            r,
        )
        assertEquals(before1, ledger.get(1))
        assertEquals(before2, ledger.get(2))
        assertEquals(now, ledger.get(3)!!.binRequestedAt)
        assertEquals(setOf(1L, 2L), decisions.rows.keys)
        assertEquals(listOf(setOf(1L, 2L, 3L)), existsAsked) // one check, only for rows missing from the query
    }

    /** NEW-1: if the unfiltered check fails, nothing is treated as gone: no marks, decisions kept. */
    @Test fun existenceCheckFailureMarksNothing() = runBlocking {
        val e = photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.KEEP)
        phone.remove(1L)
        existsFails = true
        val r = finisher().prepare(decisions.all())
        assertEquals(
            PrepareResult.NothingToDelete(true, 1, listOf(FailedRow(1, RealReviewFinisher.NOT_READY))), r,
        )
        assertEquals(e, ledger.get(1))
        assertEquals(setOf(1L), decisions.rows.keys)
    }

    /** No row missing from the phone query: the existence check is not needed. */
    @Test fun existenceCheckSkippedWhenNothingIsMissing() = runBlocking {
        photo(1, ReviewAction.JUNK)
        finisher().prepare(decisions.all())
        assertTrue(existsAsked.isEmpty())
    }

    /** A gone row that is no longer VERIFIED can't be applied: listed as gone, decision cleared, row untouched. */
    @Test fun rowGoneFromThePhoneAndNotVerifiedIsListedAndCleared() = runBlocking {
        val e = photo(1, ReviewAction.JUNK)
        ledger.upsert(e.copy(status = BackupStatus.REMOVED_FROM_PHONE))
        phone.remove(1L)
        val r = finisher().prepare(decisions.all())
        assertEquals(
            PrepareResult.NothingToDelete(false, 0, listOf(FailedRow(1, RealReviewFinisher.GONE_FROM_PHONE))), r,
        )
        assertNull(ledger.get(1)!!.binRequestedAt)
        assertTrue(decisions.rows.isEmpty())
    }

    /** A gone row with a drive move in flight waits: decision kept, applied by a later Finish. */
    @Test fun rowGoneFromThePhoneWithAMoveInFlightKeepsItsDecision() = runBlocking {
        val e = photo(1, ReviewAction.JUNK)
        ledger.upsert(e.copy(movingTo = "elsewhere\\f1.jpg"))
        phone.remove(1L)
        val r = finisher().prepare(decisions.all())
        assertEquals(PrepareResult.NothingToDelete(false, 0, listOf(FailedRow(1, RealReviewFinisher.NOT_READY))), r)
        assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
        assertEquals(setOf(1L), decisions.rows.keys)
    }

    @Test fun editedPhoneFileIsListedAndItsDecisionCleared() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.DRIVE_ONLY)
        phone[2] = phone[2]!!.copy(sizeBytes = 99) // edited since it was backed up
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        assertEquals(listOf(FailedRow(2, RealReviewFinisher.EDITED)), r.failed)
        assertEquals(setOf(1L, 2L), decisions.rows.keys)
        f.apply(r)
        assertTrue(decisions.rows.isEmpty())
    }

    @Test fun rowWithoutLedgerEntryIsGoneAndCleared() = runBlocking {
        photo(1, ReviewAction.JUNK)
        ledger.delete(1)
        val r = finisher().prepare(decisions.all())
        assertEquals(
            PrepareResult.NothingToDelete(false, 0, listOf(FailedRow(1, RealReviewFinisher.GONE_FROM_PHONE))), r,
        )
        assertTrue(decisions.rows.isEmpty())
    }

    @Test fun rowThatFailedEarlierIsNotReadyAndKeepsItsDecisionOnRetry() = runBlocking {
        photo(1, ReviewAction.JUNK)
        store.files["r\\f1.jpg"] = ByteArray(5) { 99 } // damaged: first Finish marks it FAILED
        val f = finisher()
        val first = f.prepare(decisions.all())
        assertEquals(listOf(FailedRow(1, FreeUpRepository.DAMAGED)), (first as PrepareResult.NothingToDelete).failed)
        assertEquals(BackupStatus.FAILED, ledger.get(1)!!.status)
        val retry = f.prepare(decisions.all())
        assertEquals(
            PrepareResult.NothingToDelete(false, 0, listOf(FailedRow(1, RealReviewFinisher.NOT_READY))), retry,
        )
        assertEquals(setOf(1L), decisions.rows.keys)
    }

    @Test fun rowWithAMoveInFlightIsNotReadyAndKeepsItsDecision() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.DRIVE_ONLY)
        ledger.upsert(ledger.get(2)!!.copy(movingTo = "elsewhere\\f2.jpg"))
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        assertEquals(listOf(FailedRow(2, RealReviewFinisher.NOT_READY)), r.failed)
        f.apply(r)
        assertEquals(setOf(2L), decisions.rows.keys)
    }

    @Test fun runNowStillCalledWhenAWriteFailsAfterConfirm() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.KEEP)
        val f = RealReviewFinisher(
            ledger, decisions,
            object : ReviewKeepDao by keeps { override suspend fun upsert(k: ReviewKeep) = throw IllegalStateException() },
            FreeUpRepository(ledger, clock = { now }), { store }, { phone.toMap() },
            LocalOpener { m -> phoneBytes[m.mediaId]?.let { ByteArrayInputStream(it) } },
            { "request" }, { runs++ }, { now }, phoneExists,
        )
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        try { f.apply(r); throw AssertionError("expected failure") } catch (_: IllegalStateException) {}
        assertEquals(1, runs)
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

    /** I2 (a): a move started between prepare and apply; the next Finish (phone copy now gone) bins it. */
    @Test fun junkLeftAloneByAMoveIsBinnedByTheNextFinish() = runBlocking {
        photo(1, ReviewAction.JUNK)
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        ledger.upsert(ledger.get(1)!!.copy(movingTo = "r\\Dog\\f1.jpg")) // a backup run starts a folder move
        assertEquals(ApplyResult.Done(0, 0, 0, leftAlone = 1), f.apply(r))
        phone.remove(1L) // Android deleted the phone copy
        assertEquals(setOf(1L), decisions.rows.keys) // decision kept, as the message says
        // the run finishes its move
        ledger.upsert(ledger.get(1)!!.copy(remotePath = "r\\Dog\\f1.jpg", movingTo = null))
        store.files["r\\Dog\\f1.jpg"] = store.files.remove("r\\f1.jpg")!!
        val again = f.prepare(decisions.all())
        assertEquals(
            PrepareResult.NothingToDelete(false, 0, emptyList(), listOf(ReviewDecision(1, ReviewAction.JUNK, 1))),
            again,
        )
        val one = ledger.get(1)!!
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, one.status)
        assertEquals(now, one.binRequestedAt)
        assertTrue(decisions.rows.isEmpty())
    }

    /** A confirmed row a backup run took out of VERIFIED can never apply: its decision is not kept. */
    @Test fun confirmedRowNoLongerVerifiedIsDroppedNotKept() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.DRIVE_ONLY, size = 9)
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        ledger.upsert(ledger.get(1)!!.copy(status = BackupStatus.PENDING)) // e.g. re-queued meanwhile
        assertEquals(ApplyResult.Done(9, 0, 0, leftAlone = 0, dropped = 1), f.apply(r))
        assertTrue(decisions.rows.isEmpty())
        assertEquals(1, runs)
    }

    /** T3: leaving the screen during "Applying..." cancels the caller; the marks, clears and runNow still happen. */
    @Test fun applyCompletesWhenTheCallerIsCancelled() = runBlocking {
        photo(1, ReviewAction.JUNK)
        photo(2, ReviewAction.DRIVE_ONLY)
        photo(3, ReviewAction.KEEP)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val slow = object : LedgerStore by ledger {
            override suspend fun markRemovedIfSettled(mediaId: Long, nowMs: Long, binRequestedAt: Long?): Boolean {
                entered.complete(Unit)
                gate.await()
                return ledger.markRemovedIfSettled(mediaId, nowMs, binRequestedAt)
            }
        }
        val f = finisher(slow)
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        val job = launch { f.apply(r) }
        entered.await()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, ledger.get(1)!!.status)
        assertEquals(now, ledger.get(1)!!.binRequestedAt)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, ledger.get(2)!!.status)
        assertEquals(ReviewKeep(3, now), keeps.rows[3])
        assertTrue(decisions.rows.isEmpty())
        assertEquals(1, runs)
    }

    /** T3: a ledger write that throws mid-loop still starts the drive-side work. */
    @Test fun runNowStillCalledWhenMarkingThrows() = runBlocking {
        photo(1, ReviewAction.JUNK)
        val bad = object : LedgerStore by ledger {
            override suspend fun markRemovedIfSettled(mediaId: Long, nowMs: Long, binRequestedAt: Long?): Boolean =
                throw IllegalStateException()
        }
        val f = finisher(bad)
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        try { f.apply(r); throw AssertionError("expected failure") } catch (_: IllegalStateException) {}
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

    // ---- Second-location gate ----

    private val copies = MemorySecondCopies()
    private val second = FakeRemoteStore()
    private var secondClosed = 0
    private val secondStore = object : RemoteStore by second {
        override fun close() { secondClosed++ }
    }

    private suspend fun secondCopy(e: LedgerEntry, status: SecondStatus = SecondStatus.VERIFIED) {
        val path = "s\\f${e.mediaId}.jpg"
        copies.upsert(SecondCopy(e.mediaId, path, null, null, status, e.sizeBytes, e.localSha256, null, 0))
        second.files[path] = data(e.mediaId, e.sizeBytes.toInt())
    }

    private fun gateOn() { secondGate = { SecondGateOpen.Open(SecondGate(secondStore, copies)) } }

    @Test fun gateOnWaitingRowsAreListedNotFailedAndKeepTheirDecisions() = runBlocking {
        secondCopy(photo(1, ReviewAction.JUNK))
        secondCopy(photo(2, ReviewAction.DRIVE_ONLY), SecondStatus.FAILED)
        photo(3, ReviewAction.JUNK) // no second copy at all
        gateOn()
        val f = finisher()
        val r = f.prepare(decisions.all()) as PrepareResult.Ready
        assertEquals(listOf(1L), r.passed.map { it.mediaId })
        assertEquals(
            listOf(FailedRow(2, FreeUpRepository.WAITING_SECOND), FailedRow(3, FreeUpRepository.WAITING_SECOND)),
            r.failed,
        )
        f.apply(r)
        assertEquals(setOf(2L, 3L), decisions.rows.keys)
        assertEquals(BackupStatus.VERIFIED, ledger.get(2)!!.status)
        assertEquals(BackupStatus.VERIFIED, ledger.get(3)!!.status)
        assertEquals(1, secondClosed)
    }

    @Test fun gateOnAllWaitingDeletesNothingAndKeepsDecisions() = runBlocking {
        photo(1, ReviewAction.DRIVE_ONLY)
        gateOn()
        val r = finisher().prepare(decisions.all()) as PrepareResult.NothingToDelete
        assertEquals(listOf(FailedRow(1, FreeUpRepository.WAITING_SECOND)), r.failed)
        assertTrue(requests.isEmpty())
        assertEquals(setOf(1L), decisions.rows.keys)
    }

    @Test fun secondUnreachableRemovesNothingAndKeepsAllDecisions() = runBlocking {
        secondCopy(photo(1, ReviewAction.JUNK))
        photo(2, ReviewAction.KEEP)
        secondGate = { SecondGateOpen.Unreachable }
        val before = ledger.rows.value
        assertEquals(PrepareResult.SecondUnreachable, finisher().prepare(decisions.all()))
        assertEquals(before, ledger.rows.value)
        assertEquals(setOf(1L, 2L), decisions.rows.keys)
        assertTrue(keeps.rows.isEmpty())
        assertTrue(requests.isEmpty())
        assertEquals(0, runs)
    }

    @Test fun secondDropMidCheckListsUnreachableAndKeepsDecisions() = runBlocking {
        secondCopy(photo(1, ReviewAction.JUNK))
        secondCopy(photo(2, ReviewAction.JUNK))
        second.failOn = { op, _ -> if (op == "stat") RemoteException.Unreachable() else null }
        gateOn()
        val r = finisher().prepare(decisions.all()) as PrepareResult.NothingToDelete
        assertEquals(
            listOf(FailedRow(1, FreeUpRepository.SECOND_UNREACHABLE), FailedRow(2, FreeUpRepository.SECOND_UNREACHABLE)),
            r.failed,
        )
        assertEquals(setOf(1L, 2L), decisions.rows.keys)
        assertEquals(1, secondClosed)
    }

    @Test fun gateOffFinishNeedsNoSecondCopy() = runBlocking {
        photo(1, ReviewAction.JUNK)
        var asked = 0
        secondGate = { asked++; SecondGateOpen.Off }
        val r = finisher().prepare(decisions.all()) as PrepareResult.Ready
        assertEquals(listOf(1L), r.passed.map { it.mediaId })
        assertEquals(1, asked)
    }

    @Test fun keepOnlyFinishIsNotHeldWhenTheSecondLocationIsOffline() = runBlocking {
        photo(1, ReviewAction.KEEP); photo(2, ReviewAction.KEEP)
        var asked = 0
        secondGate = { asked++; SecondGateOpen.Unreachable }
        val r = finisher().prepare(decisions.all())
        assertEquals(PrepareResult.NothingToDelete(keepOnlyApplied = true, keptCount = 2), r)
        assertEquals(0, asked) // nothing leaves the phone: the second location is not needed
        assertEquals(setOf(1L, 2L), keeps.rows.keys)
    }
}
