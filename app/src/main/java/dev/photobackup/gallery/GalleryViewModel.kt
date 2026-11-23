package dev.photobackup.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.ledger.LedgerEntry
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
    val root: SmbRoot? = null,
    /** Why the drive is not reachable (friendly text), when [drive] is [DriveState.UNREACHABLE]. */
    val driveMessage: String? = null,
) {
    /** Drive-only items are greyed out ("Drive not reachable") unless the drive answered just now. */
    fun isAvailable(item: GalleryItem): Boolean = item.location != Location.DRIVE || drive == DriveState.REACHABLE
}

/**
 * The combined Gallery. The phone list is loaded on first show and on [refresh]; the ledger is observed, so backups
 * and removals show up live. The merge runs on [compute], never on the main thread. The drive check goes through the
 * auth prompt (a refused login re-prompts once; cancelling counts as not reachable). It runs automatically on first
 * show and on every [resume] (see [autoCheck]), and always on [refresh] (user-asked).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(
    private val queryPhone: suspend () -> List<PhoneMedia>,
    ledger: kotlinx.coroutines.flow.Flow<List<LedgerEntry>>,
    private val checkDrive: suspend () -> Unit,
    private val driveRoot: suspend () -> SmbRoot?,
    compute: CoroutineDispatcher = Dispatchers.Default,
    /** True when a login is saved but its password can't be decrypted. */
    private val needsPassword: suspend () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private class Phone(val items: List<PhoneMedia>, val denied: Boolean)

    private val phone = MutableStateFlow<Phone?>(null)
    private val extra = MutableStateFlow(GalleryUiState())

    private val merged = combine(phone, ledger) { p, l -> p to l }
        .mapLatest { (p, l) -> if (p == null) null else GalleryMerge.merge(p.items, l) }
        .flowOn(compute)

    val state: StateFlow<GalleryUiState> = combine(merged, phone, extra) { items, p, x ->
        x.copy(items = items ?: emptyList(), loading = items == null, phoneDenied = p?.denied ?: false)
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

    /** Screen resumed (also on first show): re-check the drive, e.g. after fixing Wi-Fi or Settings. */
    fun resume() {
        if (!started) start() else autoCheckDrive()
    }

    /** User-asked: reload the phone list and check the drive now (may show the password dialog). */
    fun refresh() {
        started = true
        loadPhone()
        checkDriveNow()
    }

    private fun loadPhone() {
        viewModelScope.launch {
            phone.value = try {
                Phone(queryPhone(), denied = false)
            } catch (_: SecurityException) {
                Phone(emptyList(), denied = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Phone(emptyList(), denied = false) // MediaStore hiccup: show the drive side, retry on next refresh
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
}
