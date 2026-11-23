package dev.photobackup.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.photobackup.media.FolderRules

/** Why [name] can't be a new drive folder name, or null when it is fine. Same rule as the Settings folder rules. */
fun newFolderError(name: String): String? {
    val c = FolderRules.canonical(name) ?: return "Enter a folder name"
    if (c.all { it == '_' }) return "That name can't be used"
    return null
}

/**
 * Pick the drive folder for the selected items: an existing name, "Default" (clears the choice) or a new name.
 * [onPick] gets the folder, or null for Default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(
    names: List<String>,
    count: Int,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    val error = if (touched) newFolderError(text) else null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Send $count to folder", style = MaterialTheme.typography.titleMedium)
            if (creating) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; touched = true },
                    label = { Text("New folder name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                TextButton(onClick = {
                    touched = true
                    if (newFolderError(text) == null) onPick(FolderRules.canonical(text))
                }) { Text("Send") }
            } else {
                LazyColumn {
                    item { PickerRow("Default") { onPick(null) } }
                    items(names, key = { it.lowercase() }) { n -> PickerRow(n) { onPick(n) } }
                    item { PickerRow("New folder\u2026") { creating = true } }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
    )
}
