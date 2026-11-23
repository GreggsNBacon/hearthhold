package dev.photobackup.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.app.BackupControl
import dev.photobackup.backup.BackupStatusSnapshot
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
    val status: BackupStatusSnapshot? = null,
    val waiting: Int = 0,
    val backedUp: Int = 0,
    val failed: Int = 0,
    val reach: Reachability = Reachability.UNKNOWN,
    val reachMessage: String? = null,
    val freeUpBytes: Long = 0,
) {
    /** A login is saved but its password can't be decrypted (e.g. after a phone restore). */
    val needsPassword: Boolean get() = settings?.smb?.let { it.username != null && it.password == null } ?: false
}

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
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var reachJob: Job? = null
    private var freeUpJob: Job? = null
    private var autoChecked = false

    init {
        viewModelScope.launch { settings.collect { s -> _state.update { it.copy(settings = s) } } }
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
}
