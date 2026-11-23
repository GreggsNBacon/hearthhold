package dev.photobackup.gallery

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteException
import dev.photobackup.ui.auth.DriveNotConfiguredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = GalleryViewModel(
        queryPhone = { phoneError?.let { throw it }; phone },
        ledger = ledger,
        checkDrive = { checks++; driveGate?.await(); driveError?.let { throw it } },
        driveRoot = { SmbRoot("nas", "usb") },
        compute = dispatcher,
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
}
