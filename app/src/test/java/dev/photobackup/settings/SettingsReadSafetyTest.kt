package dev.photobackup.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.ui.Routes
import dev.photobackup.ui.home.HomeViewModel
import dev.photobackup.ui.settings.SettingsViewModel
import dev.photobackup.ui.setup.FakeBackupControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

private class IdCipher : CredentialCipher {
    override fun encrypt(plain: String) = plain
    override fun decrypt(cipher: String) = cipher
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsReadSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun corruptStore(): SettingsStore {
        val f = File(tmp.root, "bad.preferences_pb")
        f.writeBytes(ByteArray(64) { (it * 7 + 3).toByte() })
        val ds = PreferenceDataStoreFactory.create { f }
        return SettingsStore(ApplicationProvider.getApplicationContext(), IdCipher(), ds)
    }

    private val broken: Flow<AppSettings> = flow { throw SettingsUnreadableException() }

    private fun home(settings: Flow<AppSettings>) = HomeViewModel(
        settings = settings,
        status = MutableStateFlow(BackupStatusSnapshot(null, null, null, false, 0, 0, null)),
        counts = MutableStateFlow<Map<BackupStatus, Int>>(emptyMap()),
        checkDrive = {},
        reenterPassword = {},
        freeUpBytes = { 0L },
        backup = FakeBackupControl(),
    )

    @Test fun corruptFileReadsAsErrorNotAsDefaults() = runBlocking {
        val store = corruptStore()
        assertTrue(store.settingsResult.first() is SettingsRead.Error)
    }

    @Test fun plainSettingsFlowThrowsUnreadableOnCorruptFile() = runBlocking {
        var thrown: Throwable? = null
        try { corruptStore().settings.first() } catch (e: Throwable) { thrown = e }
        assertTrue(thrown is SettingsUnreadableException)
    }

    @Test fun healthyFileReadsOk() = runBlocking {
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "ok.preferences_pb") }
        val store = SettingsStore(ApplicationProvider.getApplicationContext(), IdCipher(), ds)
        assertTrue(store.settingsResult.first() is SettingsRead.Ok)
    }

    @Test fun errorIsNeverTheSetupRoute() {
        assertEquals(Routes.SETTINGS_RETRY, Routes.start(SettingsRead.Error))
        assertEquals(Routes.SETUP, Routes.start(SettingsRead.Ok(AppSettings(null, null))))
    }

    @Test fun homeShowsErrorAndRecoversOnRetry() {
        var broke = true
        val src = MutableStateFlow(AppSettings(null, null))
        val vm = home(flow { if (broke) throw SettingsUnreadableException() else emitAll(src) })
        assertTrue(vm.state.value.settingsError)
        assertNull(vm.state.value.settings)
        broke = false
        vm.retrySettings()
        assertFalse(vm.state.value.settingsError)
        assertEquals(AppSettings(null, null), vm.state.value.settings)
    }

    @Test fun homeSurvivesAnyIoError() {
        val vm = home(flow { throw IOException("x") })
        assertTrue(vm.state.value.settingsError)
    }

    @Test fun settingsViewModelShowsErrorAndDoesNotCrashOnEdits() = runBlocking {
        val vm = SettingsViewModel(corruptStore(), { FakeRemoteStore() }, FakeBackupControl())
        val deadline = System.currentTimeMillis() + 5000
        while (!vm.state.value.settingsError && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(20)
        assertTrue(vm.state.value.settingsError)
        assertNull(vm.state.value.settings)
        vm.setChargingOnly(true).join()
        vm.setNotifyOnComplete(false).join()
        vm.setGesture(dev.photobackup.review.Swipe.LEFT, null).join()
        assertTrue(vm.state.value.settingsError)
    }

    @Test fun driveRootIsNullWhenSettingsUnreadable() = runBlocking {
        assertNull(dev.photobackup.app.driveRootOf { throw SettingsUnreadableException() })
        val ok = dev.photobackup.app.driveRootOf { AppSettings(dev.photobackup.smb.SmbConfig("nas", "usb", null, null), "P") }
        assertEquals("nas", ok?.host)
    }

    @Test fun driveRootStillPropagatesCancellation() = runBlocking {
        var thrown: Throwable? = null
        try {
            dev.photobackup.app.driveRootOf { throw kotlinx.coroutines.CancellationException("c") }
        } catch (e: Throwable) { thrown = e }
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }

    @Test fun backUpNowDoesNothingWhileSettingsAreUnreadableEvenAfterRetry() {
        var broke = true
        val control = FakeBackupControl()
        val vm = HomeViewModel(
            settings = flow { if (broke) throw SettingsUnreadableException() else emitAll(MutableStateFlow(AppSettings(null, null))) },
            status = MutableStateFlow(BackupStatusSnapshot(null, null, null, false, 0, 0, null)),
            counts = MutableStateFlow<Map<BackupStatus, Int>>(emptyMap()),
            checkDrive = {}, reenterPassword = {}, freeUpBytes = { 0L }, backup = control,
        )
        vm.backUpNow()
        assertTrue(vm.state.value.settingsError)
        broke = false
        vm.retrySettings()
        assertEquals(emptyList<String>(), control.calls)
    }
}
