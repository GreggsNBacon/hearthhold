package dev.photobackup.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.app.BackupControl
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
)

class SettingsViewModel(
    private val store: SettingsStore,
    /** For testing newly typed drive details; the saved login is not involved. */
    private val openStore: (SmbConfig) -> RemoteStore,
    private val backup: BackupControl,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { store.settings.collect { s -> _state.update { it.copy(settings = s) } } }
    }

    private fun edit(t: (AppSettings) -> AppSettings): Job = viewModelScope.launch { store.update(t) }

    fun setChargingOnly(on: Boolean): Job = viewModelScope.launch {
        store.update { it.copy(chargingOnly = on) }
        backup.scheduleAll(on)
    }

    fun setDeviceName(name: String): Job {
        val n = name.trim()
        if (n.isEmpty() || n.any { it in INVALID_NAME_CHARS }) return Job().apply { complete() }
        return edit { it.copy(deviceName = n) }
    }

    fun setNotifyOnComplete(on: Boolean): Job = edit { it.copy(notifyOnComplete = on) }

    fun setFreeUpAge(months: Int): Job = edit { it.copy(freeUpAgeMonths = months.coerceIn(1, 36)) }

    fun setCamera(on: Boolean): Job = edit { it.copy(folders = it.folders.copy(camera = on)) }
    fun setScreenshots(on: Boolean): Job = edit { it.copy(folders = it.folders.copy(screenshots = on)) }

    /** Adds a phone folder like "Pictures/WhatsApp" (MediaStore relative path, trailing slash). */
    fun addFolder(path: String): Job {
        val p = path.trim().trim('/').takeIf { it.isNotEmpty() } ?: return Job().apply { complete() }
        return edit { it.copy(folders = it.folders.copy(extraRelativePaths = it.folders.extraRelativePaths + "$p/")) }
    }

    fun removeFolder(path: String): Job =
        edit { it.copy(folders = it.folders.copy(extraRelativePaths = it.folders.extraRelativePaths - path)) }

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
