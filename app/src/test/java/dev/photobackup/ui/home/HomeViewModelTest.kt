package dev.photobackup.ui.home

import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.settings.AppSettings
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.SmbConfig
import dev.photobackup.ui.setup.FakeBackupControl
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
class HomeViewModelTest {
    private val settings = MutableStateFlow(
        AppSettings(SmbConfig("nas", "usb", "greg", "pw"), "Photos", deviceName = "Pixel", chargingOnly = true),
    )
    private val status = MutableStateFlow(BackupStatusSnapshot(null, null, null, false, 0, 0, null))
    private val counts = MutableStateFlow<Map<BackupStatus, Int>>(emptyMap())
    private val backup = FakeBackupControl()
    private var driveError: Exception? = null
    private var checks = 0

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = HomeViewModel(
        settings = settings,
        status = status,
        counts = counts,
        checkDrive = { checks++; driveError?.let { throw it } },
        reenterPassword = {},
        freeUpBytes = { 3L shl 30 },
        backup = backup,
    )

    @Test fun countsGroupWaitingBackedUpAndFailed() {
        val vm = vm()
        counts.value = mapOf(
            BackupStatus.PENDING to 3, BackupStatus.UPLOADING to 1, BackupStatus.VERIFIED to 10,
            BackupStatus.REMOVED_FROM_PHONE to 2, BackupStatus.FAILED to 4,
        )
        val s = vm.state.value
        assertEquals(4, s.waiting)
        assertEquals(12, s.backedUp)
        assertEquals(4, s.failed)
    }

    @Test fun refreshChecksDriveAndFreeUpSize() {
        val vm = vm()
        vm.refresh()
        assertEquals(Reachability.YES, vm.state.value.reach)
        assertEquals(3L shl 30, vm.state.value.freeUpBytes)
        driveError = RemoteException.Unreachable()
        vm.refresh()
        assertEquals(Reachability.NO, vm.state.value.reach)
        assertEquals("Can't reach the drive — check you're on home Wi-Fi", vm.state.value.reachMessage)
    }

    @Test fun backUpNowPassesChargingSetting() {
        vm().backUpNow()
        assertEquals(listOf("runNow(true)"), backup.calls)
    }

    @Test fun undecryptablePasswordShowsBannerAndSkipsAutoCheck() {
        settings.value = settings.value.copy(smb = SmbConfig("nas", "usb", "greg", null))
        val vm = vm()
        assertTrue(vm.state.value.needsPassword)
        vm.refresh(auto = true)
        assertEquals(0, checks)
        settings.value = settings.value.copy(smb = SmbConfig("nas", "usb", null, null))
        assertFalse(vm.state.value.needsPassword) // guest
    }

    @Test fun refreshFreeUpDoesNotTouchTheDrive() {
        val vm = vm()
        vm.refreshFreeUp()
        assertEquals(0, checks)
        assertEquals(3L shl 30, vm.state.value.freeUpBytes)
        assertEquals(Reachability.UNKNOWN, vm.state.value.reach)
    }

    @Test fun reenterRunsCallbackOnlyOnSuccess() {
        var done = 0
        vm().reenter { done++ }
        assertEquals(1, done)
        assertEquals(0, checks) // the callback replaces the default drive re-check
        val cancelling = HomeViewModel(
            settings, status, counts, checkDrive = { checks++ },
            reenterPassword = { throw IllegalStateException("cancelled") },
            freeUpBytes = { 0 }, backup = backup,
        )
        cancelling.reenter { done++ }
        assertEquals(1, done)
    }
}
