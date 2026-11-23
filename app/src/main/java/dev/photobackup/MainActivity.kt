package dev.photobackup

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.photobackup.app.PhotoBackupApp
import dev.photobackup.backup.SecondStatusText
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
                AuthPromptHost(container.secondAuthPrompt, title = SecondStatusText.REENTER_PASSWORD)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The "Wrong username or password" notifications open the matching re-enter password dialog. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REAUTH_SECOND, false) == true) {
            intent.removeExtra(EXTRA_REAUTH_SECOND)
            // Re-opened from Recents: the extra is stale, do not prompt again.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
            container.reauthSecondFromNotification()
            return
        }
        if (intent?.getBooleanExtra(EXTRA_REAUTH, false) != true) return
        intent.removeExtra(EXTRA_REAUTH)
        // Re-opened from Recents: the extra is stale, do not prompt again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        container.reauthFromNotification()
    }

    companion object {
        const val EXTRA_REAUTH = "dev.photobackup.extra.REAUTH"
        const val EXTRA_REAUTH_SECOND = "dev.photobackup.extra.REAUTH_SECOND"
    }
}
