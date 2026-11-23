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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.photobackup.backup.BackupEngine
import dev.photobackup.media.PhoneFolders
import dev.photobackup.review.ReviewAction
import dev.photobackup.review.Swipe
import dev.photobackup.review.gestureLabel
import dev.photobackup.review.label
import dev.photobackup.settings.AppSettings
import dev.photobackup.smb.RemoteStore
import dev.photobackup.ui.Screen
import dev.photobackup.ui.setup.FolderPicker

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, driveStore: () -> RemoteStore, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var addingFolder by remember { mutableStateOf(false) }
    var addingRule by remember { mutableStateOf(false) }
    var rulePath by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<RuleConfirm?>(null) }
    val s = state.settings

    BackHandler(enabled = picking || addingFolder || addingRule) {
        if (rulePath != null) rulePath = null else { picking = false; addingFolder = false; addingRule = false }
    }
    Screen(
        when {
            picking -> "Backup folder"
            addingFolder -> "Add phone folder"
            rulePath != null -> "Name the folder"
            addingRule -> "Folder for a phone folder"
            else -> "Settings"
        },
        onBack = {
            when {
                rulePath != null -> rulePath = null
                picking || addingFolder || addingRule -> { picking = false; addingFolder = false; addingRule = false }
                else -> onBack()
            }
        },
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
        if (addingFolder) {
            PhoneFolderBrowser(
                list = state.phoneFolders,
                folders = s.folders,
                onAdd = { viewModel.addFolder(it); addingFolder = false },
                onRetry = { viewModel.loadPhoneFolders() },
            )
            return@Screen
        }
        if (addingRule) {
            val path = rulePath
            if (path == null) {
                PhoneFolderBrowser(
                    list = state.phoneFolders,
                    folders = s.folders,
                    onAdd = { rulePath = PhoneFolders.normalise(it) },
                    onRetry = { viewModel.loadPhoneFolders() },
                    pickIncluded = true,
                    actionLabel = "Choose",
                )
            } else {
                RuleNameEntry(
                    path, s.folderRules.values.distinct().sortedBy { it.lowercase() }, viewModel, state.ruleBusy,
                    state.ruleMessage,
                ) { name ->
                    viewModel.saveRule(
                        path, name,
                        onAdded = { rulePath = null; addingRule = false },
                        onAsk = { n -> confirm = RuleConfirm.Add(path, name, n) },
                    )
                }
            }
            RuleDialog(confirm, { confirm = null }) { c, yes ->
                confirm = null
                if (c is RuleConfirm.Add) {
                    viewModel.addRule(c.path, c.name, yes)
                    rulePath = null; addingRule = false
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
            SwitchRow("Notify when a backup finishes", s.notifyOnComplete) { viewModel.setNotifyOnComplete(it) }
            Overnight(s, viewModel)
            HorizontalDivider()
            Stepper(
                label = "Files at once (faster, but busier router)",
                value = s.parallelUploads,
                range = 1..BackupEngine.MAX_PARALLEL,
                format = { it.toString() },
            ) { viewModel.setParallelUploads(it) }
            HorizontalDivider()
            FreeUpAge(s, viewModel)
            HorizontalDivider()
            Gestures(s, state.gestureMessage, viewModel)
            HorizontalDivider()
            Folders(s, viewModel) { viewModel.loadPhoneFolders(); addingFolder = true }
            HorizontalDivider()
            FolderRulesSection(s, state.ruleMessage, onAdd = { viewModel.loadPhoneFolders(); addingRule = true }) { path ->
                viewModel.askRemoveRule(path) { n -> confirm = RuleConfirm.Remove(path, n) }
            }
            RuleDialog(confirm, { confirm = null }) { c, yes ->
                confirm = null
                if (c is RuleConfirm.Remove) viewModel.removeRule(c.path, yes)
            }
        }
    }
}

/** "Swipe gestures": what each swipe does in Swipe review. No action can't be chosen for the last active swipe. */
@Composable
private fun Gestures(s: AppSettings, message: String?, vm: SettingsViewModel) {
    Section("Swipe gestures")
    Text("What each swipe does in Swipe review.", style = MaterialTheme.typography.bodySmall)
    for (swipe in listOf(Swipe.UP, Swipe.RIGHT, Swipe.LEFT, Swipe.DOWN)) {
        var open by remember { mutableStateOf(false) }
        val current = s.gestures[swipe]
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(swipe.label, Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { open = true }) { Text(gestureLabel(current)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (option in GESTURE_OPTIONS) {
                        val allowed = canSetGesture(s.gestures, swipe, option)
                        DropdownMenuItem(
                            text = {
                                Text(if (allowed) gestureLabel(option) else "${gestureLabel(option)} (one swipe must do something)")
                            },
                            enabled = allowed,
                            onClick = { open = false; if (option != current) vm.setGesture(swipe, option) },
                        )
                    }
                }
            }
        }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private val GESTURE_OPTIONS: List<ReviewAction?> =
    listOf(ReviewAction.KEEP, ReviewAction.DRIVE_ONLY, ReviewAction.JUNK, ReviewAction.SKIP, null)

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
private fun Overnight(s: AppSettings, vm: SettingsViewModel) {
    SwitchRow("Only back up overnight", s.overnightOnly) { vm.setOvernightOnly(it) }
    if (!s.overnightOnly) return
    Text(
        "Automatic backups wait for this window. \"Back up now\" always runs.",
        style = MaterialTheme.typography.bodySmall,
    )
    Stepper("From", s.overnightStartHour, 0..23, { "$it:00" }) { vm.setOvernightHours(it, s.overnightEndHour) }
    Stepper("Until", s.overnightEndHour, 0..23, { "$it:00" }) { vm.setOvernightHours(s.overnightStartHour, it) }
}

/** A label with − value + buttons; [onChange] gets the new value, always inside [range]. */
@Composable
private fun Stepper(label: String, value: Int, range: IntRange, format: (Int) -> String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onChange(value - 1) }, enabled = value > range.first) { Text("−") }
        Text(format(value), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onChange(value + 1) }, enabled = value < range.last) { Text("+") }
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
private fun Folders(s: AppSettings, vm: SettingsViewModel, onAddFolder: () -> Unit) {
    Section("Phone folders to back up")
    SwitchRow("Camera (DCIM/Camera)", s.folders.camera) { vm.setCamera(it) }
    SwitchRow("Screenshots", s.folders.screenshots) { vm.setScreenshots(it) }
    for (p in s.folders.extraRelativePaths.sorted()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p, Modifier.weight(1f))
            TextButton(onClick = { vm.removeFolder(p) }) { Text("Remove") }
        }
    }
    OutlinedButton(onClick = onAddFolder) { Text("Add folder") }
}

private sealed interface RuleConfirm {
    val n: Int
    data class Add(val path: String, val name: String, override val n: Int) : RuleConfirm
    data class Remove(val path: String, override val n: Int) : RuleConfirm
}

@Composable
private fun RuleDialog(c: RuleConfirm?, onDismiss: () -> Unit, onAnswer: (RuleConfirm, Boolean) -> Unit) {
    if (c == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(
                when (c) {
                    is RuleConfirm.Add -> "Also move the ${c.n} already on the drive?"
                    is RuleConfirm.Remove -> "Move the ${c.n} back to the default?"
                }
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(c, true) }) { Text("Yes") } },
        dismissButton = { TextButton(onClick = { onAnswer(c, false) }) { Text("No") } },
    )
}

@Composable
private fun FolderRulesSection(s: AppSettings, message: String?, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Section("Folder rules")
    Text("Send a phone folder's backups to a drive folder with a name you choose.", style = MaterialTheme.typography.bodySmall)
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    for ((path, name) in s.folderRules.entries.sortedBy { it.key.lowercase() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${path.removeSuffix("/")} \u2192 $name", Modifier.weight(1f))
            TextButton(onClick = { onRemove(path) }) { Text("Remove") }
        }
    }
    OutlinedButton(onClick = onAdd) { Text("Add rule") }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RuleNameEntry(
    path: String,
    existing: List<String>,
    vm: SettingsViewModel,
    busy: Boolean,
    message: String?,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(path.removeSuffix("/"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            name, { name = it; error = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Drive folder name") }, isError = error != null,
            supportingText = error?.let { { Text(it) } },
        )
        if (existing.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (e in existing) AssistChip(onClick = { name = e; error = null }, label = { Text(e) })
            }
        }
        Button(enabled = !busy, onClick = {
            val err = vm.nameError(name)
            if (err != null) error = err else onSave(name)
        }) { Text("Save rule") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
