package dev.photobackup.bin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One Bin screen tile. [waiting]: Junk not moved yet ("Moving to bin…", age null). [error]: the row's last error. */
data class BinItem(val entry: LedgerEntry, val waiting: Boolean, val age: String?, val error: String?)

data class BinUiState(
    val loading: Boolean = true,
    val items: List<BinItem> = emptyList(),
    /** True while a restore / cancel / delete runs (single-flight). */
    val busy: Boolean = false,
    /** The last action's result; cleared by [BinViewModel.messageShown]. Never exception text. */
    val message: String? = null,
) {
    /** Items Empty bin would delete (waiting ones are left alone). */
    val binnedCount: Int get() = items.count { !it.waiting }
}

fun emptyConfirmText(n: Int) = "Delete $n ${items(n)} from the drive for good?"

private fun items(n: Int) = if (n == 1) "item" else "items"

/** "3 days in bin" from when the row became BINNED (falls back to the bin request, then the last update). */
fun binAge(e: LedgerEntry, now: Long): String {
    val since = e.binnedAt ?: e.binRequestedAt ?: e.updatedAtMs
    val days = ((now - since).coerceAtLeast(0) / 86_400_000L).toInt()
    return when (days) {
        0 -> "Less than a day in bin"
        1 -> "1 day in bin"
        else -> "$days days in bin"
    }
}

/**
 * The Bin screen: binned items plus Junk still waiting to be moved. Actions go through [BinRepository] (passed as
 * lambdas so tests use fakes). Never throws to the UI; every outcome becomes a short [BinUiState.message].
 */
class BinViewModel(
    list: Flow<List<LedgerEntry>>,
    private val restore: suspend (List<Long>) -> BinResult,
    private val empty: suspend (List<Long>) -> BinResult,
    private val emptyAll: suspend () -> BinResult,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(BinUiState())
    val state: StateFlow<BinUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            list
                .catch { _state.update { s -> s.copy(loading = false, message = GENERIC) } }
                .collect { rows ->
                    val now = clock()
                    val items = rows.map { e ->
                        val waiting = e.status != BackupStatus.BINNED
                        BinItem(e, waiting, if (waiting) null else binAge(e, now), e.lastError)
                    }
                    _state.update { it.copy(loading = false, items = items) }
                }
        }
    }

    /** Puts one item back where it came from on the drive. */
    fun restore(id: Long) = run({ restore(listOf(id)) }) { r ->
        summary("Restored", r.count, r.failedCount, "couldn't be restored")
    }

    /** Cancels a pending bin move (restore drops the request): the drive copy stays where it was. */
    fun cancel(id: Long) = run({ restore(listOf(id)) }) { r -> if (r.count > 0 && r.failedCount == 0) CANCELLED else CANCEL_FAILED }

    /** Deletes one item from the drive for good (the screen confirms first). */
    fun delete(id: Long) = run({ empty(listOf(id)) }) { r ->
        summary("Deleted", r.count, r.failedCount, "couldn't be deleted", " for good")
    }

    /** Empties the bin (the screen confirms first). */
    fun emptyAll() = run({ emptyAll.invoke() }) { r ->
        summary("Deleted", r.count, r.failedCount, "couldn't be deleted", " for good")
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun summary(verb: String, n: Int, failed: Int, failText: String, suffix: String = ""): String {
        val main = "$verb $n ${items(n)}$suffix"
        return if (failed > 0) "$main · $failed $failText" else main
    }

    private fun run(action: suspend () -> BinResult, done: (BinResult.Done) -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val msg = try {
                when (val r = action()) {
                    is BinResult.Done -> done(r)
                    BinResult.Busy -> BUSY
                    BinResult.Offline -> OFFLINE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                GENERIC // never the exception text
            }
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    companion object {
        const val BUSY = "A backup is running — try again in a moment"
        const val OFFLINE = "Connect to home Wi-Fi — some items may have changed"
        const val GENERIC = "Something went wrong"
        const val CANCELLED = "Cancelled — the drive copy stays where it was"
        const val CANCEL_FAILED = "Couldn't cancel — it may be moving now; try again in a moment"
    }
}
