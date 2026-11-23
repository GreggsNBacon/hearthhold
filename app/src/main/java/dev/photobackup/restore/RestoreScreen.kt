package dev.photobackup.restore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.photobackup.freeup.formatSize
import dev.photobackup.ui.SettingsErrorNotice

/**
 * Restore to phone: drive folders and their years (drive-only items), with tick boxes, Everything, the selected total
 * against phone free space, Start, live progress with Cancel, and the last result (tap for failures).
 */
@Composable
fun RestoreScreen(viewModel: RestoreViewModel) {
    val state by viewModel.state.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    // Back from Android settings (photo access) or elsewhere: re-read the list, free space and access.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        state.message?.let { msg ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.messageShown() }) { Text("OK") }
            }
        }
        RunPanel(state, onCancel = viewModel::cancel, onDetails = { showDetails = true })
        when {
            state.settingsError -> SettingsErrorNotice(onRetry = viewModel::refresh, modifier = Modifier.fillMaxSize())
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error!!)
                Button(onClick = viewModel::refresh) { Text("Retry") }
            }
            state.empty -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(RestoreUiState.EMPTY)
            }
            else -> Picker(state, viewModel, Modifier.weight(1f))
        }
    }

    val last = state.last
    if (showDetails && last != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("OK") } },
            title = { Text("Couldn't restore") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(last.result.failed) { f ->
                        Column {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium)
                            Text(f.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
        )
    }
}

/** Progress while a restore runs; otherwise the last result, if any. */
@Composable
private fun RunPanel(state: RestoreUiState, onCancel: () -> Unit, onDetails: () -> Unit) {
    val progress = state.progressText
    if (progress != null) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = state.run
            if (r is RestoreRun.Running && r.total > 0) {
                LinearProgressIndicator(progress = { r.done.toFloat() / r.total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(progress, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
            Text("Cancelling keeps what's already restored.", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        return
    }
    val last = state.last ?: return
    val r = last.result
    val hasFailures = r.failed.isNotEmpty()
    Column(
        Modifier.fillMaxWidth()
            .then(if (hasFailures) Modifier.clickable(onClick = onDetails) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Last restore", style = MaterialTheme.typography.labelMedium)
        Text(restoreSummary(r) + if (hasFailures) " (tap for details)" else "")
        r.stopped?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (r.redirected.isNotEmpty()) {
            Text(
                "${r.redirected.size} saved in a Restored folder (Android refused the original folder)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun Picker(state: RestoreUiState, viewModel: RestoreViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !state.running) { viewModel.setEverything(!state.everything) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Everything", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.everything, onCheckedChange = viewModel::setEverything, enabled = !state.running)
                }
                HorizontalDivider()
            }
            state.folders.forEach { f ->
                item(key = "folder:${f.folder}") {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !state.running) { viewModel.toggleFolder(f.folder) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(
                            state = when (f.tick) {
                                FolderTick.NONE -> ToggleableState.Off
                                FolderTick.SOME -> ToggleableState.Indeterminate
                                FolderTick.ALL -> ToggleableState.On
                            },
                            onClick = { viewModel.toggleFolder(f.folder) },
                            enabled = !state.running,
                        )
                        Text(f.folder, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${f.count} · ${formatSize(f.bytes)}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 12.dp))
                    }
                }
                items(f.groups, key = { "group:${it.key.folder}/${it.key.year}" }) { g ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !state.running) { viewModel.toggle(g.key) }
                            .padding(start = 32.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = state.isTicked(g.key),
                            onCheckedChange = { viewModel.toggle(g.key) },
                            enabled = !state.running,
                        )
                        Text(g.key.year ?: "Other", modifier = Modifier.weight(1f))
                        Text("${g.count} · ${formatSize(g.bytes)}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 12.dp))
                    }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Selected: ${state.selectedCount} ${if (state.selectedCount == 1) "item" else "items"} · " +
                    "${formatSize(state.selectedBytes)} — ${formatSize(state.freeBytes)} free on the phone",
            )
            if (!state.running) state.blockReason?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = viewModel::start, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
                Text("Start restore")
            }
            Text("Runs on Wi-Fi only. Nothing on the drive is changed.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
