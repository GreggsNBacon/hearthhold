package dev.photobackup.ui.auth

import dev.photobackup.smb.DriveInfo
import dev.photobackup.smb.RemoteEntry
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [RemoteStore] for the saved drive whose every call goes through [AuthPrompt.withAuthRetry]. It connects lazily,
 * keeps one connection while the saved login is unchanged, and reconnects after a login change or a drive error.
 * Opening a subfolder never prompts; only a refused login does.
 */
class AuthRetryingStore(
    private val auth: AuthPrompt,
    private val factory: (SmbConfig) -> RemoteStore,
) : RemoteStore {
    /** Serialises connecting (suspends; may wait for the password dialog). */
    private val mutex = Mutex()

    /**
     * Guards [current] and [generation] for short, non-suspending sections, so [close] (not suspending, may run on
     * the main thread) never waits for a connect in progress. A connect that finishes after [close] sees the
     * generation changed, closes its new connection and fails instead of leaking it.
     */
    private val state = Any()
    private var current: Pair<SmbConfig, RemoteStore>? = null
    private var generation = 0L

    /** Open (or reuse) a connection for [cfg]. */
    private suspend fun session(cfg: SmbConfig, fresh: Boolean): Pair<RemoteStore, DriveInfo?> = mutex.withLock {
        val (cur, gen) = synchronized(state) {
            val c = current
            if (!fresh && c != null && c.first == cfg) return c.second to null
            current = null
            c to generation
        }
        cur?.second?.let { runCatching { it.close() } }
        val s = factory(cfg)
        val info = try {
            s.connect()
        } catch (e: Throwable) {
            runCatching { s.close() }
            throw e
        }
        val closedMeanwhile = synchronized(state) {
            if (generation != gen) {
                true
            } else {
                current = cfg to s
                false
            }
        }
        if (closedMeanwhile) {
            runCatching { s.close() }
            throw IOException("Drive connection closed")
        }
        s to info
    }

    private fun drop(s: RemoteStore) {
        val dropped = synchronized(state) {
            val cur = current
            if (cur != null && cur.second === s) {
                current = null
                true
            } else {
                false
            }
        }
        if (dropped) runCatching { s.close() }
    }

    private suspend fun <T> op(block: suspend (RemoteStore) -> T): T = auth.withAuthRetry { cfg ->
        val (s, _) = session(cfg, fresh = false)
        try {
            block(s)
        } catch (e: RemoteException) {
            drop(s) // connection may be dead or the login revoked: reconnect next time
            throw e
        }
    }

    override suspend fun connect(): DriveInfo = auth.withAuthRetry { cfg -> session(cfg, fresh = true).second!! }
    override suspend fun list(dir: String): List<RemoteEntry> = op { it.list(dir) }
    override suspend fun stat(path: String): RemoteEntry? = op { it.stat(path) }
    override suspend fun mkdirs(dir: String) = op { it.mkdirs(dir) }
    override suspend fun openWrite(path: String): OutputStream = op { it.openWrite(path) }
    override suspend fun openRead(path: String, offset: Long): InputStream = op { it.openRead(path, offset) }
    override suspend fun rename(from: String, to: String) = op { it.rename(from, to) }
    override suspend fun delete(path: String) = op { it.delete(path) }

    /**
     * Closes the open connection (and any connection still being opened). The store stays usable: the next call
     * reconnects. Streams from [openRead] die with their connection, so a long-lived stream should use its own store.
     */
    override fun close() {
        val cur = synchronized(state) {
            generation++
            current.also { current = null }
        }
        cur?.second?.let { runCatching { it.close() } }
    }
}
