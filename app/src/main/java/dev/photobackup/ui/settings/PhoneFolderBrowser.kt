package dev.photobackup.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.photobackup.media.PhoneFolder
import dev.photobackup.media.PhoneFolders
import dev.photobackup.media.SourceFolders

/**
 * Lists the phone's folders that hold photos or videos (from MediaStore), each with its item count.
 * Tapping Add stores the folder's relative path, exactly as typing it would. A typed path is the fallback.
 */
@Composable
fun PhoneFolderBrowser(
    list: PhoneFolderList?,
    folders: SourceFolders,
    onAdd: (String) -> Unit,
    onRetry: () -> Unit,
    /** True when any folder may be picked, even one already backed up (used for folder rules). */
    pickIncluded: Boolean = false,
    actionLabel: String = "Add",
) {
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                typed, { typed = it }, singleLine = true, modifier = Modifier.weight(1f),
                label = { Text("Or type a path, e.g. Pictures/WhatsApp") },
            )
            TextButton(onClick = { onAdd(typed); typed = "" }, enabled = PhoneFolders.normalise(typed).isNotEmpty()) {
                Text(actionLabel)
            }
        }
        HorizontalDivider()
        when (list) {
            null, PhoneFolderList.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is PhoneFolderList.Failed -> {
                Text(list.message)
                OutlinedButton(onClick = onRetry) { Text("Try again") }
            }
            is PhoneFolderList.Loaded -> {
                val rows = remember(list, folders) { PhoneFolders.list(list.counts, folders) }
                if (rows.isEmpty()) {
                    Text("No photos or videos found on this phone.")
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(rows, key = { it.path }) { FolderRow(it, onAdd, pickIncluded, actionLabel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(f: PhoneFolder, onAdd: (String) -> Unit, pickIncluded: Boolean, actionLabel: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(f.path.removeSuffix("/"))
            Text(
                if (f.count == 1) "1 item" else "${f.count} items",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (f.included && !pickIncluded) {
            Text("Included", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp))
        } else {
            TextButton(onClick = { onAdd(f.path) }) { Text(actionLabel) }
        }
    }
}
