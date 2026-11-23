package dev.photobackup.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.backup.RemotePathPlanner
import dev.photobackup.ledger.FolderAssignment
import dev.photobackup.ledger.FolderAssignmentStore
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.FolderRules
import dev.photobackup.media.PartialMediaAccessException
import dev.photobackup.media.PhoneMedia
import dev.photobackup.ui.auth.DriveNotConfiguredException
import dev.photobackup.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Host and share of the saved drive, for building `smb://` thumbnail URIs. */
data class SmbRoot(val host: String, val share: String)

enum class DriveState {
    CHECKING, REACHABLE, UNREACHABLE, NOT_CONFIGURED,

    /** The saved password can't be decrypted: automatic checks are skipped (no surprise dialog); the user re-enters it. */
    NEEDS_PASSWORD,
}

/** Automatic drive checks (first show, on resume) run at most this often. */
const val AUTO_RECHECK_GAP_MS = 10_000L

enum class AutoCheck { CHECK, SKIP_BUSY, SKIP_PASSWORD, SKIP_RECENT }

/**
 * Whether an automatic (not user-asked) drive check should run: not while one is running, not while the saved
 * password is unreadable (that would pop the password dialog on every start), and not within [minGapMs] of the last
 * finished check. A clock that went backwards counts as "long ago". Pure.
 */
fun autoCheck(
    needsPassword: Boolean,
    checking: Boolean,
    lastDoneMs: Long?,
    nowMs: Long,
    minGapMs: Long = AUTO_RECHECK_GAP_MS,
): AutoCheck = when {
    checking -> AutoCheck.SKIP_BUSY
    needsPassword -> AutoCheck.SKIP_PASSWORD
    lastDoneMs != null && nowMs >= lastDoneMs && nowMs - lastDoneMs < minGapMs -> AutoCheck.SKIP_RECENT
    else -> AutoCheck.CHECK
}

data class GalleryUiState(
    val items: List<GalleryItem> = emptyList(),
    val loading: Boolean = true,
    val drive: DriveState = DriveState.CHECKING,
    /** No photo access: only drive items can be listed. */
    val phoneDenied: Boolean = false,
    /** Only "Select photos" access: the phone side (and backups) can't see everything. */
    val phonePartial: Boolean = false,
    /** Phone items still to back up; null until the phone list is known (or without photo access). */
    val waiting: Int? = null,
    /** Items flagged too big for this drive's format (FAT32). */
    val tooBig: Int = 0,
    val root: SmbRoot? = null,
    /** Why the drive is not reachable (friendly text), when [drive] is [DriveState.UNREACHABLE]. */
    val driveMessage: String? = null,
) {
    /** Drive-only items are greyed out ("Drive not reachable") unless the drive answered just now. */
    fun isAvailable(item: GalleryItem): Boolean = item.location != Location.DRIVE || drive == DriveState.REACHABLE
}

private const val SEND_FAILED = "Couldn't move those just now"

/**
 * The combined Gallery. The phone list is loaded on first show and on [refresh]; the ledger is observed, so backups
 * and removals show up live. The merge runs on [compute], never on the main thread. The drive check goes through the
 * auth prompt (a refused login re-prompts once; cancelling counts as not reachable). It runs automatically on first
 * show and on every [resume] (see [autoCheck]), and always on [refresh] (user-asked).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(
    private val queryPhone: suspend () -> List<PhoneMedia>,
    private val ledger: kotlinx.coroutines.flow.Flow<List<LedgerEntry>>,
    private val checkDrive: suspend () -> Unit,
    private val driveRoot: suspend () -> SmbRoot?,
    compute: CoroutineDispatcher = Dispatchers.Default,
    /** True when a login is saved but its password can't be decrypted. */
    private val needsPassword: suspend () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val assignments: FolderAssignmentStore? = null,
    /** Phone folder -> drive folder rules. */
    private val rules: suspend () -> Map<String, String> = { emptyMap() },
    /** Starts a backup run now (moves/uploads items into their new folder). */
    private val runNow: suspend () -> Unit = {},
    /** Drive destination and device name (the drive path prefix is `<destination>\<device>\`), or null. */
    private val root: suspend () -> Pair<String?, String>? = { null },
) : ViewModel() {
    private class Phone(
        val items: List<PhoneMedia>,
        val denied: Boolean = false,
        val partial: Boolean = false,
        val unreadable: Boolean = false,
    ) {
        /** The list is the whole phone library (so a "waiting" count from it is real). */
        val known: Boolean get() = !denied && !partial && !unreadable
    }

    private class Merged(val items: List<GalleryItem>, val counts: BackupCounts)

    private val phone = MutableStateFlow<Phone?>(null)
    private val extra = MutableStateFlow(GalleryUiState())

    private val merged = combine(phone, ledger) { p, l -> p to l }
        .mapLatest { (p, l) ->
            if (p == null) null else Merged(GalleryMerge.merge(p.items, l), GalleryMerge.counts(p.items, l))
        }
        .flowOn(compute)

    val state: StateFlow<GalleryUiState> = combine(merged, phone, extra) { m, p, x ->
        x.copy(
            items = m?.items ?: emptyList(),
            loading = m == null,
            phoneDenied = p?.denied ?: false,
            phonePartial = p?.partial ?: false,
            waiting = if (m != null && p?.known == true) m.counts.waiting else null,
            tooBig = m?.counts?.tooBig ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GalleryUiState())

    private var driveJob: Job? = null
    private var lastCheckDoneMs: Long? = null
    private var started = false

    /** First show: load once (coming back from the viewer keeps the list and the drive state). */
    fun start() {
        if (started) return
        started = true
        loadPhone()
        autoCheckDrive()
    }

    /**
     * Screen resumed (also on first show): re-check the drive, e.g. after fixing Wi-Fi or Settings, and reload the
     * phone list. Folders may have been added or removed in Settings, photo access granted, or photos taken; a
     * stale list would show newly backed-up items as "On drive only". The old list stays shown until the new one
     * arrives, and items keep their keys, so the grid keeps its scroll position.
     */
    fun resume() {
        if (!started) return start()
        loadPhone()
        autoCheckDrive()
    }

    /** User-asked: reload the phone list and check the drive now (may show the password dialog). */
    fun refresh() {
        started = true
        loadPhone()
        checkDriveNow()
    }

    private var phoneJob: Job? = null

    /** Latest request wins: an older, slower query never overwrites a newer list. */
    private fun loadPhone() {
        phoneJob?.cancel()
        phoneJob = viewModelScope.launch {
            phone.value = try {
                Phone(queryPhone())
            } catch (_: PartialMediaAccessException) {
                Phone(emptyList(), partial = true)
            } catch (_: SecurityException) {
                Phone(emptyList(), denied = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // MediaStore hiccup: show the drive side, retry on next refresh. The phone side is unknown.
                Phone(emptyList(), unreadable = true)
            }
        }
    }

    private fun autoCheckDrive() {
        val checking = driveJob?.isActive == true
        if (checking) return
        driveJob = viewModelScope.launch {
            val unreadable = try {
                needsPassword()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            when (autoCheck(unreadable, checking = false, lastCheckDoneMs, clock())) {
                AutoCheck.CHECK -> runCheck()
                AutoCheck.SKIP_PASSWORD -> {
                    loadRoot()
                    extra.update { it.copy(drive = DriveState.NEEDS_PASSWORD, driveMessage = null) }
                }
                AutoCheck.SKIP_BUSY, AutoCheck.SKIP_RECENT -> {}
            }
        }
    }

    private fun checkDriveNow() {
        driveJob?.cancel()
        driveJob = viewModelScope.launch { runCheck() }
    }

    private suspend fun loadRoot() {
        val root = try {
            driveRoot()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        extra.update { it.copy(root = root) }
    }

    private suspend fun runCheck() {
        extra.update { it.copy(drive = DriveState.CHECKING, driveMessage = null) }
        loadRoot()
        var message: String? = null
        val result = try {
            checkDrive()
            DriveState.REACHABLE
        } catch (e: CancellationException) {
            throw e
        } catch (_: DriveNotConfiguredException) {
            DriveState.NOT_CONFIGURED
        } catch (e: Exception) {
            message = userMessage(e)
            DriveState.UNREACHABLE
        }
        lastCheckDoneMs = clock()
        extra.update { it.copy(drive = result, driveMessage = message) }
    }

    private val _selection = MutableStateFlow<Set<String>>(emptySet())

    /** Keys of the selected items; non-empty means selection mode. */
    val selection: StateFlow<Set<String>> = _selection

    private val _notice = MutableStateFlow<String?>(null)

    /** A short message to show once (snackbar), e.g. "Moving 2 to Dog". */
    val notice: StateFlow<String?> = _notice

    fun noticeShown() { _notice.value = null }

    /** Long-press: enter selection mode with this item selected. */
    fun select(key: String) = _selection.update { it + key }

    fun toggle(key: String) = _selection.update { if (key in it) it - key else it + key }

    fun clearSelection() { _selection.value = emptySet() }

    /**
     * Puts the items into drive folder [folder] (null = back to the default: clears the assignment), clears the
     * selection and starts one backup run so the move happens now. A name that is blank or underscore-only is ignored.
     */
    fun sendTo(keys: List<String>, folder: String?): Job {
        val name = folder?.let { FolderRules.canonical(it) }
        if (folder != null && (name == null || name.all { it == '_' })) return Job().apply { complete() }
        val ids = keys.mapNotNull { it.removePrefix("media:").takeIf { _ -> it.startsWith("media:") }?.toLongOrNull() }
        val store = assignments
        if (store == null || ids.isEmpty()) return Job().apply { complete() }
        return viewModelScope.launch {
            try {
                for (id in ids) {
                    if (name == null) store.clear(id) else store.set(FolderAssignment(id, name))
                }
                clearSelection()
                runNow()
                _notice.value = if (name == null) "Back to default" else "Moving ${ids.size} to $name"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _notice.value = SEND_FAILED
            }
        }
    }

    /**
     * Folder names for the picker: drive folders seen in the ledger under `<destination>\<device>\`, then folders
     * already assigned, then rule folders; deduped ignoring case, first spelling wins. No drive access.
     */
    suspend fun folderNames(): List<String> {
        val out = ArrayList<String>()
        fun add(n: String?) {
            val c = n?.let { FolderRules.canonical(it) } ?: return
            if (c.all { it == '_' }) return
            if (out.none { FolderRules.same(it, c) }) out += c
        }
        val prefix = try {
            root()?.let { (dest, device) ->
                val d = (dest ?: "").replace('/', '\\').trim('\\')
                listOf(d, RemotePathPlanner.sanitise(device)).filter { it.isNotEmpty() }.joinToString("\\") + "\\"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (prefix != null) {
            for (e in ledger.first()) {
                val full = e.remotePath ?: continue
                if (!full.startsWith(prefix, ignoreCase = true)) continue
                val parts = full.substring(prefix.length).split('\\')
                if (parts.size == 3 && parts.none { it.isEmpty() }) add(parts[0])
            }
        }
        assignments?.all()?.sortedBy { it.mediaId }?.forEach { add(it.folder) }
        rules().values.sortedBy { it.lowercase() }.forEach { add(it) }
        return out
    }
}
