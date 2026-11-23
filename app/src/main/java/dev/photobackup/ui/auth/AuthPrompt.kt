package dev.photobackup.ui.auth

import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.SmbConfig
import dev.photobackup.ui.MSG_AUTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** No drive has been set up yet. */
class DriveNotConfiguredException : IOException("No drive configured")

/** What the "Re-enter drive password" dialog shows. [busy] while the new login is being tried. */
data class AuthRequest(val username: String, val error: String? = null, val busy: Boolean = false)

/**
 * One saved login per drive (spec "Remembering the login"). Every UI drive access runs through [withAuthRetry]:
 * on "wrong username or password" (or an undecryptable saved password) it asks the dialog shown by
 * `AuthPromptHost`, tries the action with the new login, saves the password once the drive has not refused it,
 * and returns the action's result. Cancel rethrows the original failure and leaves the old login in place.
 * Concurrent failures share one dialog.
 */
class AuthPrompt(
    private val load: suspend () -> SmbConfig?,
    private val save: suspend (username: String, password: String) -> Unit,
    /** Called once after a new login has been verified and saved (clears the stale error, resumes backups). */
    private val onReauthSaved: suspend () -> Unit = {},
) {
    private val _request = MutableStateFlow<AuthRequest?>(null)
    val request: StateFlow<AuthRequest?> = _request.asStateFlow()

    private val lock = Mutex()
    @Volatile private var answer: CompletableDeferred<Pair<String, String>?>? = null

    suspend fun <T> withAuthRetry(block: suspend (SmbConfig) -> T): T {
        val cfg = load() ?: throw DriveNotConfiguredException()
        if (undecryptable(cfg)) return reauth(cfg, RemoteException.AuthFailed(), block, force = false)
        return try {
            block(cfg)
        } catch (e: RemoteException.AuthFailed) {
            reauth(cfg, e, block, force = false)
        }
    }

    /** Shows the dialog without a failed action first (notification tap, Home banner); [verify] tries the login. */
    suspend fun <T> prompt(verify: suspend (SmbConfig) -> T): T {
        val cfg = load() ?: throw DriveNotConfiguredException()
        return reauth(cfg, RemoteException.AuthFailed(), verify, force = true)
    }

    fun submit(username: String, password: String) {
        answer?.complete(username.trim() to password)
    }

    fun cancel() {
        answer?.complete(null)
    }

    private fun undecryptable(cfg: SmbConfig) = cfg.username != null && cfg.password == null

    private suspend fun <T> reauth(
        failed: SmbConfig,
        cause: RemoteException.AuthFailed,
        block: suspend (SmbConfig) -> T,
        force: Boolean,
    ): T = lock.withLock {
        var base = failed
        if (!force) {
            // Another caller may have fixed the login while this one waited for the dialog.
            val now = load()
            if (now != null && now != failed && !undecryptable(now)) {
                try {
                    return block(now)
                } catch (_: RemoteException.AuthFailed) {
                    base = now
                }
            }
        }
        try {
            askUntilAccepted(base, cause, block)
        } finally {
            answer = null
            _request.value = null
        }
    }

    /** Asks until the drive accepts the typed login (returns the action's result) or the user cancels. */
    private suspend fun <T> askUntilAccepted(
        base: SmbConfig,
        cause: RemoteException.AuthFailed,
        block: suspend (SmbConfig) -> T,
    ): T {
        var error: String? = null
        while (true) {
            val (user, pass) = ask(base.username.orEmpty(), error) ?: throw cause
            _request.value = AuthRequest(user, busy = true)
            val next = base.copy(username = user, password = pass)
            try {
                val result = block(next)
                save(user, pass)
                onReauthSaved()
                return result
            } catch (_: RemoteException.AuthFailed) {
                error = MSG_AUTH
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The drive did not refuse the login (e.g. not reachable now): keep what the user typed.
                save(user, pass)
                throw e
            }
        }
    }

    private suspend fun ask(username: String, error: String?): Pair<String, String>? {
        val d = CompletableDeferred<Pair<String, String>?>()
        answer = d
        _request.value = AuthRequest(username, error)
        return d.await()
    }
}
