package dev.photobackup.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.discovery.FoundHost
import dev.photobackup.settings.CredentialCipher
import dev.photobackup.settings.SecondLocation
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.SmbConfig
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

private class XCipher : CredentialCipher {
    override fun encrypt(plain: String) = "x$plain"
    override fun decrypt(cipher: String) = cipher.removePrefix("x")
}

/** Setup in "second" mode: same discovery → share → login → folder steps, saved to `second` only. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecondSetupTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var settings: SettingsStore
    private val store = FakeRemoteStore()
    private val backup = FakeBackupControl()
    private val opened = mutableListOf<SmbConfig>()
    private val mainSmb = SmbConfig("192.168.50.1", "usb", "greg", "pw")
    private var statusCleared = 0

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ds = PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }
        settings = SettingsStore(ApplicationProvider.getApplicationContext(), XCipher(), ds)
        runBlocking { settings.update { it.copy(smb = mainSmb, destination = "Phone backup") } }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    /** Fake name lookup: "nas.local" is the main drive's IP; unknown names don't resolve. */
    private var addresses: Map<String, String> = mapOf("nas.local" to "192.168.50.1", "192.168.50.1" to "192.168.50.1",
        "pc.local" to "192.168.50.7")
    private var resolveFails = false
    private val looked = mutableListOf<String>()

    private fun vm() = SetupViewModel(
        settingsStore = settings,
        discover = { emptyFlow<FoundHost>() },
        listShares = { _, _, _ -> listOf("usb", "pcshare") },
        openStore = { cfg -> opened += cfg; store },
        backup = backup,
        mode = SetupMode.SECOND,
        clearSecondStatus = { statusCleared++ },
        resolve = { name -> looked += name; if (resolveFails) throw java.io.IOException("dns") else addresses[name.trim().lowercase()] },
    )

    private suspend fun SetupViewModel.loggedIn(host: String, share: String): SetupViewModel = apply {
        selectHost(host).join()
        selectShare(share)
        setGuest(false); setUsername("pc"); setPassword("pcpw")
        testConnection().join()
    }

    @Test fun testConnectionInSecondModeNeverTouchesTheMainLogin() = runBlocking {
        val vm = vm().loggedIn("pc.local", "pcshare")
        assertEquals(SetupStep.FOLDER, vm.state.value.step)
        assertEquals(listOf(SmbConfig("pc.local", "pcshare", "pc", "pcpw")), opened)
        val s = settings.settings.first()
        assertEquals(mainSmb, s.smb)
        assertNull(s.second) // nothing saved until the folder is picked and Finish is tapped
    }

    @Test fun refusesTheMainShareAndFolder() = runBlocking {
        val vm = vm().loggedIn("192.168.50.1", "usb")
        vm.onFolderPicked("Phone backup").join()
        assertEquals(SetupStep.FOLDER, vm.state.value.step)
        assertEquals(SetupViewModel.OVERLAPS_MAIN, vm.state.value.message)
        vm.finish().join()
        assertNull(settings.settings.first().second)
        assertFalse(vm.state.value.finished)
    }

    @Test fun overlapIgnoresCaseAndSlashes() = runBlocking {
        val vm = vm().loggedIn("192.168.50.1 ", "USB")
        vm.onFolderPicked("\\phone BACKUP/").join()
        assertEquals(SetupViewModel.OVERLAPS_MAIN, vm.state.value.message)
        assertTrue(overlapsMain(mainSmb, "Phone backup", SmbConfig("192.168.50.1", "/usb/", null, null), "phone backup"))
        assertTrue(overlapsMain(mainSmb, "A\\B", SmbConfig("192.168.50.1", "usb", null, null), "a//b\\"))
    }

    private val sameShare = SmbConfig("192.168.50.1", "usb", null, null)

    @Test fun exactChildAndParentOverlap() {
        assertTrue(overlapsMain(mainSmb, "Phone backup", sameShare, "Phone backup"))
        assertTrue(overlapsMain(mainSmb, "Phone backup", sameShare, "Phone backup\\Copy"))
        assertTrue(overlapsMain(mainSmb, "Phone backup", sameShare, "phone backup/copy/deeper"))
        assertTrue(overlapsMain(mainSmb, "Backups\\Phone", sameShare, "Backups"))
    }

    @Test fun shareRootOverlapsEverythingOnThatShare() {
        assertTrue(overlapsMain(mainSmb, "Phone backup", sameShare, ""))
        assertTrue(overlapsMain(mainSmb, "Phone backup", sameShare, "\\"))
        assertTrue(overlapsMain(mainSmb, "", sameShare, "Anything"))
        assertTrue(overlapsMain(mainSmb, null, sameShare, "Anything"))
    }

    @Test fun siblingsWithACommonPrefixAreAllowed() {
        assertFalse(overlapsMain(mainSmb, "Photos", sameShare, "Photos2"))
        assertFalse(overlapsMain(mainSmb, "Photos2", sameShare, "Photos"))
        assertFalse(overlapsMain(mainSmb, "Backups\\Phone", sameShare, "Backups\\Phone copy"))
        assertFalse(overlapsMain(mainSmb, "Phone backup", sameShare, "Copy"))
    }

    @Test fun differentHostOrShareIsAllowed() {
        assertFalse(overlapsMain(mainSmb, "Phone backup", SmbConfig("other", "usb", null, null), "Phone backup"))
        assertFalse(overlapsMain(mainSmb, "Phone backup", SmbConfig("192.168.50.1", "usb2", null, null), "Phone backup"))
        assertFalse(overlapsMain(mainSmb, "", SmbConfig("192.168.50.1", "usb2", null, null), ""))
    }

    @Test fun refusesTheMainShareByNameWhenItResolvesToTheMainIp() = runBlocking {
        val vm = vm().loggedIn("NAS.local", "usb")
        vm.onFolderPicked("Phone backup").join()
        assertEquals(SetupStep.FOLDER, vm.state.value.step)
        assertEquals(SetupViewModel.OVERLAPS_MAIN, vm.state.value.message)
    }

    @Test fun finishRefusesByIpToo() = runBlocking {
        val vm = vm().loggedIn("nas.local", "usb")
        vm.onFolderPicked("Archive").join()
        assertEquals(SetupStep.DONE, vm.state.value.step)
        settings.update { it.copy(destination = "Archive\\Phone backup") }
        vm.finish().join()
        assertNull(settings.settings.first().second)
        assertEquals(SetupViewModel.OVERLAPS_MAIN, vm.state.value.message)
    }

    @Test fun differentIpsAreNotTheMainDrive() = runBlocking {
        val vm = vm().loggedIn("pc.local", "usb")
        vm.onFolderPicked("Phone backup").join()
        assertNull(vm.state.value.message)
        assertEquals(SetupStep.DONE, vm.state.value.step)
    }

    @Test fun unresolvableNameFallsBackToText() = runBlocking {
        addresses = mapOf("192.168.50.1" to "192.168.50.1") // nas.local doesn't resolve
        val vm = vm().loggedIn("nas.local", "usb")
        vm.onFolderPicked("Phone backup").join()
        assertNull(vm.state.value.message) // names differ as text: allowed
        assertEquals(SetupStep.DONE, vm.state.value.step)
    }

    @Test fun failingResolverFallsBackToText() = runBlocking {
        resolveFails = true
        val byName = vm().loggedIn("nas.local", "usb")
        byName.onFolderPicked("Phone backup").join()
        assertEquals(SetupStep.DONE, byName.state.value.step)
        val sameText = vm().loggedIn("192.168.50.1", "usb")
        sameText.onFolderPicked("Phone backup").join()
        assertEquals(SetupViewModel.OVERLAPS_MAIN, sameText.state.value.message)
    }

    @Test fun noLookupWhenTheHostTextMatchesOrTheShareDiffers() = runBlocking {
        vm().loggedIn("192.168.50.1", "usb").onFolderPicked("Phone backup").join()
        vm().loggedIn("nas.local", "pcshare").onFolderPicked("Phone backup").join()
        assertTrue(looked.isEmpty())
    }

    @Test fun refusesAFolderInsideTheMainOne() = runBlocking {
        val vm = vm().loggedIn("192.168.50.1", "usb")
        vm.onFolderPicked("Phone backup\\Second").join()
        assertEquals(SetupStep.FOLDER, vm.state.value.step)
        assertEquals(SetupViewModel.OVERLAPS_MAIN, vm.state.value.message)
    }

    @Test fun refusesTheShareRoot() = runBlocking {
        val vm = vm().loggedIn("192.168.50.1", "usb")
        vm.onFolderPicked("").join()
        assertEquals(SetupViewModel.OVERLAPS_MAIN, vm.state.value.message)
    }

    @Test fun finishRechecksAgainstTheCurrentMainFolder() = runBlocking {
        val vm = vm().loggedIn("192.168.50.1", "usb")
        vm.onFolderPicked("Archive").join()
        assertEquals(SetupStep.DONE, vm.state.value.step)
        // The main folder moved inside the picked one meanwhile: Finish refuses too.
        settings.update { it.copy(destination = "Archive\\Phone backup") }
        vm.finish().join()
        assertNull(settings.settings.first().second)
        assertEquals(SetupViewModel.OVERLAPS_MAIN, vm.state.value.message)
        assertFalse(vm.state.value.finished)
        assertEquals(0, statusCleared) // nothing saved: the old status stays
    }

    @Test fun sameShareSiblingFolderIsAllowed() = runBlocking {
        val vm = vm().loggedIn("192.168.50.1", "usb")
        vm.onFolderPicked("Phone backup2").join()
        assertNull(vm.state.value.message)
        assertEquals(SetupStep.DONE, vm.state.value.step) // no permissions step in second mode
    }

    @Test fun finishSavesSecondOnlyAndStartsARun() = runBlocking {
        val vm = vm().loggedIn("pc.local", "pcshare")
        vm.onFolderPicked("Hearthhold copy").join()
        vm.finish().join()
        val s = settings.settings.first()
        assertEquals(SecondLocation(SmbConfig("pc.local", "pcshare", "pc", "pcpw"), "Hearthhold copy"), s.second)
        assertEquals(mainSmb, s.smb)
        assertEquals("Phone backup", s.destination)
        assertTrue(vm.state.value.finished)
        assertEquals(listOf("runNow(false)"), backup.calls) // no re-scheduling from the second setup
        assertEquals(1, statusCleared) // the old location's "Up to date" must not show for the new one
    }

    @Test fun finishStillSavesWhenTheStatusCantBeCleared() = runBlocking {
        val vm = SetupViewModel(
            settingsStore = settings, discover = { emptyFlow<FoundHost>() },
            listShares = { _, _, _ -> listOf("pcshare") }, openStore = { store }, backup = backup,
            mode = SetupMode.SECOND, clearSecondStatus = { throw java.io.IOException("datastore") },
        ).loggedIn("pc.local", "pcshare")
        vm.onFolderPicked("Hearthhold copy").join()
        vm.finish().join()
        assertEquals("Hearthhold copy", settings.settings.first().second?.destination)
        assertTrue(vm.state.value.finished)
        assertNull(vm.state.value.message)
    }

    @Test fun backFromDoneSkipsPermissions() = runBlocking {
        val vm = vm().loggedIn("pc.local", "pcshare")
        vm.onFolderPicked("Hearthhold copy").join()
        assertTrue(vm.back())
        assertEquals(SetupStep.FOLDER, vm.state.value.step)
    }

    @Test fun pickerStoreWithoutALoginFailsOnUseNotOnCreate() = runBlocking {
        val s = vm().pickerStore() // must not throw inside the screen's remember {}
        assertTrue(runCatching { s.list("") }.exceptionOrNull() is java.io.IOException)
        assertTrue(opened.isEmpty())
    }

    @Test fun pickerStoreUsesTheTypedLogin() = runBlocking {
        val vm = vm().loggedIn("pc.local", "pcshare")
        opened.clear()
        vm.pickerStore()
        assertEquals(listOf(SmbConfig("pc.local", "pcshare", "pc", "pcpw")), opened)
    }
}
