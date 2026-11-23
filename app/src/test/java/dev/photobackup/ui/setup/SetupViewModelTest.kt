package dev.photobackup.ui.setup

import android.Manifest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.app.BackupControl
import dev.photobackup.discovery.FoundHost
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.CredentialCipher
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.SmbConfig
import dev.photobackup.ui.Routes
import dev.photobackup.ui.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

private class PlainCipher : CredentialCipher {
    override fun encrypt(plain: String) = "x$plain"
    override fun decrypt(cipher: String) = cipher.removePrefix("x")
}

class FakeBackupControl : BackupControl {
    val calls = mutableListOf<String>()
    override fun scheduleAll(chargingOnly: Boolean, night: dev.photobackup.backup.OvernightWindow?) {
        calls += if (night == null) "scheduleAll($chargingOnly)" else
            "scheduleAll($chargingOnly, night=${night.startHour}-${night.endHour})"
    }
    override fun runNow(chargingOnly: Boolean) { calls += "runNow($chargingOnly)" }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var settings: SettingsStore
    private lateinit var store: FakeRemoteStore
    private lateinit var backup: FakeBackupControl
    private val opened = mutableListOf<SmbConfig>()
    private var shareError: RemoteException? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }
        settings = SettingsStore(ApplicationProvider.getApplicationContext(), PlainCipher(), ds)
        store = FakeRemoteStore()
        backup = FakeBackupControl()
        opened.clear()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SetupViewModel(
        settingsStore = settings,
        discover = { emptyFlow<FoundHost>() },
        listShares = { _, user, _ -> shareError?.let { if (user == null) throw it }; listOf("usb") },
        openStore = { cfg -> opened += cfg; store },
        backup = backup,
    )

    private fun SetupViewModel.toCredentials(): SetupViewModel = apply {
        selectHost("192.168.50.1")
        selectShare("usb")
    }

    @Test fun testConnectionMapsAuthFailedToMessage() = runBlocking {
        store.failOn = { op, _ -> if (op == "connect") RemoteException.AuthFailed() else null }
        val vm = vm().toCredentials()
        vm.setGuest(false); vm.setUsername("greg"); vm.setPassword("bad")
        vm.testConnection().join()
        assertEquals("Wrong username or password", vm.state.value.message)
        assertEquals(SetupStep.CREDENTIALS, vm.state.value.step)
        assertNull(settings.settings.first().smb) // nothing saved on failure
    }

    @Test fun smb1OnlyShowsRouterHint() = runBlocking {
        store.failOn = { op, _ -> if (op == "connect") RemoteException.Smb1Only() else null }
        val vm = vm().toCredentials()
        vm.testConnection().join()
        assertEquals(
            "Couldn't agree a file-sharing version with the drive. On the router, make sure SMB2 is enabled: USB Application → Network Place (Samba) Share",
            vm.state.value.message,
        )
    }

    @Test fun otherErrorsMapToFriendlyMessages() {
        assertEquals("Can't reach the drive — check you're on home Wi-Fi", userMessage(RemoteException.Unreachable()))
        assertEquals("Shared folder not found on the drive", userMessage(RemoteException.ShareNotFound()))
        assertEquals("The drive is full", userMessage(RemoteException.DriveFull()))
    }

    @Test fun successfulTestSavesLoginAndMovesToFolder() = runBlocking {
        val vm = vm().toCredentials()
        vm.setGuest(false); vm.setUsername(" greg "); vm.setPassword("pw")
        vm.testConnection().join()
        assertEquals(SetupStep.FOLDER, vm.state.value.step)
        assertEquals(SmbConfig("192.168.50.1", "usb", "greg", "pw"), opened.single())
        assertEquals(SmbConfig("192.168.50.1", "usb", "greg", "pw"), settings.settings.first().smb)
    }

    @Test fun shareListingAsksForLoginWhenGuestRefused() = runBlocking {
        shareError = RemoteException.AuthFailed()
        val vm = vm()
        vm.selectHost("nas").join()
        assertTrue(vm.state.value.sharesNeedLogin)
        vm.setUsername("greg"); vm.setPassword("pw")
        vm.loadShares().join()
        assertEquals(listOf("usb"), vm.state.value.shares)
        vm.selectShare("usb")
        // The login typed for the share list is reused for the connection test.
        assertEquals(false, vm.state.value.guest)
        assertEquals("greg", vm.state.value.username)
    }

    @Test fun finishingSetupSavesSettingsAndSchedules() = runBlocking {
        val vm = vm().toCredentials()
        vm.setGuest(false); vm.setUsername("greg"); vm.setPassword("pw")
        vm.testConnection().join()
        vm.onFolderPicked("Phone backup")
        assertEquals(SetupStep.PERMISSIONS, vm.state.value.step)
        vm.onPermissionsDone()
        assertEquals(SetupStep.DONE, vm.state.value.step)
        assertTrue(backup.calls.isEmpty())
        vm.finish().join()

        val s = settings.settings.first()
        assertEquals(SmbConfig("192.168.50.1", "usb", "greg", "pw"), s.smb)
        assertEquals("Phone backup", s.destination)
        assertEquals(listOf("scheduleAll(false)", "runNow(false)"), backup.calls)
        assertTrue(vm.state.value.finished)
        assertEquals(Routes.GALLERY, Routes.start(s))
    }

    @Test fun finishDoesNotOverwriteLoginUpdatedSinceTest() = runBlocking {
        val vm = vm().toCredentials()
        vm.setGuest(false); vm.setUsername("greg"); vm.setPassword("pw")
        vm.testConnection().join()
        // The re-enter dialog changed the saved login after the test.
        settings.update { it.copy(smb = it.smb?.copy(password = "updated")) }
        vm.onFolderPicked("Phone backup")
        vm.onPermissionsDone()
        vm.finish().join()
        val s = settings.settings.first()
        assertEquals("updated", s.smb?.password)
        assertEquals("Phone backup", s.destination)
    }

    @Test fun uiStateToStringRedactsPassword() {
        val text = SetupUiState(password = "hunter2").toString()
        assertTrue(!text.contains("hunter2"))
        assertTrue(text.contains("password=***"))
    }

    @Test fun startRouteIsSetupUntilDriveAndDestinationSet() {
        assertEquals(Routes.SETUP, Routes.start(AppSettings(null, null)))
        assertEquals(Routes.SETUP, Routes.start(AppSettings(SmbConfig("h", "s", null, null), null)))
        assertEquals(Routes.GALLERY, Routes.start(AppSettings(SmbConfig("h", "s", null, null), "Photos")))
    }

    @Test fun permissionsDependOnSdk() {
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS),
            setupPermissions(34),
        )
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), setupPermissions(32))
    }
}
