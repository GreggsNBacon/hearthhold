package dev.photobackup.gallery

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignment
import dev.photobackup.ledger.FolderAssignmentStore
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteException
import dev.photobackup.ui.auth.DriveNotConfiguredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val ledger = MutableStateFlow<List<LedgerEntry>>(emptyList())
    private var phone: List<PhoneMedia> = emptyList()
    private var phoneError: Exception? = null
    private var driveError: Exception? = null
    private var driveGate: CompletableDeferred<Unit>? = null
    private var checks = 0
    private var passwordUnreadable = false
    private var nowMs = 100_000L

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = GalleryViewModel(
        queryPhone = { phoneError?.let { throw it }; phone },
        ledger = ledger,
        checkDrive = { checks++; driveGate?.await(); driveError?.let { throw it } },
        driveRoot = { SmbRoot("nas", "usb") },
        compute = dispatcher,
        needsPassword = { passwordUnreadable },
        clock = { nowMs },
    )

    private fun phone(id: Long, taken: Long) = PhoneMedia(
        id, "content://m/$id", "P$id.jpg", "DCIM/Camera/", 1, taken, 0, 0, false, "image/jpeg",
    )

    private fun entry(id: Long, status: BackupStatus, taken: Long) = LedgerEntry(
        id, "content://m/$id", "P$id.jpg", "DCIM/Camera/", 1, taken, 0, false, "h", "D\\P$id.jpg", null, status,
        null, 0,
    )

    @Test fun refreshMergesPhoneAndLedgerAndChecksDrive() {
        phone = listOf(phone(1, 10))
        ledger.value = listOf(entry(1, BackupStatus.VERIFIED, 10), entry(2, BackupStatus.REMOVED_FROM_PHONE, 20))
        val vm = vm()
        vm.refresh()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf(Location.DRIVE, Location.BOTH), s.items.map { it.location })
        assertEquals(DriveState.REACHABLE, s.drive)
        assertEquals(SmbRoot("nas", "usb"), s.root)
        assertEquals(1, checks)
        assertTrue(s.items.all { s.isAvailable(it) })
    }

    @Test fun ledgerChangesReMerge() {
        phone = listOf(phone(1, 10))
        val vm = vm()
        vm.refresh()
        assertEquals(Location.PHONE, vm.state.value.items.single().location)
        ledger.value = listOf(entry(1, BackupStatus.VERIFIED, 10))
        assertEquals(Location.BOTH, vm.state.value.items.single().location)
    }

    @Test fun unreachableDriveGreysOutDriveOnlyItems() {
        driveError = RemoteException.Unreachable()
        phone = listOf(phone(1, 10))
        ledger.value = listOf(entry(1, BackupStatus.VERIFIED, 10), entry(2, BackupStatus.REMOVED_FROM_PHONE, 20))
        val vm = vm()
        vm.refresh()
        val s = vm.state.value
        assertEquals(DriveState.UNREACHABLE, s.drive)
        val drive = s.items.first { it.location == Location.DRIVE }
        val both = s.items.first { it.location == Location.BOTH }
        assertFalse(s.isAvailable(drive))
        assertTrue(s.isAvailable(both))
    }

    @Test fun unreachableKeepsFriendlyMessageAndRecheckClearsIt() {
        driveError = RemoteException.Unreachable()
        val vm = vm()
        vm.refresh()
        assertEquals("Can't reach the drive — check you're on home Wi-Fi", vm.state.value.driveMessage)
        driveError = null
        vm.refresh()
        assertEquals(DriveState.REACHABLE, vm.state.value.drive)
        assertEquals(null, vm.state.value.driveMessage)
    }

    @Test fun cancelledPasswordPromptCountsAsUnreachable() {
        driveError = RemoteException.AuthFailed()
        val vm = vm()
        vm.refresh()
        assertEquals(DriveState.UNREACHABLE, vm.state.value.drive)
    }

    @Test fun noDriveConfigured() {
        driveError = DriveNotConfiguredException()
        val vm = vm()
        vm.refresh()
        assertEquals(DriveState.NOT_CONFIGURED, vm.state.value.drive)
    }

    @Test fun checkingUntilDriveAnswers() {
        driveGate = CompletableDeferred()
        ledger.value = listOf(entry(2, BackupStatus.REMOVED_FROM_PHONE, 20))
        val vm = vm()
        vm.refresh()
        val s = vm.state.value
        assertEquals(DriveState.CHECKING, s.drive)
        assertFalse(s.isAvailable(s.items.single()))
        driveGate!!.complete(Unit)
        assertEquals(DriveState.REACHABLE, vm.state.value.drive)
    }

    @Test fun noPhotoAccessStillShowsDriveItems() {
        phoneError = SecurityException("no")
        ledger.value = listOf(entry(2, BackupStatus.REMOVED_FROM_PHONE, 20))
        val vm = vm()
        vm.refresh()
        val s = vm.state.value
        assertTrue(s.phoneDenied)
        assertEquals(1, s.items.size)
    }

    @Test fun startLoadsOnceAndChecksDrive() {
        val vm = vm()
        vm.start()
        vm.start()
        assertEquals(1, checks)
        assertEquals(DriveState.REACHABLE, vm.state.value.drive)
    }

    @Test fun resumeReChecksDriveAfterGap() {
        driveError = RemoteException.Unreachable()
        val vm = vm()
        vm.resume() // first resume = start
        assertEquals(1, checks)
        assertEquals(DriveState.UNREACHABLE, vm.state.value.drive)
        nowMs += 5_000
        vm.resume() // too soon after the last check
        assertEquals(1, checks)
        driveError = null
        nowMs += 6_000
        vm.resume()
        assertEquals(2, checks)
        assertEquals(DriveState.REACHABLE, vm.state.value.drive)
    }

    /** A folder added in Settings while the Gallery was open: its items must not show as "On drive only". */
    @Test fun resumeReloadsPhoneListSoNewlyAddedFolderIsNotDriveOnly() {
        val vm = vm()
        vm.resume() // first show: phone list empty (folder not added yet)
        phone = listOf(phone(1, 10)) // folder added in Settings
        ledger.value = listOf(entry(1, BackupStatus.VERIFIED, 10)) // and backed up meanwhile
        vm.resume() // back on the Gallery
        assertEquals(Location.BOTH, vm.state.value.items.single().location)
    }

    @Test fun resumeWhileCheckingDoesNotStartAnother() {
        driveGate = CompletableDeferred()
        val vm = vm()
        vm.resume()
        nowMs += 60_000
        vm.resume()
        assertEquals(1, checks)
        driveGate!!.complete(Unit)
    }

    @Test fun unreadablePasswordSkipsAutomaticChecks() {
        passwordUnreadable = true
        val vm = vm()
        vm.resume()
        nowMs += 60_000
        vm.resume()
        assertEquals(0, checks)
        assertEquals(DriveState.NEEDS_PASSWORD, vm.state.value.drive)
        assertFalse(vm.state.value.loading) // the phone side still loads
    }

    @Test fun userRefreshChecksEvenWithUnreadablePassword() {
        passwordUnreadable = true
        val vm = vm()
        vm.resume()
        vm.refresh()
        assertEquals(1, checks)
    }


    @Test fun waitingComesFromThePhoneList() {
        phone = listOf(phone(1, 10), phone(2, 20), phone(3, 30))
        ledger.value = listOf(entry(1, BackupStatus.VERIFIED, 10))
        val vm = vm()
        vm.refresh()
        assertEquals(2, vm.state.value.waiting) // 2 and 3 have no ledger row yet
    }

    @Test fun partialPhotoAccessIsFlaggedAndWaitingUnknown() {
        phoneError = dev.photobackup.media.PartialMediaAccessException()
        val vm = vm()
        vm.refresh()
        val s = vm.state.value
        assertTrue(s.phonePartial)
        assertFalse(s.phoneDenied)
        assertNull(s.waiting)
    }

    @Test fun noPhotoAccessLeavesWaitingUnknown() {
        phoneError = SecurityException("no")
        val vm = vm()
        vm.refresh()
        assertNull(vm.state.value.waiting)
    }

    @Test fun resumeReloadsPhoneAfterAccessIsGranted() {
        phoneError = SecurityException("no")
        val vm = vm()
        vm.start()
        assertTrue(vm.state.value.phoneDenied)
        phoneError = null // allowed in system settings meanwhile
        phone = listOf(phone(1, 10))
        vm.resume()
        assertFalse(vm.state.value.phoneDenied)
        assertEquals(1, vm.state.value.waiting)
    }

    private class FakeAssignments : FolderAssignmentStore {
        val map = linkedMapOf<Long, String>()
        override suspend fun get(mediaId: Long) = map[mediaId]?.let { FolderAssignment(mediaId, it) }
        override suspend fun all() = map.map { FolderAssignment(it.key, it.value) }
        override suspend fun set(a: FolderAssignment) { map[a.mediaId] = a.folder }
        override suspend fun clear(mediaId: Long) { map.remove(mediaId) }
        override fun observeAll() = MutableStateFlow(all0())
        private fun all0() = map.map { FolderAssignment(it.key, it.value) }
    }

    private fun foldersVm(
        store: FakeAssignments,
        rules: Map<String, String> = emptyMap(),
        runs: () -> Unit = {},
    ) = GalleryViewModel(
        queryPhone = { phone },
        ledger = ledger,
        checkDrive = {},
        driveRoot = { SmbRoot("nas", "usb") },
        compute = dispatcher,
        assignments = store,
        rules = { rules },
        runNow = runs,
        root = { "Backups" to "Pixel 7" },
    )

    @Test fun sendToWritesAssignmentsAndRunsOnce() {
        val store = FakeAssignments()
        var runs = 0
        val vm = foldersVm(store) { runs++ }
        vm.sendTo(listOf("media:1", "media:2"), "Dog")
        assertEquals(mapOf(1L to "Dog", 2L to "Dog"), store.map)
        assertEquals(1, runs)
    }

    @Test fun sendToNullClears() {
        val store = FakeAssignments().apply { map[1] = "Dog"; map[2] = "Dog"; map[3] = "Cat" }
        var runs = 0
        val vm = foldersVm(store) { runs++ }
        vm.sendTo(listOf("media:1", "media:2"), null)
        assertEquals(mapOf(3L to "Cat"), store.map)
        assertEquals(1, runs)
    }

    @Test fun sendToFailureShowsNoticeWithoutExceptionText() {
        val store = FakeAssignments()
        val vm = foldersVm(store) { throw IllegalStateException("secret-detail") }
        vm.sendTo(listOf("media:1"), "Dog")
        val n = vm.notice.value
        assertNotNull(n)
        assertFalse(n!!.contains("secret-detail"))
        assertNotEquals("Moving 1 to Dog", n)
    }

    @Test fun sendToDaoFailureShowsNoticeAndDoesNotRun() {
        val failing = object : FolderAssignmentStore by FakeAssignments() {
            override suspend fun set(a: FolderAssignment) { throw IllegalStateException("secret-detail") }
        }
        var runs = 0
        val vm = GalleryViewModel(
            queryPhone = { phone }, ledger = ledger, checkDrive = {}, driveRoot = { SmbRoot("nas", "usb") },
            compute = dispatcher, assignments = failing, rules = { emptyMap() }, runNow = { runs++ },
            root = { "Backups" to "Pixel 7" },
        )
        vm.sendTo(listOf("media:1"), "Dog")
        assertEquals(0, runs)
        assertFalse(vm.notice.value!!.contains("secret-detail"))
    }

    @Test fun selectionTogglesAndClearsAfterSending() {
        val vm = foldersVm(FakeAssignments())
        vm.select("media:1")
        vm.toggle("media:2")
        assertEquals(setOf("media:1", "media:2"), vm.selection.value)
        vm.toggle("media:1")
        assertEquals(setOf("media:2"), vm.selection.value)
        vm.sendTo(vm.selection.value.toList(), "Dog")
        assertTrue(vm.selection.value.isEmpty())
        vm.select("media:3")
        vm.clearSelection()
        assertTrue(vm.selection.value.isEmpty())
    }

    @Test fun sendToRejectsBadNamesAndKeys() {
        val store = FakeAssignments()
        var runs = 0
        val vm = foldersVm(store) { runs++ }
        vm.sendTo(listOf("media:1"), "___")
        vm.sendTo(listOf("junk"), "Dog")
        assertTrue(store.map.isEmpty())
        assertEquals(0, runs)
    }

    @Test fun folderNamesDedupeIgnoringCaseAndPreferLedgerSpelling() {
        val store = FakeAssignments().apply { map[5] = "dog"; map[6] = "Birds" }
        ledger.value = listOf(
            entry(1, BackupStatus.VERIFIED, 10).copy(remotePath = "Backups\\Pixel 7\\DOG\\2024\\a.jpg"),
            entry(2, BackupStatus.VERIFIED, 10).copy(remotePath = "Other\\Pixel 7\\Elsewhere\\2024\\b.jpg"),
        )
        val vm = foldersVm(store, mapOf("DCIM/Camera/" to "Dog", "x/" to "Cats"))
        var names = emptyList<String>()
        kotlinx.coroutines.runBlocking { names = vm.folderNames() }
        assertEquals(listOf("DOG", "Birds", "Cats"), names)
    }

    @Test fun folderNamesNeverOfferAYearFromTheOldYearMonthLayout() {
        ledger.value = listOf(
            entry(1, BackupStatus.VERIFIED, 10).copy(remotePath = "Backups\\Pixel 7\\2023\\05\\a.jpg"),
            entry(2, BackupStatus.VERIFIED, 10).copy(remotePath = "Backups\\Pixel 7\\Dog\\2024\\b.jpg"),
            entry(3, BackupStatus.VERIFIED, 10).copy(remotePath = "backups\\pixel 7\\Cats\\2024\\c.jpg"),
        )
        val vm = foldersVm(FakeAssignments())
        var names = emptyList<String>()
        kotlinx.coroutines.runBlocking { names = vm.folderNames() }
        assertEquals(listOf("Dog", "Cats"), names)
        assertNull(vm.notice.value)
    }

    @Test fun folderNamesNeverOfferTheBinOrBinRows() {
        ledger.value = listOf(
            entry(1, BackupStatus.BINNED, 10).copy(remotePath = "Backups\\Pixel 7\\.Bin\\Junk\\2024\\a.jpg"),
            entry(2, BackupStatus.VERIFIED, 10).copy(remotePath = "Backups\\Pixel 7\\.Bin\\2024\\b.jpg"),
            entry(3, BackupStatus.REMOVED_FROM_PHONE, 10)
                .copy(remotePath = "Backups\\Pixel 7\\Waiting\\2024\\c.jpg", binRequestedAt = 1),
            entry(4, BackupStatus.BINNED, 10).copy(remotePath = "Backups\\Pixel 7\\Old\\2024\\d.jpg"),
            entry(5, BackupStatus.VERIFIED, 10).copy(remotePath = "Backups\\Pixel 7\\Dog\\2024\\e.jpg"),
        )
        val vm = foldersVm(FakeAssignments())
        var names = emptyList<String>()
        kotlinx.coroutines.runBlocking { names = vm.folderNames() }
        assertEquals(listOf("Dog"), names)
    }

    @Test fun folderNamesSurviveDatabaseFailuresWithAFixedNotice() {
        var collects = 0
        val flaky = kotlinx.coroutines.flow.flow {
            if (collects++ == 0) emitAll(ledger) else throw IllegalStateException("secret-detail")
        }
        val failing = object : FolderAssignmentStore by FakeAssignments() {
            override suspend fun all(): List<FolderAssignment> = throw IllegalStateException("secret-detail")
        }
        val vm = GalleryViewModel(
            queryPhone = { phone }, ledger = flaky, checkDrive = {}, driveRoot = { SmbRoot("nas", "usb") },
            compute = dispatcher, assignments = failing, rules = { mapOf("x/" to "Cats") }, runNow = {},
            root = { "Backups" to "Pixel 7" },
        )
        var names = emptyList<String>()
        kotlinx.coroutines.runBlocking { names = vm.folderNames() }
        assertEquals(listOf("Cats"), names)
        assertEquals(NAMES_FAILED, vm.notice.value)
        assertFalse(vm.notice.value!!.contains("secret-detail"))
    }

    @Test fun cellTapTogglesAnyItemWhileSelectingEvenWhenUnavailable() {
        assertEquals(CellTap.TOGGLE, cellTap(selecting = true, available = false))
        assertEquals(CellTap.TOGGLE, cellTap(selecting = true, available = true))
        assertEquals(CellTap.OPEN, cellTap(selecting = false, available = true))
        assertEquals(CellTap.NONE, cellTap(selecting = false, available = false))
    }
}
