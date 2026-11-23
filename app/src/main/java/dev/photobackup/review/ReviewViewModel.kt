package dev.photobackup.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.freeup.formatSize
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignmentStore
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.ledger.ReviewDecision
import dev.photobackup.ledger.ReviewDecisionDao
import dev.photobackup.ledger.ReviewKeepDao
import dev.photobackup.media.FolderRules
import dev.photobackup.media.PhoneFolders
import dev.photobackup.media.PhoneMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

const val CONNECT_TO_FINISH_MESSAGE = "Connect to home Wi-Fi to finish"
const val KEEP_HIDE_DAYS = 180L
const val CHANGED_WHILE_FINISHING = "Changed by a backup while finishing — decision kept, try again"

/** An item Finish left as it was, with a short user-safe reason. [name] is the file name (or a count). */
data class LeftAlone(val name: String, val reason: String)

data class ReviewCard(val entry: LedgerEntry, val phone: PhoneMedia)

data class FinishSummary(val keep: Int, val driveOnly: Int, val junk: Int, val bytesToFree: Long) {
    val total: Int get() = keep + driveOnly + junk
}

sealed interface FinishState {
    data object Idle : FinishState
    data object Checking : FinishState
    data class NeedsConfirm(val request: Any?, val passedCount: Int, val failedCount: Int) : FinishState
    data object Applying : FinishState
    data class Done(val freedBytes: Long, val junkCount: Int, val keptCount: Int, val failedCount: Int = 0) : FinishState
    data class Error(val message: String) : FinishState
}

data class ReviewUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val current: ReviewCard? = null,
    val next: ReviewCard? = null,
    /** Cards left in the deck (including the current one). */
    val remaining: Int = 0,
    val summary: FinishSummary = FinishSummary(0, 0, 0, 0),
    val canUndo: Boolean = false,
    val folderFilter: String? = null,
    val finish: FinishState = FinishState.Idle,
    /** Items the last Finish left alone, for the result screen; cleared when the result is dismissed. */
    val leftAlone: List<LeftAlone> = emptyList(),
    /** Folders the deck can be narrowed to: phone folders (no trailing slash) and named folders, sorted. */
    val folderOptions: List<String> = emptyList(),
)

class ReviewViewModel(
    private val ledger: LedgerStore,
    private val queryPhone: suspend () -> List<PhoneMedia>,
    private val decisions: ReviewDecisionDao,
    private val keeps: ReviewKeepDao,
    private val assignments: FolderAssignmentStore,
    /** Named-folder rules (phone folder path -> folder name). */
    private val folderRules: suspend () -> Map<String, String>,
    /** The current gesture map; null = No action. Read on every swipe. */
    private val gestures: () -> Map<Swipe, ReviewAction?>,
    private val finisher: ReviewFinisher,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private sealed interface Step {
        class Decided(val mediaId: Long) : Step
        class Skipped(val mediaId: Long, val previousOrder: List<Long>) : Step
    }

    /** Serialises DB writes and refreshes. */
    private val lock = Mutex()

    /**
     * Changes made in memory whose DB write has not finished (null = delete). A refresh overlays these on what it
     * read, so a swipe made during a refresh is never lost. Touched only on the main thread.
     */
    private val pending = HashMap<Long, ReviewAction?>()

    private var eligible: List<ReviewCard> = emptyList() // oldest first
    private var entries: Map<Long, LedgerEntry> = emptyMap()
    private val decided = LinkedHashMap<Long, ReviewAction>()
    private var skipped: List<Long> = emptyList() // session only, in skip order
    private val history = ArrayDeque<Step>()
    private var folderFilter: String? = null
    private var folderOf: Map<Long, String?> = emptyMap() // mediaId -> named folder (assignment/rule)
    private var pinnedId: Long? = null
    private var prepared: PrepareResult.Ready? = null

    fun refresh() {
        viewModelScope.launch { lock.withLock { load() } }
    }

    private suspend fun load() {
        try {
            val phone = queryPhone().associateBy { it.mediaId }
            val verified = ledger.byStatus(BackupStatus.VERIFIED)
            val cutoff = clock() - KEEP_HIDE_DAYS * 86_400_000L
            val kept = keeps.since(cutoff).map { it.mediaId }.toSet()
            val assigned = assignments.all().associate { it.mediaId to it.folder }
            val rules = folderRules()
            val dec = decisions.all()
            decided.clear()
            dec.forEach { decided[it.mediaId] = it.action }
            for ((id, a) in pending) if (a == null) decided.remove(id) else decided[id] = a
            entries = verified.associateBy { it.mediaId }
            eligible = verified
                .filter { it.mediaId !in kept && LedgerRules.isSafeToRemove(it, phone[it.mediaId]) }
                .map { ReviewCard(it, phone.getValue(it.mediaId)) }
                .sortedWith(compareBy({ it.entry.dateTakenMs }, { it.entry.mediaId }))
            folderOf = eligible.associate {
                it.entry.mediaId to FolderRules.desired(it.phone.relativePath, assigned[it.entry.mediaId], rules)
            }
            // Drop undo steps that no longer match reality (e.g. decisions consumed by Finish).
            val open = eligible.map { it.entry.mediaId }.toSet()
            history.removeAll { step ->
                when (step) {
                    is Step.Decided -> step.mediaId !in decided
                    is Step.Skipped -> step.mediaId !in open || step.mediaId in decided
                }
            }
            skipped = skipped.filter { it in open }
            publish(error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publish(error = describe(e))
        }
    }

    /** User-safe message, never the exception text. Only Finish treats a network error as "connect to Wi-Fi". */
    private fun describe(e: Exception, finishing: Boolean = false): String = when {
        e is SecurityException -> "Photo access is needed"
        finishing && e is IOException -> CONNECT_TO_FINISH_MESSAGE
        else -> "Something went wrong"
    }

    fun setFolderFilter(filter: String?) {
        folderFilter = filter?.trim()?.ifEmpty { null }
        pinnedId = null
        publish()
    }

    private fun matchesFilter(c: ReviewCard): Boolean {
        val f = folderFilter ?: return true
        if (PhoneFolders.normalise(c.phone.relativePath).equals(PhoneFolders.normalise(f), ignoreCase = true)) return true
        val named = folderOf[c.entry.mediaId] ?: return false
        return FolderRules.same(named, f)
    }

    private fun deck(): List<ReviewCard> {
        val open = eligible.filter { it.entry.mediaId !in decided && matchesFilter(it) }
        val byId = open.associateBy { it.entry.mediaId }
        val back = skipped.mapNotNull { byId[it] }
        val backIds = back.map { it.entry.mediaId }.toSet()
        var order = open.filter { it.entry.mediaId !in backIds } + back
        pinnedId?.let { p ->
            val pinned = order.firstOrNull { it.entry.mediaId == p }
            if (pinned != null) order = listOf(pinned) + order.filter { it !== pinned }
        }
        return order
    }

    private fun summary(): FinishSummary {
        var keep = 0
        var drive = 0
        var junk = 0
        var bytes = 0L
        for ((id, action) in decided) {
            when (action) {
                ReviewAction.KEEP -> keep++
                ReviewAction.DRIVE_ONLY -> { drive++; bytes += entries[id]?.sizeBytes ?: 0 }
                ReviewAction.JUNK -> { junk++; bytes += entries[id]?.sizeBytes ?: 0 }
                ReviewAction.SKIP -> Unit
            }
        }
        return FinishSummary(keep, drive, junk, bytes)
    }

    private fun publish(error: String? = _state.value.error) {
        val d = deck()
        pinnedId = d.firstOrNull()?.entry?.mediaId
        _state.update {
            it.copy(
                loading = false, error = error, current = d.getOrNull(0), next = d.getOrNull(1),
                remaining = d.size, summary = summary(), canUndo = history.isNotEmpty() || decided.isNotEmpty(),
                folderFilter = folderFilter, folderOptions = folderOptions(),
            )
        }
    }

    private fun folderOptions(): List<String> {
        val all = eligible.map { PhoneFolders.normalise(it.phone.relativePath).trimEnd('/') }.filter { it.isNotEmpty() } +
            folderOf.values.filterNotNull()
        return all.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    fun finishSummary(): FinishSummary = summary()

    /** Applies the gesture for [dir] to the current card. No action: ignored, the card stays. */
    fun swipe(dir: Swipe) {
        if (_state.value.finish != FinishState.Idle) return
        val action = gestures()[dir] ?: return
        val card = deck().firstOrNull() ?: return
        val id = card.entry.mediaId
        if (action == ReviewAction.SKIP) {
            history.addLast(Step.Skipped(id, skipped))
            skipped = skipped.filter { it != id } + id
            pinnedId = null
            publish()
            return
        }
        val at = clock()
        val step = Step.Decided(id)
        history.addLast(step)
        decided[id] = action
        pending[id] = action
        pinnedId = null
        publish()
        viewModelScope.launch {
            try {
                lock.withLock { decisions.upsert(ReviewDecision(id, action, at)) }
                if (pending[id] == action) pending.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (pending[id] == action) {
                    pending.remove(id)
                    decided.remove(id)
                    history.remove(step)
                    pinnedId = id
                    publish(error = describe(e))
                }
            }
        }
    }

    /** Undoes the most recent swipe (repeatable). After a restart it pops the stored decisions, newest first. */
    fun undo() {
        if (_state.value.finish != FinishState.Idle) return
        when (val step = history.removeLastOrNull()) {
            is Step.Skipped -> {
                skipped = step.previousOrder
                pinnedId = step.mediaId
                publish()
            }
            is Step.Decided -> restore(step.mediaId)
            null -> {
                val last = decided.keys.lastOrNull() ?: return
                restore(last)
            }
        }
    }

    private fun restore(id: Long) {
        val prev = decided.remove(id)
        pending[id] = null
        pinnedId = id
        publish()
        viewModelScope.launch {
            try {
                lock.withLock { decisions.delete(id) }
                if (id in pending && pending[id] == null) pending.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (id in pending && pending[id] == null) {
                    pending.remove(id)
                    if (prev != null) decided[id] = prev
                    pinnedId = null
                    publish(error = describe(e))
                }
            }
        }
    }

    fun startFinish() {
        if (_state.value.finish != FinishState.Idle) return
        setFinish(FinishState.Checking)
        _state.update { it.copy(leftAlone = emptyList()) }
        viewModelScope.launch {
            try {
                val all = lock.withLock { decisions.all() }
                val r = finisher.prepare(all)
                when (r) {
                    is PrepareResult.NothingToDelete -> setLeftAlone(r.failed)
                    is PrepareResult.Ready -> setLeftAlone(r.failed)
                    PrepareResult.Offline -> Unit
                }
                when (r) {
                    PrepareResult.Offline -> setFinish(FinishState.Error(CONNECT_TO_FINISH_MESSAGE))
                    is PrepareResult.NothingToDelete -> {
                        if (r.keepOnlyApplied || r.failed.isEmpty()) {
                            setFinish(FinishState.Done(0, 0, r.keptCount, r.failed.size))
                            reload()
                        } else {
                            setFinish(FinishState.Error(failedMessage(r.failed.size)))
                            reload() // the finisher may have cleared decisions for gone or edited rows
                        }
                    }
                    is PrepareResult.Ready -> {
                        prepared = r
                        setFinish(FinishState.NeedsConfirm(r.request, r.passed.size, r.failed.size))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setFinish(FinishState.Error(describe(e, finishing = true)))
            }
        }
    }

    private fun failedMessage(n: Int) = "$n ${if (n == 1) "item" else "items"} could not be verified and were left alone"

    fun onDeleteConfirmed() {
        val p = prepared ?: return
        if (_state.value.finish !is FinishState.NeedsConfirm) return
        prepared = null
        setFinish(FinishState.Applying)
        viewModelScope.launch {
            try {
                when (val r = finisher.apply(p)) {
                    is ApplyResult.Done -> {
                        if (r.leftAlone > 0) {
                            val n = r.leftAlone
                            val more = LeftAlone("$n ${if (n == 1) "item" else "items"}", CHANGED_WHILE_FINISHING)
                            _state.update { it.copy(leftAlone = it.leftAlone + more) }
                        }
                        setFinish(FinishState.Done(r.freedBytes, r.junkCount, r.keptCount, p.failed.size + r.leftAlone))
                        reload()
                    }
                    is ApplyResult.Failed -> {
                        setFinish(FinishState.Error(r.message))
                        reload()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setFinish(FinishState.Error(describe(e, finishing = true)))
                reload()
            }
        }
    }

    /** The user dismissed the system delete dialog. Nothing changed; decisions stay. */
    fun onDeleteCancelled() {
        if (_state.value.finish !is FinishState.NeedsConfirm) return
        prepared = null
        setFinish(FinishState.Idle)
        _state.update { it.copy(leftAlone = emptyList()) }
    }

    /** Clears a Done or Error result so the deck is usable again. */
    fun dismissFinish() {
        val f = _state.value.finish
        if (f is FinishState.Done || f is FinishState.Error) {
            setFinish(FinishState.Idle)
            _state.update { it.copy(leftAlone = emptyList()) }
        }
    }

    /** Names come from the ledger; a row that is gone (or unreadable) shows as "An item". */
    private suspend fun setLeftAlone(failed: List<FailedRow>) {
        val list = failed.map { f ->
            val name = try {
                ledger.get(f.mediaId)?.displayName
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            LeftAlone(name ?: "An item", f.reason)
        }
        _state.update { it.copy(leftAlone = list) }
    }

    private fun setFinish(f: FinishState) = _state.update { it.copy(finish = f) }

    private suspend fun reload() = lock.withLock { load() }
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** The Finish question, e.g. "Free 1.8 GB: 42 drive only, 17 junk; 12 kept". Zero parts are left out. */
fun finishSummaryText(s: FinishSummary): String {
    val deletes = listOfNotNull(
        if (s.driveOnly > 0) "${s.driveOnly} drive only" else null,
        if (s.junk > 0) "${s.junk} junk" else null,
    ).joinToString(", ")
    val kept = if (s.keep > 0) "${s.keep} kept" else null
    return if (deletes.isEmpty()) kept.orEmpty()
    else "Free ${formatSize(s.bytesToFree)}: $deletes" + (kept?.let { "; $it" } ?: "")
}

/** The lines of the Finish result. */
fun doneLines(d: FinishState.Done): List<String> {
    val lines = listOfNotNull(
        if (d.freedBytes > 0) "Freed ${formatSize(d.freedBytes)} on the phone" else null,
        if (d.junkCount > 0) "${plural(d.junkCount, "junk item")} going to the drive bin" else null,
        if (d.keptCount > 0) "${d.keptCount} kept" else null,
        if (d.failedCount > 0) "${d.failedCount} left alone" else null,
    )
    return lines.ifEmpty { listOf("Nothing needed changing") }
}
