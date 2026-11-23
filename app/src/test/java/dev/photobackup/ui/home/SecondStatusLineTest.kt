package dev.photobackup.ui.home

import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.backup.SecondStatusText
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.SecondLocation
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

/** The status sheet's "Second location: …" line and its "Re-enter password" action. */
@OptIn(ExperimentalCoroutinesApi::class)
class SecondStatusLineTest {
    private val main = SmbConfig("nas", "usb", "greg", "pw")
    private val second = SecondLocation(SmbConfig("pc", "share", "pc", "pcpw"), "Copy")
    private fun snap(secondStatus: String?) = BackupStatusSnapshot(null, null, null, false, 0, 0, null, secondStatus = secondStatus)
    private fun state(second: SecondLocation?, secondStatus: String?) =
        HomeUiState(settings = AppSettings(main, "Photos", deviceName = "Pixel", second = second), status = snap(secondStatus))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun noLineWhenOff() {
        assertNull(state(null, null).secondLine)
        // A status left over from before Remove is not shown.
        assertNull(state(null, SecondStatusText.UP_TO_DATE).secondLine)
        assertFalse(state(null, SecondStatusText.REENTER_PASSWORD).secondNeedsPassword)
    }

    @Test fun lineShowsTheStoredStatus() {
        assertEquals("Second location: Up to date", state(second, SecondStatusText.UP_TO_DATE).secondLine)
        assertEquals("Second location: 3 waiting", state(second, SecondStatusText.waiting(3)).secondLine)
        assertEquals("Second location: Not reachable", state(second, SecondStatusText.NOT_REACHABLE).secondLine)
    }

    @Test fun notRunYetSaysWaiting() =
        assertEquals("Second location: not copied yet", state(second, null).secondLine)

    @Test fun passwordActionOnAuthFailureOrUnreadablePassword() {
        assertTrue(state(second, SecondStatusText.REENTER_PASSWORD).secondNeedsPassword)
        assertFalse(state(second, SecondStatusText.UP_TO_DATE).secondNeedsPassword)
        val unreadable = second.copy(smb = second.smb.copy(password = null))
        assertTrue(state(unreadable, SecondStatusText.UP_TO_DATE).secondNeedsPassword)
        val guest = second.copy(smb = SmbConfig("pc", "share", null, null))
        assertFalse(state(guest, SecondStatusText.UP_TO_DATE).secondNeedsPassword)
    }

    @Test fun reenterSecondOpensTheSecondPrompt() {
        var asked = 0
        var done = 0
        val vm = HomeViewModel(
            settings = MutableStateFlow(AppSettings(main, "Photos", deviceName = "Pixel", second = second)),
            status = MutableStateFlow(snap(SecondStatusText.REENTER_PASSWORD)),
            counts = MutableStateFlow<Map<BackupStatus, Int>>(emptyMap()),
            checkDrive = {},
            reenterPassword = { error("main prompt must not open") },
            freeUpBytes = { 0 },
            backup = FakeBackupControl(),
            reenterSecondPassword = { asked++ },
        )
        vm.reenterSecond { done++ }
        assertEquals(1, asked)
        assertEquals(1, done)
    }

    @Test fun cancelledSecondPromptDoesNothing() {
        var done = 0
        val vm = HomeViewModel(
            settings = MutableStateFlow(AppSettings(main, "Photos", deviceName = "Pixel", second = second)),
            status = MutableStateFlow(snap(null)),
            counts = MutableStateFlow<Map<BackupStatus, Int>>(emptyMap()),
            checkDrive = {},
            reenterPassword = {},
            freeUpBytes = { 0 },
            backup = FakeBackupControl(),
            reenterSecondPassword = { throw java.io.IOException("cancelled") },
        )
        vm.reenterSecond { done++ }
        assertEquals(0, done)
    }
}
