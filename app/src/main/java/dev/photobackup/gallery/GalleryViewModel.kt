package dev.photobackup.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import dev.photobackup.ui.auth.DriveNotConfiguredException
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

enum class DriveState { CHECKING, REACHABLE, UNREACHABLE, NOT_CONFIGURED }

data class GalleryUiState(
    val items: List<GalleryItem> = emptyList(),
    val loading: Boolean = true,
    val drive: DriveState = DriveState.CHECKING,
    /** No photo access: only drive items can be listed. */
    val phoneDenied: Boolean = false,
    val root: SmbRoot? = null,
) {
    /** Drive-only items are greyed out ("Drive not reachable") unless the drive answered just now. */
    fun isAvailable(item: GalleryItem): Boolean = item.location != Location.DRIVE || drive == DriveState.REACHABLE
}

/**
 * The combined Gallery. The phone list is (re)loaded on [refresh]; the ledger is observed, so backups and removals
 * show up live. The merge runs on [compute], never on the main thread. The drive check goes through the auth prompt
 * (a refused login re-prompts once; cancelling counts as not reachable).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(
    private val queryPhone: suspend () -> List<PhoneMedia>,
    ledger: kotlinx.coroutines.flow.Flow<List<LedgerEntry>>,
    private val checkDrive: suspend () -> Unit,
    private val driveRoot: suspend () -> SmbRoot?,
    compute: CoroutineDispatcher = Dispatchers.Default,
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
    private var started = false

    /** First show: load once (coming back from the viewer keeps the list and the drive state). */
    fun start() {
        if (started) return
        started = true
        refresh()
    }

    fun refresh() {
        started = true
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
        driveJob?.cancel()
        driveJob = viewModelScope.launch {
            extra.update { it.copy(drive = DriveState.CHECKING) }
            val root = try {
                driveRoot()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            extra.update { it.copy(root = root) }
            val result = try {
                checkDrive()
                DriveState.REACHABLE
            } catch (e: CancellationException) {
                throw e
            } catch (_: DriveNotConfiguredException) {
                DriveState.NOT_CONFIGURED
            } catch (_: Exception) {
                DriveState.UNREACHABLE
            }
            extra.update { it.copy(drive = result) }
        }
    }
}
