package dev.photobackup.ui.home

import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.backup.HealthStatusText
import dev.photobackup.settings.AppSettings
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The status sheet's health check line and its damaged-files details. */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthStatusLineTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day + 5000

    private fun snap(last: Long?, damaged: Int = 0, checking: Boolean = false) = BackupStatusSnapshot(
        null, null, null, false, 0, 0, null, lastHealthCheckMs = last, healthDamaged = damaged, healthChecking = checking,
    )

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun noLineBeforeTheFirstCheck() {
        assertNull(HealthStatusText.of(snap(null), now))
        assertNull(HealthStatusText.of(null, now))
    }

    @Test fun allGoodSaysHowLongAgo() {
        assertEquals("Health check: all good (last checked today)", HealthStatusText.of(snap(now - 1000), now))
        assertEquals("Health check: all good (last checked 1 day ago)", HealthStatusText.of(snap(now - day), now))
        assertEquals("Health check: all good (last checked 3 days ago)", HealthStatusText.of(snap(now - 3 * day - 1), now))
        // Clock went back: never a negative count.
        assertEquals("Health check: all good (last checked today)", HealthStatusText.of(snap(now + day), now))
    }

    @Test fun damagedCountAsksForATap() =
        assertEquals("2 damaged — tap for details", HealthStatusText.of(snap(now - day, damaged = 2), now))

    @Test fun checkingWinsWhileThePassRuns() {
        assertEquals("Checking drive health…", HealthStatusText.of(snap(null, checking = true), now))
        assertEquals("Checking drive health…", HealthStatusText.of(snap(now - day, damaged = 2, checking = true), now))
    }

    @Test fun onlyTheDamagedLineIsTappable() {
        assertTrue(HomeUiState(status = snap(now, damaged = 1)).healthTappable)
        assertFalse(HomeUiState(status = snap(now, damaged = 0)).healthTappable)
        assertFalse(HomeUiState(status = snap(now, damaged = 1, checking = true)).healthTappable)
    }

    private fun vm(details: suspend () -> List<DamagedFile>) = HomeViewModel(
        settings = MutableStateFlow(AppSettings(SmbConfig("nas", "usb", "greg", "pw"), "Photos", deviceName = "Pixel")),
        status = MutableStateFlow(snap(now, damaged = 1)),
        counts = MutableStateFlow(emptyMap()),
        checkDrive = {},
        reenterPassword = {},
        freeUpBytes = { 0L },
        backup = FakeBackupControl(),
        damagedFiles = details,
    )

    @Test fun detailsListNamesAndErrorsAndCloses() {
        val vm = vm { listOf(DamagedFile("img1.jpg", "Damaged on drive — no good copy left")) }
        assertNull(vm.state.value.damaged)
        vm.showDamaged()
        val d = vm.state.value.damaged!!
        assertEquals(listOf(DamagedFile("img1.jpg", "Damaged on drive — no good copy left")), d.files)
        assertFalse(d.failed)
        vm.hideDamaged()
        assertNull(vm.state.value.damaged)
    }

    @Test fun aDatabaseErrorShowsAMessageInsteadOfCrashing() {
        val vm = vm { throw IllegalStateException("db gone") }
        vm.showDamaged()
        val d = vm.state.value.damaged!!
        assertTrue(d.failed)
        assertTrue(d.files.isEmpty())
    }
}
