package dev.photobackup.review

import dev.photobackup.backup.MemoryLedger
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignment
import dev.photobackup.ledger.FolderAssignmentStore
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.ReviewDecision
import dev.photobackup.ledger.ReviewDecisionDao
import dev.photobackup.ledger.ReviewKeep
import dev.photobackup.ledger.ReviewKeepDao
import dev.photobackup.media.PhoneMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

private class FakeDecisions : ReviewDecisionDao {
    val rows = LinkedHashMap<Long, ReviewDecision>()
    var failUpsert = false
    override suspend fun upsert(d: ReviewDecision) {
        if (failUpsert) throw java.io.IOException("disk")
        rows[d.mediaId] = d
    }
    override suspend fun delete(mediaId: Long) { rows.remove(mediaId) }
    override suspend fun lastDecided() = rows.values.maxWithOrNull(compareBy({ it.decidedAt }, { it.mediaId }))
    override suspend fun all() = rows.values.sortedWith(compareBy({ it.decidedAt }, { it.mediaId }))
    override suspend fun clear(ids: List<Long>) { ids.forEach { rows.remove(it) } }
}

private class FakeKeeps : ReviewKeepDao {
    val rows = LinkedHashMap<Long, ReviewKeep>()
    override suspend fun upsert(k: ReviewKeep) { rows[k.mediaId] = k }
    override suspend fun get(mediaId: Long) = rows[mediaId]
    override suspend fun all() = rows.values.toList()
    override suspend fun since(cutoffMs: Long) = rows.values.filter { it.keptAt >= cutoffMs }
    override suspend fun clear(ids: List<Long>) { ids.forEach { rows.remove(it) } }
}

private class FakeAssignments : FolderAssignmentStore {
    val rows = LinkedHashMap<Long, FolderAssignment>()
    override suspend fun get(mediaId: Long) = rows[mediaId]
    override suspend fun all() = rows.values.toList()
    override suspend fun set(a: FolderAssignment) { rows[a.mediaId] = a }
    override suspend fun clear(mediaId: Long) { rows.remove(mediaId) }
    override fun observeAll(): Flow<List<FolderAssignment>> = emptyFlow()
}

private class FakeFinisher : ReviewFinisher {
    var prepareResult: PrepareResult = PrepareResult.Offline
    var applyResult: ApplyResult = ApplyResult.Done(0, 0, 0)
    var prepared: List<ReviewDecision>? = null
    var applied: PrepareResult.Ready? = null
    /** Runs inside prepare with its progress callback (to report progress and hold the check open). */
    var during: suspend ((Int, Int) -> Unit) -> Unit = {}
    override suspend fun prepare(decisions: List<ReviewDecision>, onProgress: (checked: Int, total: Int) -> Unit): PrepareResult {
        prepared = decisions; during(onProgress); return prepareResult
    }
    override suspend fun apply(prepared: PrepareResult.Ready): ApplyResult { applied = prepared; return applyResult }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private lateinit var ledger: MemoryLedger
    private lateinit var decisions: FakeDecisions
    private lateinit var keeps: FakeKeeps
    private lateinit var assignments: FakeAssignments
    private lateinit var finisher: FakeFinisher
    private val phone = LinkedHashMap<Long, PhoneMedia>()
    private var gestures: Map<Swipe, ReviewAction?> = DEFAULT_GESTURES
    private var rules: Map<String, String> = emptyMap()
    private var gate: CompletableDeferred<Unit>? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ledger = MemoryLedger(); decisions = FakeDecisions(); keeps = FakeKeeps(); assignments = FakeAssignments()
        finisher = FakeFinisher(); gate = null; phone.clear(); gestures = DEFAULT_GESTURES; rules = emptyMap()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    /** Adds a verified row and its unchanged phone copy; taken is the ordering key. */
    private suspend fun add(
        id: Long, taken: Long = id * 1000, path: String = "DCIM/Camera/", size: Long = 100,
        status: BackupStatus = BackupStatus.VERIFIED, onPhone: Boolean = true,
    ) {
        ledger.upsert(LedgerEntry(id, "content://m/$id", "f$id.jpg", path, size, taken, 10, false, "h$id",
            "r\\f$id.jpg", null, status, null, 0))
        if (onPhone) phone[id] = PhoneMedia(id, "content://m/$id", "f$id.jpg", path, size, taken, 0, 10, false, "image/jpeg")
    }

    private fun vm(clock: () -> Long = { now }) = ReviewViewModel(
        ledger = ledger, queryPhone = { gate?.await(); phone.values.toList() }, decisions = decisions, keeps = keeps,
        assignments = assignments, folderRules = { rules }, gestures = { gestures }, finisher = finisher, clock = clock,
    ).also { it.refresh() }

    private fun ReviewViewModel.cur() = state.value.current?.entry?.mediaId

    @Test fun deck_is_oldest_first_and_excludes_ineligible() = runTest {
        add(3, taken = 3000); add(1, taken = 1000); add(2, taken = 2000)
        add(4, status = BackupStatus.REMOVED_FROM_PHONE); add(5, status = BackupStatus.BINNED)
        add(6, onPhone = false)
        val v = vm()
        assertEquals(1L, v.cur()); assertEquals(2L, v.state.value.next?.entry?.mediaId); assertEquals(3, v.state.value.remaining)
    }

    @Test fun kept_within_180_days_hidden_and_older_keeps_return() = runTest {
        add(1); add(2); add(3)
        keeps.upsert(ReviewKeep(1, now - 179 * day)); keeps.upsert(ReviewKeep(2, now - 181 * day))
        val v = vm()
        assertEquals(2L, v.cur()); assertEquals(2, v.state.value.remaining)
    }

    @Test fun decided_cards_are_excluded_and_new_vm_sees_prior_decisions() = runTest {
        add(1); add(2); add(3)
        val v1 = vm()
        v1.swipe(Swipe.LEFT)
        assertEquals(ReviewAction.JUNK, decisions.rows[1]?.action)
        val v2 = vm() // simulates a restart
        assertEquals(2L, v2.cur()); assertEquals(1, v2.state.value.summary.junk); assertTrue(v2.state.value.canUndo)
    }

    @Test fun default_gestures_map_to_actions() = runTest {
        add(1); add(2); add(3)
        val v = vm()
        v.swipe(Swipe.UP); v.swipe(Swipe.RIGHT); v.swipe(Swipe.LEFT)
        assertEquals(ReviewAction.KEEP, decisions.rows[1]?.action)
        assertEquals(ReviewAction.DRIVE_ONLY, decisions.rows[2]?.action)
        assertEquals(ReviewAction.JUNK, decisions.rows[3]?.action)
        assertNull(v.cur())
    }

    @Test fun gestures_can_be_remapped() = runTest {
        add(1); add(2)
        gestures = mapOf(Swipe.UP to ReviewAction.JUNK, Swipe.RIGHT to ReviewAction.KEEP, Swipe.LEFT to null, Swipe.DOWN to null)
        val v = vm()
        v.swipe(Swipe.UP); v.swipe(Swipe.RIGHT)
        assertEquals(ReviewAction.JUNK, decisions.rows[1]?.action)
        assertEquals(ReviewAction.KEEP, decisions.rows[2]?.action)
    }

    @Test fun no_action_direction_is_ignored_and_card_stays() = runTest {
        add(1); add(2)
        gestures = mapOf(Swipe.UP to ReviewAction.KEEP, Swipe.RIGHT to null, Swipe.LEFT to null, Swipe.DOWN to null)
        val v = vm()
        v.swipe(Swipe.LEFT); v.swipe(Swipe.DOWN)
        assertEquals(1L, v.cur()); assertTrue(decisions.rows.isEmpty()); assertFalse(v.state.value.canUndo)
    }

    @Test fun skip_goes_to_back_and_is_not_persisted() = runTest {
        add(1); add(2); add(3)
        val v = vm()
        v.swipe(Swipe.DOWN)
        assertEquals(2L, v.cur()); assertEquals(3, v.state.value.remaining)
        v.swipe(Swipe.DOWN); v.swipe(Swipe.DOWN)
        assertEquals(1L, v.cur())
        assertTrue(decisions.rows.isEmpty())
        assertEquals(1L, vm().cur()) // new session: skips forgotten
    }

    @Test fun skipped_card_stays_decidable_when_alone() = runTest {
        add(1)
        val v = vm()
        v.swipe(Swipe.DOWN)
        assertEquals(1L, v.cur())
    }

    @Test fun undo_restores_card_and_deletes_decision_repeatably() = runTest {
        add(1); add(2); add(3)
        val v = vm()
        v.swipe(Swipe.UP); v.swipe(Swipe.LEFT)
        assertEquals(3L, v.cur())
        v.undo()
        assertEquals(2L, v.cur()); assertNull(decisions.rows[2]); assertEquals(1, decisions.rows.size)
        v.undo()
        assertEquals(1L, v.cur()); assertTrue(decisions.rows.isEmpty()); assertFalse(v.state.value.canUndo)
        v.undo() // nothing left; harmless
        assertEquals(1L, v.cur())
    }

    @Test fun undo_of_skip_returns_card_to_front() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.DOWN)
        assertEquals(2L, v.cur())
        v.undo()
        assertEquals(1L, v.cur())
    }

    @Test fun undo_after_restart_pops_stored_decisions_newest_first() = runTest {
        add(1); add(2); add(3)
        var t = now
        val v1 = vm { t++ }
        v1.swipe(Swipe.UP); v1.swipe(Swipe.UP)
        val v2 = vm { t++ }
        v2.undo()
        assertEquals(2L, v2.cur()); assertEquals(setOf(1L), decisions.rows.keys)
    }

    @Test fun refresh_keeps_current_card_and_session_skips() = runTest {
        add(2); add(3)
        val v = vm()
        v.swipe(Swipe.DOWN) // 3 now current, 2 skipped to back
        add(1, taken = 1) // an older item appears
        v.refresh()
        assertEquals(3L, v.cur())
        assertEquals(3, v.state.value.remaining)
        v.swipe(Swipe.UP)
        assertEquals(1L, v.cur()) // oldest unskipped before skipped 2
        v.swipe(Swipe.UP)
        assertEquals(2L, v.cur())
    }

    @Test fun folder_filter_by_phone_folder() = runTest {
        add(1, path = "DCIM/Camera/"); add(2, path = "Pictures/WhatsApp/"); add(3, path = "Pictures/WhatsApp/")
        val v = vm()
        v.setFolderFilter("Pictures/WhatsApp")
        assertEquals(2L, v.cur()); assertEquals(2, v.state.value.remaining)
        v.setFolderFilter(null)
        assertEquals(3, v.state.value.remaining)
    }

    @Test fun folder_filter_by_named_folder_uses_assignment_and_rules() = runTest {
        add(1, path = "DCIM/Camera/"); add(2, path = "Pictures/Memes/"); add(3, path = "Pictures/Other/")
        rules = mapOf("Pictures/Memes/" to "Funny")
        assignments.set(FolderAssignment(3, "funny"))
        val v = vm()
        v.setFolderFilter("Funny")
        assertEquals(2, v.state.value.remaining); assertEquals(2L, v.cur())
        v.swipe(Swipe.UP)
        assertEquals(3L, v.cur())
    }

    @Test fun summary_counts_and_bytes_to_free() = runTest {
        add(1, size = 10); add(2, size = 20); add(3, size = 40); add(4, size = 80)
        val v = vm()
        v.swipe(Swipe.UP); v.swipe(Swipe.RIGHT); v.swipe(Swipe.LEFT)
        assertEquals(FinishSummary(keep = 1, driveOnly = 1, junk = 1, bytesToFree = 60), v.finishSummary())
        assertEquals(v.finishSummary(), v.state.value.summary)
    }

    @Test fun phone_query_failure_shows_message_not_crash() = runTest {
        val v = ReviewViewModel(ledger, { throw SecurityException("secret") }, decisions, keeps, assignments,
            { rules }, { gestures }, finisher) { now }
        v.refresh()
        assertEquals("Photo access is needed", v.state.value.error); assertNull(v.cur())
    }

    @Test fun finish_offline_reports_connect_message_and_keeps_decisions() = runTest {
        add(1)
        val v = vm()
        v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.Offline
        v.startFinish()
        assertEquals(FinishState.Error(CONNECT_TO_FINISH_MESSAGE), v.state.value.finish)
        assertEquals(1, decisions.rows.size)
        assertEquals(listOf(1L), finisher.prepared?.map { it.mediaId })
    }

    @Test fun finish_checking_shows_progress() = runTest {
        add(1)
        val v = vm()
        v.swipe(Swipe.LEFT)
        val hold = CompletableDeferred<Unit>()
        finisher.during = { progress ->
            progress(0, 40)
            progress(12, 40)
            hold.await()
        }
        v.startFinish()
        assertEquals(FinishState.Checking(12, 40), v.state.value.finish)
        assertEquals("Checking 12 of 40…", checkingText(v.state.value.finish as FinishState.Checking))
        hold.complete(Unit)
        assertEquals(FinishState.Error(CONNECT_TO_FINISH_MESSAGE), v.state.value.finish)
    }

    @Test fun checking_text_before_the_count_is_known() {
        assertEquals("Checking the drive copies…", checkingText(FinishState.Checking()))
    }

    @Test fun finish_second_unreachable_reports_it_and_keeps_decisions() = runTest {
        add(1)
        val v = vm()
        v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.SecondUnreachable
        v.startFinish()
        assertEquals(FinishState.Error("Second location not reachable"), v.state.value.finish)
        assertEquals(1, decisions.rows.size)
    }

    @Test fun finish_ready_confirm_applies_and_refreshes() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.LEFT)
        val ready = PrepareResult.Ready("req", decisions.all(), listOf(FailedRow(9, "changed")))
        finisher.prepareResult = ready
        finisher.applyResult = ApplyResult.Done(100, 1, 0)
        v.startFinish()
        assertEquals(FinishState.NeedsConfirm("req", 1, 1), v.state.value.finish)
        // the real finisher consumes the decision and settles the row
        decisions.rows.clear(); ledger.upsert(ledger.get(1)!!.copy(status = BackupStatus.REMOVED_FROM_PHONE))
        v.onDeleteConfirmed()
        assertEquals(FinishState.Done(100, 1, 0, 1), v.state.value.finish)
        assertEquals(ready, finisher.applied)
        assertEquals(2L, v.cur()); assertEquals(0, v.state.value.summary.junk)
    }

    @Test fun finish_rows_left_alone_at_apply_count_as_failed() = runTest {
        add(1)
        val v = vm()
        v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.Ready("req", decisions.all(), listOf(FailedRow(9, "changed")))
        finisher.applyResult = ApplyResult.Done(0, 0, 0, leftAlone = 1)
        v.startFinish(); v.onDeleteConfirmed()
        assertEquals(FinishState.Done(0, 0, 0, 2), v.state.value.finish)
    }

    @Test fun finish_nothing_to_delete_with_failures_reloads_cleared_decisions() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.LEFT)
        assertEquals(2L, v.cur())
        finisher.prepareResult = PrepareResult.NothingToDelete(false, 0, listOf(FailedRow(1, "gone")))
        decisions.rows.clear() // the real finisher cleared the gone row's decision during prepare
        v.startFinish()
        assertEquals(FinishState.Error("1 item could not be verified and were left alone"), v.state.value.finish)
        assertEquals(0, v.state.value.summary.junk)
        assertEquals(2, v.state.value.remaining) // card 1 is back in the deck
    }

    /** NEW-3: rows applied at prepare (phone copy already gone) leave the summary even if the dialog is cancelled. */
    @Test fun finish_cancelled_reloads_rows_applied_at_prepare() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.LEFT); v.swipe(Swipe.LEFT)
        assertEquals(2, v.state.value.summary.junk)
        val all = decisions.all()
        finisher.prepareResult = PrepareResult.Ready("req", all.drop(1), emptyList(), alreadyGone = all.take(1))
        v.startFinish()
        decisions.rows.remove(1L) // the real finisher applied row 1 and cleared its decision during prepare
        v.onDeleteCancelled()
        assertEquals(FinishState.Idle, v.state.value.finish)
        assertEquals(1, v.state.value.summary.junk)
    }

    @Test fun finish_cancelled_returns_to_idle_without_applying() = runTest {
        add(1)
        val v = vm()
        v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.Ready("req", decisions.all(), emptyList())
        v.startFinish()
        v.onDeleteCancelled()
        assertEquals(FinishState.Idle, v.state.value.finish)
        assertNull(finisher.applied); assertEquals(1, decisions.rows.size)
        v.onDeleteConfirmed() // nothing pending: ignored
        assertNull(finisher.applied)
    }

    @Test fun finish_nothing_to_delete_is_done_and_apply_failure_is_error() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.UP)
        finisher.prepareResult = PrepareResult.NothingToDelete(keepOnlyApplied = true, keptCount = 1)
        v.startFinish()
        assertEquals(FinishState.Done(0, 0, 1, 0), v.state.value.finish)
        v.dismissFinish()
        v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.Ready("r", decisions.all(), emptyList())
        finisher.applyResult = ApplyResult.Failed("Delete was refused")
        v.startFinish(); v.onDeleteConfirmed()
        assertEquals(FinishState.Error("Delete was refused"), v.state.value.finish)
    }

    @Test fun swipes_ignored_while_finishing() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.Ready("r", decisions.all(), emptyList())
        v.startFinish()
        v.swipe(Swipe.UP); v.undo()
        assertEquals(1, decisions.rows.size); assertEquals(2L, v.cur())
    }

    @Test fun swipe_during_inflight_refresh_is_not_lost() = runTest {
        add(1); add(2)
        val v = vm()
        gate = CompletableDeferred()
        v.refresh() // suspends in the phone query
        v.swipe(Swipe.LEFT)
        gate!!.complete(Unit)
        assertEquals(2L, v.cur())
        assertEquals(ReviewAction.JUNK, decisions.rows[1]?.action)
        assertEquals(1, v.state.value.summary.junk)
    }

    @Test fun undo_is_disabled_after_finish_consumes_decisions() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.LEFT)
        assertTrue(v.state.value.canUndo)
        finisher.prepareResult = PrepareResult.Ready("req", decisions.all(), emptyList())
        finisher.applyResult = ApplyResult.Done(100, 1, 0)
        v.startFinish()
        decisions.rows.clear(); ledger.upsert(ledger.get(1)!!.copy(status = BackupStatus.REMOVED_FROM_PHONE))
        v.onDeleteConfirmed()
        assertFalse(v.state.value.canUndo)
        v.dismissFinish()
        v.undo()
        assertEquals(2L, v.cur()); assertTrue(decisions.rows.isEmpty())
    }

    @Test fun failed_write_rolls_back_card_and_undo_state() = runTest {
        add(1); add(2)
        val v = vm()
        decisions.failUpsert = true
        v.swipe(Swipe.LEFT)
        assertEquals(1L, v.cur()); assertFalse(v.state.value.canUndo)
        assertEquals("Something went wrong", v.state.value.error)
        assertEquals(0, v.state.value.summary.junk)
    }

    @Test fun deck_load_io_error_is_not_the_wifi_message() = runTest {
        val v = ReviewViewModel(ledger, { throw java.io.IOException("x") }, decisions, keeps, assignments,
            { rules }, { gestures }, finisher) { now }
        v.refresh()
        assertEquals("Something went wrong", v.state.value.error)
    }

    @Test fun changed_phone_file_is_excluded() = runTest {
        add(1); add(2)
        phone[1] = phone[1]!!.copy(dateModifiedSec = 99)
        assertEquals(2L, vm().cur())
    }

    @Test fun keep_boundary_is_exactly_180_days() = runTest {
        add(1); add(2)
        keeps.upsert(ReviewKeep(1, now - 180 * day)); keeps.upsert(ReviewKeep(2, now - 180 * day - 1))
        val v = vm()
        assertEquals(2L, v.cur()); assertEquals(1, v.state.value.remaining)
    }

    @Test fun phone_subfolder_is_not_matched_by_folder_filter() = runTest {
        add(1, path = "Pictures/"); add(2, path = "Pictures/Sub/")
        val v = vm()
        v.setFolderFilter("Pictures")
        assertEquals(1, v.state.value.remaining); assertEquals(1L, v.cur())
    }

    @Test fun finish_lists_left_alone_rows_with_names_and_reasons() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.LEFT); v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.Ready(
            "req", decisions.all().filter { it.mediaId == 2L }, listOf(FailedRow(1, "Phone file changed"), FailedRow(9, "gone")),
        )
        finisher.applyResult = ApplyResult.Done(100, 1, 0, leftAlone = 1)
        v.startFinish(); v.onDeleteConfirmed()
        assertEquals(
            listOf(LeftAlone("f1.jpg", "Phone file changed"), LeftAlone("An item", "gone"), LeftAlone("1 item", CHANGED_WHILE_FINISHING)),
            v.state.value.leftAlone,
        )
        v.dismissFinish()
        assertEquals(emptyList<LeftAlone>(), v.state.value.leftAlone)
    }

    /** I2: Junk already gone from the phone is applied at prepare; Finish reports it as done, even with failures. */
    @Test fun finish_already_gone_junk_counts_as_done() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.NothingToDelete(
            false, 0, listOf(FailedRow(2, "Not ready")), alreadyGone = listOf(ReviewDecision(1, ReviewAction.JUNK, 1)),
        )
        v.startFinish()
        assertEquals(FinishState.Done(0, 1, 0, 1), v.state.value.finish)
    }

    /** Confirmed rows that can never apply are not reported as "decision kept, try again". */
    @Test fun finish_dropped_rows_are_not_reported_as_kept() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.LEFT); v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.Ready("req", decisions.all(), emptyList())
        finisher.applyResult = ApplyResult.Done(0, 0, 0, leftAlone = 1, dropped = 1)
        v.startFinish(); v.onDeleteConfirmed()
        assertEquals(
            listOf(LeftAlone("1 item", CHANGED_WHILE_FINISHING), LeftAlone("1 item", DROPPED_WHILE_FINISHING)),
            v.state.value.leftAlone,
        )
        assertEquals(FinishState.Done(0, 0, 0, 2), v.state.value.finish)
    }

    @Test fun finish_nothing_to_delete_lists_left_alone_rows() = runTest {
        add(1)
        val v = vm()
        v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.NothingToDelete(false, 0, listOf(FailedRow(1, "Not ready")))
        v.startFinish()
        assertEquals(listOf(LeftAlone("f1.jpg", "Not ready")), v.state.value.leftAlone)
    }

    @Test fun finish_cancel_clears_left_alone() = runTest {
        add(1); add(2)
        val v = vm()
        v.swipe(Swipe.LEFT); v.swipe(Swipe.LEFT)
        finisher.prepareResult = PrepareResult.Ready("req", decisions.all().take(1), listOf(FailedRow(2, "x")))
        v.startFinish(); v.onDeleteCancelled()
        assertEquals(emptyList<LeftAlone>(), v.state.value.leftAlone)
    }

    @Test fun folder_options_list_phone_folders_and_named_folders() = runTest {
        add(1, path = "DCIM/Camera/"); add(2, path = "Pictures/WhatsApp/"); add(3, path = "Pictures/WhatsApp/")
        rules = mapOf("Pictures/WhatsApp/" to "Family")
        val v = vm()
        assertEquals(listOf("DCIM/Camera", "Family", "Pictures/WhatsApp"), v.state.value.folderOptions)
        v.setFolderFilter("Family")
        assertEquals(2, v.state.value.remaining)
        assertEquals(listOf("DCIM/Camera", "Family", "Pictures/WhatsApp"), v.state.value.folderOptions)
    }

    @Test fun finish_texts() {
        assertEquals("Free 3 KB: 2 drive only, 1 junk; 3 kept", finishSummaryText(FinishSummary(3, 2, 1, 3072)))
        assertEquals("1 kept", finishSummaryText(FinishSummary(1, 0, 0, 0)))
        assertEquals(
            listOf("Freed 2 KB on the phone", "1 junk item going to the drive bin", "2 kept", "1 left alone"),
            doneLines(FinishState.Done(2048, 1, 2, 1)),
        )
        assertEquals(listOf("Nothing needed changing"), doneLines(FinishState.Done(0, 0, 0)))
    }

    @Test fun deck_running_out_with_decisions_offers_finish_once() = runTest {
        add(1); add(2)
        val v = vm()
        assertFalse(v.state.value.offerFinish)
        v.swipe(Swipe.LEFT)
        assertFalse(v.state.value.offerFinish)
        v.swipe(Swipe.RIGHT)
        assertTrue(v.state.value.offerFinish)
        v.finishOfferShown()
        assertFalse(v.state.value.offerFinish)
        v.undo(); v.swipe(Swipe.RIGHT) // runs out again: not offered a second time
        assertFalse(v.state.value.offerFinish)
    }

    @Test fun empty_deck_without_decisions_offers_nothing() = runTest {
        val v = vm()
        assertFalse(v.state.value.offerFinish)
        add(1)
        v.refresh()
        v.swipe(Swipe.DOWN) // skip only: the card stays, nothing to finish
        assertFalse(v.state.value.offerFinish)
    }

    @Test fun screenStaysOnOnlyWhileFinishIsWorking() {
        assertTrue(keepsScreenOn(FinishState.Checking()))
        assertTrue(keepsScreenOn(FinishState.Checking(3, 40)))
        assertTrue(keepsScreenOn(FinishState.Applying))
        assertFalse(keepsScreenOn(FinishState.Idle))
        assertFalse(keepsScreenOn(FinishState.NeedsConfirm(null, 1, 0)))
        assertFalse(keepsScreenOn(FinishState.Done(0, 0, 0)))
        assertFalse(keepsScreenOn(FinishState.Error("x")))
    }
}
