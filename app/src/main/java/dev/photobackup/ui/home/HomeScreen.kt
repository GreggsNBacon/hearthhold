package dev.photobackup.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.photobackup.backup.BackupNotifications
import dev.photobackup.freeup.formatSize
import dev.photobackup.ui.Screen

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onFreeUp: () -> Unit,
    onGallery: () -> Unit,
    onBrowse: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh(auto = true) }

    Screen(
        "Hearthhold",
        actions = {
            TextButton(onClick = { viewModel.refresh() }) { Text("Refresh") }
            TextButton(onClick = onSettings) { Text("Settings") }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.needsPassword) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp)) {
                        Text("The saved drive password can't be read.", Modifier.weight(1f))
                        TextButton(onClick = { viewModel.reenter() }) { Text("Re-enter drive password") }
                    }
                }
            }
            StatusCard(state)
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Count("Waiting", state.waiting, Modifier.weight(1f))
                    Count("Backed up", state.backedUp, Modifier.weight(1f))
                    Count("Failed", state.failed, Modifier.weight(1f))
                }
            }
            Button(
                onClick = { viewModel.backUpNow() },
                enabled = state.status?.inProgress != true,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Back up now") }
            if (state.freeUpBytes > 0) {
                Button(onClick = onFreeUp, modifier = Modifier.fillMaxWidth()) {
                    Text("Free up ${formatSize(state.freeUpBytes)}")
                }
            }
            OutlinedButton(onClick = onGallery, modifier = Modifier.fillMaxWidth()) { Text("Gallery") }
            OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) { Text("Browse drive") }
        }
    }
}

@Composable
private fun StatusCard(state: HomeUiState) {
    val status = state.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (state.reach) {
                    Reachability.YES -> "Drive reachable"
                    Reachability.NO -> "Drive not reachable"
                    Reachability.CHECKING -> "Checking the drive…"
                    Reachability.UNKNOWN -> "Drive: not checked"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            state.reachMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val last = status?.lastRunMs?.let {
                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            }
            Text("Last backup: ${last ?: "never"}")
            status?.lastResult?.let { Text(it) }
            status?.lastError?.let { if (it != status.lastResult) Text(it, color = MaterialTheme.colorScheme.error) }
            if (status?.inProgress == true) {
                Text("Backing up ${status.done} of ${status.total}")
                if (status.total > 0) {
                    LinearProgressIndicator(
                        progress = { status.done.toFloat() / status.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                status.etaSec?.let { Text("About ${BackupNotifications.formatEta(it)} left") }
            }
        }
    }
}

@Composable
private fun Count(label: String, n: Int, modifier: Modifier) {
    Column(modifier) {
        Text("$n", style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
