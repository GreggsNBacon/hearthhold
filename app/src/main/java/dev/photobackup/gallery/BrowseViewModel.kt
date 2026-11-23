package dev.photobackup.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.photobackup.smb.RemoteStore
import dev.photobackup.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseEntry(
    val name: String,
    val path: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

data class BrowseUiState(
    val path: String,
    val entries: List<BrowseEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Remote paths being copied to the phone now. */
    val copying: Set<String> = emptySet(),
    /** One-off result line ("Copied …"); cleared by [BrowseViewModel.messageShown]. */
    val message: String? = null,
) {
    /** The folder's photos and videos, in list order: what the viewer swipes through. */
    val viewerItems: List<GalleryItem> by lazy {
        entries.filter { it.kind.isMedia }.map {
            GalleryItem(
                key = "drive:${it.path}",
                displayName = it.name,
                dateMs = it.modifiedMs,
                isVideo = it.kind == MediaKind.VIDEO,
                sizeBytes = it.sizeBytes,
                location = Location.DRIVE,
                phoneUri = null,
                remotePath = it.path,
            )
        }
    }

    /** Position of [entry] in [viewerItems], or -1 if it can't be opened. */
    fun viewerIndexOf(entry: BrowseEntry): Int = viewerItems.indexOfFirst { it.remotePath == entry.path }
}

/**
 * One folder of the drive share. [store] is an `AuthRetryingStore` (saved login, re-prompt only on a refused one)
 * owned by this ViewModel and closed with it. [copy] copies a drive file into the phone's MediaStore.
 */
class BrowseViewModel(
    private val path: String,
    private val store: RemoteStore,
    private val copy: suspend (remotePath: String, isVideo: Boolean) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowseUiState(path))
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    private var started = false

    /** First show: list once (coming back from the viewer or a subfolder keeps the list). */
    fun start() {
        if (started) return
        refresh()
    }

    fun refresh() {
        started = true
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val entries = store.list(path)
                    .filter { it.name != "." && it.name != ".." }
                    .map { BrowseEntry(it.name, it.path, if (it.isDirectory) MediaKind.DIR else MediaKind.of(it.name), it.sizeBytes, it.modifiedMs) }
                    .sortedWith(compareBy<BrowseEntry> { it.kind != MediaKind.DIR }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                _state.update { it.copy(entries = entries, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = userMessage(e)) }
            }
        }
    }

    fun copyToPhone(entry: BrowseEntry) {
        if (!entry.kind.isMedia || entry.path in _state.value.copying) return
        _state.update { it.copy(copying = it.copying + entry.path) }
        viewModelScope.launch {
            val msg = try {
                copy(entry.path, entry.kind == MediaKind.VIDEO)
                "Copied ${entry.name} to the phone"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't copy ${entry.name}: ${userMessage(e)}"
            }
            _state.update { it.copy(copying = it.copying - entry.path, message = msg) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        store.close()
    }
}
