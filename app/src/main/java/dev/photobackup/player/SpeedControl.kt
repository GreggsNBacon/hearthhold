package dev.photobackup.player

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** "1×" chip; tapping opens the [PlayerPrefs.SPEEDS] menu. */
@Composable
fun SpeedChip(speed: Float, onSelect: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(PlayerPrefs.label(speed)) },
            colors = AssistChipDefaults.assistChipColors(labelColor = Color.White),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PlayerPrefs.SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(PlayerPrefs.label(s) + if (s == speed) "  ✓" else "") },
                    onClick = {
                        open = false
                        onSelect(s)
                    },
                )
            }
        }
    }
}

/** "Loop" chip, selected while videos repeat. */
@Composable
fun LoopChip(loop: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = loop,
        onClick = { onChange(!loop) },
        label = { Text(if (loop) "Loop on" else "Loop off") },
        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
    )
}
