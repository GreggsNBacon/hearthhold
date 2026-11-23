package dev.photobackup.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Place once at the app root (per prompt): shows "Re-enter drive password" (or [title], e.g. for the second backup
 * location) whenever [prompt] asks for it.
 */
@Composable
fun AuthPromptHost(prompt: AuthPrompt, title: String = "Re-enter drive password") {
    val request by prompt.request.collectAsState()
    val req = request ?: return
    var username by remember { mutableStateOf(req.username) }
    // A fresh field after each refused attempt.
    var password by remember(req.error) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!req.busy) prompt.cancel() },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    enabled = !req.busy,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !req.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                req.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (req.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Logging in…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { prompt.submit(username, password) },
                enabled = !req.busy && username.isNotBlank(),
            ) { Text("Log in") }
        },
        dismissButton = {
            TextButton(onClick = { prompt.cancel() }, enabled = !req.busy) { Text("Cancel") }
        },
    )
}
