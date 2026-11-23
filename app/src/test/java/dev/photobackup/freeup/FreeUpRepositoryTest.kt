package dev.photobackup.freeup

import dev.photobackup.backup.Hashing
import dev.photobackup.backup.LocalOpener
import dev.photobackup.backup.MemoryLedger
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class FreeUpRepositoryTest {
    private fun data(id: Long, size: Int = 5) = ByteArray(size) { (id + it).toByte() }
    private fun hash(b: ByteArray) = Hashing.sha256(ByteArrayInputStream(b))

    private fun entry(id: Long, size: Long = 5, remote: String? = "r\\f$id.jpg", sha: String? = hash(data(id, size.toInt()))) =
        LedgerEntry(id, "content://media/$id", "f$id.jpg", "DCIM/", size, 1000, 10, false, sha, remote,
            null, BackupStatus.VERIFIED, null, 0)

    private fun phone(e: LedgerEntry, size: Long = e.sizeBytes) =
        PhoneMedia(e.mediaId, e.localUri, e.displayName, e.relativePath, size, e.dateTakenMs, 0, e.dateModifiedSec,
            false, "image/jpeg")

    /** Phone opener serving [data] (default: the bytes the entry was hashed from). */
    private fun opener(vararg override: Pair<Long, ByteArray>): LocalOpener {
        val o = override.toMap()
        return LocalOpener { m -> ByteArrayInputStream(o[m.mediaId] ?: data(m.mediaId)) }
    }

    private suspend fun setup(vararg es: LedgerEntry): Pair<MemoryLedger, FakeRemoteStore> {
        val ledger = MemoryLedger(); val store = FakeRemoteStore()
        es.forEach { ledger.upsert(it); store.files[it.remotePath!!] = data(it.mediaId, it.sizeBytes.toInt()) }
        return ledger to store
    }

    @Test fun recheckDropsItemsWhoseRemoteIsMissingOrWrongSize() = runBlocking {
        val ok = entry(1); val missing = entry(2); val wrong = entry(3); val edited = entry(4)
        val (ledger, store) = setup(ok, missing, wrong, edited)
        store.files.remove(missing.remotePath!!)
        store.files[wrong.remotePath!!] = ByteArray(9)
        val all = listOf(ok, missing, wrong, edited)
        val phone = all.associate { it.mediaId to phone(it) } + (4L to phone(edited, size = 77))
        val kept = FreeUpRepository(ledger).recheck(all, store, phone, opener())
        assertEquals(listOf(1L), kept.map { it.mediaId })
        assertEquals(BackupStatus.FAILED, ledger.get(2)!!.status)
        assertEquals(BackupStatus.FAILED, ledger.get(3)!!.status)
        assertEquals(BackupStatus.VERIFIED, ledger.get(4)!!.status) // edited on phone: just not suggested
    }

    @Test fun damagedRemoteIsExcludedAndMarkedFailed() = runBlocking {
        val e = entry(1)
        val (ledger, store) = setup(e)
        store.corruptReads = true
        val kept = FreeUpRepository(ledger).recheck(listOf(e), store, mapOf(1L to phone(e)), opener())
        assertTrue(kept.isEmpty())
        assertEquals(BackupStatus.FAILED, ledger.get(1)!!.status)
        assertEquals(FreeUpRepository.DAMAGED, ledger.get(1)!!.lastError)
    }

    @Test fun phoneBytesDifferingFromRecordedHashAreExcludedAndMarkedFailed() = runBlocking {
        val e = entry(1)
        val (ledger, store) = setup(e)
        val kept = FreeUpRepository(ledger).recheck(
            listOf(e), store, mapOf(1L to phone(e)), opener(1L to ByteArray(5) { 99 }),
        )
        assertTrue(kept.isEmpty())
        assertEquals(BackupStatus.FAILED, ledger.get(1)!!.status)
        assertEquals(FreeUpRepository.PHONE_CHANGED, ledger.get(1)!!.lastError)
    }

    @Test fun nullRecordedHashFailsAsDamaged() = runBlocking {
        val e = entry(1, sha = null)
        val (ledger, store) = setup(e)
        val kept = FreeUpRepository(ledger).recheck(listOf(e), store, mapOf(1L to phone(e)), opener())
        assertTrue(kept.isEmpty())
        assertEquals(FreeUpRepository.DAMAGED, ledger.get(1)!!.lastError)
    }

    @Test fun allGoodIsIncludedAndReportsProgress() = runBlocking {
        val a = entry(1); val b = entry(2)
        val (ledger, store) = setup(a, b)
        val progress = mutableListOf<Int>()
        val kept = FreeUpRepository(ledger).recheck(
            listOf(a, b), store, mapOf(1L to phone(a), 2L to phone(b)), opener(),
            onProgress = { c, t, _, _ -> assertEquals(2, t); progress.add(c) },
        )
        assertEquals(listOf(1L, 2L), kept.map { it.mediaId })
        assertEquals(2, progress.last())
    }

    @Test fun itemNoLongerVerifiedInLedgerIsDropped() = runBlocking {
        val e = entry(1)
        val (ledger, store) = setup(e)
        ledger.upsert(e.copy(status = BackupStatus.PENDING))
        val kept = FreeUpRepository(ledger).recheck(listOf(e), store, mapOf(1L to phone(e)), opener())
        assertTrue(kept.isEmpty())
        assertEquals(BackupStatus.PENDING, ledger.get(1)!!.status)
    }

    @Test fun cancellationProducesNoResult() = runBlocking {
        val e = entry(1, size = 3 * 1024 * 1024)
        val (ledger, store) = setup(e)
        var result: List<LedgerEntry>? = null
        var maxBytes = 0L
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            result = FreeUpRepository(ledger).recheck(listOf(e), store, mapOf(1L to phone(e)), opener(), { _, _, b, _ ->
                maxBytes = maxOf(maxBytes, b)
                if (b > 0) job.cancel() // cancel mid-read; the next chunk's ensureActive must stop the check
            })
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertNull(result)
        assertTrue("stopped after the first chunk, got $maxBytes", maxBytes in 1..(1024 * 1024))
        assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
    }

    @Test fun phoneItemVanishedIsDroppedNotFailed() = runBlocking {
        val e = entry(1)
        val (ledger, store) = setup(e)
        var failed = 0
        val kept = FreeUpRepository(ledger).recheck(
            listOf(e), store, mapOf(1L to phone(e)), LocalOpener { null }, onFailed = { _, _ -> failed++ },
        )
        assertTrue(kept.isEmpty())
        assertEquals(0, failed)
        assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
    }

    @Test fun recheckPropagatesUnreachableDrive() {
        val e = entry(1)
        val store = FakeRemoteStore(failOn = { op, _ -> if (op == "stat") RemoteException.Unreachable() else null })
        try {
            runBlocking {
                val ledger = MemoryLedger().also { it.upsert(e) }
                FreeUpRepository(ledger).recheck(listOf(e), store, mapOf(1L to phone(e)), opener())
            }
            fail("expected RemoteException")
        } catch (ex: RemoteException) {
            assertTrue(ex is RemoteException.Unreachable)
        }
    }

    @Test fun markRemovedSetsStatus() = runBlocking {
        val ledger = MemoryLedger(); val e = entry(1)
        ledger.upsert(e)
        FreeUpRepository(ledger, clock = { 4242L }).markRemoved(listOf(e))
        val got = ledger.get(1)!!
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, got.status)
        assertEquals(4242L, got.updatedAtMs)
        assertEquals(e.remotePath, got.remotePath)
    }

    @Test fun markRemovedSkipsEntriesNoLongerVerified() = runBlocking {
        val ledger = MemoryLedger(); val e = entry(1)
        ledger.upsert(e.copy(status = BackupStatus.FAILED))
        assertEquals(0, FreeUpRepository(ledger).markRemoved(listOf(e)))
        assertEquals(BackupStatus.FAILED, ledger.get(1)!!.status)
    }


    @Test fun failedCheckDoesNotOverwriteARowABackupChangedMeanwhile() = runBlocking {
        val e = entry(1)
        val (ledger, store) = setup(e)
        store.files[e.remotePath!!] = ByteArray(5) // damaged drive copy
        // While the (slow) check runs, a backup run starts re-uploading this item.
        val uploading = e.copy(status = BackupStatus.UPLOADING, partPath = "r\\f1 (1).jpg.part", remotePath = null)
        val racing = object : dev.photobackup.ledger.LedgerStore by ledger {
            var reads = 0
            override suspend fun get(mediaId: Long): LedgerEntry? {
                if (++reads == 2) ledger.upsert(uploading) // the re-read before the FAILED write sees the new row
                return ledger.get(mediaId)
            }
        }
        val failed = mutableListOf<String>()
        val kept = FreeUpRepository(racing).recheck(listOf(e), store, mapOf(1L to phone(e)), opener(), onFailed = { _, r -> failed += r })
        assertTrue(kept.isEmpty())
        assertEquals(listOf(FreeUpRepository.DAMAGED), failed)
        assertEquals(uploading, ledger.get(1)) // partPath kept, so the next run cleans that .part
    }

    @Test fun verifiedEntriesExcludeRowsBeingMoved() = runBlocking {
        val still = entry(1); val moving = entry(2).copy(movingTo = "elsewhere-f2.jpg")
        val (ledger, _) = setup(still, moving)
        assertEquals(listOf(1L), FreeUpRepository(ledger).verifiedEntries().map { it.mediaId })
    }

    @Test fun recheckRefusesRowsBeingMoved() = runBlocking {
        val moving = entry(1)
        val (ledger, store) = setup(moving)
        ledger.upsert(moving.copy(movingTo = "elsewhere-f1.jpg"))
        val kept = FreeUpRepository(ledger).recheck(listOf(moving), store, mapOf(1L to phone(moving)), opener())
        assertTrue(kept.isEmpty())
        assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
    }

    @Test fun markRemovedRefusesRowsBeingMoved() = runBlocking {
        val moving = entry(1).copy(movingTo = "elsewhere-f1.jpg")
        val (ledger, _) = setup(moving)
        assertEquals(0, FreeUpRepository(ledger).markRemoved(listOf(moving)))
        assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
    }

    /** A backup run sets movingTo right after Free up read the row: the write must not drop the marker. */
    @Test fun markRemovedNeverDropsAMoveStartedAfterItsRead() = runBlocking {
        val e = entry(1)
        val (inner, _) = setup(e)
        // The engine's movingTo write lands right after Free up's first ledger access, before Free up writes.
        val racing = object : LedgerStore by inner {
            var raced = false
            suspend fun race() {
                if (raced) return
                raced = true
                inner.upsert(inner.get(1)!!.copy(movingTo = "elsewhere-f1.jpg"))
            }
            override suspend fun get(mediaId: Long): LedgerEntry? = inner.get(mediaId).also { race() }
            override suspend fun markRemovedIfSettled(mediaId: Long, nowMs: Long, binRequestedAt: Long?): Boolean {
                race()
                return inner.markRemovedIfSettled(mediaId, nowMs, binRequestedAt)
            }
        }
        assertEquals(0, FreeUpRepository(racing).markRemoved(listOf(e)))
        val now = inner.get(1)!!
        assertEquals("elsewhere-f1.jpg", now.movingTo)
        assertEquals(BackupStatus.VERIFIED, now.status)
    }

    @Test fun markRemovedMarksSettledVerifiedRows() = runBlocking {
        val e = entry(1)
        val (ledger, _) = setup(e)
        assertEquals(1, FreeUpRepository(ledger, clock = { 7L }).markRemoved(listOf(e)))
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, ledger.get(1)!!.status)
        assertEquals(7L, ledger.get(1)!!.updatedAtMs)
    }
}
