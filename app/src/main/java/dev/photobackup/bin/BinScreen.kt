package dev.photobackup.bin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.photobackup.gallery.SmbRoot
import dev.photobackup.gallery.THUMB_PX
import dev.photobackup.smb.SmbUri
import dev.photobackup.ui.Screen

/**
 * The drive bin: items review marked Junk, with how long they have been in the bin. Restore puts one back where it
 * came from; Empty bin deletes everything in it for good (after a confirmation). Junk not moved yet shows
 * "Moving to bin…" with Cancel. Items are deleted for good after 30 days by the backup run.
 */
@Composable
fun BinScreen(viewModel: BinViewModel, root: SmbRoot?, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var confirmEmpty by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<BinItem?>(null) }
    var confirmDelete by remember { mutableStateOf<BinItem?>(null) }

    Screen(
        "Bin",
        onBack = onBack,
        actions = {
            TextButton(onClick = { confirmEmpty = true }, enabled = !state.busy && state.binnedCount > 0) {
                Text("Empty bin")
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { msg ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.messageShown() }) { Text("OK") }
                }
            }
            Text(
                "Items here are deleted from the drive for good after 30 days.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("The bin is empty")
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.entry.mediaId }) { item ->
                        Tile(
                            item, root, enabled = !state.busy,
                            onOpen = { opened = item },
                            onAction = { if (item.waiting) viewModel.cancel(item.entry.mediaId) else viewModel.restore(item.entry.mediaId) },
                        )
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty bin?") },
            text = { Text(emptyConfirmText(state.binnedCount)) },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; viewModel.emptyAll() }) { Text("Delete for good") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
    opened?.let { item ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text(item.entry.displayName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (item.waiting) "Moving to bin…" else item.age.orEmpty())
                    item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    opened = null
                    if (item.waiting) viewModel.cancel(item.entry.mediaId) else viewModel.restore(item.entry.mediaId)
                }) { Text(if (item.waiting) "Cancel move" else "Restore") }
            },
            dismissButton = {
                Row {
                    if (!item.waiting) {
                        TextButton(enabled = !state.busy, onClick = { opened = null; confirmDelete = item }) {
                            Text("Delete for good")
                        }
                    }
                    TextButton(onClick = { opened = null }) { Text("Close") }
                }
            },
        )
    }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete for good?") },
            text = { Text(emptyConfirmText(1)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; viewModel.delete(item.entry.mediaId) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Tile(item: BinItem, root: SmbRoot?, enabled: Boolean, onOpen: () -> Unit, onAction: () -> Unit) {
    val context = LocalContext.current
    val path = item.entry.remotePath
    val model = if (root == null || path == null) null else SmbUri.build(root.host, root.share, path)
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpen),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(model).size(THUMB_PX).build(),
                contentDescription = item.entry.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            if (item.waiting) "Moving to bin…" else item.age.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        item.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3)
        }
        TextButton(onClick = onAction, enabled = enabled) { Text(if (item.waiting) "Cancel" else "Restore") }
    }
}
