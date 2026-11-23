package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignment
import dev.photobackup.ledger.FolderAssignmentStore
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.ZoneOffset

class MemoryAssignments : FolderAssignmentStore {
    val rows = MutableStateFlow<Map<Long, FolderAssignment>>(emptyMap())
    override suspend fun get(mediaId: Long) = rows.value[mediaId]
    override suspend fun all() = rows.value.values.toList()
    override suspend fun set(a: FolderAssignment) { rows.update { it + (a.mediaId to a) } }
    override suspend fun clear(mediaId: Long) { rows.update { it - mediaId } }
    override fun observeAll(): Flow<List<FolderAssignment>> = rows.map { it.values.toList() }
}

class BackupEngineMoveTest {
    private val camera = "B\\Dev\\Camera\\2026"
    private val dog = "B\\Dev\\Dog\\2026"
    private val settings = BackupSettings("B", "Dev", ZoneOffset.UTC)
    private val march2026 = 1_772_400_000_000L

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun row(
        id: Long,
        remote: String?,
        status: BackupStatus = BackupStatus.VERIFIED,
        rel: String = "DCIM/Camera/",
        movingTo: String? = null,
        name: String = "IMG.jpg",
    ) = LedgerEntry(
        id, "content://media/$id", name, rel, 100, march2026, 10, false, "sha", remote, null, status, null, 0,
        movingTo = movingTo,
    )

    private fun media(id: Long, name: String = "IMG.jpg") =
        PhoneMedia(id, "content://media/$id", name, "DCIM/Camera/", 100, march2026, 5, 10, false, "image/jpeg")

    private fun engine(
        store: FakeRemoteStore,
        ledger: MemoryLedger,
        folderFor: suspend (Long, String) -> String? = { _, _ -> null },
        assignments: MemoryAssignments? = null,
        opener: LocalOpener = LocalOpener { null },
        pauses: MutableList<Long>? = null,
        pauseMs: Long = 0,
    ) = BackupEngine(
        store, ledger, opener, settings, { 1234L },
        itemPauseMs = pauseMs, pause = { pauses?.add(it) },
        folderFor = folderFor, assignments = assignments,
    )

    private suspend fun seed(store: FakeRemoteStore, ledger: MemoryLedger, e: LedgerEntry, data: ByteArray = bytes(100)) {
        ledger.upsert(e)
        val p = e.remotePath!!
        store.mkdirs(p.substringBeforeLast('\\'))
        store.files[p] = data
    }

    private val toDog: suspend (Long, String) -> String? = { _, _ -> "Dog" }

    @Test fun reportsHowManyFilesAreAboutToMove() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\a.jpg", name = "a.jpg"))
        seed(store, ledger, row(2, "$camera\\b.jpg", name = "b.jpg"))
        val seen = mutableListOf<Int>()
        val r = engine(store, ledger, toDog).run(emptyList(), onMoving = { seen += it }) as BackupResult.Done
        assertEquals(listOf(2), seen)
        assertEquals(2, r.moved)
        val none = mutableListOf<Int>()
        engine(store, ledger, toDog).run(emptyList(), onMoving = { none += it })
        assertTrue(none.isEmpty())
    }

    @Test fun movesVerifiedFileIntoDesiredFolder() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(100)
        seed(store, ledger, row(1, "$camera\\IMG.jpg"), data)
        val r = engine(store, ledger, toDog).run(emptyList())
        assertEquals(1, (r as BackupResult.Done).moved)
        assertNull(store.files["$camera\\IMG.jpg"])
        assertArrayEquals(data, store.files["$dog\\IMG.jpg"])
        val e = ledger.get(1)!!
        assertEquals("$dog\\IMG.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertNull(e.lastError)
        assertEquals(BackupStatus.VERIFIED, e.status)
        assertEquals("sha", e.localSha256)
    }

    @Test fun clashTakesNextFreeNameAndLeavesExistingFileUntouched() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val mine = bytes(100, 1); val theirs = bytes(100, 2)
        seed(store, ledger, row(1, "$camera\\IMG.jpg"), mine)
        store.mkdirs(dog); store.files["$dog\\IMG.jpg"] = theirs
        engine(store, ledger, toDog).run(emptyList())
        assertArrayEquals(theirs, store.files["$dog\\IMG.jpg"])
        assertArrayEquals(mine, store.files["$dog\\IMG (1).jpg"])
        assertEquals("$dog\\IMG (1).jpg", ledger.get(1)!!.remotePath)
    }

    /** The earlier planner wrote `<dest>\<device>\YYYY\MM\name`; those files must never be moved. */
    @Test fun oldYearMonthLayoutIsNeverMoved() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(100)
        val engine = BackupEngine(
            store, ledger, LocalOpener { null }, BackupSettings("Hearthhold", "Pixel 9", ZoneOffset.UTC), { 1234L },
            itemPauseMs = 0, folderFor = toDog,
        )
        val old = "Hearthhold\\Pixel 9\\2024\\07\\IMG.jpg"
        seed(store, ledger, row(1, old), data)
        val r = engine.run(emptyList())
        assertEquals(0, (r as BackupResult.Done).moved)
        assertEquals(old, ledger.get(1)!!.remotePath)
        assertNull(ledger.get(1)!!.lastError)
        assertArrayEquals(data, store.files[old])
        assertEquals(setOf(old), store.files.keys)
    }

    @Test fun rowsUnderAnotherDestinationOrDeviceAreNotMoved() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "Old\\Dev\\Camera\\2026\\IMG.jpg"))
        seed(store, ledger, row(2, "B\\OtherPhone\\Camera\\2026\\IMG.jpg"))
        seed(store, ledger, row(3, "B\\Devx\\Camera\\2026\\IMG.jpg"))
        val r = engine(store, ledger, toDog).run(emptyList())
        assertEquals(0, (r as BackupResult.Done).moved)
        assertEquals("Old\\Dev\\Camera\\2026\\IMG.jpg", ledger.get(1)!!.remotePath)
        assertEquals("B\\OtherPhone\\Camera\\2026\\IMG.jpg", ledger.get(2)!!.remotePath)
        assertEquals("B\\Devx\\Camera\\2026\\IMG.jpg", ledger.get(3)!!.remotePath)
        assertTrue(store.files.keys.none { it.contains("\\Dog\\") })
    }

    @Test fun prefixMatchIgnoresCase() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "b\\dev\\Camera\\2026\\IMG.jpg"))
        engine(store, ledger, toDog).run(emptyList())
        assertEquals("B\\Dev\\Dog\\2026\\IMG.jpg", ledger.get(1)!!.remotePath)
    }

    @Test fun folderDifferingOnlyInCaseIsNotMoved() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "B\\Dev\\camera\\2026\\IMG.jpg"))
        val r = engine(store, ledger).run(emptyList())
        assertEquals(0, (r as BackupResult.Done).moved)
        assertEquals("B\\Dev\\camera\\2026\\IMG.jpg", ledger.get(1)!!.remotePath)
    }

    @Test fun keepsYearAndFileName() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "B\\Dev\\Camera\\2019\\IMG (3).jpg"))
        engine(store, ledger, toDog).run(emptyList())
        assertEquals("B\\Dev\\Dog\\2019\\IMG (3).jpg", ledger.get(1)!!.remotePath)
    }

    @Test fun defaultMovesBackToSourceFolder() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$dog\\IMG.jpg", rel = "DCIM/Camera/"))
        engine(store, ledger).run(emptyList())
        assertEquals("$camera\\IMG.jpg", ledger.get(1)!!.remotePath)
        assertTrue("$camera\\IMG.jpg" in store.files)
    }

    @Test fun zeroUploadsButPendingMoveStillMoves() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\IMG.jpg"))
        // The phone item is unchanged since backup, so nothing needs uploading.
        val r = engine(store, ledger, toDog).run(listOf(media(1)))
        assertEquals(BackupResult.Done(0, 0, 0, driveFreeBytes = Long.MAX_VALUE, moved = 1), r)
        assertEquals("$dog\\IMG.jpg", ledger.get(1)!!.remotePath)
    }

    @Test fun removedFromPhoneRowMoves() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\IMG.jpg", status = BackupStatus.REMOVED_FROM_PHONE))
        engine(store, ledger, toDog).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals("$dog\\IMG.jpg", e.remotePath)
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, e.status)
    }

    @Test fun otherStatusesAreNotMoved() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\IMG.jpg", status = BackupStatus.FAILED))
        engine(store, ledger, toDog).run(emptyList())
        assertEquals("$camera\\IMG.jpg", ledger.get(1)!!.remotePath)
        assertTrue("$camera\\IMG.jpg" in store.files)
    }

    @Test fun driveOnlyRowUsesRecordedRelativePathForRules() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\IMG.jpg", status = BackupStatus.REMOVED_FROM_PHONE, rel = "Pictures/WhatsApp/"))
        val seen = mutableListOf<Pair<Long, String>>()
        engine(store, ledger, { id, rel -> seen += id to rel; null }).run(emptyList())
        assertTrue(1L to "Pictures/WhatsApp/" in seen)
        assertEquals("B\\Dev\\WhatsApp\\2026\\IMG.jpg", ledger.get(1)!!.remotePath)
    }

    @Test fun renameFailureLeavesFileAndRowAndSetsMappedError() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(100)
        seed(store, ledger, row(1, "$camera\\IMG.jpg"), data)
        seed(store, ledger, row(2, "$camera\\B.jpg", name = "B.jpg"))
        store.failOn = { op, path -> if (op == "rename" && path.endsWith("IMG.jpg")) IOException("secret \\\\server\\path") else null }
        val r = engine(store, ledger, toDog).run(emptyList())
        assertEquals(1, (r as BackupResult.Done).moved)
        assertArrayEquals(data, store.files["$camera\\IMG.jpg"])
        assertNull(store.files["$dog\\IMG.jpg"])
        val e = ledger.get(1)!!
        assertEquals("$camera\\IMG.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertEquals(BackupEngine.MOVE_ERROR, e.lastError)
        assertEquals(BackupStatus.VERIFIED, e.status)
        // The run carried on with the next row.
        assertEquals("$dog\\B.jpg", ledger.get(2)!!.remotePath)
    }

    @Test fun mkdirsFailureSetsMappedErrorAndContinues() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\IMG.jpg"))
        store.failOn = { op, _ -> if (op == "mkdirs") IOException("x") else null }
        val r = engine(store, ledger, toDog).run(emptyList())
        assertEquals(0, (r as BackupResult.Done).moved)
        assertEquals("$camera\\IMG.jpg", ledger.get(1)!!.remotePath)
        assertNull(ledger.get(1)!!.movingTo)
        assertEquals(BackupEngine.MOVE_ERROR, ledger.get(1)!!.lastError)
    }

    @Test fun unreachableDuringMoveStopsTheRun() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(100)
        seed(store, ledger, row(1, "$camera\\IMG.jpg"), data)
        store.failOn = { op, _ -> if (op == "rename") RemoteException.Unreachable() else null }
        val r = engine(store, ledger, toDog).run(emptyList())
        assertTrue(r is BackupResult.Stopped && r.reason is RemoteException.Unreachable)
        assertArrayEquals(data, store.files["$camera\\IMG.jpg"])
        assertEquals("$camera\\IMG.jpg", ledger.get(1)!!.remotePath)
    }

    @Test fun pausesBetweenMoves() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val pauses = mutableListOf<Long>()
        seed(store, ledger, row(1, "$camera\\A.jpg", name = "A.jpg"))
        seed(store, ledger, row(2, "$camera\\B.jpg", name = "B.jpg"))
        engine(store, ledger, toDog, pauses = pauses, pauseMs = 500).run(emptyList())
        assertEquals(listOf(500L), pauses)
    }

    // --- Crash recovery ---

    @Test fun recoveryNewPathOnlyUpdatesRecord() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        ledger.upsert(row(1, "$camera\\IMG.jpg", movingTo = "$dog\\IMG.jpg"))
        store.mkdirs(dog); store.files["$dog\\IMG.jpg"] = bytes(100)
        engine(store, ledger, toDog).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals("$dog\\IMG.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertNull(e.lastError)
    }

    @Test fun recoveryOldPathOnlyClearsMovingTo() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        seed(store, ledger, row(1, "$camera\\IMG.jpg", movingTo = "$dog\\IMG.jpg"))
        // Desired folder is now the default again, so nothing else moves it this run.
        engine(store, ledger).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals("$camera\\IMG.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertTrue("$camera\\IMG.jpg" in store.files)
    }

    @Test fun recoveryBothPathsKeepsOldAndSetsConflictError() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val mine = bytes(100, 1); val other = bytes(100, 2)
        seed(store, ledger, row(1, "$camera\\IMG.jpg", movingTo = "$dog\\IMG.jpg"), mine)
        store.mkdirs(dog); store.files["$dog\\IMG.jpg"] = other
        engine(store, ledger, toDog).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals("$camera\\IMG.jpg", e.remotePath)
        assertNull(e.movingTo)
        assertEquals(BackupEngine.MOVE_CONFLICT_ERROR, e.lastError)
        assertArrayEquals(mine, store.files["$camera\\IMG.jpg"])
        assertArrayEquals(other, store.files["$dog\\IMG.jpg"])
    }

    @Test fun recoveryNewPathWithOtherSizeLeavesRowAndSetsError() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val other = bytes(55)
        ledger.upsert(row(1, "$camera\\IMG.jpg", movingTo = "$dog\\IMG.jpg"))
        store.mkdirs(dog); store.files["$dog\\IMG.jpg"] = other
        engine(store, ledger, toDog).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals("$camera\\IMG.jpg", e.remotePath)
        assertEquals("$dog\\IMG.jpg", e.movingTo)
        assertEquals(BackupEngine.MOVE_ERROR, e.lastError)
        assertArrayEquals(other, store.files["$dog\\IMG.jpg"])
    }

    @Test fun recoveryNeitherPathLeavesRowAndSetsError() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        ledger.upsert(row(1, "$camera\\IMG.jpg", movingTo = "$dog\\IMG.jpg"))
        engine(store, ledger, toDog).run(emptyList())
        val e = ledger.get(1)!!
        assertEquals("$camera\\IMG.jpg", e.remotePath)
        assertEquals("$dog\\IMG.jpg", e.movingTo)
        assertEquals(BackupEngine.MOVE_ERROR, e.lastError)
        assertTrue(store.files.isEmpty())
    }

    @Test fun recoveryRunsAfterLeftoverCleanupAndBeforeUploads() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val data = bytes(100)
        ledger.upsert(row(1, "$camera\\IMG.jpg", movingTo = "$dog\\IMG.jpg"))
        store.mkdirs(dog); store.files["$dog\\IMG.jpg"] = bytes(100, 3)
        // A new phone item with the same name, aimed at Dog: must not take the name the recovered row holds.
        val r = engine(store, ledger, toDog, opener = LocalOpener { ByteArrayInputStream(data) }).run(listOf(media(2)))
        assertEquals(1, (r as BackupResult.Done).uploaded)
        assertEquals("$dog\\IMG.jpg", ledger.get(1)!!.remotePath)
        assertEquals("$dog\\IMG (1).jpg", ledger.get(2)!!.remotePath)
    }

    // --- Forgotten items ---

    @Test fun missingOnPhoneClearsTheAssignment() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger(); val assignments = MemoryAssignments()
        assignments.set(FolderAssignment(7, "Dog"))
        assignments.set(FolderAssignment(8, "Dog"))
        val r = engine(store, ledger, toDog, assignments = assignments, opener = LocalOpener { null })
            .run(listOf(media(7)))
        assertTrue(r is BackupResult.Done)
        assertNull(ledger.get(7))
        assertNull(assignments.get(7))
        assertEquals("Dog", assignments.get(8)?.folder) // phone-only items without a run are kept
    }
}
