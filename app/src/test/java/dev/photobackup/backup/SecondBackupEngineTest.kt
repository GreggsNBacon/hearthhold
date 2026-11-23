package dev.photobackup.backup

import dev.photobackup.freeup.FreeUpRepository
import dev.photobackup.freeup.SecondGate
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.SECOND_MISSING_ERROR
import dev.photobackup.media.PhoneMedia
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.ledger.SecondCopyDao
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.ZoneOffset

/** In-memory [SecondCopyDao] with the same conditional writes as the Room queries. */
class MemorySecondCopies : SecondCopyDao {
    val rows = MutableStateFlow<Map<Long, SecondCopy>>(emptyMap())
    override suspend fun get(mediaId: Long) = rows.value[mediaId]
    override fun observeAll(): Flow<List<SecondCopy>> = rows.map { it.values.toList() }
    override suspend fun upsert(row: SecondCopy) { rows.update { it + (row.mediaId to row) } }
    override suspend fun all() = rows.value.values.toList()
    override suspend fun withPartPath() = rows.value.values.filter { it.partPath != null }
    override suspend fun setLastError(mediaId: Long, error: String?, nowMs: Long) {
        rows.update { m -> m[mediaId]?.let { m + (mediaId to it.copy(lastError = error, updatedAtMs = nowMs)) } ?: m }
    }
    override suspend fun byStatus(status: SecondStatus) = rows.value.values.filter { it.status == status }
    override suspend fun withMovingTo() = rows.value.values.filter { it.movingTo != null }
    override suspend fun beginMoveCount(mediaId: Long, from: String, target: String, nowMs: Long): Int {
        var n = 0
        rows.update { m ->
            val e = m[mediaId]
            if (e != null && e.movingTo == null && e.partPath == null && e.remotePath == from) {
                n = 1; m + (mediaId to e.copy(movingTo = target, updatedAtMs = nowMs))
            } else { n = 0; m }
        }
        return n
    }
    override suspend fun endMove(mediaId: Long, remotePath: String?, movingTo: String?, lastError: String?, nowMs: Long) {
        rows.update { m ->
            m[mediaId]?.let {
                m + (mediaId to it.copy(remotePath = remotePath, movingTo = movingTo, lastError = lastError, updatedAtMs = nowMs))
            } ?: m
        }
    }
    override suspend fun endMoveIfCount(
        mediaId: Long, expected: String, remotePath: String?, movingTo: String?, lastError: String?, nowMs: Long,
    ): Int {
        var n = 0
        rows.update { m ->
            val e = m[mediaId]
            if (e != null && e.movingTo == expected) {
                n = 1
                m + (mediaId to e.copy(remotePath = remotePath, movingTo = movingTo, lastError = lastError, updatedAtMs = nowMs))
            } else { n = 0; m }
        }
        return n
    }
    override suspend fun demoteCount(
        mediaId: Long, remotePath: String, sha: String, from: SecondStatus, to: SecondStatus, error: String, nowMs: Long,
    ): Int {
        var n = 0
        rows.update { m ->
            val e = m[mediaId]
            if (e != null && e.status == from && e.remotePath == remotePath && e.sha256 == sha &&
                e.movingTo == null && e.partPath == null
            ) {
                n = 1; m + (mediaId to e.copy(status = to, lastError = error, updatedAtMs = nowMs))
            } else { n = 0; m }
        }
        return n
    }
    override suspend fun delete(mediaId: Long) { rows.update { it - mediaId } }
    override suspend fun clear() { rows.value = emptyMap() }
}

class SecondBackupEngineTest {
    private val march2026 = 1_772_400_000_000L
    private val mainDir = "B\\Dev\\Camera\\2026"
    private val dir2 = "S\\Dev\\Camera\\2026"
    private val dog2 = "S\\Dev\\Dog\\2026"
    private val settings2 = BackupSettings("S", "Dev", ZoneOffset.UTC)

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun row(
        id: Long,
        data: ByteArray,
        name: String = "IMG.jpg",
        status: BackupStatus = BackupStatus.VERIFIED,
        remote: String? = "$mainDir\\$name",
    ) = LedgerEntry(
        id, "content://media/$id", name, "DCIM/Camera/", data.size.toLong(), march2026, 10, false, sha(data),
        remote, null, status, null, 0,
    )

    /** Phone files by uri; a missing uri = gone from the phone. */
    private class Phone {
        val files = mutableMapOf<String, ByteArray>()
        var opens = 0
        val opener = LocalOpener { m -> opens++; files[m.uri]?.let { ByteArrayInputStream(it) } }
    }

    private class Main(val store: RemoteStore?) {
        var calls = 0
        val provider: suspend () -> RemoteStore? = { calls++; store }
    }

    private fun engine(
        store2: FakeRemoteStore,
        ledger: MemoryLedger,
        copies: MemorySecondCopies,
        phone: Phone = Phone(),
        main: Main = Main(null),
        folderFor: suspend (Long, String) -> String? = { _, _ -> null },
        pauses: MutableList<Long>? = null,
        pauseMs: Long = 0,
        opener: LocalOpener = phone.opener,
        mainProvider: suspend () -> RemoteStore? = main.provider,
    ) = SecondBackupEngine(
        store2, mainProvider, ledger, copies, opener, settings2, { 1234L },
        itemPauseMs = pauseMs, pause = { pauses?.add(it) }, folderFor = folderFor,
    )

    private suspend fun FakeRemoteStore.put(path: String, data: ByteArray) {
        mkdirs(path.substringBeforeLast('\\'))
        files[path] = data
    }

    private fun done(r: SecondResult) = r as SecondResult.Done

    // ---- Brief tests ----

    @Test fun happyPathCopiesFromThePhoneAndVerifies() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(5000); val phone = Phone(); val main = Main(FakeRemoteStore())
        ledger.upsert(row(1, data)); phone.files["content://media/1"] = data
        val r = engine(s2, ledger, copies, phone, main).run()
        assertEquals(SecondResult.Done(copied = 1, adopted = 0, failed = 0, waiting = 0), r)
        assertArrayEquals(data, s2.files["$dir2\\IMG.jpg"])
        val c = copies.get(1)!!
        assertEquals(SecondStatus.VERIFIED, c.status)
        assertEquals("$dir2\\IMG.jpg", c.remotePath)
        assertEquals(sha(data), c.sha256)
        assertNull(c.partPath); assertNull(c.lastError)
        assertEquals(0, main.calls) // the main share is only opened when a phone-gone item needs it
        assertEquals(march2026, s2.times["$dir2\\IMG.jpg"])
        assertTrue(s2.deletes.isEmpty())
        // Main ledger untouched.
        assertEquals(row(1, data), ledger.get(1))
    }

    @Test fun copiesFromTheMainDriveWhenThePhoneFileIsGone() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(3000); val mainStore = FakeRemoteStore(); val main = Main(mainStore)
        ledger.upsert(row(1, data, status = BackupStatus.REMOVED_FROM_PHONE)); mainStore.put("$mainDir\\IMG.jpg", data)
        val r = engine(s2, ledger, copies, main = main).run()
        assertEquals(SecondResult.Done(1, 0, 0, 0), r)
        assertArrayEquals(data, s2.files["$dir2\\IMG.jpg"])
        assertEquals(SecondStatus.VERIFIED, copies.get(1)!!.status)
        assertEquals(1, main.calls)
        assertArrayEquals(data, mainStore.files["$mainDir\\IMG.jpg"]) // main copy untouched
        assertTrue(mainStore.writes.isEmpty() && mainStore.deletes.isEmpty())
    }

    @Test fun mainUnreachableCopiesPhoneItemsAndTheOthersWait() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val a = bytes(100, 1); val b = bytes(100, 2); val phone = Phone(); val main = Main(null)
        ledger.upsert(row(1, a, name = "a.jpg")); phone.files["content://media/1"] = a
        ledger.upsert(row(2, b, name = "b.jpg", status = BackupStatus.REMOVED_FROM_PHONE))
        val r = engine(s2, ledger, copies, phone, main).run()
        assertEquals(SecondResult.Done(1, 0, 0, 1), r)
        assertEquals(SecondStatus.VERIFIED, copies.get(1)!!.status)
        assertNull(copies.get(2)) // waiting: no row made
        assertNull(s2.files["$dir2\\b.jpg"])
        assertTrue(s2.files.keys.none { it.endsWith(".part") })
        assertEquals(1, main.calls)
    }

    @Test fun mainProviderThrowingCountsAsWaitingAndIsAskedOnce() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        var calls = 0
        ledger.upsert(row(1, bytes(100, 1), name = "a.jpg", status = BackupStatus.REMOVED_FROM_PHONE))
        ledger.upsert(row(2, bytes(100, 2), name = "b.jpg", status = BackupStatus.REMOVED_FROM_PHONE))
        val r = engine(s2, ledger, copies, mainProvider = { calls++; throw RemoteException.Unreachable() }).run()
        assertEquals(SecondResult.Done(0, 0, 0, 2), r)
        assertEquals(1, calls)
        assertTrue(copies.all().none { it.status == SecondStatus.FAILED })
    }

    @Test fun adoptsAnIdenticalExistingFile() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(800)
        ledger.upsert(row(1, data)); s2.put("$dir2\\IMG.jpg", data)
        // A source must be available (here the phone) before the second drive is touched at all.
        val phone = Phone(); phone.files["content://media/1"] = data
        val main = Main(null)
        val r = engine(s2, ledger, copies, phone, main).run()
        assertEquals(SecondResult.Done(0, 1, 0, 0), r)
        assertEquals(SecondStatus.VERIFIED, copies.get(1)!!.status)
        assertEquals("$dir2\\IMG.jpg", copies.get(1)!!.remotePath)
        assertTrue(s2.writes.isEmpty())
        assertEquals(0, main.calls)
    }

    @Test fun sameSizeDifferentHashTakesAClashNameAndLeavesTheFileAlone() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val mine = bytes(800, 1); val theirs = bytes(800, 2); val phone = Phone()
        ledger.upsert(row(1, mine)); phone.files["content://media/1"] = mine
        s2.put("$dir2\\IMG.jpg", theirs)
        assertEquals(SecondResult.Done(1, 0, 0, 0), engine(s2, ledger, copies, phone).run())
        assertArrayEquals(theirs, s2.files["$dir2\\IMG.jpg"])
        assertArrayEquals(mine, s2.files["$dir2\\IMG (1).jpg"])
        assertEquals("$dir2\\IMG (1).jpg", copies.get(1)!!.remotePath)
        assertTrue(s2.deletes.isEmpty())
    }

    @Test fun dropMidCopyLeavesPartThatTheNextRunCleans() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(3 * Hashing.BUFFER_BYTES); val phone = Phone()
        ledger.upsert(row(1, data)); phone.files["content://media/1"] = data
        var writes = 0
        s2.failOn = { op, _ -> if (op == "write" && ++writes == 2) RemoteException.Unreachable() else null }
        assertEquals(SecondResult.Unreachable, engine(s2, ledger, copies, phone).run())
        val mid = copies.get(1)!!
        assertNotEquals(SecondStatus.VERIFIED, mid.status)
        assertEquals("$dir2\\IMG.jpg.part", mid.partPath)
        assertTrue("$dir2\\IMG.jpg.part" in s2.files)

        s2.failOn = null
        assertEquals(SecondResult.Done(1, 0, 0, 0), engine(s2, ledger, copies, phone).run())
        assertEquals(listOf("$dir2\\IMG.jpg.part"), s2.deletes)
        assertArrayEquals(data, s2.files["$dir2\\IMG.jpg"])
        assertEquals(SecondStatus.VERIFIED, copies.get(1)!!.status)
        assertNull(copies.get(1)!!.partPath)
    }

    @Test fun noGoodSourceMarksFailedAndRetriesEachRun() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(500); val mainStore = FakeRemoteStore()
        ledger.upsert(row(1, data, status = BackupStatus.REMOVED_FROM_PHONE)) // main file missing
        val r = engine(s2, ledger, copies, main = Main(mainStore)).run()
        assertEquals(SecondResult.Done(0, 0, 1, 0), r)
        assertEquals(SecondStatus.FAILED, copies.get(1)!!.status)
        assertEquals(SecondBackupEngine.DAMAGED_ERROR, copies.get(1)!!.lastError)
        assertEquals("Main copy damaged — can't make a second copy", SecondBackupEngine.DAMAGED_ERROR)

        // Main copy present but not matching the recorded hash (same size): still damaged, and no copy is kept.
        mainStore.put("$mainDir\\IMG.jpg", bytes(500, 99))
        assertEquals(SecondResult.Done(0, 0, 1, 0), engine(s2, ledger, copies, main = Main(mainStore)).run())
        assertEquals(SecondBackupEngine.DAMAGED_ERROR, copies.get(1)!!.lastError)
        assertTrue(s2.files.keys.none { it.endsWith("IMG.jpg") || it.endsWith(".part") })

        // Fixed: the next run makes the copy.
        mainStore.files["$mainDir\\IMG.jpg"] = data
        assertEquals(SecondResult.Done(1, 0, 0, 0), engine(s2, ledger, copies, main = Main(mainStore)).run())
        assertEquals(SecondStatus.VERIFIED, copies.get(1)!!.status)
        assertNull(copies.get(1)!!.lastError)
    }

    @Test fun mainCopyOfWrongSizeIsDamaged() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val mainStore = FakeRemoteStore()
        ledger.upsert(row(1, bytes(500), status = BackupStatus.REMOVED_FROM_PHONE))
        mainStore.put("$mainDir\\IMG.jpg", bytes(400))
        assertEquals(SecondResult.Done(0, 0, 1, 0), engine(s2, ledger, copies, main = Main(mainStore)).run())
        assertEquals(SecondBackupEngine.DAMAGED_ERROR, copies.get(1)!!.lastError)
        assertTrue(s2.writes.isEmpty())
    }

    @Test fun binnedAndWaitingForBinAreNeverCopiedAndExistingCopiesStay() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val a = bytes(100, 1); val b = bytes(100, 2); val c = bytes(100, 3); val phone = Phone()
        ledger.upsert(row(1, a, name = "a.jpg", status = BackupStatus.BINNED, remote = "B\\Dev\\.Bin\\Camera\\2026\\a.jpg")
            .copy(binRequestedAt = 5, binPath = "$mainDir\\a.jpg", binnedAt = 6))
        ledger.upsert(row(2, b, name = "b.jpg", status = BackupStatus.REMOVED_FROM_PHONE).copy(binRequestedAt = 5))
        ledger.upsert(row(3, c, name = "c.jpg", status = BackupStatus.REMOVED_FROM_PHONE).copy(binRequestedAt = 5))
        phone.files["content://media/2"] = b
        // c already has a verified second copy: kept as it is.
        s2.put("$dir2\\c.jpg", c)
        val existing = SecondCopy(3, "$dir2\\c.jpg", null, null, SecondStatus.VERIFIED, 100, sha(c), null, 1)
        copies.upsert(existing)
        val main = Main(FakeRemoteStore())
        assertEquals(SecondResult.Done(0, 0, 0, 0), engine(s2, ledger, copies, phone, main).run())
        assertEquals(listOf("$dir2\\c.jpg"), s2.files.keys.toList())
        assertEquals(existing, copies.get(3))
        assertNull(copies.get(1)); assertNull(copies.get(2))
        assertTrue(s2.deletes.isEmpty())
        assertEquals(0, main.calls)
    }

    @Test fun rowsWithoutHashOrInOtherStatesAreNotCopied() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val a = bytes(100); val phone = Phone(); phone.files["content://media/1"] = a; phone.files["content://media/2"] = a
        ledger.upsert(row(1, a).copy(localSha256 = null))
        ledger.upsert(row(2, a, status = BackupStatus.FAILED))
        ledger.upsert(row(3, a, status = BackupStatus.UPLOADING))
        ledger.upsert(row(4, a, status = BackupStatus.PENDING))
        assertEquals(SecondResult.Done(0, 0, 0, 0), engine(s2, ledger, copies, phone).run())
        assertTrue(s2.files.isEmpty())
    }

    @Test fun folderMoveIsMirroredOnTheSecondLocation() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100)
        ledger.upsert(row(1, data, remote = "B\\Dev\\Dog\\2026\\IMG.jpg"))
        s2.put("$dir2\\IMG.jpg", data)
        copies.upsert(SecondCopy(1, "$dir2\\IMG.jpg", null, null, SecondStatus.VERIFIED, 100, sha(data), null, 1))
        val r = engine(s2, ledger, copies, folderFor = { _, _ -> "Dog" }).run()
        assertEquals(SecondResult.Done(0, 0, 0, 0), r)
        assertNull(s2.files["$dir2\\IMG.jpg"])
        assertArrayEquals(data, s2.files["$dog2\\IMG.jpg"])
        val c = copies.get(1)!!
        assertEquals("$dog2\\IMG.jpg", c.remotePath); assertNull(c.movingTo); assertEquals(SecondStatus.VERIFIED, c.status)
        assertTrue(s2.deletes.isEmpty())
    }

    @Test fun folderMoveTakesClashNameNeverOverwrites() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100, 1); val other = bytes(100, 2)
        ledger.upsert(row(1, data))
        s2.put("$dir2\\IMG.jpg", data); s2.put("$dog2\\IMG.jpg", other)
        copies.upsert(SecondCopy(1, "$dir2\\IMG.jpg", null, null, SecondStatus.VERIFIED, 100, sha(data), null, 1))
        engine(s2, ledger, copies, folderFor = { _, _ -> "Dog" }).run()
        assertArrayEquals(other, s2.files["$dog2\\IMG.jpg"])
        assertArrayEquals(data, s2.files["$dog2\\IMG (1).jpg"])
        assertEquals("$dog2\\IMG (1).jpg", copies.get(1)!!.remotePath)
    }

    @Test fun binnedItemSecondCopyStaysInItsNormalFolder() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100)
        ledger.upsert(row(1, data, status = BackupStatus.BINNED, remote = "B\\Dev\\.Bin\\Camera\\2026\\IMG.jpg")
            .copy(binRequestedAt = 5, binPath = "$mainDir\\IMG.jpg", binnedAt = 6))
        s2.put("$dir2\\IMG.jpg", data)
        val c = SecondCopy(1, "$dir2\\IMG.jpg", null, null, SecondStatus.VERIFIED, 100, sha(data), null, 1)
        copies.upsert(c)
        engine(s2, ledger, copies).run()
        assertEquals(c, copies.get(1))
        assertArrayEquals(data, s2.files["$dir2\\IMG.jpg"])
    }

    @Test fun interruptedMoveRecoveryFollowsTheFourCaseRule() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val d = bytes(100)
        for (id in 1L..4L) ledger.upsert(row(id, d, name = "f$id.jpg"))
        fun c(id: Long) = SecondCopy(
            id, "$dir2\\f$id.jpg", null, "$dog2\\f$id.jpg", SecondStatus.VERIFIED, 100, sha(d), null, 1,
        )
        // 1: only the new path holds it -> point at it. 2: only the old -> forget the move.
        // 3: both -> keep the old, flag a conflict. 4: neither -> leave it, flag an error.
        s2.put("$dog2\\f1.jpg", d); s2.put("$dir2\\f2.jpg", d); s2.put("$dir2\\f3.jpg", d); s2.put("$dog2\\f3.jpg", d)
        for (id in 1L..4L) copies.upsert(c(id))
        engine(s2, ledger, copies, folderFor = { _, _ -> "Dog" }).run()
        assertEquals("$dog2\\f1.jpg", copies.get(1)!!.remotePath); assertNull(copies.get(1)!!.movingTo)
        // 2 was settled, then this run's reconcile moved it into Dog as asked.
        assertEquals("$dog2\\f2.jpg", copies.get(2)!!.remotePath); assertNull(copies.get(2)!!.movingTo)
        assertEquals("$dir2\\f3.jpg", copies.get(3)!!.remotePath)
        assertEquals("$dog2\\f3.jpg", copies.get(3)!!.movingTo)
        assertEquals(BackupEngine.MOVE_CONFLICT_ERROR, copies.get(3)!!.lastError)
        assertEquals("$dir2\\f4.jpg", copies.get(4)!!.remotePath)
        assertEquals(BackupEngine.MOVE_ERROR, copies.get(4)!!.lastError)
        assertTrue(s2.files.containsKey("$dir2\\f3.jpg") && s2.files.containsKey("$dog2\\f3.jpg"))
        assertTrue(s2.deletes.isEmpty())
    }

    @Test fun crashDuringMoveIsSettledByTheNextRun() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val d = bytes(100)
        ledger.upsert(row(1, d))
        s2.put("$dir2\\IMG.jpg", d)
        copies.upsert(SecondCopy(1, "$dir2\\IMG.jpg", null, null, SecondStatus.VERIFIED, 100, sha(d), null, 1))
        // The rename lands, then the app dies before the row records the new path.
        val crashing = object : SecondCopyDao by copies {
            override suspend fun endMoveIf(
                mediaId: Long, expected: String, remotePath: String?, movingTo: String?, lastError: String?, nowMs: Long,
            ): Boolean = throw IllegalStateException("process died")
        }
        val crashed = runCatching {
            SecondBackupEngine(
                s2, { null }, ledger, crashing, LocalOpener { null }, settings2, { 1L }, itemPauseMs = 0,
                folderFor = { _, _ -> "Dog" },
            ).run()
        }
        assertTrue(crashed.exceptionOrNull() is IllegalStateException)
        assertEquals("$dog2\\IMG.jpg", copies.get(1)!!.movingTo)
        assertArrayEquals(d, s2.files["$dog2\\IMG.jpg"])
        engine(s2, ledger, copies, folderFor = { _, _ -> "Dog" }).run()
        assertEquals("$dog2\\IMG.jpg", copies.get(1)!!.remotePath)
        assertNull(copies.get(1)!!.movingTo)
        assertTrue(s2.deletes.isEmpty())
    }

    @Test fun nothingIsEverDeletedOutsideItsOwnPartOrFailedCopy() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val phone = Phone(); val mainStore = FakeRemoteStore()
        // A stranger's files, a stranger's .part, an old copy of another version: none may be touched.
        s2.put("S\\Dev\\Camera\\2026\\stranger.jpg", bytes(10))
        s2.put("S\\Dev\\Camera\\2026\\x.jpg.part", bytes(10))
        s2.put("S\\Other\\file.jpg", bytes(10))
        val a = bytes(100, 1); val b = bytes(100, 2); val c = bytes(100, 3)
        ledger.upsert(row(1, a, name = "a.jpg")); phone.files["content://media/1"] = a
        ledger.upsert(row(2, b, name = "b.jpg", status = BackupStatus.REMOVED_FROM_PHONE)); mainStore.put("$mainDir\\b.jpg", b)
        ledger.upsert(row(3, c, name = "c.jpg", status = BackupStatus.BINNED).copy(binRequestedAt = 1))
        ledger.upsert(row(4, c, name = "x.jpg")); phone.files["content://media/4"] = c
        val before = s2.files.keys.toSet()
        assertEquals(SecondResult.Done(3, 0, 0, 0), engine(s2, ledger, copies, phone, Main(mainStore)).run())
        assertEquals("S\\Dev\\Camera\\2026\\x (1).jpg", copies.get(4)!!.remotePath)
        assertTrue(s2.deletes.isEmpty())
        assertTrue(s2.files.keys.containsAll(before))
        assertArrayEquals(bytes(10), s2.files["S\\Dev\\Camera\\2026\\x.jpg.part"])
        assertTrue(mainStore.deletes.isEmpty() && mainStore.writes.isEmpty())
    }

    // ---- Rulings ----

    @Test fun connectFailuresGiveUnreachableOrAuthFailed() = runBlocking {
        val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val auth = FakeRemoteStore(failOn = { op, _ -> if (op == "connect") RemoteException.AuthFailed() else null })
        assertEquals(SecondResult.AuthFailed, engine(auth, ledger, copies).run())
        val down = FakeRemoteStore(failOn = { op, _ -> if (op == "connect") RemoteException.Unreachable() else null })
        assertEquals(SecondResult.Unreachable, engine(down, ledger, copies).run())
        val plain = FakeRemoteStore(failOn = { op, _ -> if (op == "connect") IOException("no route") else null })
        assertEquals(SecondResult.Unreachable, engine(plain, ledger, copies).run())
    }

    @Test fun authFailureMidRunGivesAuthFailedAndNoVerifiedRow() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100); val phone = Phone()
        ledger.upsert(row(1, data)); phone.files["content://media/1"] = data
        s2.failOn = { op, _ -> if (op == "rename") RemoteException.AuthFailed() else null }
        assertEquals(SecondResult.AuthFailed, engine(s2, ledger, copies, phone).run())
        assertNotEquals(SecondStatus.VERIFIED, copies.get(1)!!.status)
    }

    @Test fun dropDuringReadBackNeverMarksVerifiedAndNextRunAdopts() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100); val phone = Phone()
        ledger.upsert(row(1, data)); phone.files["content://media/1"] = data
        s2.failOn = { op, _ -> if (op == "openRead") RemoteException.Unreachable() else null }
        assertEquals(SecondResult.Unreachable, engine(s2, ledger, copies, phone).run())
        assertNotEquals(SecondStatus.VERIFIED, copies.get(1)!!.status)
        s2.failOn = null
        assertEquals(SecondResult.Done(0, 1, 0, 0), engine(s2, ledger, copies, phone).run())
        assertEquals(SecondStatus.VERIFIED, copies.get(1)!!.status)
        assertEquals("$dir2\\IMG.jpg", copies.get(1)!!.remotePath)
    }

    @Test fun badReadBackRemovesOnlyTheFailedCopy() = runBlocking {
        val s2 = FakeRemoteStore(corruptReads = true); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100); val phone = Phone()
        ledger.upsert(row(1, data)); phone.files["content://media/1"] = data
        assertEquals(SecondResult.Done(0, 0, 1, 0), engine(s2, ledger, copies, phone).run())
        assertEquals(listOf("$dir2\\IMG.jpg"), s2.deletes)
        assertEquals(SecondStatus.FAILED, copies.get(1)!!.status)
        assertNull(copies.get(1)!!.remotePath)
    }

    @Test fun editedPhoneFileFallsBackToTheMainCopy() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100); val phone = Phone(); val mainStore = FakeRemoteStore(); val main = Main(mainStore)
        ledger.upsert(row(1, data)); phone.files["content://media/1"] = bytes(100, 42) // changed on the phone
        mainStore.put("$mainDir\\IMG.jpg", data)
        assertEquals(SecondResult.Done(1, 0, 0, 0), engine(s2, ledger, copies, phone, main).run())
        assertArrayEquals(data, s2.files["$dir2\\IMG.jpg"])
        assertEquals(1, main.calls)
        assertTrue(s2.files.keys.none { it.endsWith(".part") })
    }

    @Test fun mainDroppingMidReadMakesTheItemWaitNotFail() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(3 * Hashing.BUFFER_BYTES)
        val mainStore = object : RemoteStore by FakeRemoteStore() {
            override suspend fun stat(path: String) = dev.photobackup.smb.RemoteEntry("IMG.jpg", path, false, data.size.toLong(), 0)
            override suspend fun openRead(path: String, offset: Long): InputStream = object : InputStream() {
                var n = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n++ == 1) throw RemoteException.Unreachable()
                    return len
                }
            }
        }
        ledger.upsert(row(1, data, status = BackupStatus.REMOVED_FROM_PHONE))
        ledger.upsert(row(2, bytes(100), name = "b.jpg", status = BackupStatus.REMOVED_FROM_PHONE))
        val r = engine(s2, ledger, copies, main = Main(mainStore)).run()
        assertEquals(SecondResult.Done(0, 0, 0, 2), r)
        assertTrue(copies.all().none { it.status == SecondStatus.FAILED || it.status == SecondStatus.VERIFIED })
        assertTrue(s2.files.keys.none { it.endsWith(".part") })
    }

    @Test fun pausesBetweenItemsThatWentToTheDrive() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val phone = Phone()
        for (id in 1L..3L) { val d = bytes(100, id.toInt()); ledger.upsert(row(id, d, name = "f$id.jpg")); phone.files["content://media/$id"] = d }
        val pauses = mutableListOf<Long>()
        engine(s2, ledger, copies, phone, pauses = pauses, pauseMs = 2000).run()
        assertEquals(listOf(2000L, 2000L), pauses)
    }

    @Test fun cancellationBetweenChunksLeavesThePartForCleanup() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(3 * Hashing.BUFFER_BYTES)
        ledger.upsert(row(1, data))
        lateinit var job: Job
        val opener = LocalOpener {
            object : InputStream() {
                val inner = ByteArrayInputStream(data)
                var reads = 0
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (++reads == 2) job.cancel()
                    return inner.read(b, off, len)
                }
            }
        }
        var result: SecondResult? = null
        job = launch { result = engine(s2, ledger, copies, opener = opener).run() }
        job.join()
        assertNull(result)
        assertEquals("$dir2\\IMG.jpg.part", copies.get(1)!!.partPath)
        assertNotEquals(SecondStatus.VERIFIED, copies.get(1)!!.status)
        assertNull(s2.files["$dir2\\IMG.jpg"])
    }

    @Test fun reUploadedMainVersionGetsANewCopyAndTheOldOneStays() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val old = bytes(100, 1); val new = bytes(100, 2); val phone = Phone()
        s2.put("$dir2\\IMG.jpg", old)
        copies.upsert(SecondCopy(1, "$dir2\\IMG.jpg", null, null, SecondStatus.VERIFIED, 100, sha(old), null, 1))
        ledger.upsert(row(1, new)); phone.files["content://media/1"] = new
        assertEquals(SecondResult.Done(1, 0, 0, 0), engine(s2, ledger, copies, phone).run())
        assertArrayEquals(old, s2.files["$dir2\\IMG.jpg"])
        assertArrayEquals(new, s2.files["$dir2\\IMG (1).jpg"])
        assertEquals("$dir2\\IMG (1).jpg", copies.get(1)!!.remotePath)
        assertEquals(sha(new), copies.get(1)!!.sha256)
    }

    @Test fun targetUsesTheMainCopysYearAndNameAndTheDesiredFolder() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100); val phone = Phone()
        ledger.upsert(row(1, data, remote = "B\\Dev\\Camera\\2019\\IMG (2).jpg")); phone.files["content://media/1"] = data
        engine(s2, ledger, copies, phone, folderFor = { _, _ -> "Dog" }).run()
        assertEquals("S\\Dev\\Dog\\2019\\IMG (2).jpg", copies.get(1)!!.remotePath)
    }

    @Test fun oldLayoutMainPathFallsBackToThePlanner() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100); val phone = Phone()
        ledger.upsert(row(1, data, remote = "B\\Dev\\2026\\03\\IMG.jpg")); phone.files["content://media/1"] = data
        engine(s2, ledger, copies, phone).run()
        assertEquals("$dir2\\IMG.jpg", copies.get(1)!!.remotePath)
    }

    @Test fun verifiedCopiesAreNotRedoneAndAFailedOneIsRetried() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100); val phone = Phone()
        ledger.upsert(row(1, data)); phone.files["content://media/1"] = data
        s2.failOn = { op, _ -> if (op == "rename") IOException("busy") else null }
        assertEquals(SecondResult.Done(0, 0, 1, 0), engine(s2, ledger, copies, phone).run())
        assertEquals(SecondStatus.FAILED, copies.get(1)!!.status)
        assertFalse(copies.get(1)!!.lastError!!.contains("busy"))
        s2.failOn = null
        assertEquals(SecondResult.Done(1, 0, 0, 0), engine(s2, ledger, copies, phone).run())
        val opens = phone.opens
        assertEquals(SecondResult.Done(0, 0, 0, 0), engine(s2, ledger, copies, phone).run())
        assertEquals(opens, phone.opens)
    }

    // ---- Review fixes ----

    /** Every second-store call except connect, recorded through the failOn hook. */
    private fun FakeRemoteStore.recordOps(): MutableList<String> {
        val ops = mutableListOf<String>()
        failOn = { op, path -> if (op != "connect") synchronized(ops) { ops += "$op $path" }; null }
        return ops
    }

    @Test fun waitingItemsNeverTouchTheSecondDriveNorPause() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        for (id in 1L..5L) ledger.upsert(row(id, bytes(100, id.toInt()), name = "f$id.jpg", status = BackupStatus.REMOVED_FROM_PHONE))
        val ops = s2.recordOps(); val pauses = mutableListOf<Long>(); val main = Main(null)
        val r = engine(s2, ledger, copies, main = main, pauses = pauses, pauseMs = 2000).run()
        assertEquals(SecondResult.Done(0, 0, 0, 5), r)
        assertEquals(emptyList<String>(), ops)
        assertEquals(emptyList<Long>(), pauses)
        assertTrue(s2.dirs.isEmpty())
        assertEquals(1, main.calls)
        assertTrue(copies.all().isEmpty())
    }

    @Test fun mainRowMidMoveWaitsWithoutTouchingTheSecondDrive() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        ledger.upsert(row(1, bytes(100), status = BackupStatus.REMOVED_FROM_PHONE).copy(movingTo = "B\\Dev\\Dog\\2026\\IMG.jpg"))
        val ops = s2.recordOps(); val main = Main(FakeRemoteStore())
        assertEquals(SecondResult.Done(0, 0, 0, 1), engine(s2, ledger, copies, main = main).run())
        assertEquals(emptyList<String>(), ops)
        assertEquals(0, main.calls)
    }

    @Test fun afterTheMainDropsLaterPhoneGoneItemsDoNotTouchTheSecondDrive() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val mainStore = object : RemoteStore by FakeRemoteStore() {
            override suspend fun stat(path: String): dev.photobackup.smb.RemoteEntry? = throw RemoteException.Unreachable()
        }
        for (id in 1L..3L) ledger.upsert(row(id, bytes(100, id.toInt()), name = "f$id.jpg", status = BackupStatus.REMOVED_FROM_PHONE))
        val pauses = mutableListOf<Long>()
        engine(s2, ledger, copies, main = Main(mainStore), pauses = pauses, pauseMs = 2000).run()
        val ops = s2.recordOps()
        // A second run against a main that is already down from the start of this one: nothing touches the drive.
        val r = engine(s2, ledger, copies, main = Main(null), pauses = pauses, pauseMs = 2000).run()
        assertEquals(SecondResult.Done(0, 0, 0, 3), r)
        assertEquals(emptyList<String>(), ops)
        // First run: item 1 found the main down at its stat (before the second drive); 2 and 3 never touched anything.
        assertEquals(emptyList<Long>(), pauses)
        assertTrue(s2.dirs.isEmpty())
    }

    @Test fun failedNewVersionKeepsTheOldVerifiedCopyTracked() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val old = bytes(100, 1); val new = bytes(100, 2); val phone = Phone()
        s2.put("$dir2\\IMG.jpg", old)
        val prior = SecondCopy(1, "$dir2\\IMG.jpg", null, null, SecondStatus.VERIFIED, 100, sha(old), null, 1)
        copies.upsert(prior)
        ledger.upsert(row(1, new)); phone.files["content://media/1"] = new
        s2.failOn = { op, _ -> if (op == "rename") IOException("busy") else null }
        assertEquals(SecondResult.Done(0, 0, 1, 0), engine(s2, ledger, copies, phone).run())
        val c = copies.get(1)!!
        assertEquals(SecondStatus.VERIFIED, c.status)
        assertEquals("$dir2\\IMG.jpg", c.remotePath)
        assertEquals(sha(old), c.sha256) // still differs from the ledger, so the next run tries again
        assertEquals(SecondBackupEngine.COPY_ERROR, c.lastError)
    }

    @Test fun copyDateFallsBackToDateModifiedAndDriveErrorTextIsMapped() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(100); val phone = Phone()
        ledger.upsert(row(1, data).copy(dateTakenMs = 0)); phone.files["content://media/1"] = data
        engine(s2, ledger, copies, phone).run()
        assertEquals(10_000L, s2.times["$dir2\\IMG.jpg"])
        assertEquals(dev.photobackup.smb.MSG_DRIVE_ERROR, SecondBackupEngine.itemError(IOException(dev.photobackup.smb.MSG_DRIVE_ERROR)))
    }

    // ---- Lost second copies are made again ----

    private fun verifiedCopy(e: LedgerEntry, path: String) =
        SecondCopy(e.mediaId, path, null, null, SecondStatus.VERIFIED, e.sizeBytes, e.localSha256, null, 0)

    @Test fun aSecondCopyTheGateFoundMissingIsCopiedAgainNextRun() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val data = bytes(700); val other = bytes(50, 3)
        val e = row(1, data); ledger.upsert(e)
        val keep = row(2, other, name = "keep.jpg"); ledger.upsert(keep)
        copies.upsert(verifiedCopy(e, "$dir2\\IMG.jpg")) // deleted by hand on the second drive
        copies.upsert(verifiedCopy(keep, "$dir2\\keep.jpg")); s2.put("$dir2\\keep.jpg", other)
        val mainStore = FakeRemoteStore(); mainStore.put("$mainDir\\IMG.jpg", data)
        val phone = Phone(); phone.files["content://media/1"] = data
        val pm = PhoneMedia(1, e.localUri, e.displayName, e.relativePath, e.sizeBytes, e.dateTakenMs,
            e.dateModifiedSec, e.dateModifiedSec, false, "")
        val reasons = mutableMapOf<Long, String>()
        val kept = FreeUpRepository(ledger).recheck(
            listOf(e), mainStore, mapOf(1L to pm), phone.opener,
            onFailed = { id, r -> reasons[id] = r }, second = SecondGate(s2, copies),
        )
        assertTrue(kept.isEmpty())
        assertEquals(FreeUpRepository.WAITING_SECOND, reasons[1L])
        assertEquals(SecondStatus.FAILED, copies.get(1)!!.status)
        assertEquals(1, SecondWaiting.count(ledger, copies)) // status shows it waiting, not up to date

        val r = engine(s2, ledger, copies, phone).run()
        assertEquals(SecondResult.Done(copied = 1, adopted = 0, failed = 0, waiting = 0), r)
        assertArrayEquals(data, s2.files["$dir2\\IMG.jpg"])
        val c = copies.get(1)!!
        assertEquals(SecondStatus.VERIFIED, c.status)
        assertEquals("$dir2\\IMG.jpg", c.remotePath)
        assertNull(c.lastError)
        assertEquals(0, SecondWaiting.count(ledger, copies))
        assertTrue(s2.deletes.isEmpty())
        assertEquals(e, ledger.get(1)) // main row untouched
    }

    @Test fun aWipedDeviceFolderRecopiesEveryVerifiedCopy() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val a = bytes(100, 1); val b = bytes(120, 2)
        val ea = row(1, a, name = "a.jpg"); val eb = row(2, b, name = "b.jpg")
        ledger.upsert(ea); ledger.upsert(eb)
        copies.upsert(verifiedCopy(ea, "$dir2\\a.jpg")); copies.upsert(verifiedCopy(eb, "$dir2\\b.jpg"))
        s2.mkdirs("S") // the share is there, the device folder is not (new drive, wiped PC folder)
        val phone = Phone(); phone.files["content://media/1"] = a; phone.files["content://media/2"] = b
        val r = engine(s2, ledger, copies, phone).run()
        assertEquals(SecondResult.Done(copied = 2, adopted = 0, failed = 0, waiting = 0), r)
        assertArrayEquals(a, s2.files["$dir2\\a.jpg"])
        assertArrayEquals(b, s2.files["$dir2\\b.jpg"])
        assertTrue(copies.all().all { it.status == SecondStatus.VERIFIED && it.lastError == null })
        assertTrue(s2.deletes.isEmpty())
    }

    @Test fun aWipedDeviceFolderWithNoSourceShowsWaitingNotUpToDate() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val a = bytes(100, 1)
        val ea = row(1, a, name = "a.jpg", status = BackupStatus.REMOVED_FROM_PHONE); ledger.upsert(ea)
        copies.upsert(verifiedCopy(ea, "$dir2\\a.jpg"))
        val r = engine(s2, ledger, copies, main = Main(null)).run() // phone gone, main drive away
        assertEquals(SecondResult.Done(copied = 0, adopted = 0, failed = 0, waiting = 1), r)
        val c = copies.get(1)!!
        assertEquals(SecondStatus.FAILED, c.status)
        assertEquals(SECOND_MISSING_ERROR, c.lastError)
        val waiting = SecondWaiting.count(ledger, copies)
        assertEquals(SecondStatusText.waiting(1), SecondStatusText.of(r, waiting))
        assertEquals(ea, ledger.get(1))
    }

    @Test fun aWipedDeviceFolderLeavesARowWithAMoveInFlight() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val a = bytes(100, 1)
        val ea = row(1, a, name = "a.jpg"); ledger.upsert(ea)
        copies.upsert(verifiedCopy(ea, "$dir2\\a.jpg").copy(movingTo = "$dog2\\a.jpg"))
        val phone = Phone(); phone.files["content://media/1"] = a
        engine(s2, ledger, copies, phone).run()
        val c = copies.get(1)!!
        assertEquals(SecondStatus.VERIFIED, c.status) // conditional: a row with a move in flight is not demoted
        assertEquals("$dog2\\a.jpg", c.movingTo)
    }

    @Test fun aPresentDeviceFolderDemotesNothing() = runBlocking {
        val s2 = FakeRemoteStore(); val ledger = MemoryLedger(); val copies = MemorySecondCopies()
        val a = bytes(100, 1)
        val ea = row(1, a, name = "a.jpg"); ledger.upsert(ea)
        copies.upsert(verifiedCopy(ea, "$dir2\\a.jpg")); s2.put("$dir2\\a.jpg", a)
        val r = engine(s2, ledger, copies, Phone()).run()
        assertEquals(SecondResult.Done(0, 0, 0, 0), r)
        assertEquals(verifiedCopy(ea, "$dir2\\a.jpg"), copies.get(1))
    }
}
