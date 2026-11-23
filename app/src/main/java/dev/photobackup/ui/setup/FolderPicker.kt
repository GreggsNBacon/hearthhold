package dev.photobackup.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.photobackup.smb.RemoteEntry
import dev.photobackup.smb.RemoteStore
import dev.photobackup.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderPickerState(
    val path: String = "",
    val dirs: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Folder browsing logic for [FolderPicker]: directories only, share-relative `\` paths. */
class FolderPickerModel(private val store: RemoteStore, private val scope: CoroutineScope, initialPath: String) {
    private val _state = MutableStateFlow(FolderPickerState(path = initialPath))
    val state: StateFlow<FolderPickerState> = _state.asStateFlow()
    private var job: Job? = null

    fun open(path: String): Job {
        job?.cancel()
        _state.update { it.copy(path = path, loading = true, error = null) }
        return scope.launch {
            try {
                val dirs = store.list(path).filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                _state.update { it.copy(dirs = dirs) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(dirs = emptyList(), error = userMessage(e)) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }.also { job = it }
    }

    fun up(): Job? {
        val p = _state.value.path
        if (p.isEmpty()) return null
        return open(if ('\\' in p) p.substringBeforeLast('\\') else "")
    }

    /** Creates [name] inside the current folder and opens it. */
    fun createFolder(name: String): Job? {
        val n = name.trim()
        if (!isValidFolderName(n)) {
            _state.update { it.copy(error = "Folder names can't be empty or contain \\ / : * ? \" < > |") }
            return null
        }
        val target = join(_state.value.path, n)
        return scope.launch {
            try {
                store.mkdirs(target)
                open(target).join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = userMessage(e)) }
            }
        }
    }

    companion object {
        fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent\\$name"
        fun isValidFolderName(n: String) =
            n.isNotEmpty() && n != "." && n != ".." && n.none { it in "\\/:*?\"<>|" } && !n.endsWith('.')
    }
}

/** Browses folders on the drive (dirs only) with "New folder"; [onPicked] gets the share-relative path. */
@Composable
fun FolderPicker(store: RemoteStore, initialPath: String, onPicked: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(store) { FolderPickerModel(store, scope, initialPath) }
    val state by model.state.collectAsState()
    var newFolder by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(model) { model.open(initialPath) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            if (state.path.isEmpty()) "Top of the shared folder" else state.path.replace('\\', '/'),
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { model.up() }, enabled = state.path.isNotEmpty()) { Text("Up") }
            OutlinedButton(onClick = { newFolder = "" }) { Text("New folder") }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(state.dirs, key = { it.path }) { d ->
                Text(
                    "${d.name}/",
                    Modifier.fillMaxWidth().clickable { model.open(d.path) }.padding(vertical = 12.dp),
                )
                HorizontalDivider()
            }
            if (!state.loading && state.dirs.isEmpty() && state.error == null) {
                item { Text("No folders here", Modifier.padding(vertical = 12.dp)) }
            }
        }
        Button(
            onClick = { onPicked(state.path) },
            enabled = state.path.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(if (state.path.isEmpty()) "Open or create a folder" else "Use this folder") }
    }

    newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text("New folder") },
            text = { OutlinedTextField(value = name, onValueChange = { newFolder = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { model.createFolder(name); newFolder = null }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Cancel") } },
        )
    }
}
