package dev.photobackup.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.backup.MemoryAssignments
import dev.photobackup.backup.MemoryLedger
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignment
import dev.photobackup.ledger.FolderAssignmentStore
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.gallery.newFolderError
import dev.photobackup.media.FolderRules
import dev.photobackup.media.PartialMediaAccessException
import dev.photobackup.review.ReviewAction
import dev.photobackup.review.Swipe
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

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
    private lateinit var ds: DataStore<Preferences>
    private val store = FakeRemoteStore()
    private val backup = FakeBackupControl()
    private val opened = mutableListOf<SmbConfig>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ds = PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") }
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

    @Test fun notifyOnCompleteToggleIsSaved() = runBlocking {
        assertEquals(true, settings.settings.first().notifyOnComplete)
        vm().setNotifyOnComplete(false).join()
        assertEquals(false, settings.settings.first().notifyOnComplete)
    }

    @Test fun overnightToggleSavesAndReschedules() = runBlocking {
        val vm = vm()
        vm.setOvernightOnly(true).join()
        assertEquals(true, settings.settings.first().overnightOnly)
        vm.setOvernightHours(23, 5).join()
        vm.setOvernightHours(24, -1).join() // ignored: out of range
        val s = settings.settings.first()
        assertEquals(23, s.overnightStartHour)
        assertEquals(5, s.overnightEndHour)
        vm.setOvernightOnly(false).join()
        assertEquals(
            listOf("scheduleAll(false, night=1-6)", "scheduleAll(false, night=23-5)", "scheduleAll(false)"),
            backup.calls,
        )
    }

    @Test fun chargingToggleKeepsTheNightSchedule() = runBlocking {
        settings.update { it.copy(overnightOnly = true) }
        vm().setChargingOnly(true).join()
        assertEquals(listOf("scheduleAll(true, night=1-6)"), backup.calls)
    }

    @Test fun parallelUploadsIsClampedTo1To4() = runBlocking {
        val vm = vm()
        vm.setParallelUploads(3).join()
        assertEquals(3, settings.settings.first().parallelUploads)
        vm.setParallelUploads(7).join()
        assertEquals(4, settings.settings.first().parallelUploads)
        vm.setParallelUploads(0).join()
        assertEquals(1, settings.settings.first().parallelUploads)
    }

    @Test fun staleWarnDaysIsClampedTo1To30() = runBlocking {
        val vm = vm()
        vm.setStaleWarnDays(0).join()
        assertEquals(1, settings.settings.first().staleWarnDays)
        vm.setStaleWarnDays(99).join()
        assertEquals(30, settings.settings.first().staleWarnDays)
        vm.setStaleWarnDays(5).join()
        assertEquals(5, settings.settings.first().staleWarnDays)
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

    @Test fun addFolderStoresPickedFolderPathAsIs() = runBlocking {
        vm().addFolder("Pictures/WhatsApp/").join()
        assertEquals(setOf("Pictures/WhatsApp/"), settings.settings.first().folders.extraRelativePaths)
    }

    @Test fun loadPhoneFoldersPublishesCounts() = runBlocking {
        val vm = SettingsViewModel(settings, { store }, backup, { mapOf("Pictures/WhatsApp/" to 3) })
        vm.loadPhoneFolders().join()
        assertEquals(PhoneFolderList.Loaded(mapOf("Pictures/WhatsApp/" to 3)), vm.state.value.phoneFolders)
    }

    @Test fun loadPhoneFoldersPartialAccessAsksForFullAccess() = runBlocking {
        val vm = SettingsViewModel(settings, { store }, backup, { throw PartialMediaAccessException() })
        vm.loadPhoneFolders().join()
        assertEquals(
            PhoneFolderList.Failed("Allow access to all photos to see every folder"),
            vm.state.value.phoneFolders,
        )
    }

    @Test fun loadPhoneFoldersErrorNeverShowsExceptionText() = runBlocking {
        val vm = SettingsViewModel(settings, { store }, backup, { throw IllegalStateException("secret /path") })
        vm.loadPhoneFolders().join()
        assertEquals(PhoneFolderList.Failed("Couldn't read the phone's folders"), vm.state.value.phoneFolders)
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

    // ---- folder rules ----

    private val ledger = MemoryLedger()
    private val assigns = MemoryAssignments()

    private fun row(id: Long, rel: String, remote: String?, status: BackupStatus = BackupStatus.VERIFIED) = LedgerEntry(
        id, "content://media/$id", "$id.jpg", rel, 100, 1000, 10, false, "sha", remote, null, status, null, 0,
    )

    private fun ruleVm(): SettingsViewModel {
        runBlocking { settings.update { it.copy(deviceName = "Dev") } }
        return SettingsViewModel(settings, { opened += it; store }, backup, ledger = ledger, assignments = assigns)
    }

    private fun seedRows() = runBlocking {
        ledger.upsert(row(1, "Pictures/WhatsApp/", "Photos\\Dev\\WhatsApp\\2026\\1.jpg"))
        ledger.upsert(row(2, "Pictures/WhatsApp/Sent/", "Photos\\Dev\\Sent\\2026\\2.jpg", BackupStatus.REMOVED_FROM_PHONE))
        ledger.upsert(row(3, "DCIM/Camera/", "Photos\\Dev\\Camera\\2026\\3.jpg"))
        ledger.upsert(row(4, "Pictures/WhatsApp/", null, BackupStatus.PENDING))
    }

    @Test fun saveRuleWithNothingToMoveAddsAndClearsBusy() = runBlocking {
        val vm = ruleVm()
        var added = false
        var asked = -1
        vm.saveRule("Pictures/WhatsApp", "Family", { added = true }, { asked = it }).join()
        assertTrue(added)
        assertEquals(-1, asked)
        assertFalse(vm.state.value.ruleBusy)
        assertEquals(mapOf("Pictures/WhatsApp/" to "Family"), settings.settings.first().folderRules)
    }

    @Test fun saveRuleAsksWhenFilesWouldMoveAndClearsBusy() = runBlocking {
        seedRows()
        val vm = ruleVm()
        var asked = -1
        vm.saveRule("Pictures/WhatsApp", "Family", { }, { asked = it }).join()
        assertEquals(2, asked)
        assertFalse(vm.state.value.ruleBusy)
    }

    @Test fun saveRuleFailureClearsBusy() = runBlocking {
        runBlocking { settings.update { it.copy(deviceName = "Dev") } }
        val failing = object : FolderAssignmentStore by assigns {
            override suspend fun all(): List<FolderAssignment> = throw IllegalStateException("db")
        }
        val vm = SettingsViewModel(settings, { opened += it; store }, backup, ledger = ledger, assignments = failing)
        var called = false
        vm.saveRule("Pictures/WhatsApp", "Family", { called = true }, { called = true }).join()
        assertFalse(called)
        assertFalse(vm.state.value.ruleBusy)
        assertEquals(SettingsViewModel.SAVE_FAILED, vm.state.value.ruleMessage)
    }

    @Test fun addRuleWithoutMovingPinsExistingRows() = runBlocking {
        seedRows()
        val vm = ruleVm()
        assertEquals(2, vm.countOnDrive("Pictures/WhatsApp", "Family"))
        vm.addRule("Pictures/WhatsApp", "Family", moveExisting = false).join()
        assertEquals(mapOf("Pictures/WhatsApp/" to "Family"), settings.settings.first().folderRules)
        assertEquals(mapOf(1L to "WhatsApp", 2L to "Sent"), assigns.rows.value.mapValues { it.value.folder })
        assertEquals(emptyList<String>(), backup.calls)
    }

    @Test fun addRuleMovingPinsNothingAndRunsNow() = runBlocking {
        seedRows()
        val vm = ruleVm()
        vm.addRule("Pictures/WhatsApp/", "Family", moveExisting = true).join()
        assertEquals(emptyMap<Long, FolderAssignment>(), assigns.rows.value)
        assertEquals(listOf("runNow(false)"), backup.calls)
        assertEquals("Family", settings.settings.first().folderRules["Pictures/WhatsApp/"])
    }

    @Test fun removeRuleWithoutMovingBackPins() = runBlocking {
        seedRows()
        val vm = ruleVm()
        vm.addRule("Pictures/WhatsApp/", "Family", moveExisting = true).join()
        ledger.upsert(row(1, "Pictures/WhatsApp/", "Photos\\Dev\\Family\\2026\\1.jpg"))
        backup.calls.clear()
        vm.removeRule("Pictures/WhatsApp/", moveBack = false).join()
        assertEquals(emptyMap<String, String>(), settings.settings.first().folderRules)
        assertEquals("Family", assigns.rows.value[1L]?.folder)
        assertEquals(emptyList<String>(), backup.calls)
    }

    @Test fun removeRuleMovingBackRunsNow() = runBlocking {
        seedRows()
        val vm = ruleVm()
        vm.addRule("Pictures/WhatsApp/", "Family", moveExisting = false).join()
        assigns.rows.value = emptyMap()
        vm.removeRule("Pictures/WhatsApp/", moveBack = true).join()
        assertEquals(emptyMap<String, String>(), settings.settings.first().folderRules)
        assertEquals(emptyMap<Long, FolderAssignment>(), assigns.rows.value)
        assertEquals(listOf("runNow(false)"), backup.calls)
    }

    @Test fun addRuleReplacesRuleDifferingOnlyByCase() = runBlocking {
        val vm = ruleVm()
        vm.addRule("pictures/whatsapp/", "Old", moveExisting = false).join()
        vm.addRule("Pictures/WhatsApp/", "New", moveExisting = false).join()
        assertEquals(mapOf("Pictures/WhatsApp/" to "New"), settings.settings.first().folderRules)
    }

    @Test fun longestPrefixRuleKeepsItsOwnRows() = runBlocking {
        seedRows()
        val vm = ruleVm()
        vm.addRule("Pictures/WhatsApp/Sent/", "Out", moveExisting = true).join()
        assertEquals(1, vm.countOnDrive("Pictures/WhatsApp/", "Family")) // row 2 belongs to the deeper rule
    }

    @Test fun rowsWithAHandPickedFolderAreNotCounted() = runBlocking {
        seedRows()
        assigns.set(FolderAssignment(1, "Mine"))
        assertEquals(1, ruleVm().countOnDrive("Pictures/WhatsApp/", "Family"))
    }

    @Test fun badNamesAreRejected() = runBlocking {
        val vm = ruleVm()
        for (n in listOf("", "   ", "???", "/\\:")) {
            assertNotNull(n, vm.nameError(n))
            vm.addRule("Pictures/WhatsApp/", n, moveExisting = true).join()
        }
        assertEquals(emptyMap<String, String>(), settings.settings.first().folderRules)
        assertNull(vm.nameError("Family"))
    }

    @Test fun binRowsAreNotCountedOrPinned() = runBlocking {
        seedRows()
        ledger.upsert(row(5, "Pictures/WhatsApp/", "Photos\\Dev\\WhatsApp\\2026\\5.jpg", BackupStatus.REMOVED_FROM_PHONE).copy(binRequestedAt = 1))
        ledger.upsert(row(6, "Pictures/WhatsApp/", "Photos\\Dev\\.Bin\\WhatsApp\\2026\\6.jpg", BackupStatus.BINNED).copy(binRequestedAt = 1))
        ledger.upsert(row(7, "Pictures/WhatsApp/", "Photos\\Dev\\WhatsApp\\2026\\7.jpg", BackupStatus.BINNED).copy(binRequestedAt = 1))
        val vm = ruleVm()
        assertEquals(2, vm.countOnDrive("Pictures/WhatsApp/", "Family"))
        vm.addRule("Pictures/WhatsApp/", "Family", moveExisting = false).join()
        assertEquals(setOf(1L, 2L), assigns.rows.value.keys)
    }

    @Test fun rowsOutsideTheCurrentRootAreNotCountedOrPinned() = runBlocking {
        seedRows()
        ledger.upsert(row(5, "Pictures/WhatsApp/", "Old\\Dev\\WhatsApp\\2026\\5.jpg"))
        ledger.upsert(row(6, "Pictures/WhatsApp/", "Photos\\Dev\\WhatsApp\\07\\6.jpg")) // old YYYY/MM layout
        val vm = ruleVm()
        assertEquals(2, vm.countOnDrive("Pictures/WhatsApp/", "Family"))
        vm.addRule("Pictures/WhatsApp/", "Family", moveExisting = false).join()
        assertEquals(setOf(1L, 2L), assigns.rows.value.keys)
    }

    // --- Failures never crash and never leave pins without their rule ---

    /** A settings store over [ds] whose writes throw while [failWrites] is true (reads keep working). */
    private var failWrites = false

    private fun failingVm(
        assignments: FolderAssignmentStore = assigns,
        control: dev.photobackup.app.BackupControl = backup,
    ): Pair<SettingsViewModel, SettingsStore> {
        runBlocking { settings.update { it.copy(deviceName = "Dev") } }
        val wrapped = object : DataStore<Preferences> {
            override val data = ds.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failWrites) throw IOException("disk /secret/path")
                return ds.updateData(transform)
            }
        }
        val s = SettingsStore(ApplicationProvider.getApplicationContext(), PlainCipher(), wrapped)
        return SettingsViewModel(s, { opened += it; store }, control, ledger = ledger, assignments = assignments) to s
    }

    @Test fun addRuleSettingsWriteFailureUndoesPinsAndShowsMessage() = runBlocking {
        seedRows()
        val (vm, s) = failingVm()
        failWrites = true
        vm.addRule("Pictures/WhatsApp", "Family", moveExisting = false).join()
        assertEquals(emptyMap<String, String>(), s.settings.first().folderRules)
        assertEquals(emptyMap<Long, FolderAssignment>(), assigns.rows.value)
        assertEquals(SettingsViewModel.SAVE_FAILED, vm.state.value.ruleMessage)
        assertEquals(emptyList<String>(), backup.calls)
    }

    @Test fun addRulePinFailureSavesNoRuleAndUndoesEarlierPins() = runBlocking {
        seedRows()
        var sets = 0
        val failing = object : FolderAssignmentStore by assigns {
            override suspend fun set(a: FolderAssignment) {
                if (++sets == 2) throw IllegalStateException("db")
                assigns.set(a)
            }
        }
        val (vm, s) = failingVm(assignments = failing)
        vm.addRule("Pictures/WhatsApp", "Family", moveExisting = false).join()
        assertEquals(emptyMap<String, String>(), s.settings.first().folderRules)
        assertEquals(emptyMap<Long, FolderAssignment>(), assigns.rows.value)
        assertEquals(SettingsViewModel.SAVE_FAILED, vm.state.value.ruleMessage)
    }

    @Test fun addRuleYesWhenTheRunCannotStartKeepsTheRuleAndSaysSo() = runBlocking {
        val control = object : dev.photobackup.app.BackupControl {
            override fun scheduleAll(chargingOnly: Boolean, night: dev.photobackup.backup.OvernightWindow?) {}
            override fun runNow(chargingOnly: Boolean) = throw IllegalStateException("wm")
        }
        val (vm, s) = failingVm(control = control)
        vm.addRule("Pictures/WhatsApp", "Family", moveExisting = true).join()
        assertEquals(mapOf("Pictures/WhatsApp/" to "Family"), s.settings.first().folderRules)
        assertEquals(SettingsViewModel.RUN_FAILED, vm.state.value.ruleMessage)
    }

    @Test fun removeRuleSettingsWriteFailureKeepsRuleUndoesPinsAndShowsMessage() = runBlocking {
        seedRows()
        val (vm, s) = failingVm()
        vm.addRule("Pictures/WhatsApp/", "Family", moveExisting = true).join()
        ledger.upsert(row(1, "Pictures/WhatsApp/", "Photos\\Dev\\Family\\2026\\1.jpg"))
        failWrites = true
        vm.removeRule("Pictures/WhatsApp/", moveBack = false).join()
        assertEquals(mapOf("Pictures/WhatsApp/" to "Family"), s.settings.first().folderRules)
        assertEquals(emptyMap<Long, FolderAssignment>(), assigns.rows.value)
        assertEquals(SettingsViewModel.REMOVE_FAILED, vm.state.value.ruleMessage)
    }

    @Test fun askRemoveRuleRemovesAtOnceWhenNothingWouldMove() = runBlocking {
        val vm = ruleVm()
        vm.addRule("Pictures/WhatsApp/", "Family", moveExisting = true).join()
        var asked = -1
        vm.askRemoveRule("Pictures/WhatsApp/") { asked = it }.join()
        assertEquals(-1, asked)
        assertEquals(emptyMap<String, String>(), settings.settings.first().folderRules)
    }

    @Test fun askRemoveRuleAsksWhenFilesWouldMoveBack() = runBlocking {
        seedRows()
        val vm = ruleVm()
        vm.addRule("Pictures/WhatsApp/", "Family", moveExisting = true).join()
        ledger.upsert(row(1, "Pictures/WhatsApp/", "Photos\\Dev\\Family\\2026\\1.jpg"))
        var asked = -1
        vm.askRemoveRule("Pictures/WhatsApp/") { asked = it }.join()
        assertEquals(1, asked)
        assertEquals("Family", settings.settings.first().folderRules["Pictures/WhatsApp/"])
    }

    @Test fun askRemoveRuleCheckFailureShowsMessageAndKeepsRule() = runBlocking {
        runBlocking { settings.update { it.copy(deviceName = "Dev", folderRules = mapOf("Pictures/WhatsApp/" to "Family")) } }
        val failing = object : FolderAssignmentStore by assigns {
            override suspend fun all(): List<FolderAssignment> = throw IllegalStateException("db")
        }
        val vm = SettingsViewModel(settings, { opened += it; store }, backup, ledger = ledger, assignments = failing)
        var asked = -1
        vm.askRemoveRule("Pictures/WhatsApp/") { asked = it }.join()
        assertEquals(-1, asked)
        assertEquals(SettingsViewModel.REMOVE_FAILED, vm.state.value.ruleMessage)
        assertEquals("Family", settings.settings.first().folderRules["Pictures/WhatsApp/"])
        vm.ruleMessageShown()
        assertNull(vm.state.value.ruleMessage)
    }

    @Test fun saveRuleOnAnEmptyPathNeitherSavesNorCloses() = runBlocking {
        val vm = ruleVm()
        var called = false
        vm.saveRule("/", "Family", { called = true }, { called = true }).join()
        assertFalse(called)
        assertEquals(SettingsViewModel.CHOOSE_FOLDER, vm.state.value.ruleMessage)
        assertEquals(emptyMap<String, String>(), settings.settings.first().folderRules)
        assertFalse(vm.state.value.ruleBusy)
    }

    @Test fun saveRuleWhenTheSaveFailsDoesNotClose() = runBlocking {
        val (vm, s) = failingVm()
        failWrites = true
        var added = false
        vm.saveRule("Pictures/WhatsApp", "Family", { added = true }, { }).join()
        assertFalse(added)
        assertEquals(SettingsViewModel.SAVE_FAILED, vm.state.value.ruleMessage)
        assertEquals(emptyMap<String, String>(), s.settings.first().folderRules)
    }

    @Test fun rowsAlreadyInTheTargetFolderAreNotCounted() = runBlocking {
        seedRows()
        val vm = ruleVm()
        assertEquals(1, vm.countOnDrive("Pictures/WhatsApp/", "whatsapp")) // row 1 stays; row 2 (Sent) would move
    }

    @Test fun gestureChangeIsSaved() = runBlocking {
        vm().setGesture(Swipe.DOWN, null).join()
        assertEquals(null, settings.settings.first().gestures[Swipe.DOWN])
        vm().setGesture(Swipe.UP, ReviewAction.JUNK).join()
        assertEquals(ReviewAction.JUNK, settings.settings.first().gestures[Swipe.UP])
    }

    @Test fun lastActiveGestureCannotBeSetToNoAction() = runBlocking {
        val vm = vm()
        vm.setGesture(Swipe.UP, null).join(); vm.setGesture(Swipe.RIGHT, null).join(); vm.setGesture(Swipe.LEFT, null).join()
        assertTrue(canSetGesture(settings.settings.first().gestures, Swipe.LEFT, null))
        assertFalse(canSetGesture(settings.settings.first().gestures, Swipe.DOWN, null))
        vm.setGesture(Swipe.DOWN, null).join()
        assertEquals(ReviewAction.SKIP, settings.settings.first().gestures[Swipe.DOWN])
        assertEquals(SettingsViewModel.GESTURE_NEEDED, vm.state.value.gestureMessage)
        vm.setGesture(Swipe.UP, ReviewAction.KEEP).join()
        assertNull(vm.state.value.gestureMessage)
    }

    @Test fun binFolderNameIsReservedWithAClearMessage() {
        val vm = vm()
        for (n in listOf(".Bin", ".bin", " .BIN ")) assertEquals(FolderRules.RESERVED_MESSAGE, vm.nameError(n))
        assertEquals("\".Bin\" is reserved for the bin", FolderRules.RESERVED_MESSAGE)
        assertEquals(FolderRules.RESERVED_MESSAGE, newFolderError(".bIn"))
        assertNull(vm.nameError("Bin"))
    }

    @Test fun concurrentGestureChangesNeverLeaveAllNoAction() = runBlocking {
        val vm = vm()
        vm.setGesture(Swipe.UP, null).join(); vm.setGesture(Swipe.RIGHT, null).join()
        // Both checks would pass against the same starting settings; only one may win.
        val a = vm.setGesture(Swipe.LEFT, null); val b = vm.setGesture(Swipe.DOWN, null)
        a.join(); b.join()
        assertTrue(settings.settings.first().gestures.values.any { it != null })
        assertEquals(SettingsViewModel.GESTURE_NEEDED, vm.state.value.gestureMessage)
    }
}
