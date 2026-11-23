package dev.photobackup.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.app.BackupControl
import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.backup.SecondStatusText
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.settings.AppSettings
import dev.photobackup.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Reachability { UNKNOWN, CHECKING, YES, NO }

data class HomeUiState(
    val settings: AppSettings? = null,
    /** The settings could not be read (not the same as "not set up"): the screen offers Retry. */
    val settingsError: Boolean = false,
    val status: BackupStatusSnapshot? = null,
    val waiting: Int = 0,
    val backedUp: Int = 0,
    val failed: Int = 0,
    val reach: Reachability = Reachability.UNKNOWN,
    val reachMessage: String? = null,
    val freeUpBytes: Long = 0,
) {
    /** A login is saved but its password can't be decrypted (e.g. after a phone restore). */
    val needsPassword: Boolean get() = passwordUnreadable(settings)

    /** "Second location: …" for the status sheet; null when no second location is set (a stale status is hidden). */
    val secondLine: String? get() {
        if (settings?.second == null) return null
        return "Second location: " + (status?.secondStatus ?: "not copied yet")
    }

    /** Show "Re-enter password" for the second location: its login was refused, or its saved password can't be read. */
    val secondNeedsPassword: Boolean get() {
        val second = settings?.second ?: return false
        return status?.secondStatus == SecondStatusText.REENTER_PASSWORD ||
            (second.smb.username != null && second.smb.password == null)
    }
}

/** A login is saved but its password can't be decrypted (e.g. after a phone restore). Guest logins never need one. */
fun passwordUnreadable(s: AppSettings?): Boolean = s?.smb?.let { it.username != null && it.password == null } ?: false

class HomeViewModel(
    settings: Flow<AppSettings>,
    status: Flow<BackupStatusSnapshot>,
    counts: Flow<Map<BackupStatus, Int>>,
    /** Connect + log in to the saved drive through the auth prompt; throws on failure. */
    private val checkDrive: suspend () -> Unit,
    /** Opens the "Re-enter drive password" dialog; throws if cancelled. */
    private val reenterPassword: suspend () -> Unit,
    private val freeUpBytes: suspend (AppSettings) -> Long,
    private val backup: BackupControl,
    /** Opens the "Re-enter second location password" dialog; throws if cancelled. */
    private val reenterSecondPassword: suspend () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var reachJob: Job? = null
    private var freeUpJob: Job? = null
    private var autoChecked = false
    private val settingsSource = settings
    private var settingsJob: Job? = null

    init {
        collectSettings()
        viewModelScope.launch { status.collect { s -> _state.update { it.copy(status = s) } } }
        viewModelScope.launch {
            counts.collect { c ->
                _state.update {
                    it.copy(
                        waiting = c.n(BackupStatus.PENDING) + c.n(BackupStatus.UPLOADING),
                        backedUp = c.n(BackupStatus.VERIFIED) + c.n(BackupStatus.REMOVED_FROM_PHONE),
                        failed = c.n(BackupStatus.FAILED),
                    )
                }
            }
        }
    }

    private fun collectSettings() {
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            try {
                settingsSource.collect { s -> _state.update { it.copy(settings = s, settingsError = false) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreadable settings file: say so (Retry) instead of crashing or looking like "not set up".
                _state.update { it.copy(settings = null, settingsError = true) }
            }
        }
    }

    /** "Couldn't read settings — Retry". */
    fun retrySettings() {
        _state.update { it.copy(settingsError = false) }
        collectSettings()
    }

    private fun Map<BackupStatus, Int>.n(s: BackupStatus) = this[s] ?: 0

    private suspend fun currentSettings(): AppSettings = _state.map { it.settings }.filterNotNull().first()

    /**
     * Re-checks reachability (non-blocking) and the "Free up" size. [auto] (screen start/resume) checks the drive
     * only once per screen, and not while the saved password is unreadable (the banner asks instead), so a
     * cancelled password dialog does not pop up again on every resume. The Refresh button always checks.
     */
    fun refresh(auto: Boolean = false) {
        refreshFreeUp()
        if (reachJob?.isActive == true || (auto && autoChecked)) return
        reachJob = viewModelScope.launch {
            currentSettings()
            if (auto && _state.value.needsPassword) {
                _state.update { it.copy(reach = Reachability.UNKNOWN, reachMessage = null) }
                return@launch
            }
            if (auto) autoChecked = true
            _state.update { it.copy(reach = Reachability.CHECKING, reachMessage = null) }
            try {
                checkDrive()
                _state.update { it.copy(reach = Reachability.YES) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(reach = Reachability.NO, reachMessage = userMessage(e)) }
            }
        }
    }

    /**
     * Re-computes only the "Free up" size. The Gallery (main screen) checks the drive itself and its result drives
     * the status chip, so the status sheet uses this instead of [refresh] to avoid a second drive login.
     */
    fun refreshFreeUp() {
        freeUpJob?.cancel()
        freeUpJob = viewModelScope.launch {
            val bytes = try {
                freeUpBytes(currentSettings())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0L
            }
            _state.update { it.copy(freeUpBytes = bytes) }
        }
    }

    fun backUpNow() {
        viewModelScope.launch { backup.runNow(currentSettings().chargingOnly) }
    }

    /** Re-enter the drive password; on success runs [onDone] (default: [refresh]). A cancelled dialog does nothing. */
    fun reenter(onDone: () -> Unit = { refresh() }) {
        viewModelScope.launch {
            try {
                reenterPassword()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // cancelled: banner stays
            }
        }
    }

    /** Re-enter the second location's password; on success runs [onDone]. A cancelled dialog does nothing. */
    fun reenterSecond(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                reenterSecondPassword()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // cancelled or still refused: the status line stays
            }
        }
    }
}
