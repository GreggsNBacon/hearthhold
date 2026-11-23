package dev.photobackup.ui.setup

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.app.BackupControl
import dev.photobackup.discovery.FoundHost
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import dev.photobackup.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStep { DISCOVER, SHARE, CREDENTIALS, FOLDER, PERMISSIONS, DONE }

data class SetupUiState(
    val step: SetupStep = SetupStep.DISCOVER,
    val hosts: List<FoundHost> = emptyList(),
    val searching: Boolean = false,
    val host: String? = null,
    val shares: List<String> = emptyList(),
    val loadingShares: Boolean = false,
    /** Guest may not list shares: ask for the login on the share step. */
    val sharesNeedLogin: Boolean = false,
    val share: String? = null,
    val guest: Boolean = true,
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val destination: String? = null,
    val finished: Boolean = false,
) {
    override fun toString() =
        "SetupUiState(step=$step, host=$host, share=$share, guest=$guest, username=$username, password=***, " +
            "destination=$destination, finished=$finished)"
}

/** Media read + notifications, as the platform names them for [sdk]. */
fun setupPermissions(sdk: Int): List<String> =
    if (sdk >= 34) {
        // USER_SELECTED is requested too, so "Select photos" shows up as partial access (see MediaAccess).
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.POST_NOTIFICATIONS)
    } else if (sdk >= 33) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** First run: find drive → share → login → folder → permissions → done. */
class SetupViewModel(
    private val settingsStore: SettingsStore,
    private val discover: () -> Flow<FoundHost>,
    private val listShares: suspend (host: String, user: String?, pass: String?) -> List<String>,
    private val openStore: (SmbConfig) -> RemoteStore,
    private val backup: BackupControl,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private var discoveryJob: Job? = null
    private var shareJob: Job? = null

    fun startDiscovery() {
        discoveryJob?.cancel()
        _state.update { it.copy(hosts = emptyList(), searching = true) }
        discoveryJob = viewModelScope.launch {
            try {
                discover().collect { h ->
                    _state.update { s -> s.copy(hosts = s.hosts.filter { it.address != h.address } + h) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // discovery is best effort; manual entry is always there
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
    }

    /** Picks a found host or a manually typed address and lists its shares. */
    fun selectHost(address: String): Job {
        discoveryJob?.cancel()
        _state.update {
            it.copy(host = address.trim(), step = SetupStep.SHARE, shares = emptyList(), share = null,
                sharesNeedLogin = false, message = null)
        }
        return loadShares()
    }

    fun loadShares(): Job {
        shareJob?.cancel()
        val s = _state.value
        val host = s.host ?: return Job().apply { complete() }
        val user = s.username.trim().takeIf { s.sharesNeedLogin && it.isNotEmpty() }
        val pass = if (user != null) s.password else null
        _state.update { it.copy(loadingShares = true, message = null) }
        return viewModelScope.launch {
            try {
                val shares = listShares(host, user, pass)
                _state.update { it.copy(shares = shares, message = if (shares.isEmpty()) "No shared folders found" else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteException.AuthFailed) {
                _state.update {
                    it.copy(sharesNeedLogin = true, message = if (user == null) "Log in to see the shared folders" else userMessage(e))
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = userMessage(e)) }
            } finally {
                _state.update { it.copy(loadingShares = false) }
            }
        }.also { shareJob = it }
    }

    fun selectShare(name: String) {
        val share = name.trim()
        if (share.isEmpty()) return
        _state.update {
            // The login typed for the share list is the drive's login.
            val guest = if (it.sharesNeedLogin) false else it.guest
            it.copy(share = share, guest = guest, step = SetupStep.CREDENTIALS, message = null)
        }
    }

    fun setGuest(guest: Boolean) = _state.update { it.copy(guest = guest, message = null) }
    fun setUsername(v: String) = _state.update { it.copy(username = v) }
    fun setPassword(v: String) = _state.update { it.copy(password = v) }

    private fun config(s: SetupUiState): SmbConfig? {
        val host = s.host ?: return null
        val share = s.share ?: return null
        return if (s.guest) SmbConfig(host, share, null, null)
        else SmbConfig(host, share, s.username.trim(), s.password)
    }

    /** Logs in to the share; on success saves the login (so later steps use it) and moves to the folder step. */
    fun testConnection(): Job {
        val cfg = config(_state.value) ?: return Job().apply { complete() }
        _state.update { it.copy(busy = true, message = null) }
        return viewModelScope.launch {
            try {
                openStore(cfg).use { it.connect() }
                settingsStore.update { it.copy(smb = cfg) }
                _state.update { it.copy(step = SetupStep.FOLDER) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = userMessage(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun onFolderPicked(path: String) =
        _state.update { it.copy(destination = path, step = SetupStep.PERMISSIONS, message = null) }

    fun onPermissionsDone() = _state.update { it.copy(step = SetupStep.DONE) }

    /** Saves drive + destination, schedules background backups and starts the first one. */
    fun finish(): Job {
        val s = _state.value
        val destination = s.destination
        // The login was saved at "Test connection" and may have been updated since; only the destination is written here.
        if (destination.isNullOrBlank()) return Job().apply { complete() }
        _state.update { it.copy(busy = true, message = null) }
        return viewModelScope.launch {
            try {
                settingsStore.update { it.copy(destination = destination) }
                val saved = settingsStore.settings.first()
                val chargingOnly = saved.chargingOnly
                backup.scheduleAll(chargingOnly, saved.overnightWindow())
                backup.runNow(chargingOnly)
                _state.update { it.copy(finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = userMessage(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Back one step; returns false on the first step. */
    fun back(): Boolean {
        val prev = when (_state.value.step) {
            SetupStep.DISCOVER -> return false
            SetupStep.SHARE -> SetupStep.DISCOVER
            SetupStep.CREDENTIALS -> SetupStep.SHARE
            SetupStep.FOLDER -> SetupStep.CREDENTIALS
            SetupStep.PERMISSIONS -> SetupStep.FOLDER
            SetupStep.DONE -> SetupStep.PERMISSIONS
        }
        _state.update { it.copy(step = prev, message = null) }
        if (prev == SetupStep.DISCOVER) startDiscovery()
        return true
    }
}
