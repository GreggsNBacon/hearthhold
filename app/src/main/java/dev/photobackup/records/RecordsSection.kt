package dev.photobackup.records

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.photobackup.media.MediaAccess
import dev.photobackup.ui.setup.setupPermissions

/** Settings → "Records": Export records / Import records. */
@Composable
fun RecordsSettingsSection(vm: RecordsViewModel) {
    val state by vm.state.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
        vm.export(uri?.toString())
    }
    val startImport = rememberImportLauncher(vm)
    Text("Records", style = MaterialTheme.typography.titleMedium)
    Text(
        "Everything the app remembers (backed-up and freed items, Bin, second copies, folder rules, settings) in one " +
            "file, for moving to a new install on this phone. The drive password is not included.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { exportLauncher.launch(vm.fileName()) }, enabled = state.working == null) {
            Text("Export records")
        }
        OutlinedButton(onClick = startImport, enabled = state.working == null) { Text("Import records") }
    }
    RecordsStatus(state, vm)
}

/** Setup's first step: "Have records from before? Import". */
@Composable
fun ImportRecordsEntry(vm: RecordsViewModel) {
    val state by vm.state.collectAsState()
    val startImport = rememberImportLauncher(vm)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Have records from before?", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = startImport, enabled = state.working == null) { Text("Import") }
    }
    RecordsStatus(state, vm)
}

/**
 * Asks for photo access first when it is missing (the import checks each phone row against MediaStore), then opens
 * the file picker. Without full access the import itself refuses with a plain message.
 */
@Composable
private fun rememberImportLauncher(vm: RecordsViewModel): () -> Unit {
    val context = LocalContext.current
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.pickImport(uri?.toString())
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        openLauncher.launch(arrayOf("*/*"))
    }
    return {
        if (MediaAccess.current(context) == MediaAccess.FULL) openLauncher.launch(arrayOf("*/*"))
        else permLauncher.launch(setupPermissions(Build.VERSION.SDK_INT).toTypedArray())
    }
}

@Composable
private fun RecordsStatus(state: RecordsUiState, vm: RecordsViewModel) {
    state.working?.let { w ->
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            when (w) {
                RecordsWork.EXPORTING -> "Exporting records…"
                RecordsWork.CHECKING -> "Checking the file…"
                RecordsWork.IMPORTING -> "Importing records — the app restarts when done…"
            }
        )
    }
    state.message?.let { m ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m, Modifier.weight(1f))
            if (state.working == null) TextButton(onClick = { vm.messageShown() }) { Text("OK") }
        }
    }
    state.confirmItems?.let { n ->
        AlertDialog(
            onDismissRequest = { vm.cancelImport() },
            title = { Text("Import records?") },
            text = {
                Text(
                    RecordsMessages.CONFIRM + "\n\nThe file holds $n items. The app restarts afterwards; then enter " +
                        "the drive password again (it is never saved in the file)."
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmImport() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { vm.cancelImport() }) { Text("Cancel") } },
        )
    }
}

private const val MIME = "application/octet-stream"
