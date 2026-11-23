package dev.photobackup.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.app.BackupControl
import dev.photobackup.backup.BackupEngine
import dev.photobackup.backup.DrivePaths
import dev.photobackup.backup.RemotePathPlanner
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignment
import dev.photobackup.ledger.FolderAssignmentStore
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.media.FolderRules
import dev.photobackup.media.PartialMediaAccessException
import dev.photobackup.media.PhoneFolders
import dev.photobackup.review.ReviewAction
import dev.photobackup.review.Swipe
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import dev.photobackup.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: AppSettings? = null,
    val testing: Boolean = false,
    val driveMessage: String? = null,
    /** Phone folders with photos or videos, for the "Add folder" browser; null until loaded. */
    val phoneFolders: PhoneFolderList? = null,
    /** True while a new rule is being checked against the drive files it would move. */
    val ruleBusy: Boolean = false,
    /** A short fixed message after a rule could not be saved or removed (never exception text); null when none. */
    val ruleMessage: String? = null,
    /** Shown after a gesture change was refused (all four would be No action); cleared by the next change. */
    val gestureMessage: String? = null,
)

/** False when setting [swipe] to [action] would leave every swipe direction on No action. */
fun canSetGesture(current: Map<Swipe, ReviewAction?>, swipe: Swipe, action: ReviewAction?): Boolean =
    action != null || Swipe.values().any { it != swipe && current[it] != null }

sealed interface PhoneFolderList {
    data object Loading : PhoneFolderList
    /** Item counts per folder path ("Pictures/WhatsApp/"). */
    data class Loaded(val counts: Map<String, Int>) : PhoneFolderList
    data class Failed(val message: String) : PhoneFolderList
}

class SettingsViewModel(
    private val store: SettingsStore,
    /** For testing newly typed drive details; the saved login is not involved. */
    private val openStore: (SmbConfig) -> RemoteStore,
    private val backup: BackupControl,
    /** Item counts per phone folder from MediaStore. */
    private val phoneFolderCounts: suspend () -> Map<String, Int> = { emptyMap() },
    private val ledger: LedgerStore? = null,
    private val assignments: FolderAssignmentStore? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { store.settings.collect { s -> _state.update { it.copy(settings = s) } } }
    }

    private fun edit(t: (AppSettings) -> AppSettings): Job = viewModelScope.launch { store.update(t) }

    fun setChargingOnly(on: Boolean): Job = editAndReschedule { it.copy(chargingOnly = on) }

    fun setOvernightOnly(on: Boolean): Job = editAndReschedule { it.copy(overnightOnly = on) }

    /** Hours 0..23; anything else is ignored. Start may be after end (the window then spans midnight). */
    fun setOvernightHours(start: Int, end: Int): Job {
        if (start !in 0..23 || end !in 0..23) return Job().apply { complete() }
        return editAndReschedule { it.copy(overnightStartHour = start, overnightEndHour = end) }
    }

    /** Saves, then re-schedules background work (charging constraint and the overnight job) from the saved values. */
    private fun editAndReschedule(t: (AppSettings) -> AppSettings): Job = viewModelScope.launch {
        store.update(t)
        val s = store.settings.first()
        backup.scheduleAll(s.chargingOnly, s.overnightWindow())
    }

    fun setDeviceName(name: String): Job {
        val n = name.trim()
        if (n.isEmpty() || n.any { it in INVALID_NAME_CHARS }) return Job().apply { complete() }
        return edit { it.copy(deviceName = n) }
    }

    fun setNotifyOnComplete(on: Boolean): Job = edit { it.copy(notifyOnComplete = on) }

    fun setParallelUploads(n: Int): Job = edit { it.copy(parallelUploads = n.coerceIn(1, BackupEngine.MAX_PARALLEL)) }

    fun setFreeUpAge(months: Int): Job = edit { it.copy(freeUpAgeMonths = months.coerceIn(1, 36)) }

    /** Sets what swiping [swipe] does in review (null = No action). Refused if no direction would do anything. */
    fun setGesture(swipe: Swipe, action: ReviewAction?): Job = viewModelScope.launch {
        val current = store.settings.first().gestures
        if (!canSetGesture(current, swipe, action)) {
            _state.update { it.copy(gestureMessage = GESTURE_NEEDED) }
            return@launch
        }
        _state.update { it.copy(gestureMessage = null) }
        store.update { it.copy(gestures = Swipe.values().associateWith { s -> if (s == swipe) action else it.gestures[s] }) }
    }

    fun setCamera(on: Boolean): Job = edit { it.copy(folders = it.folders.copy(camera = on)) }
    fun setScreenshots(on: Boolean): Job = edit { it.copy(folders = it.folders.copy(screenshots = on)) }

    /** Adds a phone folder like "Pictures/WhatsApp" (MediaStore relative path, trailing slash). */
    fun addFolder(path: String): Job {
        val p = PhoneFolders.normalise(path).takeIf { it.isNotEmpty() } ?: return Job().apply { complete() }
        return edit { it.copy(folders = it.folders.copy(extraRelativePaths = it.folders.extraRelativePaths + p)) }
    }

    /** Loads (or reloads) the phone's folders that hold photos or videos. Never shows exception text. */
    fun loadPhoneFolders(): Job {
        _state.update { it.copy(phoneFolders = PhoneFolderList.Loading) }
        return viewModelScope.launch {
            val result = try {
                PhoneFolderList.Loaded(phoneFolderCounts())
            } catch (e: CancellationException) {
                throw e
            } catch (_: PartialMediaAccessException) {
                PhoneFolderList.Failed("Allow access to all photos to see every folder")
            } catch (_: SecurityException) {
                PhoneFolderList.Failed("Photo access is needed to list folders")
            } catch (_: Exception) {
                PhoneFolderList.Failed("Couldn't read the phone's folders")
            }
            _state.update { it.copy(phoneFolders = result) }
        }
    }

    fun removeFolder(path: String): Job =
        edit { it.copy(folders = it.folders.copy(extraRelativePaths = it.folders.extraRelativePaths - path)) }

    /** Null when [name] is usable as a drive folder name, else a message to show next to the field. */
    fun nameError(name: String): String? {
        if (FolderRules.isReserved(name)) return FolderRules.RESERVED_MESSAGE
        val c = FolderRules.canonical(name) ?: return "Enter a folder name"
        if (c.all { it == '_' }) return "That name can't be used"
        return null
    }

    /**
     * Existing drive files whose folder would change if the rule on [path] ended up naming [target] (null = the rule is
     * removed, so each file's default folder). Mirrors what the engine can move: no hand-picked folder, longest-prefix
     * match, and a path of today's `<destination>\<device>\<folder>\<year>\<name>` shape.
     */
    private suspend fun affectedRows(path: String, rules: Map<String, String>, target: String?, s: AppSettings): List<LedgerEntry> {
        val l = ledger ?: return emptyList()
        val key = PhoneFolders.normalise(path)
        val marked = rules.keys.map { PhoneFolders.normalise(it) }.filter { !it.equals(key, ignoreCase = true) }
            .associateWith { "other" } + (key to "this")
        val pinned = assignments?.all()?.mapTo(HashSet()) { it.mediaId }.orEmpty()
        return (l.byStatus(BackupStatus.VERIFIED) + l.byStatus(BackupStatus.REMOVED_FROM_PHONE)).filter { e ->
            if (e.mediaId in pinned || e.movingTo != null || e.partPath != null) return@filter false
            if (FolderRules.ruleFor(e.relativePath, marked) != "this") return@filter false
            val current = currentFolder(e, s) ?: return@filter false
            !FolderRules.same(current, target ?: RemotePathPlanner.sourceFolder(e.relativePath))
        }
    }

    /**
     * How many already-backed-up items would move if the rule on [path] named [name] ([name] null: the rule is being
     * removed). Used for the "also move them?" question.
     */
    suspend fun countOnDrive(path: String, name: String? = null): Int {
        val s = store.settings.first()
        return affectedRows(path, s.folderRules, name?.let { FolderRules.canonical(it) }, s).size
    }

    /** The folder segment of a drive path shaped `<destination>\<device>\<folder>\<year>\<name>`, else null. */
    private fun currentFolder(e: LedgerEntry, s: AppSettings): String? =
        DrivePaths.parse(e, DrivePaths.deviceRoot(s.destination, s.deviceName))?.folder

    /** Pins [rows] to their current folder, adding each pinned id to [pinned] so a later failure can undo them. */
    private suspend fun pin(rows: List<LedgerEntry>, s: AppSettings, pinned: MutableList<Long>) {
        val a = assignments ?: return
        for (r in rows) currentFolder(r, s)?.let {
            a.set(FolderAssignment(r.mediaId, it))
            pinned += r.mediaId
        }
    }

    /** Best effort: removes pins this call added (affected rows never had a pin before). */
    private suspend fun unpin(ids: List<Long>) {
        val a = assignments ?: return
        for (id in ids) {
            try {
                a.clear(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private suspend fun runNowFromSettings() = backup.runNow(store.settings.first().chargingOnly)

    fun ruleMessageShown() = _state.update { it.copy(ruleMessage = null) }

    private fun showRuleMessage(m: String) = _state.update { it.copy(ruleMessage = m) }

    /**
     * Runs a rule change: [work] pins first (so after a "No" a backup run can never move those files before their
     * pins exist), then writes the settings, and returns whether it changed anything. If [work] fails, the pins it
     * added are undone, so items are never left pinned without the rule change they belong to. Never throws (except
     * cancellation); shows [failMessage] instead. The run started afterwards ([runAfter]) is separate: the rule is
     * already saved by then, so its failure only shows a message.
     */
    private suspend fun ruleChange(
        failMessage: String,
        runAfter: Boolean,
        work: suspend (pinned: MutableList<Long>) -> Boolean,
    ) {
        val pinned = ArrayList<Long>()
        val changed = try {
            work(pinned)
        } catch (e: Exception) {
            withContext(NonCancellable) { unpin(pinned) }
            if (e is CancellationException) throw e
            showRuleMessage(failMessage)
            return
        }
        if (!changed || !runAfter) return
        try {
            runNowFromSettings()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            showRuleMessage(RUN_FAILED)
        }
    }

    /**
     * Saves a new rule when no existing drive file would move ([onAdded]); otherwise calls [onAsk] with how many
     * would, for the "also move them?" question. [ruleBusy] is set only while checking, and cleared on any outcome.
     */
    fun saveRule(path: String, name: String, onAdded: () -> Unit, onAsk: (Int) -> Unit): Job {
        if (_state.value.ruleBusy) return Job().apply { complete() }
        if (PhoneFolders.normalise(path).isEmpty()) {
            showRuleMessage(CHOOSE_FOLDER)
            return Job().apply { complete() }
        }
        if (nameError(name) != null) return Job().apply { complete() }
        _state.update { it.copy(ruleBusy = true, ruleMessage = null) }
        return viewModelScope.launch {
            try {
                val n = countOnDrive(path, name)
                if (n == 0) {
                    addRule(path, name, moveExisting = false).join()
                    if (_state.value.ruleMessage != SAVE_FAILED) onAdded()
                } else onAsk(n)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showRuleMessage(SAVE_FAILED)
            } finally {
                _state.update { it.copy(ruleBusy = false) }
            }
        }
    }

    /**
     * Adds (or replaces) a rule sending [path] to drive folder [name]. [moveExisting] false keeps the files already on
     * the drive where they are by pinning them; true leaves them to be moved by the next run, which starts now.
     */
    fun addRule(path: String, name: String, moveExisting: Boolean): Job {
        val key = PhoneFolders.normalise(path).takeIf { it.isNotEmpty() }
        val folder = FolderRules.canonical(name)
        if (key == null || folder == null || nameError(name) != null) return Job().apply { complete() }
        _state.update { it.copy(ruleMessage = null) }
        return viewModelScope.launch {
            ruleChange(SAVE_FAILED, runAfter = moveExisting) { pinned ->
                val s = store.settings.first()
                if (!moveExisting) pin(affectedRows(key, s.folderRules, folder, s), s, pinned)
                store.update { cur ->
                    cur.copy(folderRules = cur.folderRules.filterKeys { !it.equals(key, ignoreCase = true) } + (key to folder))
                }
                true
            }
        }
    }

    /** Removes the rule on [path]. [moveBack] false pins the covered files where they are; true runs a backup now. */
    fun removeRule(path: String, moveBack: Boolean): Job {
        _state.update { it.copy(ruleMessage = null) }
        return viewModelScope.launch {
            ruleChange(REMOVE_FAILED, runAfter = moveBack) { pinned ->
                val s = store.settings.first()
                val target = PhoneFolders.normalise(path)
                val keys = s.folderRules.keys.filter { PhoneFolders.normalise(it).equals(target, ignoreCase = true) }
                if (keys.isEmpty()) return@ruleChange false
                if (!moveBack) pin(affectedRows(path, s.folderRules, null, s), s, pinned)
                store.update { cur -> cur.copy(folderRules = cur.folderRules - keys.toSet()) }
                true
            }
        }
    }

    /**
     * The Remove button: removes the rule on [path] straight away when no drive file would move back, else calls
     * [onAsk] with how many would. Never throws; a failed check shows a message instead.
     */
    fun askRemoveRule(path: String, onAsk: (Int) -> Unit): Job {
        _state.update { it.copy(ruleMessage = null) }
        return viewModelScope.launch {
            val n = try {
                countOnDrive(path)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showRuleMessage(REMOVE_FAILED)
                return@launch
            }
            if (n == 0) removeRule(path, false).join() else onAsk(n)
        }
    }

    /** Only updates settings; the ledger is never touched. */
    fun setDestination(path: String): Job = edit { it.copy(destination = path) }

    /**
     * Tests the typed drive details and saves them only if the login works. A blank password with the same
     * username keeps the saved password.
     */
    fun saveDrive(host: String, share: String, guest: Boolean, username: String, password: String): Job {
        _state.update { it.copy(testing = true, driveMessage = null) }
        return viewModelScope.launch {
            try {
                val saved = store.settings.first().smb
                val user = username.trim()
                if (!guest && password.isEmpty() && saved?.username == user && saved.password == null) {
                    _state.update { it.copy(driveMessage = "Enter the drive password") }
                    return@launch
                }
                val cfg = when {
                    guest -> SmbConfig(host.trim(), share.trim(), null, null)
                    password.isEmpty() && saved?.username == user -> SmbConfig(host.trim(), share.trim(), user, saved.password)
                    else -> SmbConfig(host.trim(), share.trim(), user, password)
                }
                if (cfg.host.isEmpty() || cfg.share.isEmpty() || (!guest && user.isEmpty())) {
                    _state.update { it.copy(driveMessage = "Fill in the drive, shared folder and username") }
                    return@launch
                }
                openStore(cfg).use { it.connect() }
                store.update { it.copy(smb = cfg) }
                _state.update { it.copy(driveMessage = "Connected — saved") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(driveMessage = userMessage(e)) }
            } finally {
                _state.update { it.copy(testing = false) }
            }
        }
    }

    companion object {
        private const val INVALID_NAME_CHARS = "\\/:*?\"<>|"
        const val SAVE_FAILED = "Couldn't save the rule"
        const val REMOVE_FAILED = "Couldn't remove the rule"
        const val RUN_FAILED = "Rule saved — the move starts with the next backup"
        const val CHOOSE_FOLDER = "Choose a phone folder"
        const val GESTURE_NEEDED = "At least one swipe must do something"
    }
}
