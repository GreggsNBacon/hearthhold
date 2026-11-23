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
import kotlinx.coroutines.flow.update
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

/** In-memory [LedgerStore] for tests; atomic updates so parallel workers can share it. */
class MemoryLedger : LedgerStore {
    val rows = MutableStateFlow<Map<Long, LedgerEntry>>(emptyMap())
    override suspend fun upsert(e: LedgerEntry) { rows.update { it + (e.mediaId to e) } }
    override suspend fun get(mediaId: Long) = rows.value[mediaId]
    override suspend fun byStatus(s: BackupStatus) = rows.value.values.filter { it.status == s }
    override suspend fun withPartPath() = rows.value.values.filter { it.partPath != null }
    override suspend fun withMovingTo() = rows.value.values.filter { it.movingTo != null }
    override suspend fun delete(mediaId: Long) { rows.update { it - mediaId } }
    override suspend fun markRemovedIfSettled(mediaId: Long, nowMs: Long): Boolean {
        var changed = false
        rows.update { m ->
            val e = m[mediaId]
            changed = e != null && e.status == BackupStatus.VERIFIED && e.movingTo == null
            if (changed) m + (mediaId to e!!.copy(status = BackupStatus.REMOVED_FROM_PHONE, updatedAtMs = nowMs)) else m
        }
        return changed
    }
    override suspend fun setLastError(mediaId: Long, error: String?, nowMs: Long) {
        rows.update { m -> m[mediaId]?.let { m + (mediaId to it.copy(lastError = error, updatedAtMs = nowMs)) } ?: m }
    }
    override suspend fun beginMove(mediaId: Long, from: String, target: String, nowMs: Long): Boolean {
        var changed = false
        rows.update { m ->
            val e = m[mediaId]
            changed = e != null && e.movingTo == null && e.partPath == null && e.remotePath == from
            if (changed) m + (mediaId to e!!.copy(movingTo = target, updatedAtMs = nowMs)) else m
        }
        return changed
    }
    override suspend fun endMove(mediaId: Long, remotePath: String?, movingTo: String?, lastError: String?, nowMs: Long) {
        rows.update { m ->
            m[mediaId]?.let {
                m + (mediaId to it.copy(remotePath = remotePath, movingTo = movingTo, lastError = lastError, updatedAtMs = nowMs))
            } ?: m
        }
    }
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
        parallel: Int = 1,
        pauseMs: Long = 0,
        pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        folderFor: suspend (Long, String) -> String? = { _, _ -> null },
    ) = BackupEngine(
        store, ledger, { data?.let { ByteArrayInputStream(it) } }, settings, { 1234L }, fat32Max, slack,
        parallelUploads = parallel, itemPauseMs = pauseMs, pause = pause, folderFor = folderFor,
    )

    @Test fun uploadsAndVerifiesNewItem() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
        val r = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(1, 0, 0), counts(r))
        assertArrayEquals(data, store.files[final])
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.VERIFIED, e.status)
        assertEquals(sha(data), e.localSha256)
        assertEquals(final, e.remotePath)
        assertNull(e.partPath)
        assertFalse(store.files.keys.any { it.endsWith(".part") })
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun uploadsIntoDesiredFolder() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
        val seen = mutableListOf<Pair<Long, String>>()
        val r = engine(store, ledger, data, folderFor = { id, rel -> seen += id to rel; "Dog" })
            .run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(1, 0, 0), counts(r))
        val target = "B\\Dev\\Dog\\2026\\IMG.jpg"
        assertArrayEquals(data, store.files[target])
        assertEquals(target, ledger.get(1)!!.remotePath)
        assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
        // Asked for the upload, and again by the end-of-run move pass (which finds it already in place).
        assertEquals(listOf(1L to "DCIM/Camera/", 1L to "DCIM/Camera/"), seen)
        assertEquals(0, (r as BackupResult.Done).moved)
    }

    @Test fun neverOverwritesDifferentFileWithSameName() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
        val other = bytes(5000, seed = 99) // same size, different content
        store.mkdirs(dir); store.files[final] = other
        val r = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(1, 0, 0), counts(r))
        assertArrayEquals(other, store.files[final])
        assertArrayEquals(data, store.files["$dir\\IMG (1).jpg"])
        assertEquals("$dir\\IMG (1).jpg", ledger.get(1)!!.remotePath)
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun identicalExistingFileIsAdoptedNotReuploaded() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
        store.mkdirs(dir); store.files[final] = data.copyOf()
        val r = engine(store, ledger, data).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 1, 0), counts(r))
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
        assertEquals(BackupResult.Done(1, 0, 0), counts(r2))
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
        assertEquals(BackupResult.Done(0, 0, 1), counts(r))
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
        assertEquals(BackupResult.Done(0, 1, 0), counts(r))
        assertNull(ledger.get(1))
        assertTrue(store.files.isEmpty())
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun fat32OversizeIsFailedWithReason() = runBlocking {
        val store = FakeRemoteStore(fileSystem = "FAT32"); val ledger = MemoryLedger()
        val r = engine(store, ledger, ByteArray(0)).run(listOf(media(5L * 1024 * 1024 * 1024)))
        assertEquals(BackupResult.Done(0, 0, 1), counts(r))
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals(fat32Msg, e.lastError)
        assertTrue(store.writes.isEmpty())
    }

    @Test fun driveFullStops() = runBlocking {
        // The first file fits; the run then has used the space up, so the second stops the run.
        val store = FakeRemoteStore(freeBytes = 1200); val ledger = MemoryLedger()
        val data = bytes(1000)
        val second = media(1000, id = 2).copy(displayName = "IMG2.jpg")
        val r = engine(store, ledger, data).run(listOf(media(1000, id = 1), second))
        assertTrue(r is BackupResult.Stopped && r.reason is RemoteException.DriveFull)
        assertEquals(listOf(part), store.writes) // the second item never started writing
    }

    @Test fun itemBiggerThanFreeSpaceFailsAloneAndSmallerOnesStillGo() = runBlocking {
        val store = FakeRemoteStore(freeBytes = 600); val ledger = MemoryLedger()
        val small = bytes(500)
        val big = media(5000, id = 1).copy(displayName = "BIG.jpg")
        val opener = LocalOpener { m -> if (m.mediaId == 2L) ByteArrayInputStream(small) else ByteArrayInputStream(bytes(5000)) }
        val e = BackupEngine(store, ledger, opener, settings, { 1234L }, itemPauseMs = 0)
        val r = e.run(listOf(big, media(500, id = 2)))
        assertTrue(r is BackupResult.Done)
        r as BackupResult.Done
        assertEquals(1, r.uploaded)
        assertEquals(1, r.failed)
        assertEquals(1, r.noRoom)
        assertEquals(BackupStatus.FAILED, ledger.get(1)!!.status)
        assertEquals(BackupEngine.NO_ROOM_ERROR, ledger.get(1)!!.lastError)
        assertEquals(BackupStatus.VERIFIED, ledger.get(2)!!.status)
        assertFalse(store.writes.any { it.contains("BIG") })
    }

    @Test fun doneReportsFreeSpaceLeftAfterUploads() = runBlocking {
        val store = FakeRemoteStore(freeBytes = 10_000); val ledger = MemoryLedger(); val data = bytes(3000)
        val r = engine(store, ledger, data).run(listOf(media(3000)))
        assertEquals(7000L, (r as BackupResult.Done).driveFreeBytes)
    }

    @Test fun phoneReadErrorStoresFriendlyTextNotExceptionMessage() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val opener = LocalOpener { throw java.io.IOException("/storage/emulated/0/DCIM/secret EIO") }
        val r = BackupEngine(store, ledger, opener, settings, { 1234L }, itemPauseMs = 0).run(listOf(media(10)))
        assertEquals(BackupResult.Done(0, 0, 1), counts(r))
        assertEquals(BackupEngine.PHONE_READ_ERROR, ledger.get(1)!!.lastError)
    }

    @Test fun leftoverThatCannotBeRemovedStoresFriendlyText() = runBlocking {
        val store = FakeRemoteStore(failOn = { op, _ ->
            if (op == "delete") java.io.IOException("STATUS_SHARING_VIOLATION \\\\nas\\usb\\x.part") else null
        })
        val ledger = MemoryLedger()
        store.mkdirs(dir); store.files[part] = bytes(10)
        ledger.upsert(
            LedgerEntry(1, "content://media/1", "IMG.jpg", "DCIM/Camera/", 5000, march2026, 10, false,
                null, null, part, BackupStatus.UPLOADING, null, 1)
        )
        engine(store, ledger, bytes(5000)).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals(BackupEngine.LEFTOVER_ERROR, e.lastError)
        assertEquals(part, e.partPath) // still tracked, so the next run tries again
    }

    @Test fun itemErrorNeverReturnsExceptionText() {
        assertEquals(BackupEngine.COPY_ERROR, BackupEngine.itemError(java.io.IOException("\\\\nas\\usb STATUS_X")))
        assertEquals(BackupEngine.NOT_FOUND_ERROR, BackupEngine.itemError(java.io.FileNotFoundException("\\\\nas\\a")))
        assertEquals(
            dev.photobackup.smb.MSG_ACCESS_DENIED,
            BackupEngine.itemError(dev.photobackup.smb.AccessDeniedException()),
        )
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
        assertEquals(BackupResult.Done(1, 0, 0), counts(r))
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
        assertEquals(BackupResult.Done(0, 0, 1), counts(r1))
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals(fat32Msg, e.lastError)
        assertNull(e.partPath)
        assertEquals(listOf(part), store.deletes)
        assertTrue(store.files.isEmpty())

        store.truncateWritesAfter = null
        val writesBefore = store.writes.size
        val r2 = engine(store, ledger, data, fat32Max = threshold).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 1, 0), counts(r2))
        assertEquals(writesBefore, store.writes.size)
        assertEquals(BackupStatus.FAILED, ledger.get(1)!!.status)

        // A changed file (new dateModified) is retried.
        val r3 = engine(store, ledger, data, fat32Max = threshold).run(listOf(media(data.size.toLong(), dateModifiedSec = 99)))
        assertEquals(BackupResult.Done(1, 0, 0), counts(r3))
    }

    @Test fun genericWriteErrorFailsItemAndContinues() = runBlocking {
        val data = bytes(800) // file smaller than the FAT32 threshold, so not a FAT32 failure
        val store = FakeRemoteStore(truncateWritesAfter = 500)
        val ledger = MemoryLedger()
        val r = engine(store, ledger, data, fat32Max = 1000).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 0, 1), counts(r))
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals(BackupEngine.COPY_ERROR, e.lastError) // never the exception text
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
        assertEquals(BackupResult.Done(0, 0, 1), counts(r1))
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals(fat32Msg, e.lastError)
        assertNull(e.partPath)
        assertEquals(listOf(part), store.deletes)
        assertFalse(part in store.files)

        store.failOn = null
        val writesBefore = store.writes.size
        val r2 = engine(store, ledger, data, fat32Max = threshold).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(0, 1, 0), counts(r2))
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
        assertEquals(BackupResult.Done(0, 0, 1), counts(r1))
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals(BackupEngine.COPY_ERROR, e.lastError) // never the exception text
        assertEquals(listOf(part), store.deletes)

        store.truncateWritesAfter = null
        val r2 = engine(store, ledger, data, fat32Max = threshold, slack = 100).run(listOf(media(data.size.toLong())))
        assertEquals(BackupResult.Done(1, 0, 0), counts(r2))
        assertArrayEquals(data, store.files[final])
    }

    @Test fun shortReadFromPhoneIsSizeMismatchAndCopyDeleted() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val data = bytes(4000) // phone claims 5000 bytes but delivers 4000
        val r = engine(store, ledger, data).run(listOf(media(5000)))
        assertEquals(BackupResult.Done(0, 0, 1), counts(r))
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.FAILED, e.status)
        assertEquals("size mismatch", e.lastError)
        assertNull(e.remotePath)
        assertNull(e.partPath)
        assertFalse(final in store.files)
        assertFalse(part in store.files)
        // Checked before the rename: the short copy never had the final name; only its own .part was removed.
        assertEquals(listOf(part), store.deletes)
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
        assertEquals(BackupResult.Done(1, 0, 0), counts(r2))
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

    @Test fun accessDeniedOnOpenWriteFailsOnlyThatItem() = runBlocking {
        var calls = 0
        val store = FakeRemoteStore(failOn = { op, _ ->
            if (op == "openWrite" && ++calls == 1) dev.photobackup.smb.AccessDeniedException() else null
        })
        val ledger = MemoryLedger(); val data = bytes(5000)
        val r = engine(store, ledger, data).run(listOf(media(5000, id = 1), media(5000, id = 2)))
        assertEquals(BackupResult.Done(1, 0, 1), counts(r))
        assertEquals(BackupStatus.FAILED, ledger.get(1)!!.status)
        assertEquals("You don't have permission to open this on the drive", ledger.get(1)!!.lastError)
        assertEquals(BackupStatus.VERIFIED, ledger.get(2)!!.status)
    }

    @Test fun wrappedSmbjRuntimeErrorFailsOnlyThatItem() = runBlocking {
        // SmbjRemoteStore.mapOp turns e.g. IllegalArgumentException into an IOException; the engine must not stop.
        var calls = 0
        val store = FakeRemoteStore(failOn = { op, _ ->
            if (op == "openWrite" && ++calls == 1) {
                dev.photobackup.smb.SmbjRemoteStore.mapOp(IllegalStateException("smbj")) as java.io.IOException
            } else null
        })
        val ledger = MemoryLedger(); val data = bytes(5000)
        val r = engine(store, ledger, data).run(listOf(media(5000, id = 1), media(5000, id = 2)))
        assertEquals(BackupResult.Done(1, 0, 1), counts(r))
        assertEquals(BackupStatus.FAILED, ledger.get(1)!!.status)
        assertEquals("Drive error — will try again", ledger.get(1)!!.lastError) // not a bare class name
        assertNull(ledger.get(1)!!.partPath)
        assertEquals(BackupStatus.VERIFIED, ledger.get(2)!!.status)
    }

    /** Compares counts only; bytesUploaded has its own test. */
    private fun counts(r: BackupResult) = if (r is BackupResult.Done) r.copy(bytesUploaded = 0, driveFreeBytes = null) else r

    /** Drops the free-space figure (its own test covers it). */
    private fun BackupResult.noFree() = if (this is BackupResult.Done) copy(driveFreeBytes = null) else this

    @Test fun uploadedFileGetsDateTakenAsDriveTime() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
        val r = engine(store, ledger, data).run(listOf(media(5000)))
        assertEquals(BackupResult.Done(1, 0, 0, bytesUploaded = 5000), r.noFree())
        assertEquals(mapOf(final to march2026), store.times)
    }

    @Test fun driveTimeFallsBackToDateAdded() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
        val m = media(5000).copy(dateTakenMs = 0, dateAddedSec = 1_700_000_000)
        engine(store, ledger, data).run(listOf(m))
        assertEquals(1_700_000_000_000L, store.times.values.single()) // 2023 folder
    }

    @Test fun adoptedFileIsNotTouched() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
        store.mkdirs(dir); store.files[final] = data.copyOf()
        val r = engine(store, ledger, data).run(listOf(media(5000)))
        assertEquals(BackupResult.Done(0, 1, 0, bytesUploaded = 0), r.noFree())
        assertTrue(store.times.isEmpty())
    }

    @Test fun failedVerifyDoesNotSetTimes() = runBlocking {
        val store = FakeRemoteStore(corruptReads = true); val ledger = MemoryLedger(); val data = bytes(5000)
        engine(store, ledger, data).run(listOf(media(5000)))
        assertTrue(store.times.isEmpty())
    }

    @Test fun setTimesFailureIsCosmetic() = runBlocking {
        for (err in listOf<Throwable>(IllegalStateException("x"), java.io.IOException("x"), RemoteException.Unreachable())) {
            val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(5000)
            store.setTimesError = err
            val second = media(5000, id = 2).copy(displayName = "IMG2.jpg") // not adoptable as a copy of the first
            val r = engine(store, ledger, data).run(listOf(media(5000, id = 1), second))
            assertEquals(err.javaClass.simpleName, BackupResult.Done(2, 0, 0, bytesUploaded = 10_000), r.noFree())
            assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
            assertEquals(BackupStatus.VERIFIED, ledger.get(2)!!.status)
        }
    }
}
