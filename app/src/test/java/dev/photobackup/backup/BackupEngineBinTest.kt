package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignment
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset

/** Review's Junk: the engine moves the drive copy into `<dest>\<device>\.Bin\...`, and auto-empties old bin files. */
class BackupEngineBinTest {
    private val camera = "B\\Dev\\Camera\\2026"
    private val bin = "B\\Dev\\.Bin\\Camera\\2026"
    private val settings = BackupSettings("B", "Dev", ZoneOffset.UTC)
    private val march2026 = 1_772_400_000_000L
    private val now = 10_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun row(
        id: Long,
        remote: String?,
        status: BackupStatus = BackupStatus.REMOVED_FROM_PHONE,
        binRequestedAt: Long? = 5L,
        movingTo: String? = null,
        binPath: String? = null,
        binnedAt: Long? = null,
        name: String = "IMG.jpg",
    ) = LedgerEntry(
        id, "content://media/$id", name, "DCIM/Camera/", 100, march2026, 10, false, "sha", remote, null, status, null, 0,
        movingTo = movingTo, binRequestedAt = binRequestedAt, binPath = binPath, binnedAt = binnedAt,
    )

    private fun engine(
        store: FakeRemoteStore,
        ledger: MemoryLedger,
        settings: BackupSettings = this.settings,
        folderFor: suspend (Long, String) -> String? = { _, _ -> null },
        assignments: MemoryAssignments? = null,
        clock: () -> Long = { now },
    ) = BackupEngine(
        store, ledger, LocalOpener { null }, settings, clock, itemPauseMs = 0, pause = {},
        folderFor = folderFor, assignments = assignments,
    )

    private suspend fun seed(store: FakeRemoteStore, ledger: MemoryLedger, e: LedgerEntry, data: ByteArray = bytes(100)) {
        ledger.upsert(e)
        val p = e.remotePath!!
        store.mkdirs(p.substringBeforeLast('\\'))
        store.files[p] = data
    }

    // ---- bin moves ----

    @Test fun binRequestMovesTheFileIntoTheBinAndMarksItBinned() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(100)
        seed(store, ledger, row(1, "$camera\\IMG.jpg"), data)
        val r = engine(store, ledger).run(emptyList())
        assertTrue(r is BackupResult.Done)
        assertNull(store.files["$camera\\IMG.jpg"])
        assertArrayEquals(data, store.files["$bin\\IMG.jpg"])
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.BINNED, e.status)
        assertEquals("$bin\\IMG.jpg", e.remotePath) // remotePath = where the file really is
        assertEquals("$camera\\IMG.jpg", e.binPath) // binPath = where restore puts it back
        assertEquals(now, e.binnedAt)
        assertEquals(5L, e.binRequestedAt)
        assertNull(e.movingTo)
        assertNull(e.lastError)
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun binClashTakesTheNextFreeNameAndLeavesTheOtherFileAlone() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val mine = bytes(100, 1); val theirs = bytes(100, 2)
        seed(store, ledger, row(1, "$camera\\IMG.jpg"), mine)
        store.mkdirs(bin); store.files["$bin\\IMG.jpg"] = theirs
        engine(store, ledger).run(emptyList())
        assertArrayEquals(theirs, store.files["$bin\\IMG.jpg"])
        assertArrayEquals(mine, store.files["$bin\\IMG (1).jpg"])
        assertEquals("$bin\\IMG (1).jpg", ledger.get(1)!!.remotePath)
        assertEquals("$camera\\IMG.jpg", ledger.get(1)!!.binPath)
    }

    /** Wi-Fi drops mid-move: the run stops with movingTo recorded; the next run settles it either way. */
    @Test fun dropDuringRenameThenNextRunFinishesTheBinMove() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(100)
        seed(store, ledger, row(1, "$camera\\IMG.jpg"), data)
        store.failOn = { op, _ -> if (op == "rename") RemoteException.Unreachable() else null }
        val r = engine(store, ledger).run(emptyList())
        assertTrue(r is BackupResult.Stopped)
        var e = ledger.get(1)!!
        assertEquals("$camera\\IMG.jpg", e.remotePath) // never points at a missing file
        assertEquals("$bin\\IMG.jpg", e.movingTo)
        assertEquals("$camera\\IMG.jpg", e.binPath) // recorded with movingTo
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)

        store.failOn = null
        engine(store, ledger).run(emptyList())
        e = ledger.get(1)!!
        assertEquals(BackupStatus.BINNED, e.status)
        assertEquals("$bin\\IMG.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertArrayEquals(data, store.files["$bin\\IMG.jpg"])
        assertNull(store.files["$camera\\IMG.jpg"])
    }

    /** The rename reached the drive but the app died before recording it. */
    @Test fun crashAfterRenameBeforeEndMoveIsRecoveredAndBinned() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        ledger.upsert(row(1, "$camera\\IMG.jpg", movingTo = "$bin\\IMG.jpg", binPath = "$camera\\IMG.jpg"))
        store.mkdirs(bin); store.files["$bin\\IMG.jpg"] = bytes(100)
        engine(store, ledger).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.BINNED, e.status)
        assertEquals("$bin\\IMG.jpg", e.remotePath)
        assertEquals("$camera\\IMG.jpg", e.binPath)
        assertNull(e.movingTo)
        assertEquals(setOf("$bin\\IMG.jpg"), store.files.keys)
    }

    /** endMove landed but marking BINNED did not: the next run just marks it. */
    @Test fun crashBetweenEndMoveAndMarkingIsMarkedNextRun() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$bin\\IMG.jpg", binPath = "$camera\\IMG.jpg"))
        engine(store, ledger).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.BINNED, e.status)
        assertEquals("$bin\\IMG.jpg", e.remotePath)
        assertEquals(now, e.binnedAt)
        assertEquals(setOf("$bin\\IMG.jpg"), store.files.keys)
    }

    @Test fun plainRenameFailureLeavesTheRowAtItsFileAndRetriesNextRun() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\IMG.jpg"))
        store.failOn = { op, _ -> if (op == "rename") IOException("secret \\\\server\\path") else null }
        val r = engine(store, ledger).run(emptyList())
        assertTrue(r is BackupResult.Done)
        var e = ledger.get(1)!!
        assertEquals("$camera\\IMG.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertEquals(BackupEngine.BIN_MOVE_ERROR, e.lastError)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)
        store.failOn = null
        engine(store, ledger).run(emptyList())
        e = ledger.get(1)!!
        assertEquals(BackupStatus.BINNED, e.status)
        assertNull(e.lastError)
    }

    @Test fun missingSourceFileIsNotMovedAndKeepsTheRequest() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        ledger.upsert(row(1, "$camera\\IMG.jpg"))
        engine(store, ledger).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)
        assertEquals("$camera\\IMG.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertEquals(BackupEngine.BIN_MOVE_ERROR, e.lastError)
    }

    @Test fun binRequestsOutsideTheCurrentRootOrLayoutAreLeftAlone() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val paths = listOf(
            "Old\\Dev\\Camera\\2026\\a.jpg", // destination changed
            "B\\Phone\\Camera\\2026\\b.jpg", // device renamed
            "B\\Dev\\2024\\07\\c.jpg", // old YYYY\MM layout
        )
        paths.forEachIndexed { i, p -> seed(store, ledger, row(i + 1L, p)) }
        val before = store.files.keys.toSet()
        val r = engine(store, ledger).run(emptyList())
        assertTrue(r is BackupResult.Done)
        assertEquals(before, store.files.keys)
        paths.forEachIndexed { i, p ->
            val e = ledger.get(i + 1L)!!
            assertEquals(p, e.remotePath)
            assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)
            assertNull(e.movingTo)
        }
    }

    /** A Junk row is never re-filed into a named folder: it goes to the bin only. */
    @Test fun binRequestedRowIsNotReconciledIntoAFolder() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\IMG.jpg"))
        val r = engine(store, ledger, folderFor = { _, _ -> "Dog" }).run(emptyList()) as BackupResult.Done
        assertEquals(0, r.moved)
        assertEquals("$bin\\IMG.jpg", ledger.get(1)!!.remotePath)
        assertFalse(store.files.keys.any { it.contains("\\Dog\\") })
    }

    @Test fun binnedRowIsNeverReconciledOutOfTheBin() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$bin\\IMG.jpg", status = BackupStatus.BINNED, binPath = "$camera\\IMG.jpg", binnedAt = now))
        val r = engine(store, ledger, folderFor = { _, _ -> "Dog" }).run(emptyList()) as BackupResult.Done
        assertEquals(0, r.moved)
        assertEquals("$bin\\IMG.jpg", ledger.get(1)!!.remotePath)
    }

    @Test fun verifiedRowsStillMoveNormally() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\IMG.jpg", status = BackupStatus.VERIFIED, binRequestedAt = null))
        val r = engine(store, ledger, folderFor = { _, _ -> "Dog" }).run(emptyList()) as BackupResult.Done
        assertEquals(1, r.moved)
        assertEquals("B\\Dev\\Dog\\2026\\IMG.jpg", ledger.get(1)!!.remotePath)
    }

    // ---- restore recovery ----

    /** A restore (BinRepository) renamed the file back, then the app died before recording it. */
    @Test fun interruptedRestoreIsFinishedByTheNextRun() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        ledger.upsert(
            row(
                1, "$bin\\IMG.jpg", status = BackupStatus.BINNED, binPath = "$camera\\IMG.jpg", binnedAt = now,
                movingTo = "$camera\\IMG.jpg",
            )
        )
        store.mkdirs(camera); store.files["$camera\\IMG.jpg"] = bytes(100)
        engine(store, ledger).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)
        assertEquals("$camera\\IMG.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertNull(e.binRequestedAt)
        assertNull(e.binPath)
        assertNull(e.binnedAt)
        assertEquals(setOf("$camera\\IMG.jpg"), store.files.keys) // never moved back to the bin
    }

    @Test fun restoreThatNeverRenamedStaysBinned() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(
            store, ledger,
            row(1, "$bin\\IMG.jpg", status = BackupStatus.BINNED, binPath = "$camera\\IMG.jpg", binnedAt = now, movingTo = "$camera\\IMG.jpg"),
        )
        engine(store, ledger).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals(BackupStatus.BINNED, e.status)
        assertEquals("$bin\\IMG.jpg", e.remotePath)
        assertNull(e.movingTo)
    }

    // ---- auto-empty ----

    private fun binned(id: Long, path: String, binnedAt: Long) =
        row(id, path, status = BackupStatus.BINNED, binPath = "$camera\\$id.jpg", binnedAt = binnedAt, name = "$id.jpg")

    @Test fun autoEmptyDeletesOnlyFilesBinnedMoreThan30DaysAgo() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val assigns = MemoryAssignments()
        seed(store, ledger, binned(1, "$bin\\1.jpg", now - 30 * day)) // exactly 30 days: stays
        seed(store, ledger, binned(2, "$bin\\2.jpg", now - 30 * day - 1)) // just older: goes
        seed(store, ledger, binned(3, "$bin\\3.jpg", now - day))
        assigns.set(FolderAssignment(2, "Dog"))
        engine(store, ledger, assignments = assigns).run(emptyList())
        assertEquals(listOf("$bin\\2.jpg"), store.deletes)
        assertNull(ledger.get(2))
        assertNull(assigns.get(2))
        assertNotNull(ledger.get(1)); assertNotNull(store.files["$bin\\1.jpg"])
        assertNotNull(ledger.get(3)); assertNotNull(store.files["$bin\\3.jpg"])
    }

    @Test fun autoEmptyOfAMissingFileDeletesOnlyTheRow() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        ledger.upsert(binned(1, "$bin\\1.jpg", now - 31 * day))
        engine(store, ledger).run(emptyList())
        assertNull(ledger.get(1))
        assertTrue(store.deletes.isEmpty())
    }

    @Test fun autoEmptyNeverDeletesAfterTheDestinationOrDeviceChanged() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, binned(1, "B\\Dev\\.Bin\\Camera\\2026\\1.jpg", 0))
        // Same files, but the app now backs up to another destination / under another device name.
        for (s in listOf(BackupSettings("C", "Dev", ZoneOffset.UTC), BackupSettings("B", "Pixel", ZoneOffset.UTC))) {
            engine(store, ledger, settings = s).run(emptyList())
        }
        assertTrue(store.deletes.isEmpty())
        assertNotNull(ledger.get(1))
    }

    @Test fun autoEmptyRefusesOddPathsAndUnsettledRows() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val odd = listOf(
            "B\\Dev\\.Bin\\..\\Camera\\2026\\1.jpg",
            "B\\Dev\\Camera\\2026\\2.jpg",
            "B\\Dev\\.Bins\\Camera\\2026\\3.jpg",
            "B\\Dev\\.Bin\\\\4.jpg",
        )
        odd.forEachIndexed { i, p -> ledger.upsert(binned(i + 1L, p, 0)); store.files[p] = bytes(10) }
        // An interrupted restore that left two copies: recovery keeps movingTo (conflict), so it is never emptied.
        seed(store, ledger, binned(5, "$bin\\5.jpg", 0).copy(movingTo = "$camera\\5.jpg"))
        store.mkdirs(camera); store.files["$camera\\5.jpg"] = bytes(100)
        seed(store, ledger, row(6, "$bin\\6.jpg", status = BackupStatus.REMOVED_FROM_PHONE, binnedAt = 0)) // not BINNED
        engine(store, ledger).run(emptyList())
        assertTrue(store.deletes.isEmpty())
        for (id in 1L..5L) assertNotNull(ledger.get(id))
    }

    @Test fun autoEmptyDeleteFailureKeepsTheRowAndTheRunGoesOn() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, binned(1, "$bin\\1.jpg", 0))
        seed(store, ledger, binned(2, "$bin\\2.jpg", 0))
        store.failOn = { op, p -> if (op == "delete" && p.endsWith("1.jpg")) IOException("x") else null }
        val r = engine(store, ledger).run(emptyList())
        assertTrue(r is BackupResult.Done)
        assertNotNull(ledger.get(1))
        assertNull(ledger.get(2))
    }

    @Test fun autoEmptyRunsAfterBinMovesInTheSameRun() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\IMG.jpg"))
        // A clock far in the future: the file just binned is not 30 days old by binnedAt (set now), so it stays.
        engine(store, ledger).run(emptyList())
        assertEquals(BackupStatus.BINNED, ledger.get(1)!!.status)
        engine(store, ledger, clock = { now + 30 * day + 1 }).run(emptyList())
        assertNull(ledger.get(1))
        assertEquals(listOf("$bin\\IMG.jpg"), store.deletes)
    }
}
