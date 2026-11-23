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

    /** True while [FreeUpUiState.error] holds a removal result the user has not dismissed or superseded. */
    private var sticky = false

    fun refresh() {
        if (removing) return
        viewModelScope.launch { load() }
    }

    internal suspend fun load() {
        if (removing) return
        _state.update { it.copy(busy = true, error = if (sticky) it.error else null) }
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
            if (!sticky) _state.update { it.copy(error = describe(e)) }
        } finally {
            if (!removing) _state.update { it.copy(busy = false) }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SecurityException -> "Photo access is needed"
        is IOException -> CONNECT_HOME_MESSAGE // includes RemoteException
        else -> "Something went wrong" // never the exception text (userMessage policy)
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
        sticky = false
        var damaged = 0
        var changed = 0
        _state.update { it.copy(busy = true, error = null, checking = null) }
        removalJob = viewModelScope.launch {
            var published = false
            try {
                val store = openStore()
                if (store == null) {
                    showMessage(CONNECT_HOME_MESSAGE) { it.copy(driveReachable = false) }
                    return@launch
                }
                val phone = queryPhone().associateBy { it.mediaId }
                val safe = store.use {
                    it.connect()
                    repo.recheck(selected, it, phone, opener,
                        onProgress = { c, t, b, tb ->
                            _state.update { s -> s.copy(checking = CheckProgress(c, t, b, tb)) }
                        },
                        onFailed = { reason ->
                            if (reason == FreeUpRepository.DAMAGED) damaged++ else changed++
                        },
                    )
                }
                val summary = failureSummary(damaged, changed)
                if (safe.isEmpty()) {
                    showMessage(summary ?: "Nothing selected can be safely removed")
                    return@launch
                }
                if (summary != null) showMessage(summary)
                val sender = makeDeleteRequest(safe)
                pending = Pending(sender, safe)
                published = true
                _state.update { it.copy(pendingDelete = sender) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = listOfNotNull(failureSummary(damaged, changed), describe(e)).joinToString("\n")
                showMessage(msg) { it.copy(driveReachable = e !is IOException && it.driveReachable) }
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

    private fun showMessage(msg: String, extra: (FreeUpUiState) -> FreeUpUiState = { it }) {
        sticky = true
        _state.update { extra(it).copy(error = msg) }
    }

    private fun failureSummary(damaged: Int, changed: Int): String? {
        val n = damaged + changed
        if (n == 0) return null
        val parts = listOfNotNull(
            if (damaged > 0) "$damaged drive ${if (damaged == 1) "copy" else "copies"} damaged" else null,
            if (changed > 0) "$changed phone ${if (changed == 1) "file" else "files"} changed" else null,
        ).joinToString(", ")
        return "$n ${if (n == 1) "item" else "items"} failed the check \u2014 will back up again ($parts)"
    }

    /** Clears the message shown after a removal attempt. */
    fun dismissError() {
        sticky = false
        _state.update { it.copy(error = null) }
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
                showMessage(describe(e))
            } finally {
                removing = false
                _state.update { it.copy(busy = false) }
            }
            load()
        }
    }
}

/**
 * The message line under the list: the removal result (e.g. "2 items failed the check") and, when the drive is not
 * reachable, the "connect to home Wi-Fi" hint; both when both apply, so one never hides the other. Pure.
 */
fun freeUpMessage(state: FreeUpUiState): String? {
    val lines = mutableListOf<String>()
    state.error?.let { lines += it }
    if (!state.driveReachable && lines.none { CONNECT_HOME_MESSAGE in it }) lines += CONNECT_HOME_MESSAGE
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
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
