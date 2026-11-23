package dev.photobackup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** "Couldn't read settings" with a Retry button; shown instead of a screen whose settings can't be read. */
@Composable
fun SettingsErrorNotice(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't read settings")
        Button(onClick = onRetry) { Text("Retry") }
    }
}
