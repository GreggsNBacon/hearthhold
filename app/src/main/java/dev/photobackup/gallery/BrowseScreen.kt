package dev.photobackup.gallery

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.photobackup.freeup.formatSize
import dev.photobackup.smb.SmbUri
import dev.photobackup.ui.Screen

/** Folder view of the drive share. Tap a folder to open it, a photo/video to view it; ⋮ → "Copy to phone". */
@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel,
    root: SmbRoot?,
    onBack: () -> Unit,
    onOpenFolder: (path: String) -> Unit,
    onOpen: (items: List<GalleryItem>, index: Int) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.start() }
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.messageShown()
        }
    }
    val title = if (state.path.isEmpty()) root?.share ?: "Drive" else state.path.substringAfterLast('\\')

    Screen(title, onBack = onBack, actions = { TextButton(onClick = { viewModel.refresh() }) { Text("Refresh") } }) {
        when {
            state.loading && state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error!!)
                Spacer(Modifier.size(12.dp))
                Button(onClick = { viewModel.refresh() }) { Text("Try again") }
            }
            state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This folder is empty")
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.entries, key = { it.path }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = entry.kind != MediaKind.OTHER) {
                                when (entry.kind) {
                                    MediaKind.DIR -> onOpenFolder(entry.path)
                                    MediaKind.IMAGE, MediaKind.VIDEO -> onOpen(state.viewerItems, state.viewerIndexOf(entry))
                                    MediaKind.OTHER -> {}
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Leading(entry, root)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, maxLines = 2)
                            Text(
                                when (entry.kind) {
                                    MediaKind.DIR -> "Folder"
                                    MediaKind.OTHER -> "${formatSize(entry.sizeBytes)} · can't open this file type"
                                    else -> formatSize(entry.sizeBytes)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (entry.kind.isMedia) {
                            if (entry.path in state.copying) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Overflow(onCopy = { viewModel.copyToPhone(entry) })
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Leading(entry: BrowseEntry, root: SmbRoot?) {
    val box = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant)
    if (entry.kind.isMedia && root != null) {
        Box(box) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(SmbUri.build(root.host, root.share, entry.path))
                    .size(THUMB_PX)
                    .build(),
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (entry.kind == MediaKind.VIDEO) Text("▶", Modifier.align(Alignment.Center))
        }
    } else {
        Box(box, contentAlignment = Alignment.Center) {
            Text(if (entry.kind == MediaKind.DIR) "Folder" else "File", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Overflow(onCopy: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Copy to phone") }, onClick = { open = false; onCopy() })
        }
    }
}
