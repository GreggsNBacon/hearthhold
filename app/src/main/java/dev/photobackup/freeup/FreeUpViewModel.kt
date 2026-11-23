package dev.photobackup.freeup

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.ZoneId
import java.util.Locale

const val CONNECT_HOME_MESSAGE = "Connect to home Wi-Fi to free up space"

data class FreeUpItem(val entry: LedgerEntry, val selected: Boolean)

data class FreeUpUiState(
    val items: List<FreeUpItem> = emptyList(),
    val driveReachable: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** Set when the system delete dialog should be launched; cleared by [FreeUpViewModel.onDeleteResult]. */
    val pendingDelete: IntentSender? = null,
) {
    val selectedItems: List<LedgerEntry> get() = items.filter { it.selected }.map { it.entry }
    val selectedCount: Int get() = items.count { it.selected }
    val totalBytes: Long get() = items.filter { it.selected }.sumOf { it.entry.sizeBytes }
    val allSelected: Boolean get() = items.isNotEmpty() && items.all { it.selected }
}

class FreeUpViewModel(
    private val repo: FreeUpRepository,
    private val queryPhone: suspend () -> List<PhoneMedia>,
    /** Opens a store for the configured drive, or null if no drive is configured. */
    private val openStore: suspend () -> RemoteStore?,
    private val ageMonths: suspend () -> Int,
    private val makeDeleteRequest: (List<LedgerEntry>) -> IntentSender,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _state = MutableStateFlow(FreeUpUiState())
    val state: StateFlow<FreeUpUiState> = _state.asStateFlow()

    private var pendingItems: List<LedgerEntry> = emptyList()

    fun refresh() {
        viewModelScope.launch { load() }
    }

    internal suspend fun load() {
        _state.update { it.copy(busy = true, error = null) }
        try {
            val phone = queryPhone().associateBy { it.mediaId }
            val suggestions = SuggestionRule.suggest(repo.verifiedEntries(), phone, clock(), ageMonths(), zone)
            val reachable = checkReachable()
            _state.update { s ->
                val keep = s.items.filter { it.selected }.map { it.entry.mediaId }.toSet()
                s.copy(
                    items = suggestions.map { FreeUpItem(it, it.mediaId in keep) },
                    driveReachable = reachable,
                    busy = false,
                )
            }
        } catch (e: SecurityException) {
            _state.update { it.copy(busy = false, error = "Photo access is needed") }
        }
    }

    private suspend fun checkReachable(): Boolean {
        val store = openStore() ?: return false
        return try {
            store.use { it.connect() }
            true
        } catch (e: IOException) { // includes RemoteException
            false
        }
    }

    fun toggle(mediaId: Long) = _state.update { s ->
        s.copy(items = s.items.map { if (it.entry.mediaId == mediaId) it.copy(selected = !it.selected) else it })
    }

    fun setAllSelected(selected: Boolean) = _state.update { s ->
        s.copy(items = s.items.map { it.copy(selected = selected) })
    }

    /** Re-checks the drive copies, then publishes the system delete dialog in [FreeUpUiState.pendingDelete]. */
    fun removeSelected() {
        val selected = _state.value.selectedItems
        if (selected.isEmpty() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val store = openStore()
                if (store == null) {
                    _state.update { it.copy(busy = false, driveReachable = false, error = CONNECT_HOME_MESSAGE) }
                    return@launch
                }
                val phone = queryPhone().associateBy { it.mediaId }
                val safe = store.use {
                    it.connect()
                    repo.recheck(selected, it, phone)
                }
                if (safe.isEmpty()) {
                    load()
                    _state.update { it.copy(error = "Nothing selected can be safely removed") }
                    return@launch
                }
                pendingItems = safe
                val sender = makeDeleteRequest(safe)
                _state.update { it.copy(busy = false, pendingDelete = sender) }
            } catch (e: IOException) { // includes RemoteException
                _state.update { it.copy(busy = false, driveReachable = false, error = CONNECT_HOME_MESSAGE) }
            } catch (e: SecurityException) {
                _state.update { it.copy(busy = false, error = "Photo access is needed") }
            }
        }
    }

    /** Called with whether the system dialog returned RESULT_OK. */
    fun onDeleteResult(ok: Boolean) {
        val items = pendingItems
        pendingItems = emptyList()
        _state.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            if (ok && items.isNotEmpty()) repo.markRemoved(items)
            load()
        }
    }
}

fun formatSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
