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
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import dev.photobackup.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings? = null,
    val testing: Boolean = false,
    val driveMessage: String? = null,
    /** Phone folders with photos or videos, for the "Add folder" browser; null until loaded. */
    val phoneFolders: PhoneFolderList? = null,
)

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

    private suspend fun pin(rows: List<LedgerEntry>, s: AppSettings) {
        val a = assignments ?: return
        for (r in rows) currentFolder(r, s)?.let { a.set(FolderAssignment(r.mediaId, it)) }
    }

    private suspend fun runNowFromSettings() = backup.runNow(store.settings.first().chargingOnly)

    /**
     * Adds (or replaces) a rule sending [path] to drive folder [name]. [moveExisting] false keeps the files already on
     * the drive where they are by pinning them; true leaves them to be moved by the next run, which starts now.
     */
    fun addRule(path: String, name: String, moveExisting: Boolean): Job {
        val key = PhoneFolders.normalise(path).takeIf { it.isNotEmpty() }
        val folder = FolderRules.canonical(name)
        if (key == null || folder == null || nameError(name) != null) return Job().apply { complete() }
        return viewModelScope.launch {
            val s = store.settings.first()
            if (!moveExisting) pin(affectedRows(key, s.folderRules, folder, s), s)
            store.update { cur ->
                cur.copy(folderRules = cur.folderRules.filterKeys { !it.equals(key, ignoreCase = true) } + (key to folder))
            }
            if (moveExisting) runNowFromSettings()
        }
    }

    /** Removes the rule on [path]. [moveBack] false pins the covered files where they are; true runs a backup now. */
    fun removeRule(path: String, moveBack: Boolean): Job = viewModelScope.launch {
        val s = store.settings.first()
        val target = PhoneFolders.normalise(path)
        val keys = s.folderRules.keys.filter { PhoneFolders.normalise(it).equals(target, ignoreCase = true) }
        if (keys.isEmpty()) return@launch
        if (!moveBack) pin(affectedRows(path, s.folderRules, null, s), s)
        store.update { cur -> cur.copy(folderRules = cur.folderRules - keys.toSet()) }
        if (moveBack) runNowFromSettings()
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

    private companion object {
        const val INVALID_NAME_CHARS = "\\/:*?\"<>|"
    }
}
