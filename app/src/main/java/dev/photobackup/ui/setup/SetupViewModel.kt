package dev.photobackup.ui.setup

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.app.BackupControl
import dev.photobackup.discovery.FoundHost
import dev.photobackup.settings.SecondLocation
import dev.photobackup.settings.SettingsStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import dev.photobackup.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

enum class SetupStep { DISCOVER, SHARE, CREDENTIALS, FOLDER, PERMISSIONS, DONE }

/** [MAIN]: first run, the main drive. [SECOND]: the optional second backup location, saved to `second` only. */
enum class SetupMode { MAIN, SECOND }

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

/**
 * First run: find drive → share → login → folder → permissions → done. In [SetupMode.SECOND] the same steps pick the
 * second backup location: nothing is saved until Finish, which writes `second` only (never the main login or
 * folder); the permissions step is skipped; the main share+folder is refused.
 */
class SetupViewModel(
    private val settingsStore: SettingsStore,
    private val discover: () -> Flow<FoundHost>,
    private val listShares: suspend (host: String, user: String?, pass: String?) -> List<String>,
    private val openStore: (SmbConfig) -> RemoteStore,
    private val backup: BackupControl,
    val mode: SetupMode = SetupMode.MAIN,
    /** Drops the stored second-location status line once a new second location is saved. */
    private val clearSecondStatus: suspend () -> Unit = {},
    /**
     * A host name or address → its IP address text, or null if it can't be resolved (production: [resolveHost]).
     * Lets the overlap check catch the main drive typed by name instead of IP. Failures fall back to comparing text.
     */
    private val resolve: suspend (String) -> String? = { null },
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
                if (mode == SetupMode.MAIN) settingsStore.update { it.copy(smb = cfg) }
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

    /** A store for the folder step, logged in with the typed login (second mode; the main mode uses the saved drive). */
    fun pickerStore(): RemoteStore = config(_state.value)?.let(openStore) ?: NoDriveStore

    fun onFolderPicked(path: String): Job {
        if (mode == SetupMode.MAIN) {
            _state.update { it.copy(destination = path, step = SetupStep.PERMISSIONS, message = null) }
            return Job().apply { complete() }
        }
        val cfg = config(_state.value) ?: return Job().apply { complete() }
        return viewModelScope.launch {
            val same = try {
                settingsStore.settings.first().let { s ->
                    s.smb != null && overlapsMain(s.smb, s.destination, cfg, path, sameAddress(s.smb.host, cfg.host))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(message = CHECK_FAILED) }
                return@launch
            }
            _state.update {
                if (same) it.copy(message = OVERLAPS_MAIN)
                else it.copy(destination = path, step = SetupStep.DONE, message = null)
            }
        }
    }

    fun onPermissionsDone() = _state.update { it.copy(step = SetupStep.DONE) }

    /** Saves drive + destination, schedules background backups and starts the first one. */
    fun finish(): Job {
        val s = _state.value
        val destination = s.destination
        // The login was saved at "Test connection" and may have been updated since; only the destination is written here.
        if (destination.isNullOrBlank()) return Job().apply { complete() }
        if (mode == SetupMode.SECOND) return finishSecond(destination)
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

    /** Saves the second location (re-checking it is not the main one) and starts a run so copying begins. */
    private fun finishSecond(destination: String): Job {
        val cfg = config(_state.value) ?: return Job().apply { complete() }
        _state.update { it.copy(busy = true, message = null) }
        return viewModelScope.launch {
            try {
                // Name lookups can't run inside the settings write: resolve against the main host read now, and use
                // that answer only if the main host is still the same when the write runs.
                val mainHost = settingsStore.settings.first().smb?.host
                val ipSame = mainHost != null && sameAddress(mainHost, cfg.host)
                var same = false
                settingsStore.update { cur ->
                    same = cur.smb != null &&
                        overlapsMain(cur.smb, cur.destination, cfg, destination, ipSame && cur.smb.host == mainHost)
                    if (same) cur else cur.copy(second = SecondLocation(cfg, destination))
                }
                if (same) {
                    _state.update { it.copy(message = OVERLAPS_MAIN, step = SetupStep.FOLDER) }
                    return@launch
                }
                try {
                    clearSecondStatus() // the old location's line must not show for the new one
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best effort; the first run's result replaces it.
                }
                try {
                    backup.runNow(settingsStore.settings.first().chargingOnly)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Saved; the next scheduled run starts the copying.
                }
                _state.update { it.copy(finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(message = SAVE_FAILED) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** True when both hosts resolve to the same address. False when either can't be resolved (text compare decides). */
    private suspend fun sameAddress(a: String, b: String): Boolean {
        val ra = safeResolve(a) ?: return false
        val rb = safeResolve(b) ?: return false
        return ra == rb
    }

    private suspend fun safeResolve(host: String): String? = try {
        resolve(host.trim())?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null // never shown or logged
    }

    /** Back one step; returns false on the first step. */
    fun back(): Boolean {
        val prev = when (_state.value.step) {
            SetupStep.DISCOVER -> return false
            SetupStep.SHARE -> SetupStep.DISCOVER
            SetupStep.CREDENTIALS -> SetupStep.SHARE
            SetupStep.FOLDER -> SetupStep.CREDENTIALS
            SetupStep.PERMISSIONS -> SetupStep.FOLDER
            SetupStep.DONE -> if (mode == SetupMode.SECOND) SetupStep.FOLDER else SetupStep.PERMISSIONS
        }
        _state.update { it.copy(step = prev, message = null) }
        if (prev == SetupStep.DISCOVER) startDiscovery()
        return true
    }

    companion object {
        const val OVERLAPS_MAIN = "That's the main backup location, or inside or around it — pick a different drive or folder"
        const val SAVE_FAILED = "Couldn't save the second location"
        const val CHECK_FAILED = "Couldn't check the folder — try again"
    }
}

/**
 * True when [cfg] + [folder] overlap the main drive's share and folder ([main] + [mainFolder]): same host and share,
 * and one folder equals, sits inside, or contains the other. Folders are compared whole segment by segment,
 * case-insensitively, with `/` and `\` treated alike and leading, trailing and doubled slashes ignored, so
 * `Photos` and `Photos2` are siblings, not nested. A blank folder is the share root, which contains everything on
 * that share. Hosts match when equal as text (case-insensitively) or when [sameAddress] says both resolve to the same
 * IP address (the caller resolves; false when either name doesn't resolve).
 */
fun overlapsMain(
    main: SmbConfig,
    mainFolder: String?,
    cfg: SmbConfig,
    folder: String,
    sameAddress: Boolean = false,
): Boolean {
    if (!sameAddress && !main.host.trim().equals(cfg.host.trim(), ignoreCase = true)) return false
    if (!segments(main.share).equals(segments(cfg.share))) return false
    val a = segments(mainFolder.orEmpty())
    val b = segments(folder)
    val n = minOf(a.size, b.size)
    return a.subList(0, n) == b.subList(0, n)
}

/**
 * Production resolver for [SetupViewModel]: the host's IP address text via [InetAddress], on [Dispatchers.IO] with a
 * short timeout; null on any failure (never logged).
 */
suspend fun resolveHost(host: String): String? = try {
    // The lookup itself ignores interrupts, so it runs detached and only the wait is bounded.
    val lookup = CoroutineScope(Dispatchers.IO).async { runCatching { InetAddress.getByName(host).hostAddress }.getOrNull() }
    withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { lookup.await() }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private const val RESOLVE_TIMEOUT_MS = 2_000L

/** Lower-cased path segments: `/` and `\` alike, blank segments (leading, trailing, doubled slashes) dropped. */
private fun segments(p: String): List<String> =
    p.replace('/', BACKSLASH).split(BACKSLASH).map { it.trim().lowercase() }.filter { it.isNotEmpty() }

/** The folder step's store before any login was typed (unreachable in practice): every call fails as not reachable. */
private object NoDriveStore : RemoteStore {
    private fun fail(): Nothing = throw RemoteException.Unreachable()
    override suspend fun connect() = fail()
    override suspend fun list(dir: String) = fail()
    override suspend fun stat(path: String) = fail()
    override suspend fun mkdirs(dir: String) = fail()
    override suspend fun openWrite(path: String) = fail()
    override suspend fun openRead(path: String, offset: Long) = fail()
    override suspend fun rename(from: String, to: String) = fail()
    override suspend fun delete(path: String) = fail()
    override suspend fun setTimes(path: String, modifiedMs: Long) = fail()
    override fun close() {}
}

private const val BACKSLASH = '\\'
