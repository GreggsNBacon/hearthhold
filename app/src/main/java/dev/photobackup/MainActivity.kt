package dev.photobackup

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.photobackup.app.PhotoBackupApp
import dev.photobackup.ui.AppNav
import dev.photobackup.ui.auth.AuthPromptHost
import dev.photobackup.ui.theme.PhotoBackupTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as PhotoBackupApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            PhotoBackupTheme {
                AppNav(container)
                AuthPromptHost(container.authPrompt)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The "Wrong username or password" notification opens the re-enter password dialog. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REAUTH, false) != true) return
        intent.removeExtra(EXTRA_REAUTH)
        container.reauthFromNotification()
    }

    companion object {
        const val EXTRA_REAUTH = "dev.photobackup.extra.REAUTH"
    }
}
