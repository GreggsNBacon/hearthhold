package dev.photobackup.freeup

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.backup.LocalOpener
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.ZoneId
import java.util.Locale

const val CONNECT_HOME_MESSAGE = "Connect to home Wi-Fi to free up space"

data class FreeUpItem(val entry: LedgerEntry, val selected: Boolean)

data class CheckProgress(val checked: Int, val total: Int, val bytesChecked: Long, val totalBytes: Long)

data class FreeUpUiState(
    val items: List<FreeUpItem> = emptyList(),
    val driveReachable: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** Non-null while the pre-delete integrity check runs. */
    val checking: CheckProgress? = null,
    /** Set when the system delete dialog should be launched; cleared by [FreeUpViewModel.onDeleteLaunched]. */
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
    private val opener: LocalOpener,
    private val makeDeleteRequest: (List<LedgerEntry>) -> IntentSender,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _state = MutableStateFlow(FreeUpUiState())
    val state: StateFlow<FreeUpUiState> = _state.asStateFlow()

    /** The sender handed to the system dialog together with exactly the items it deletes. */
    private class Pending(val sender: IntentSender, val items: List<LedgerEntry>)

    /** True from the start of a removal until its dialog result (or failure/cancel). Single-flight. */
    private var removing = false
    private var removalJob: Job? = null
    private var pending: Pending? = null

    fun refresh() {
        if (removing) return
        viewModelScope.launch { load() }
    }

    internal suspend fun load() {
        if (removing) return
        _state.update { it.copy(busy = true, error = null) }
        try {
            val phone = queryPhone().associateBy { it.mediaId }
            val suggestions = SuggestionRule.suggest(repo.verifiedEntries(), phone, clock(), ageMonths(), zone)
            val reachable = checkReachable()
            _state.update { s ->
                val keep = s.items.filter { it.selected }.map { it.entry.mediaId }.toSet()
                s.copy(items = suggestions.map { FreeUpItem(it, it.mediaId in keep) }, driveReachable = reachable)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = describe(e)) }
        } finally {
            if (!removing) _state.update { it.copy(busy = false) }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SecurityException -> "Photo access is needed"
        is IOException -> CONNECT_HOME_MESSAGE // includes RemoteException
        else -> e.message ?: "Something went wrong"
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

    /** Runs the full integrity check, then publishes the system delete dialog in [FreeUpUiState.pendingDelete]. */
    fun removeSelected() {
        val selected = _state.value.selectedItems
        if (removing || selected.isEmpty()) return
        removing = true
        _state.update { it.copy(busy = true, error = null, checking = null) }
        removalJob = viewModelScope.launch {
            var published = false
            try {
                val store = openStore()
                if (store == null) {
                    _state.update { it.copy(driveReachable = false, error = CONNECT_HOME_MESSAGE) }
                    return@launch
                }
                val phone = queryPhone().associateBy { it.mediaId }
                val safe = store.use {
                    it.connect()
                    repo.recheck(selected, it, phone, opener) { c, t, b, tb ->
                        _state.update { s -> s.copy(checking = CheckProgress(c, t, b, tb)) }
                    }
                }
                if (safe.isEmpty()) {
                    _state.update { it.copy(error = "Nothing selected can be safely removed") }
                    return@launch
                }
                val sender = makeDeleteRequest(safe)
                pending = Pending(sender, safe)
                published = true
                _state.update { it.copy(pendingDelete = sender) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(driveReachable = e !is IOException && it.driveReachable, error = describe(e)) }
            } finally {
                _state.update { it.copy(checking = null) }
                if (!published) {
                    removing = false
                    _state.update { it.copy(busy = false) }
                    if (isActive) launch { load() }
                }
            }
        }
    }

    /** Stops the integrity check. Nothing is deleted. */
    fun cancelCheck() {
        removalJob?.takeIf { pending == null }?.cancel()
    }

    /** The screen launched the dialog; clears the one-shot event so rotation cannot launch it again. */
    fun onDeleteLaunched() = _state.update { it.copy(pendingDelete = null) }

    /** Called with whether the system dialog returned RESULT_OK. Ignored if nothing is pending. */
    fun onDeleteResult(ok: Boolean) {
        val p = pending ?: return
        pending = null
        _state.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            try {
                if (ok) repo.markRemoved(p.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = describe(e)) }
            } finally {
                removing = false
                _state.update { it.copy(busy = false) }
            }
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
