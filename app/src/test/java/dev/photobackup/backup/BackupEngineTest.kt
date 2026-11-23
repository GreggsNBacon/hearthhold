package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.ZoneOffset

/** In-memory [LedgerStore] for tests. */
class MemoryLedger : LedgerStore {
    val rows = MutableStateFlow<Map<Long, LedgerEntry>>(emptyMap())
    override suspend fun upsert(e: LedgerEntry) { rows.value = rows.value + (e.mediaId to e) }
    override suspend fun get(mediaId: Long) = rows.value[mediaId]
    override suspend fun byStatus(s: BackupStatus) = rows.value.values.filter { it.status == s }
    override suspend fun withPartPath() = rows.value.values.filter { it.partPath != null }
    override suspend fun delete(mediaId: Long) { rows.value = rows.value - mediaId }
    override fun observeCounts(): Flow<Map<BackupStatus, Int>> =
        rows.map { m -> m.values.groupingBy { it.status }.eachCount() }
    override fun observeAll(): Flow<List<LedgerEntry>> =
        rows.map { m -> m.values.sortedByDescending { it.dateTakenMs } }
}

class BackupEngineTest {
    private val dir = "B\\Dev\\Camera\\2026"
    private val final = "$dir\\IMG.jpg"
    private val part = "$dir\\IMG.jpg.part"
    private val march2026 = 1_772_400_000_000L // 2026-03-01T21:20Z
    private val fat32Msg = "too big for this drive's format (FAT32, 4 GB limit)"

    private fun media(size: Long, dateModifiedSec: Long = 10, id: Long = 1) = PhoneMedia(
        id, "content://media/$id", "IMG.jpg", "DCIM/Camera/", size, march2026, 5, dateModifiedSec, false, "image/jpeg"
    )

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val settings = BackupSettings("B", "Dev", ZoneOffset.UTC)

    private fun engine(
        store: FakeRemoteStore,
        ledger: MemoryLedger,
        data: ByteArray?,
        fat32Max: Long = RemotePathPlanner.FAT32_MAX_BYTES,
        slack: Long = 8L * 1024 * 1024,
    ) = BackupEngine(store, ledger, { data?.let { ByteArrayInputStream(it) } }, settings, { 1234L }, fat32Max, slack)

    @Test fun uploadsAndVerifiesNewItem() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
        val r = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(1, 0, 0), r)
        assertArrayEquals(data, store.files[final])
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.VERIFIED, e.status)
        assertEquals(sha(data), e.localSha256)
        assertEquals(final, e.remotePath)
        assertNull(e.partPath)
        assertFalse(store.files.keys.any { it.endsWith(".part") })
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun neverOverwritesDifferentFileWithSameName() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
        val other = bytes(5000, seed = 99) // same size, different content
        store.mkdirs(dir); store.files[final] = other
        val r = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(1, 0, 0), r)
        assertArrayEquals(other, store.files[final])
        assertArrayEquals(data, store.files["$dir\\IMG (1).jpg"])
        assertEquals("$dir\\IMG (1).jpg", ledger.get(1)!!.remotePath)
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun identicalExistingFileIsAdoptedNotReuploaded() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
        store.mkdirs(dir); store.files[final] = data.copyOf()
        val r = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 1, 0), r)
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.VERIFIED, e.status)
        assertEquals(final, e.remotePath)
        assertEquals(sha(data), e.localSha256)
        assertTrue(store.writes.isEmpty())
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun interruptedUploadLeavesItemPendingAndNextRunCleansPart() = runBlocking {
        // 1.5 MiB => two 1 MiB-buffer writes; the second one fails with Unreachable mid-file.
        val data = bytes(1_572_864)
        var writeCalls = 0
        val store = FakeRemoteStore(failOn = { op, _ ->
            if (op == "write" && ++writeCalls == 2) RemoteException.Unreachable() else null
        })
        val ledger = MemoryLedger()
        val r1 = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertTrue(r1 is BackupResult.Stopped && r1.reason is RemoteException.Unreachable)
        val mid = ledger.get(1)!!
        assertEquals(BackupStatus.UPLOADING, mid.status)
        assertEquals(part, mid.partPath)
        assertTrue(part in store.files)
        assertFalse(final in store.files)

        store.failOn = null
        val r2 = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(1, 0, 0), r2)
        assertEquals(listOf(part), store.deletes) // leftover cleaned, nothing else deleted
        assertFalse(part in store.files)
        assertArrayEquals(data, store.files[final])
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.VERIFIED, e.status)
        assertNull(e.partPath)
    }

    @Test fun verificationMismatchDeletesCopyAndFails() = runBlocking {
        val store = FakeRemoteStore(corruptReads = true); val ledger = MemoryLedger(); val data = bytes(5000)
        val r = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 0, 1), r)
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals("verification failed", e.lastError)
        assertNull(e.partPath)
        assertFalse(final in store.files)
        assertFalse(part in store.files)
        assertEquals(listOf(final), store.deletes)
    }

    @Test fun authFailureStopsRunAndRecordsError() = runBlocking {
        val store = FakeRemoteStore(failOn = { op, _ -> if (op == "connect") RemoteException.AuthFailed() else null })
        val ledger = MemoryLedger(); val data = bytes(10)
        val r = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertTrue(r is BackupResult.Stopped && r.reason is RemoteException.AuthFailed)
        assertTrue(ledger.rows.value.isEmpty())
        assertTrue(store.writes.isEmpty())
    }

    @Test fun itemMissingOnPhoneIsSkipped() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val r = engine(store, ledger, null).run(listOf(media(100)))
        assertEquals(BackupResult.Done(0, 1, 0), r)
        assertNull(ledger.get(1))
        assertTrue(store.files.isEmpty())
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun fat32OversizeIsFailedWithReason() = runBlocking {
        val store = FakeRemoteStore(fileSystem = "FAT32"); val ledger = MemoryLedger()
        val r = engine(store, ledger, ByteArray(0)).run(listOf(media(5L * 1024 * 1024 * 1024)))
        assertEquals(BackupResult.Done(0, 0, 1), r)
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals(fat32Msg, e.lastError)
        assertTrue(store.writes.isEmpty())
    }

    @Test fun driveFullStops() = runBlocking {
        val store = FakeRemoteStore(freeBytes = 100); val ledger = MemoryLedger(); val data = bytes(500)
        val r = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertTrue(r is BackupResult.Stopped && r.reason is RemoteException.DriveFull)
        assertTrue(store.writes.isEmpty())
    }

    @Test fun editedItemUploadsAsNewCopy() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val old = bytes(5000)
        store.mkdirs(dir); store.files[final] = old.copyOf()
        ledger.upsert(
            LedgerEntry(1, "content://media/1", "IMG.jpg", "DCIM/Camera/", 5000, march2026, 10, false,
                sha(old), final, null, BackupStatus.VERIFIED, null, 1)
        )
        val edited = bytes(5000, seed = 42)
        val r = engine(store, ledger, edited).run(listOf(media(5000, dateModifiedSec = 20)))
        assertEquals(BackupResult.Done(1, 0, 0), r)
        assertArrayEquals(old, store.files[final])
        assertArrayEquals(edited, store.files["$dir\\IMG (1).jpg"])
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.VERIFIED, e.status)
        assertEquals("$dir\\IMG (1).jpg", e.remotePath)
        assertEquals(20, e.dateModifiedSec)
        assertTrue(store.deletes.isEmpty())
    }

    /**
     * smbj cannot report the file system, so a >4 GB write failing on a FAT32 drive must be detected
     * empirically. Uses the engine's injectable FAT32 threshold (1000 bytes) instead of 4 GB.
     */
    @Test fun oversizeWriteFailureOnUnknownFsIsMarkedFat32AndNotRetried() = runBlocking {
        val threshold = 1000L
        val data = bytes(2000)
        val store = FakeRemoteStore(fileSystem = "UNKNOWN", truncateWritesAfter = threshold)
        val ledger = MemoryLedger()
        val r1 = engine(store, ledger, data, fat32Max = threshold).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 0, 1), r1)
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals(fat32Msg, e.lastError)
        assertNull(e.partPath)
        assertEquals(listOf(part), store.deletes)
        assertTrue(store.files.isEmpty())

        store.truncateWritesAfter = null
        val writesBefore = store.writes.size
        val r2 = engine(store, ledger, data, fat32Max = threshold).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 1, 0), r2)
        assertEquals(writesBefore, store.writes.size)
        assertEquals(BackupStatus.FAILED, ledger.get(1)!!.status)

        // A changed file (new dateModified) is retried.
        val r3 = engine(store, ledger, data, fat32Max = threshold).run(listOf(media(data.size.toLong(), dateModifiedSec = 99)))
        assertEquals(BackupResult.Done(1, 0, 0), r3)
    }

    @Test fun genericWriteErrorFailsItemAndContinues() = runBlocking {
        val data = bytes(800) // file smaller than the FAT32 threshold, so not a FAT32 failure
        val store = FakeRemoteStore(truncateWritesAfter = 500)
        val ledger = MemoryLedger()
        val r = engine(store, ledger, data, fat32Max = 1000).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 0, 1), r)
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertTrue(e.lastError!!.contains("Simulated write failure"))
        assertEquals(listOf(part), store.deletes)
        assertNull(e.partPath)
    }

    @Test fun fat32LimitReportedAsDiskFullIsMarkedFat32AndNotRetried() = runBlocking {
        val threshold = 1000L
        val data = bytes(2000)
        // Samba/Windows report a past-4 GB FAT write as DISK_FULL although the drive has plenty of room.
        val store = FakeRemoteStore(fileSystem = "UNKNOWN", failOn = { op, _ ->
            if (op == "write") RemoteException.DriveFull() else null
        })
        val ledger = MemoryLedger()
        val r1 = engine(store, ledger, data, fat32Max = threshold).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 0, 1), r1)
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals(fat32Msg, e.lastError)
        assertNull(e.partPath)
        assertEquals(listOf(part), store.deletes)
        assertFalse(part in store.files)

        store.failOn = null
        val writesBefore = store.writes.size
        val r2 = engine(store, ledger, data, fat32Max = threshold).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 1, 0), r2)
        assertEquals(writesBefore, store.writes.size)
    }

    @Test fun diskFullWhenDriveReallyLacksRoomStillStops() = runBlocking {
        val threshold = 1000L
        val data = bytes(2000)
        val store = FakeRemoteStore(freeBytes = 2500, failOn = { op, _ ->
            if (op == "write") RemoteException.DriveFull() else null
        })
        val ledger = MemoryLedger()
        val r = engine(store, ledger, data, fat32Max = threshold, slack = 100).run(listOf(media(data.size.toLong())))
        // 2000 offered is beyond threshold + slack, so this is a genuine DriveFull.
        assertTrue(r is BackupResult.Stopped && r.reason is RemoteException.DriveFull)
        assertEquals(BackupStatus.UPLOADING, ledger.get(1)!!.status)
        assertEquals(part, ledger.get(1)!!.partPath)
    }

    @Test fun writeFailureFarPastLimitIsOrdinaryFailureAndRetried() = runBlocking {
        val threshold = 1000L
        val data = bytes(10_000)
        val store = FakeRemoteStore(fileSystem = "UNKNOWN", truncateWritesAfter = 5000)
        val ledger = MemoryLedger()
        val r1 = engine(store, ledger, data, fat32Max = threshold, slack = 100).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 0, 1), r1)
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertTrue(e.lastError!!.contains("Simulated write failure"))
        assertEquals(listOf(part), store.deletes)

        store.truncateWritesAfter = null
        val r2 = engine(store, ledger, data, fat32Max = threshold, slack = 100).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(1, 0, 0), r2)
        assertArrayEquals(data, store.files[final])
    }

    @Test fun shortReadFromPhoneIsSizeMismatchAndCopyDeleted() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val data = bytes(4000) // phone claims 5000 bytes but delivers 4000
        val r = engine(store, ledger, data).run(listOf(media(5000)))
        assertEquals(BackupResult.Done(0, 0, 1), r)
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals("size mismatch", e.lastError)
        assertNull(e.remotePath)
        assertNull(e.partPath)
        assertFalse(final in store.files)
        assertEquals(listOf(final), store.deletes)
    }

    @Test fun openWriteRemoteFailureDoesNotClaimThePart() = runBlocking {
        val store = FakeRemoteStore(failOn = { op, _ -> if (op == "openWrite") RemoteException.Unreachable() else null })
        val ledger = MemoryLedger(); val data = bytes(500)
        val r1 = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertTrue(r1 is BackupResult.Stopped && r1.reason is RemoteException.Unreachable)
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.PENDING, e.status)
        assertNull(e.partPath)

        // Someone else's .part now sits at that path; the next run must not delete it.
        val foreign = bytes(10, seed = 3)
        store.files[part] = foreign
        store.failOn = null
        val r2 = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(1, 0, 0), r2)
        assertTrue(store.deletes.isEmpty())
        assertArrayEquals(foreign, store.files[part])
        assertArrayEquals(data, store.files["$dir\\IMG (1).jpg"])
    }

    @Test fun copyIsCancellablePerChunk() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val data = bytes(3 * 1024 * 1024)
        lateinit var job: Job
        job = launch {
            engine(store, ledger, data).run(listOf(media(data.size.toLong()))) { _, _, _ -> job.cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1024 * 1024, store.files[part]!!.size) // only the first chunk was written
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.UPLOADING, e.status)
        assertEquals(part, e.partPath)
    }
}
