package dev.photobackup.restore

import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.photobackup.backup.SetupIncompleteException
import dev.photobackup.media.MediaAccess
import dev.photobackup.settings.SettingsUnreadableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreViewModelTest {
    private val mb = 1024L * 1024
    private val cam24 = RestoreGroupKey("Camera", "2024")
    private val cam23 = RestoreGroupKey("Camera", "2023")
    private val wa24 = RestoreGroupKey("WhatsApp", "2024")
    private var groups = listOf(
        RestoreGroup(cam24, 10, 100 * mb),
        RestoreGroup(cam23, 5, 50 * mb),
        RestoreGroup(wa24, 2, 20 * mb),
    )
    private var free = 10_000 * mb
    private var planError: Exception? = null
    private var plans = 0
    private val run = MutableStateFlow<RestoreRun>(RestoreRun.Idle)
    private val updates = MutableStateFlow<SavedRestore?>(null)
    private var saved: SavedRestore? = null
    private var access = MediaAccess.FULL
    private val started = mutableListOf<RestoreSelection>()
    private var cancels = 0

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = RestoreViewModel(
        plan = {
            plans++
            planError?.let { throw it }
            RestorePlan(groups, 0, 0, free, true)
        },
        run = run,
        loadSaved = { saved },
        savedUpdates = updates,
        start = { started += it },
        cancel = { cancels++ },
        photoAccess = { access },
    )

    @Test fun loads_groups_by_folder_with_nothing_ticked() = runTest {
        val s = vm().state.value
        assertFalse(s.loading)
        assertEquals(listOf("Camera", "WhatsApp"), s.folders.map { it.folder })
        assertEquals(listOf(cam24, cam23), s.folders[0].groups.map { it.key })
        assertEquals(0, s.selectedCount)
        assertFalse(s.canStart)
        assertEquals(RestoreUiState.NOTHING_TICKED, s.blockReason)
        assertFalse(s.empty)
    }

    @Test fun ticking_groups_sums_count_and_bytes() = runTest {
        val v = vm()
        v.toggle(cam24)
        v.toggle(wa24)
        val s = v.state.value
        assertEquals(12, s.selectedCount)
        assertEquals(120 * mb, s.selectedBytes)
        assertTrue(s.canStart)
        assertNull(s.blockReason)
        v.toggle(cam24)
        assertEquals(2, v.state.value.selectedCount)
    }

    @Test fun folder_tick_toggles_all_its_years() = runTest {
        val v = vm()
        v.toggleFolder("Camera")
        assertEquals(setOf(cam24, cam23), v.state.value.selected)
        assertEquals(FolderTick.ALL, v.state.value.folders[0].tick)
        v.toggle(cam23)
        assertEquals(FolderTick.SOME, v.state.value.folders[0].tick)
        v.toggleFolder("Camera") // partly ticked: ticks the rest
        assertEquals(setOf(cam24, cam23), v.state.value.selected)
        v.toggleFolder("Camera")
        assertEquals(emptySet<RestoreGroupKey>(), v.state.value.selected)
        assertEquals(FolderTick.NONE, v.state.value.folders[0].tick)
    }

    @Test fun everything_ticks_all_and_starts_with_everything() = runTest {
        val v = vm()
        v.setEverything(true)
        val s = v.state.value
        assertTrue(s.everything)
        assertEquals(17, s.selectedCount)
        assertEquals(170 * mb, s.selectedBytes)
        v.start()
        assertEquals(listOf(RestoreSelection.EVERYTHING), started)
    }

    @Test fun unticking_one_group_turns_everything_off_and_keeps_the_rest() = runTest {
        val v = vm()
        v.setEverything(true)
        v.toggle(wa24)
        val s = v.state.value
        assertFalse(s.everything)
        assertEquals(setOf(cam24, cam23), s.selected)
        v.start()
        assertEquals(listOf(RestoreSelection(setOf(cam24, cam23))), started)
    }

    @Test fun refuses_when_it_will_not_fit() = runTest {
        free = 300 * mb // 100 MB + 200 MB margin fits; 150 MB + margin doesn't
        val v = vm()
        v.toggle(cam24)
        assertTrue(v.state.value.canStart)
        v.toggle(cam23)
        val s = v.state.value
        assertFalse(s.fits)
        assertFalse(s.canStart)
        assertEquals(RestoreEngine.noRoom(150 * mb + RestoreEngine.MARGIN_BYTES, 300 * mb), s.blockReason)
        v.start()
        assertTrue(started.isEmpty())
    }

    @Test fun empty_state_when_nothing_is_drive_only() = runTest {
        groups = emptyList()
        val s = vm().state.value
        assertTrue(s.empty)
        assertEquals(RestoreUiState.EMPTY, "Nothing to restore — everything on the drive is also on this phone.")
    }

    @Test fun missing_photo_access_blocks_start() = runTest {
        access = MediaAccess.PARTIAL
        val v = vm()
        v.toggle(cam24)
        assertFalse(v.state.value.canStart)
        assertEquals(RestoreUiState.NO_ACCESS, v.state.value.blockReason)
        v.start()
        assertTrue(started.isEmpty())
        access = MediaAccess.FULL
        v.refresh()
        assertTrue(v.state.value.canStart)
    }

    @Test fun unreadable_settings_shows_retry_and_recovers() = runTest {
        planError = SettingsUnreadableException()
        val v = vm()
        assertTrue(v.state.value.settingsError)
        assertFalse(v.state.value.canStart)
        planError = null
        v.refresh()
        assertFalse(v.state.value.settingsError)
        assertEquals(3, v.state.value.folders.sumOf { it.groups.size })
    }

    @Test fun setup_and_other_errors_become_fixed_texts() = runTest {
        planError = SetupIncompleteException()
        assertEquals(RestoreUiState.SETUP, vm().state.value.error)
        planError = IOException("secret host details")
        val s = vm().state.value
        assertEquals(RestoreUiState.LOAD_FAILED, s.error)
        assertFalse(s.empty)
        assertFalse(s.canStart)
    }

    @Test fun progress_text_and_cancel() = runTest {
        val v = vm()
        run.value = RestoreRun.Queued
        assertEquals("Waiting for Wi-Fi…", v.state.value.progressText)
        run.value = RestoreRun.Running(0, 0)
        assertEquals("Starting restore…", v.state.value.progressText)
        run.value = RestoreRun.Running(12, 40)
        assertEquals("Restoring 12 of 40…", v.state.value.progressText)
        assertFalse(v.state.value.canStart)
        v.cancel()
        assertEquals(1, cancels)
    }

    @Test fun finished_run_reloads_the_list_and_shows_the_result() = runTest {
        val v = vm()
        run.value = RestoreRun.Running(1, 2)
        val before = plans
        saved = SavedRestore(RestoreResult(restored = 2), 1L)
        run.value = RestoreRun.Idle
        assertEquals(before + 1, plans)
        assertNull(v.state.value.progressText)
        assertEquals("Restored 2", v.state.value.last?.let { restoreSummary(it.result) })
    }

    @Test fun shows_the_last_saved_result_on_open_and_updates_live() = runTest {
        saved = SavedRestore(RestoreResult(restored = 3, failed = listOf(RestoreFailure("a.jpg", RestoreEngine.NO_GOOD_COPY))), 1L)
        val v = vm()
        assertEquals("Restored 3, 1 failed", restoreSummary(v.state.value.last!!.result))
        updates.value = SavedRestore(RestoreResult(restored = 5), 2L)
        assertEquals(5, v.state.value.last!!.result.restored)
    }

    @Test fun summary_counts_already_on_phone_separately_and_keeps_failures() {
        val r = RestoreResult(
            restored = 812,
            alreadyThere = 4,
            failed = listOf(RestoreFailure("a.jpg", RestoreEngine.NO_GOOD_COPY), RestoreFailure("b.mp4", RestoreEngine.PHONE_WRITE)),
        )
        assertEquals("Restored 812, 2 failed · 4 were already on the phone", restoreSummary(r))
        assertEquals("Restored 0", restoreSummary(RestoreResult()))
        assertEquals("Restored 0 · 1 was already on the phone", restoreSummary(RestoreResult(alreadyThere = 1)))
    }

    @Test fun run_state_from_work_info() {
        fun info(state: WorkInfo.State, done: Int = 0, total: Int = 0) = WorkInfo(
            UUID.randomUUID(), state, emptySet(),
            progress = workDataOf(RestoreScheduler.KEY_DONE to done, RestoreScheduler.KEY_TOTAL to total),
        )
        assertEquals(RestoreRun.Idle, restoreRunOf(emptyList()))
        assertEquals(RestoreRun.Idle, restoreRunOf(listOf(info(WorkInfo.State.SUCCEEDED))))
        assertEquals(RestoreRun.Queued, restoreRunOf(listOf(info(WorkInfo.State.ENQUEUED))))
        assertEquals(RestoreRun.Running(3, 9), restoreRunOf(listOf(info(WorkInfo.State.SUCCEEDED), info(WorkInfo.State.RUNNING, 3, 9))))
    }
}
