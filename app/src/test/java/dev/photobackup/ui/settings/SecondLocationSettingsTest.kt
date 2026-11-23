package dev.photobackup.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.settings.CredentialCipher
import dev.photobackup.settings.SecondLocation
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.SmbConfig
import dev.photobackup.ui.setup.FakeBackupControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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

private class XCipher : CredentialCipher {
    override fun encrypt(plain: String) = "x$plain"
    override fun decrypt(cipher: String) = cipher.removePrefix("x")
}

/** Settings' "Second backup location" row: Remove / Change and "Allow freeing up with only the main drive". */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecondLocationSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var settings: SettingsStore
    private val store = FakeRemoteStore()
    private val backup = FakeBackupControl()
    private val opened = mutableListOf<SmbConfig>()
    private val lock = Mutex()
    private var cleared = 0
    private var clearError: Exception? = null
    private val mainSmb = SmbConfig("nas", "usb", "greg", "pw")
    private val second = SecondLocation(SmbConfig("pc", "share", "pc", "pcpw"), "Copy")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }
        settings = SettingsStore(ApplicationProvider.getApplicationContext(), XCipher(), ds)
        runBlocking { settings.update { it.copy(smb = mainSmb, destination = "Photos", second = second) } }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SettingsViewModel(
        settings, { opened += it; store }, backup,
        clearSecondCopies = { clearError?.let { throw it }; cleared++ },
        lock = lock,
    )

    @Test fun removeClearsTheTableAndTheSettingsOnly() = runBlocking {
        var done = 0
        vm().removeSecond { done++ }.join()
        val s = settings.settings.first()
        assertNull(s.second)
        assertEquals(mainSmb, s.smb)
        assertEquals("Photos", s.destination)
        assertEquals(1, cleared)
        assertEquals(1, done)
        assertTrue(opened.isEmpty()) // no drive is touched
        assertTrue(store.writes.isEmpty() && store.deletes.isEmpty())
        assertTrue(backup.calls.isEmpty())
        assertFalse(lock.isLocked)
    }

    @Test fun removeWhileABackupRunsChangesNothing() = runBlocking {
        lock.lock()
        var done = 0
        val vm = vm()
        vm.removeSecond { done++ }.join()
        assertEquals(second, settings.settings.first().second)
        assertEquals(0, cleared)
        assertEquals(0, done)
        assertEquals(SettingsViewModel.BACKUP_RUNNING, vm.state.value.secondMessage)
        assertTrue(lock.isLocked) // still the backup's
    }

    @Test fun removeKeepsTheLocationWhenTheTableCantBeCleared() = runBlocking {
        clearError = IOException("db")
        var done = 0
        val vm = vm()
        vm.removeSecond { done++ }.join()
        assertEquals(second, settings.settings.first().second)
        assertEquals(0, done)
        assertEquals(SettingsViewModel.REMOVE_SECOND_FAILED, vm.state.value.secondMessage)
        assertFalse(lock.isLocked)
    }

    @Test fun allowMainOnlyIsTheInverseOfNeedsBoth() = runBlocking {
        assertTrue(settings.settings.first().freeUpNeedsBoth) // "Allow freeing up with only the main drive" off
        val vm = vm()
        vm.setAllowMainOnly(true).join()
        assertFalse(settings.settings.first().freeUpNeedsBoth)
        vm.setAllowMainOnly(false).join()
        assertTrue(settings.settings.first().freeUpNeedsBoth)
        assertEquals(second, settings.settings.first().second)
    }
}
