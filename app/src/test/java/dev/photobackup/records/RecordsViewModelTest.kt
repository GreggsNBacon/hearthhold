package dev.photobackup.records

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.LocalDate

private class FakeActions : RecordsActions {
    val calls = mutableListOf<String>()
    var exportGate = CompletableDeferred<ExportResult>()
    var inspectGate = CompletableDeferred<InspectResult>()
    var applyGate = CompletableDeferred<ImportResult>()
    override suspend fun export(uri: String): ExportResult { calls += "export($uri)"; return exportGate.await() }
    override suspend fun inspect(uri: String): InspectResult { calls += "inspect($uri)"; return inspectGate.await() }
    override suspend fun apply(): ImportResult { calls += "apply"; return applyGate.await() }
    override fun discard() { calls += "discard" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordsViewModelTest {
    private val actions = FakeActions()
    private lateinit var vm: RecordsViewModel
    /** Stands in for the app scope: outlives the VM. */
    private lateinit var appScope: CoroutineScope
    private val restarts = mutableListOf<String>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        vm = RecordsViewModel(actions, appScope, restart = { restarts += "restart" }, today = { LocalDate.of(2026, 11, 23) })
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun suggestsTheDatedFileName() {
        assertEquals("hearthhold-records-20261123.hearthhold", vm.fileName())
    }

    @Test fun exportShowsWorkingThenDoneCount() {
        vm.export("content://doc/1")
        assertEquals(RecordsWork.EXPORTING, vm.state.value.working)
        actions.exportGate.complete(ExportResult.Done(812))
        assertNull(vm.state.value.working)
        assertEquals("Exported 812 items", vm.state.value.message)
    }

    @Test fun exportErrorShowsMessage() {
        vm.export("content://doc/1")
        actions.exportGate.complete(ExportResult.Failed(RecordsMessages.BUSY))
        assertNull(vm.state.value.working)
        assertEquals(RecordsMessages.BUSY, vm.state.value.message)
    }

    @Test fun exportThatThrowsShowsPlainMessage() {
        vm.export("content://doc/1")
        actions.exportGate.completeExceptionally(IllegalStateException("/storage/secret/path"))
        assertNull(vm.state.value.working)
        assertEquals(RecordsMessages.EXPORT_FAILED, vm.state.value.message)
    }

    @Test fun cancelledPickersDoNothing() {
        vm.export(null)
        vm.pickImport(null)
        assertEquals(emptyList<String>(), actions.calls)
        assertEquals(RecordsUiState(), vm.state.value)
    }

    @Test fun importChecksThenAsksToConfirm() {
        vm.pickImport("content://doc/2")
        assertEquals(RecordsWork.CHECKING, vm.state.value.working)
        actions.inspectGate.complete(InspectResult.Ready(40))
        assertNull(vm.state.value.working)
        assertEquals(40, vm.state.value.confirmItems)
        assertEquals(listOf("inspect(content://doc/2)"), actions.calls)
    }

    @Test fun rejectedFileShowsMessageAndNoConfirm() {
        vm.pickImport("content://doc/2")
        actions.inspectGate.complete(InspectResult.Rejected(RecordsMessages.NOT_RECORDS))
        assertNull(vm.state.value.confirmItems)
        assertEquals(RecordsMessages.NOT_RECORDS, vm.state.value.message)
    }

    @Test fun confirmImportsThenRestarts() {
        vm.pickImport("content://doc/2")
        actions.inspectGate.complete(InspectResult.Ready(40))
        vm.confirmImport()
        assertNull(vm.state.value.confirmItems)
        assertEquals(RecordsWork.IMPORTING, vm.state.value.working)
        assertFalse(vm.state.value.restart)
        actions.applyGate.complete(ImportResult.Done(38, 2, true))
        assertTrue(vm.state.value.restart)
        assertEquals(RecordsWork.IMPORTING, vm.state.value.working) // stays busy until the app restarts
        assertEquals(listOf("restart"), restarts)
    }

    @Test fun leavingTheScreenMidImportStillRestarts() {
        vm.pickImport("content://doc/2")
        actions.inspectGate.complete(InspectResult.Ready(40))
        vm.confirmImport()
        vm.viewModelScope.cancel() // Back: the VM is cleared while "Importing" shows
        actions.applyGate.complete(ImportResult.Done(40, 0, true))
        assertEquals(listOf("restart"), restarts)
    }

    @Test fun importWithoutSettingsStillRestartsWithNotice() {
        vm.pickImport("content://doc/2")
        actions.inspectGate.complete(InspectResult.Ready(40))
        vm.confirmImport()
        actions.applyGate.complete(ImportResult.Done(40, 0, false))
        assertTrue(vm.state.value.restart)
        assertEquals(RecordsMessages.SETTINGS_NOT_RESTORED, vm.state.value.message)
    }

    @Test fun failedImportShowsMessageAndDoesNotRestart() {
        vm.pickImport("content://doc/2")
        actions.inspectGate.complete(InspectResult.Ready(40))
        vm.confirmImport()
        actions.applyGate.complete(ImportResult.Failed(RecordsMessages.BUSY))
        assertFalse(vm.state.value.restart)
        assertEquals(emptyList<String>(), restarts)
        assertNull(vm.state.value.working)
        assertEquals(RecordsMessages.BUSY, vm.state.value.message)
    }

    @Test fun cancelConfirmDiscards() {
        vm.pickImport("content://doc/2")
        actions.inspectGate.complete(InspectResult.Ready(40))
        vm.cancelImport()
        assertNull(vm.state.value.confirmItems)
        assertEquals(listOf("inspect(content://doc/2)", "discard"), actions.calls)
    }

    @Test fun secondActionIgnoredWhileWorking() {
        vm.export("content://doc/1")
        vm.pickImport("content://doc/2")
        vm.export("content://doc/3")
        assertEquals(listOf("export(content://doc/1)"), actions.calls)
    }

    @Test fun messageShownClears() {
        vm.export("content://doc/1")
        actions.exportGate.complete(ExportResult.Done(1))
        vm.messageShown()
        assertNull(vm.state.value.message)
    }
}
