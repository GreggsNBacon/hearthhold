package dev.photobackup.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.photobackup.backup.BackupMessages
import dev.photobackup.backup.BackupNotifications
import dev.photobackup.freeup.formatSize

/** The compact status chip in the Gallery's top bar; tap opens the status sheet. */
@Composable
fun StatusChip(chip: ChipState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (chip.kind) {
        ChipKind.OK -> colors.secondaryContainer to colors.onSecondaryContainer
        ChipKind.BUSY -> colors.primaryContainer to colors.onPrimaryContainer
        ChipKind.WAITING -> colors.surfaceVariant to colors.onSurfaceVariant
        ChipKind.PROBLEM -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = bg, contentColor = fg, modifier = modifier) {
        Text(
            chip.text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * The old Home content, shown in a bottom sheet from the status chip. [reach] and [reachMessage] come from the
 * Gallery's own drive check.
 */
@Composable
fun StatusSheetContent(
    state: HomeUiState,
    reach: Reachability,
    reachMessage: String?,
    onBackUpNow: () -> Unit,
    onFreeUp: () -> Unit,
    onReenter: () -> Unit,
    /** Opens "Re-enter second location password". */
    onReenterSecond: () -> Unit = {},
    /** Opens Swipe review. */
    onReview: () -> Unit = {},
    /** Files flagged too big for this drive's format (FAT32). */
    tooBig: Int = 0,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.needsPassword) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp)) {
                    Text("The saved drive password can't be read.", Modifier.weight(1f))
                    TextButton(onClick = onReenter) { Text("Re-enter drive password") }
                }
            }
        }
        StatusCard(state, reach, reachMessage)
        state.secondLine?.let { line ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(line, Modifier.weight(1f))
                    if (state.secondNeedsPassword) TextButton(onClick = onReenterSecond) { Text("Re-enter password") }
                }
            }
        }
        val limits = driveLimitLines(state.status?.driveFreeBytes, tooBig)
        if (limits.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    limits.forEach { Text(it) }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Count("Waiting", state.waiting, Modifier.weight(1f))
                Count("Backed up", state.backedUp, Modifier.weight(1f))
                Count("Failed", state.failed, Modifier.weight(1f))
            }
        }
        Button(
            onClick = onBackUpNow,
            enabled = state.status?.inProgress != true,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back up now") }
        if (state.freeUpBytes > 0) {
            Button(onClick = onFreeUp, modifier = Modifier.fillMaxWidth()) {
                Text("Free up ${formatSize(state.freeUpBytes)}")
            }
        }
        OutlinedButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) { Text("Swipe review") }
    }
}

@Composable
private fun StatusCard(state: HomeUiState, reach: Reachability, reachMessage: String?) {
    val status = state.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (reach) {
                    Reachability.YES -> "Drive reachable"
                    Reachability.NO -> "Drive not reachable"
                    Reachability.CHECKING -> "Checking the drive…"
                    Reachability.UNKNOWN -> "Drive: not checked"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            reachMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val last = status?.lastRunMs?.let {
                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            }
            Text("Last backup: ${last ?: "never"}")
            status?.lastResult?.let { Text(it) }
            status?.lastError?.let { if (it != status.lastResult) Text(it, color = MaterialTheme.colorScheme.error) }
            if (status?.inProgress == true) {
                Text(if (status.moving > 0) BackupMessages.moving(status.moving) else "Backing up ${status.done} of ${status.total}")
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
