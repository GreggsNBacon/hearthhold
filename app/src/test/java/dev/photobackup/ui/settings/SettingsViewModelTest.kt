package dev.photobackup.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.settings.CredentialCipher
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.SmbConfig
import dev.photobackup.ui.setup.FakeBackupControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private class PlainCipher : CredentialCipher {
    override fun encrypt(plain: String) = "x$plain"
    override fun decrypt(cipher: String) = cipher.removePrefix("x")
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var settings: SettingsStore
    private val store = FakeRemoteStore()
    private val backup = FakeBackupControl()
    private val opened = mutableListOf<SmbConfig>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }
        settings = SettingsStore(ApplicationProvider.getApplicationContext(), PlainCipher(), ds)
        runBlocking { settings.update { it.copy(smb = SmbConfig("nas", "usb", "greg", "pw"), destination = "Photos") } }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SettingsViewModel(settings, { opened += it; store }, backup)

    @Test fun changingChargingOnlyReschedules() = runBlocking {
        vm().setChargingOnly(true).join()
        assertEquals(true, settings.settings.first().chargingOnly)
        assertEquals(listOf("scheduleAll(true)"), backup.calls)
    }

    @Test fun freeUpAgeIsClampedTo1To36() = runBlocking {
        val vm = vm()
        vm.setFreeUpAge(0).join()
        assertEquals(1, settings.settings.first().freeUpAgeMonths)
        vm.setFreeUpAge(99).join()
        assertEquals(36, settings.settings.first().freeUpAgeMonths)
    }

    @Test fun addFolderNormalisesRelativePath() = runBlocking {
        vm().addFolder(" /Pictures/WhatsApp ").join()
        assertEquals(setOf("Pictures/WhatsApp/"), settings.settings.first().folders.extraRelativePaths)
    }

    @Test fun saveDriveRequiresPasswordWhenSavedOneIsUnreadable() = runBlocking {
        settings.update { it.copy(smb = SmbConfig("nas", "usb", "greg", null)) }
        val vm = vm()
        vm.saveDrive("nas", "usb", guest = false, username = "greg", password = "").join()
        assertEquals("Enter the drive password", vm.state.value.driveMessage)
        assertEquals(true, opened.isEmpty())
        assertEquals(null, settings.settings.first().smb?.password)
    }

    @Test fun saveDriveKeepsSavedPasswordWhenBlankAndTestsFirst() = runBlocking {
        val vm = vm()
        vm.saveDrive("nas2", "usb", guest = false, username = "greg", password = "").join()
        assertEquals(SmbConfig("nas2", "usb", "greg", "pw"), opened.single())
        assertEquals(SmbConfig("nas2", "usb", "greg", "pw"), settings.settings.first().smb)
        assertEquals("Destination", "Photos", settings.settings.first().destination) // untouched
    }

    @Test fun saveDriveFailureKeepsOldSettings() = runBlocking {
        store.failOn = { op, _ -> if (op == "connect") RemoteException.AuthFailed() else null }
        val vm = vm()
        vm.saveDrive("nas", "usb", guest = false, username = "greg", password = "bad").join()
        assertEquals("Wrong username or password", vm.state.value.driveMessage)
        assertEquals("pw", settings.settings.first().smb?.password)
    }
}
