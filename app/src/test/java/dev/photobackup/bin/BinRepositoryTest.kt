package dev.photobackup.bin

import dev.photobackup.backup.MemoryAssignments
import dev.photobackup.backup.MemoryLedger
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignment
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BinRepositoryTest {
    private val camera = "B\\Dev\\Camera\\2026"
    private val bin = "B\\Dev\\.Bin\\Camera\\2026"
    private val now = 9_000L

    private val store = FakeRemoteStore()
    private val ledger = MemoryLedger()
    private val assigns = MemoryAssignments()
    private val lock = Mutex()
    private var root: Pair<String?, String>? = "B" to "Dev"
    private var opened = 0

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun repo(
        l: LedgerStore = ledger,
        open: suspend () -> RemoteStore? = { opened++; store },
    ) = BinRepository(l, assigns, open, { root }, { now }, lock)

    private fun binned(id: Long, path: String = "$bin\\$id.jpg", binPath: String? = "$camera\\$id.jpg") = LedgerEntry(
        id, "content://media/$id", "$id.jpg", "DCIM/Camera/", 100, 1000, 10, false, "sha", path, null,
        BackupStatus.BINNED, null, 0, binRequestedAt = 1, binPath = binPath, binnedAt = 2,
    )

    private fun waiting(id: Long, path: String = "$camera\\$id.jpg") = LedgerEntry(
        id, "content://media/$id", "$id.jpg", "DCIM/Camera/", 100, 1000, 10, false, "sha", path, null,
        BackupStatus.REMOVED_FROM_PHONE, null, 0, binRequestedAt = 1,
    )

    private suspend fun seed(e: LedgerEntry, data: ByteArray = bytes(100)) {
        ledger.upsert(e)
        val p = e.remotePath!!
        if ('\\' in p) store.mkdirs(p.substringBeforeLast('\\'))
        store.files[p] = data
    }

    // ---- list ----

    @Test fun listShowsBinnedAndWaitingRowsOnly() = runBlocking {
        ledger.upsert(binned(1))
        ledger.upsert(waiting(2))
        ledger.upsert(waiting(3).copy(binRequestedAt = null)) // Drive only: not a bin row
        ledger.upsert(waiting(4).copy(status = BackupStatus.VERIFIED, binRequestedAt = null))
        assertEquals(setOf(1L, 2L), repo().list().first().map { it.mediaId }.toSet())
    }

    // ---- restore ----

    @Test fun restoreMovesTheFileBackAndMarksItDriveOnly() = runBlocking {
        val data = bytes(100)
        seed(binned(1), data)
        assigns.set(FolderAssignment(1, "Dog"))
        val r = repo().restore(listOf(1))
        assertEquals(BinResult.Done(1, 0), r)
        assertArrayEquals(data, store.files["$camera\\1.jpg"])
        assertNull(store.files["$bin\\1.jpg"])
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)
        assertEquals("$camera\\1.jpg", e.remotePath)
        assertNull(e.binRequestedAt); assertNull(e.binPath); assertNull(e.binnedAt); assertNull(e.movingTo)
        assertEquals("Dog", assigns.get(1)?.folder) // restore keeps the hand-picked folder
        assertTrue(store.deletes.isEmpty())
        assertTrue(store.writes.isEmpty()) // never downloads or writes
    }

    @Test fun restoreOntoATakenNameUsesTheNextFreeNameAndLeavesTheOriginalUntouched() = runBlocking {
        val mine = bytes(100, 1); val theirs = bytes(100, 2)
        seed(binned(1), mine)
        store.files["$camera\\1.jpg"] = theirs
        assertEquals(BinResult.Done(1, 0), repo().restore(listOf(1)))
        assertArrayEquals(theirs, store.files["$camera\\1.jpg"])
        assertArrayEquals(mine, store.files["$camera\\1 (1).jpg"])
        assertEquals("$camera\\1 (1).jpg", ledger.get(1)!!.remotePath)
    }

    @Test fun restoreRecreatesAMissingOriginalFolder() = runBlocking {
        seed(binned(1, binPath = "B\\Dev\\Gone\\2025\\1.jpg"))
        assertEquals(BinResult.Done(1, 0), repo().restore(listOf(1)))
        assertNotNull(store.files["B\\Dev\\Gone\\2025\\1.jpg"])
    }

    @Test fun restoreOfAWaitingRowCancelsTheRequestWithoutTouchingTheDrive() = runBlocking {
        seed(waiting(1))
        assertEquals(BinResult.Done(1, 0), repo().restore(listOf(1)))
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)
        assertEquals("$camera\\1.jpg", e.remotePath)
        assertNull(e.binRequestedAt)
        assertEquals(setOf("$camera\\1.jpg"), store.files.keys)
    }

    /** A bin move whose rename landed but whose BINNED mark did not: restore still moves it back. */
    @Test fun restoreOfAHalfBinnedRowMovesItBack() = runBlocking {
        seed(waiting(1, "$bin\\1.jpg").copy(binPath = "$camera\\1.jpg"))
        assertEquals(BinResult.Done(1, 0), repo().restore(listOf(1)))
        assertEquals("$camera\\1.jpg", ledger.get(1)!!.remotePath)
        assertNull(ledger.get(1)!!.binRequestedAt)
        assertNotNull(store.files["$camera\\1.jpg"])
    }

    @Test fun restoreFailureCountsAndKeepsTheRowInTheBin() = runBlocking {
        seed(binned(1)); seed(binned(2))
        store.failOn = { op, p -> if (op == "rename" && p.endsWith("\\1.jpg")) IOException("secret") else null }
        assertEquals(BinResult.Done(1, 1), repo().restore(listOf(1, 2)))
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.BINNED, e.status)
        assertEquals("$bin\\1.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertNotNull(store.files["$bin\\1.jpg"])
    }

    @Test fun restoreOfUnknownOrUnsettledRowsFails() = runBlocking {
        seed(binned(1).copy(movingTo = "$camera\\1.jpg"))
        seed(binned(2, binPath = null))
        assertEquals(BinResult.Done(0, 3), repo().restore(listOf(1, 2, 99)))
        assertEquals(BackupStatus.BINNED, ledger.get(1)!!.status)
    }

    // ---- empty ----

    @Test fun emptyDeletesBinFilesAndTheirRows() = runBlocking {
        seed(binned(1)); seed(binned(2))
        assigns.set(FolderAssignment(1, "Dog"))
        assertEquals(BinResult.Done(1, 0), repo().empty(listOf(1)))
        assertEquals(listOf("$bin\\1.jpg"), store.deletes)
        assertNull(ledger.get(1))
        assertNull(assigns.get(1))
        assertNotNull(ledger.get(2))
    }

    @Test fun emptyAllDeletesEveryBinnedRowButNotWaitingOnes() = runBlocking {
        seed(binned(1)); seed(binned(2)); seed(waiting(3))
        assertEquals(BinResult.Done(2, 0), repo().emptyAll())
        assertEquals(setOf("$bin\\1.jpg", "$bin\\2.jpg"), store.deletes.toSet())
        assertNotNull(ledger.get(3))
        assertNotNull(store.files["$camera\\3.jpg"])
    }

    @Test fun emptyRefusesPathsOutsideTheBin() = runBlocking {
        val odd = listOf(
            "$camera\\1.jpg",
            "B\\Dev\\.Bin\\..\\Camera\\2026\\2.jpg",
            "B\\Dev\\.Bin\\Camera\\..\\..\\3.jpg",
            "B\\Dev\\.Bins\\4.jpg",
            "B\\Dev\\.Bin",
            "B\\Devx\\.Bin\\6.jpg",
            "B\\Dev\\.Bin/../Camera/7.jpg",
            "B\\Dev\\.Bin\\.\\8.jpg",
            "B\\Dev\\.Bin\\\\9.jpg",
        )
        odd.forEachIndexed { i, p -> ledger.upsert(binned(i + 1L, p)); store.files[p] = bytes(10) }
        val ids = odd.indices.map { it + 1L }
        assertEquals(BinResult.Done(0, odd.size), repo().empty(ids))
        assertEquals(BinResult.Done(0, odd.size), repo().emptyAll())
        assertTrue(store.deletes.isEmpty())
        for (id in ids) assertNotNull(ledger.get(id))
    }

    @Test fun emptyNeverDeletesAfterTheDestinationChanged() = runBlocking {
        seed(binned(1))
        root = "C" to "Dev"
        assertEquals(BinResult.Done(0, 1), repo().empty(listOf(1)))
        root = "B" to "Pixel"
        assertEquals(BinResult.Done(0, 1), repo().emptyAll())
        assertTrue(store.deletes.isEmpty())
        assertNotNull(ledger.get(1))
    }

    @Test fun emptyRefusesWaitingAndMovingRows() = runBlocking {
        seed(waiting(1, "$bin\\1.jpg").copy(binPath = "$camera\\1.jpg")) // not BINNED yet
        seed(binned(2).copy(movingTo = "$camera\\2.jpg")) // a restore in flight
        assertEquals(BinResult.Done(0, 2), repo().empty(listOf(1, 2)))
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun emptyOfAMissingFileDeletesOnlyTheRow() = runBlocking {
        ledger.upsert(binned(1))
        assertEquals(BinResult.Done(1, 0), repo().empty(listOf(1)))
        assertNull(ledger.get(1))
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun emptyDeleteFailureKeepsTheRow() = runBlocking {
        seed(binned(1))
        store.failOn = { op, _ -> if (op == "delete") IOException("secret") else null }
        assertEquals(BinResult.Done(0, 1), repo().empty(listOf(1)))
        assertNotNull(ledger.get(1))
    }

    // ---- lock, offline, errors ----

    @Test fun busyWhenABackupHoldsTheLock() = runBlocking {
        seed(binned(1))
        lock.lock()
        assertEquals(BinResult.Busy, repo().restore(listOf(1)))
        assertEquals(BinResult.Busy, repo().empty(listOf(1)))
        assertEquals(BinResult.Busy, repo().emptyAll())
        assertEquals(0, opened)
        lock.unlock()
        assertEquals(BinResult.Done(1, 0), repo().empty(listOf(1)))
        assertFalse(lock.isLocked) // released afterwards
    }

    @Test fun offlineWhenTheDriveIsUnreachableOrNotSetUp() = runBlocking {
        seed(binned(1))
        store.failOn = { op, _ -> if (op == "connect") RemoteException.Unreachable() else null }
        assertEquals(BinResult.Offline, repo().restore(listOf(1)))
        assertEquals(BinResult.Offline, repo().emptyAll())
        assertEquals(BinResult.Offline, repo(open = { null }).empty(listOf(1)))
        assertEquals(BinResult.Offline, repo(open = { throw IOException("no settings") }).empty(listOf(1)))
        store.failOn = null
        root = null
        assertEquals(BinResult.Offline, repo().empty(listOf(1)))
        assertNotNull(ledger.get(1))
        assertFalse(lock.isLocked)
    }

    @Test fun dropMidRestoreIsOfflineAndTheRowStillKnowsBothPaths() = runBlocking {
        seed(binned(1))
        store.failOn = { op, _ -> if (op == "rename") RemoteException.Unreachable() else null }
        assertEquals(BinResult.Offline, repo().restore(listOf(1)))
        val e = ledger.get(1)!!
        assertEquals("$bin\\1.jpg", e.remotePath)
        assertEquals("$camera\\1.jpg", e.movingTo)
        assertEquals("$camera\\1.jpg", e.binPath)
        assertFalse(lock.isLocked)
    }

    @Test fun nonIoErrorsFromSettingsOpeningOrClosingAreOffline() = runBlocking {
        seed(binned(1))
        val throwingRoot = BinRepository(ledger, assigns, { store }, { throw IllegalStateException("settings") }, { now }, lock)
        assertEquals(BinResult.Offline, throwingRoot.empty(listOf(1)))
        assertEquals(BinResult.Offline, repo(open = { throw java.security.GeneralSecurityException("key") }).restore(listOf(1)))
        assertEquals(BinResult.Offline, repo(open = { throw IllegalStateException("x") }).emptyAll())
        val badClose = object : RemoteStore by store {
            override fun close() = throw IllegalStateException("close")
        }
        assertEquals(BinResult.Offline, repo(open = { badClose }).restore(listOf(99)))
        assertFalse(lock.isLocked)
    }

    /** The bin name now holds another file (the old one was deleted by hand and the name reused). */
    @Test fun emptyAndRestoreRefuseAFileThatIsNotThisRowsSize() = runBlocking {
        seed(binned(1), bytes(50)) // row says 100 bytes
        assertEquals(BinResult.Done(0, 1), repo().empty(listOf(1)))
        assertEquals(BinResult.Done(0, 1), repo().emptyAll())
        assertEquals(BinResult.Done(0, 1), repo().restore(listOf(1)))
        assertTrue(store.deletes.isEmpty())
        assertNotNull(store.files["$bin\\1.jpg"])
        assertNull(store.files["$camera\\1.jpg"])
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.BINNED, e.status)
        assertEquals("$bin\\1.jpg", e.remotePath)
        assertEquals(dev.photobackup.backup.BinFiles.MISMATCH_ERROR, e.lastError)
    }

    @Test fun databaseErrorsNeverThrow() = runBlocking {
        seed(binned(1))
        val broken = object : LedgerStore by ledger {
            override suspend fun get(mediaId: Long): LedgerEntry? = throw IllegalStateException("db")
            override suspend fun byStatus(s: BackupStatus): List<LedgerEntry> = throw IllegalStateException("db")
        }
        assertEquals(BinResult.Done(0, 1), repo(broken).restore(listOf(1)))
        assertEquals(BinResult.Done(0, 1), repo(broken).empty(listOf(1)))
        assertEquals(BinResult.Done(0, 1), repo(broken).emptyAll())
        assertTrue(store.deletes.isEmpty())
        assertFalse(lock.isLocked)
    }
}
