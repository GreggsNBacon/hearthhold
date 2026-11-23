package dev.photobackup.restore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dev.photobackup.backup.SetupIncompleteException
import dev.photobackup.media.MediaAccess
import dev.photobackup.settings.SettingsUnreadableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The restore job as the screen sees it. */
sealed interface RestoreRun {
    data object Idle : RestoreRun

    /** Queued: waiting for Wi-Fi (or for WorkManager to start it). */
    data object Queued : RestoreRun

    /** [total] 0: started, not counted yet. */
    data class Running(val done: Int, val total: Int) : RestoreRun
}

/** The unique "restore" job's WorkInfos (from [RestoreScheduler.WORK]) as a [RestoreRun]. */
fun restoreRunOf(infos: List<WorkInfo>): RestoreRun {
    infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let {
        return RestoreRun.Running(
            it.progress.getInt(RestoreScheduler.KEY_DONE, 0),
            it.progress.getInt(RestoreScheduler.KEY_TOTAL, 0),
        )
    }
    return if (infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) {
        RestoreRun.Queued
    } else {
        RestoreRun.Idle
    }
}

/**
 * "Restored 812, 3 failed". Items that turned out to be on the phone already are not failures: they get a calm
 * "· 4 were already on the phone" instead.
 */
fun restoreSummary(r: RestoreResult): String {
    val main = if (r.failed.isEmpty()) "Restored ${r.restored}" else "Restored ${r.restored}, ${r.failed.size} failed"
    return when (r.alreadyThere) {
        0 -> main
        1 -> "$main · 1 was already on the phone"
        else -> "$main · ${r.alreadyThere} were already on the phone"
    }
}

enum class FolderTick { NONE, SOME, ALL }

/** One drive folder with its years (in the engine's order). */
data class RestoreFolder(val folder: String, val groups: List<RestoreGroup>, val tick: FolderTick) {
    val count: Int get() = groups.sumOf { it.count }
    val bytes: Long get() = groups.sumOf { it.bytes }
}

data class RestoreUiState(
    val loading: Boolean = true,
    /** "Couldn't read settings — Retry". */
    val settingsError: Boolean = false,
    /** A fixed text when the list couldn't be loaded ([SETUP], [LOAD_FAILED]); never exception text. */
    val error: String? = null,
    val groups: List<RestoreGroup> = emptyList(),
    val selected: Set<RestoreGroupKey> = emptySet(),
    val everything: Boolean = false,
    val freeBytes: Long = 0,
    val access: MediaAccess = MediaAccess.FULL,
    val run: RestoreRun = RestoreRun.Idle,
    /** The last finished restore (shown when idle). */
    val last: SavedRestore? = null,
    /** A one-off note (couldn't start); cleared by [RestoreViewModel.messageShown]. */
    val message: String? = null,
) {
    private val ready: Boolean get() = !loading && !settingsError && error == null

    /** Nothing drive-only: [EMPTY]. */
    val empty: Boolean get() = ready && groups.isEmpty()

    val folders: List<RestoreFolder>
        get() = groups.groupBy { it.key.folder }.map { (folder, gs) ->
            val ticked = gs.count { isTicked(it.key) }
            RestoreFolder(folder, gs, if (ticked == 0) FolderTick.NONE else if (ticked == gs.size) FolderTick.ALL else FolderTick.SOME)
        }

    fun isTicked(key: RestoreGroupKey) = everything || key in selected

    private val chosen: List<RestoreGroup> get() = groups.filter { isTicked(it.key) }
    val selectedCount: Int get() = chosen.sumOf { it.count }
    val selectedBytes: Long get() = chosen.sumOf { it.bytes }
    val fits: Boolean get() = selectedBytes + RestoreEngine.MARGIN_BYTES <= freeBytes

    val running: Boolean get() = run != RestoreRun.Idle

    /** Why Start is off (null = it can start). Not shown while a restore runs. */
    val blockReason: String?
        get() = when {
            !ready || groups.isEmpty() -> null
            selectedCount == 0 -> NOTHING_TICKED
            access != MediaAccess.FULL -> NO_ACCESS
            !fits -> RestoreEngine.noRoom(selectedBytes + RestoreEngine.MARGIN_BYTES, freeBytes)
            else -> null
        }

    val canStart: Boolean get() = ready && groups.isNotEmpty() && !running && blockReason == null

    val progressText: String?
        get() = when (val r = run) {
            RestoreRun.Idle -> null
            RestoreRun.Queued -> "Waiting for Wi-Fi…"
            is RestoreRun.Running -> if (r.total == 0) "Starting restore…" else "Restoring ${r.done} of ${r.total}…"
        }

    companion object {
        const val EMPTY = "Nothing to restore — everything on the drive is also on this phone."
        const val NOTHING_TICKED = "Tick what to restore"
        const val NO_ACCESS = "Allow access to all photos and videos first, so nothing is restored twice"
        const val SETUP = "Finish setup first"
        const val LOAD_FAILED = "Couldn't load the list — try again"
        const val START_FAILED = "Couldn't start the restore — try again"
    }
}

/**
 * The Restore screen: drive-only groups from [plan] (the engine's own grouping), tick boxes, Everything, total vs
 * phone free space, and the restore job's progress and last result. Start/cancel go to WorkManager (lambdas so
 * tests use fakes). Never throws to the UI.
 */
class RestoreViewModel(
    private val plan: suspend (RestoreSelection) -> RestorePlan,
    run: Flow<RestoreRun>,
    private val loadSaved: () -> SavedRestore?,
    savedUpdates: Flow<SavedRestore?>,
    private val start: (RestoreSelection) -> Unit,
    private val cancel: () -> Unit,
    private val photoAccess: () -> MediaAccess,
) : ViewModel() {
    private val _state = MutableStateFlow(RestoreUiState())
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(last = safeLoadSaved()) }
        refresh()
        viewModelScope.launch {
            run.catch { /* no job info: treat as idle */ }.collect { r ->
                val was = _state.value.run
                _state.update { it.copy(run = r) }
                if (was != RestoreRun.Idle && r == RestoreRun.Idle) {
                    // Finished: the list changed, and the summary was saved (maybe by another process).
                    safeLoadSaved()?.let { s -> _state.update { it.copy(last = s) } }
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            savedUpdates.catch { }.filterNotNull().collect { s -> _state.update { it.copy(last = s) } }
        }
    }

    private fun safeLoadSaved(): SavedRestore? = try {
        loadSaved()
    } catch (_: Exception) {
        null
    }

    /** Reloads the list, free space and photo access (also on resume and Retry). Keeps ticks that still exist. */
    fun refresh() {
        val access = try {
            photoAccess()
        } catch (_: Exception) {
            MediaAccess.NONE
        }
        // Spinner on first load and on Retry; a resume refresh keeps the list on screen.
        _state.update { it.copy(loading = it.loading || it.settingsError || it.error != null, access = access) }
        viewModelScope.launch {
            try {
                val p = plan(RestoreSelection.EVERYTHING)
                val keys = p.groups.map { it.key }.toSet()
                _state.update {
                    it.copy(
                        loading = false, settingsError = false, error = null,
                        groups = p.groups, freeBytes = p.freeBytes, selected = it.selected intersect keys,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SettingsUnreadableException) {
                _state.update { it.copy(loading = false, settingsError = true, error = null) }
            } catch (_: SetupIncompleteException) {
                _state.update { it.copy(loading = false, settingsError = false, error = RestoreUiState.SETUP) }
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, settingsError = false, error = RestoreUiState.LOAD_FAILED) }
            }
        }
    }

    fun toggle(key: RestoreGroupKey) = _state.update { s ->
        val current = if (s.everything) s.groups.map { it.key }.toSet() else s.selected
        s.copy(everything = false, selected = if (key in current) current - key else current + key)
    }

    /** A folder's tick: all its years on, unless all are on already (then off). */
    fun toggleFolder(folder: String) = _state.update { s ->
        val current = if (s.everything) s.groups.map { it.key }.toSet() else s.selected
        val keys = s.groups.filter { it.key.folder == folder }.map { it.key }.toSet()
        s.copy(everything = false, selected = if (current.containsAll(keys)) current - keys else current + keys)
    }

    fun setEverything(on: Boolean) = _state.update { s ->
        if (on) s.copy(everything = true) else s.copy(everything = false, selected = emptySet())
    }

    fun start() {
        val s = _state.value
        if (!s.canStart) return
        val selection = if (s.everything) RestoreSelection.EVERYTHING else RestoreSelection(s.selected)
        try {
            start.invoke(selection)
            _state.update { it.copy(run = if (it.run == RestoreRun.Idle) RestoreRun.Queued else it.run) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update { it.copy(message = RestoreUiState.START_FAILED) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun cancel() {
        try {
            cancel.invoke()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }
}
