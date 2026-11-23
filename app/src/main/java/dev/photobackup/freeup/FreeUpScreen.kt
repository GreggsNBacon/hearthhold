package dev.photobackup.freeup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun FreeUpScreen(viewModel: FreeUpViewModel) {
    val state by viewModel.state.collectAsState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onDeleteResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.pendingDelete) {
        state.pendingDelete?.let {
            viewModel.onDeleteLaunched()
            launcher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "${state.items.size} items can be removed (${formatSize(state.items.sumOf { it.entry.sizeBytes })})",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            Modifier.fillMaxWidth().clickable { viewModel.setAllSelected(!state.allSelected) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.allSelected, onCheckedChange = { viewModel.setAllSelected(it) })
            Text("Select all")
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.items, key = { it.entry.mediaId }) { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.toggle(item.entry.mediaId) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = item.selected, onCheckedChange = { viewModel.toggle(item.entry.mediaId) })
                    AsyncImage(
                        model = item.entry.localUri,
                        contentDescription = item.entry.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.entry.displayName, maxLines = 1)
                        Text(formatSize(item.entry.sizeBytes), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        val message = if (!state.driveReachable) CONNECT_HOME_MESSAGE else state.error
        if (message != null) {
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }
        state.checking?.let { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Checking ${c.checked} of ${c.total} · ${formatSize(c.bytesChecked)}", Modifier.weight(1f))
                TextButton(onClick = { viewModel.cancelCheck() }) { Text("Cancel") }
            }
        }
        Button(
            onClick = { viewModel.removeSelected() },
            enabled = state.driveReachable && !state.busy && state.selectedCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Remove ${state.selectedCount} ${if (state.selectedCount == 1) "item" else "items"} from phone (${formatSize(state.totalBytes)})")
        }
    }
}
