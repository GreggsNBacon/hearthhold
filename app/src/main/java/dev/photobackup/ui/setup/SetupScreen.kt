package dev.photobackup.ui.setup

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.photobackup.smb.RemoteStore
import dev.photobackup.media.MediaAccess
import dev.photobackup.records.ImportRecordsEntry
import dev.photobackup.records.RecordsViewModel
import dev.photobackup.ui.Screen

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    driveStore: () -> RemoteStore,
    onFinished: () -> Unit,
    /** Second mode only: leave setup from its first step without saving. */
    onCancel: () -> Unit = {},
    /** First-run only: "Have records from before? Import". Null hides it. */
    records: RecordsViewModel? = null,
    /** Restarts the app after an import. */
    onRestart: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { if (state.step == SetupStep.DISCOVER && state.hosts.isEmpty()) viewModel.startDiscovery() }
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    BackHandler(enabled = state.step != SetupStep.DISCOVER) { viewModel.back() }

    val second = viewModel.mode == SetupMode.SECOND
    // Second mode: Back from the first step leaves setup (back to Settings); nothing has been saved.
    BackHandler(enabled = second && state.step == SetupStep.DISCOVER) { onCancel() }
    val title = when (state.step) {
        SetupStep.DISCOVER -> if (second) "Find the second drive" else "Find your drive"
        SetupStep.SHARE -> "Pick the shared folder"
        SetupStep.CREDENTIALS -> "Log in"
        SetupStep.FOLDER -> if (second) "Where to put second copies" else "Where to put backups"
        SetupStep.PERMISSIONS -> "Permissions"
        SetupStep.DONE -> "All set"
    }
    val onBack: (() -> Unit)? = when {
        state.step != SetupStep.DISCOVER -> ({ viewModel.back() })
        second -> onCancel
        else -> null
    }
    Screen(title, onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy || state.loadingShares) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (state.step) {
                SetupStep.DISCOVER -> {
                    if (!second && records != null) {
                        ImportRecordsEntry(records, onRestart)
                        HorizontalDivider()
                    }
                    DiscoverStep(state, viewModel)
                }
                SetupStep.SHARE -> ShareStep(state, viewModel)
                SetupStep.CREDENTIALS -> CredentialsStep(state, viewModel)
                SetupStep.FOLDER -> {
                    val store = remember { driveStore() }
                    DisposableEffect(store) { onDispose { store.close() } }
                    FolderPicker(store, initialPath = state.destination.orEmpty(), onPicked = { viewModel.onFolderPicked(it) })
                }
                SetupStep.PERMISSIONS -> PermissionsStep(viewModel)
                SetupStep.DONE -> if (second) {
                    Text("Second copies go to ${state.destination?.replace('\\', '/')} on ${state.share} (${state.host}).")
                    Text("Each backed-up photo and video is copied there too and checked. Nothing is ever deleted there.")
                    Button(onClick = { viewModel.finish() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Save and start copying")
                    }
                } else {
                    Text("Backups go to ${state.destination?.replace('\\', '/')} on ${state.share}.")
                    Text("New photos and videos back up on home Wi-Fi.")
                    Button(onClick = { viewModel.finish() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Start backup")
                    }
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun DiscoverStep(state: SetupUiState, vm: SetupViewModel) {
    var manual by remember { mutableStateOf("") }
    Text(if (state.searching) "Looking for drives on this Wi-Fi…" else "Drives found on this Wi-Fi:")
    LazyColumn(Modifier.fillMaxWidth()) {
        items(state.hosts, key = { it.address }) { h ->
            Column(Modifier.fillMaxWidth().clickable { vm.selectHost(h.address) }.padding(vertical = 12.dp)) {
                Text(h.name ?: h.address, style = MaterialTheme.typography.titleMedium)
                if (h.name != null) Text(h.address, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
    if (!state.searching) OutlinedButton(onClick = { vm.startDiscovery() }) { Text("Search again") }
    Text("Enter address manually", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = manual, onValueChange = { manual = it }, singleLine = true,
            label = { Text("e.g. 192.168.50.1") }, modifier = Modifier.weight(1f),
        )
        Button(onClick = { vm.selectHost(manual) }, enabled = manual.isNotBlank()) { Text("Next") }
    }
}

@Composable
private fun ShareStep(state: SetupUiState, vm: SetupViewModel) {
    var manual by remember { mutableStateOf("") }
    Text("Drive: ${state.host}")
    if (state.sharesNeedLogin) {
        LoginFields(state, vm)
        Button(onClick = { vm.loadShares() }, enabled = !state.loadingShares && state.username.isNotBlank()) {
            Text("List shared folders")
        }
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(state.shares, key = { it }) { s ->
            Text(s, Modifier.fillMaxWidth().clickable { vm.selectShare(s) }.padding(vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
        }
    }
    Text("Or type the shared folder name", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = manual, onValueChange = { manual = it }, singleLine = true, modifier = Modifier.weight(1f))
        Button(onClick = { vm.selectShare(manual) }, enabled = manual.isNotBlank()) { Text("Next") }
    }
}

@Composable
private fun LoginFields(state: SetupUiState, vm: SetupViewModel) {
    OutlinedTextField(
        value = state.username, onValueChange = vm::setUsername, label = { Text("Username") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.password, onValueChange = vm::setPassword, label = { Text("Password") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun CredentialsStep(state: SetupUiState, vm: SetupViewModel) {
    Text("Shared folder: ${state.share} on ${state.host}")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Guest (no password)", Modifier.weight(1f))
        Switch(checked = state.guest, onCheckedChange = vm::setGuest)
    }
    if (!state.guest) LoginFields(state, vm)
    Button(
        onClick = { vm.testConnection() },
        enabled = !state.busy && (state.guest || state.username.isNotBlank()),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Test connection") }
}

@Composable
private fun PermissionsStep(vm: SetupViewModel) {
    val context = LocalContext.current
    val perms = remember { setupPermissions(Build.VERSION.SDK_INT) }
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val granted = remember(tick) {
        perms.associateWith { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    val access = remember(tick) { MediaAccess.current(context) }
    val mediaOk = access == MediaAccess.FULL
    Text("Hearthhold needs to read your photos and videos to back them up, and to show notifications about problems.")
    Text(
        when (access) {
            MediaAccess.FULL -> "Photos and videos: allowed"
            MediaAccess.PARTIAL -> "Photos and videos: only some allowed — choose \"Allow all\""
            MediaAccess.NONE -> "Photos and videos: not allowed yet"
        }
    )
    if (perms.any { it.endsWith("POST_NOTIFICATIONS") }) {
        val n = granted.entries.first { it.key.endsWith("POST_NOTIFICATIONS") }.value
        Text(if (n) "Notifications: allowed" else "Notifications: not allowed yet")
    }
    if (!mediaOk || granted.entries.any { it.key.endsWith("POST_NOTIFICATIONS") && !it.value }) {
        Button(onClick = { launcher.launch(perms.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) { Text("Allow") }
    }
    if (!mediaOk) {
        Text("Without access to all photos, only some (or none) can be backed up.", color = MaterialTheme.colorScheme.error)
    }
    OutlinedButton(onClick = { vm.onPermissionsDone() }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
}
