package dev.photobackup.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.photobackup.settings.AppSettings
import dev.photobackup.smb.RemoteStore
import dev.photobackup.ui.Screen
import dev.photobackup.ui.setup.FolderPicker

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, driveStore: () -> RemoteStore, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val s = state.settings

    BackHandler(enabled = picking) { picking = false }
    Screen(
        if (picking) "Backup folder" else "Settings",
        onBack = { if (picking) picking = false else onBack() },
    ) {
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Screen
        }
        if (picking) {
            val store = remember { driveStore() }
            DisposableEffect(store) { onDispose { store.close() } }
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                FolderPicker(store, initialPath = s.destination.orEmpty()) {
                    viewModel.setDestination(it)
                    picking = false
                }
            }
            return@Screen
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DriveSection(s, state, viewModel)
            HorizontalDivider()
            Section("Backup folder")
            Text(s.destination?.replace('\\', '/') ?: "Not set")
            OutlinedButton(onClick = { picking = true }) { Text("Change folder") }
            HorizontalDivider()
            DeviceName(s, viewModel)
            HorizontalDivider()
            SwitchRow("Only back up while charging", s.chargingOnly) { viewModel.setChargingOnly(it) }
            HorizontalDivider()
            FreeUpAge(s, viewModel)
            HorizontalDivider()
            Folders(s, viewModel)
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DriveSection(s: AppSettings, state: SettingsUiState, vm: SettingsViewModel) {
    var host by remember(s.smb?.host) { mutableStateOf(s.smb?.host.orEmpty()) }
    var share by remember(s.smb?.share) { mutableStateOf(s.smb?.share.orEmpty()) }
    var guest by remember(s.smb) { mutableStateOf(s.smb != null && s.smb.username == null) }
    var user by remember(s.smb?.username) { mutableStateOf(s.smb?.username.orEmpty()) }
    var pass by remember { mutableStateOf("") }

    Section("Drive")
    OutlinedTextField(host, { host = it }, label = { Text("Drive address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(share, { share = it }, label = { Text("Shared folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    SwitchRow("Guest (no password)", guest) { guest = it }
    if (!guest) {
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") }, placeholder = { Text("Leave blank to keep") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }
    Button(
        onClick = { vm.saveDrive(host, share, guest, user, pass); pass = "" },
        enabled = !state.testing,
    ) { Text(if (state.testing) "Testing…" else "Test and save") }
    state.driveMessage?.let { Text(it) }
}

@Composable
private fun DeviceName(s: AppSettings, vm: SettingsViewModel) {
    var name by remember(s.deviceName) { mutableStateOf(s.deviceName) }
    Section("Device name")
    Text("Backups go into a folder with this name.", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.setDeviceName(name) }, enabled = name.trim() != s.deviceName) { Text("Save") }
    }
}

@Composable
private fun FreeUpAge(s: AppSettings, vm: SettingsViewModel) {
    var months by remember(s.freeUpAgeMonths) { mutableFloatStateOf(s.freeUpAgeMonths.toFloat()) }
    Section("Free up: items older than ${months.toInt()} ${if (months.toInt() == 1) "month" else "months"}")
    Slider(
        value = months,
        onValueChange = { months = it },
        onValueChangeFinished = { vm.setFreeUpAge(months.toInt()) },
        valueRange = 1f..36f,
        steps = 34,
    )
}

@Composable
private fun Folders(s: AppSettings, vm: SettingsViewModel) {
    var extra by remember { mutableStateOf("") }
    Section("Phone folders to back up")
    SwitchRow("Camera (DCIM/Camera)", s.folders.camera) { vm.setCamera(it) }
    SwitchRow("Screenshots", s.folders.screenshots) { vm.setScreenshots(it) }
    for (p in s.folders.extraRelativePaths.sorted()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p, Modifier.weight(1f))
            TextButton(onClick = { vm.removeFolder(p) }) { Text("Remove") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            extra, { extra = it }, singleLine = true, modifier = Modifier.weight(1f),
            label = { Text("Add folder, e.g. Pictures/WhatsApp") },
        )
        TextButton(onClick = { vm.addFolder(extra); extra = "" }, enabled = extra.isNotBlank()) { Text("Add") }
    }
}
