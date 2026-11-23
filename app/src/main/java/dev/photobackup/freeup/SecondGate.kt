package dev.photobackup.freeup

import dev.photobackup.ledger.SecondCopyDao
import dev.photobackup.settings.AppSettings
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.CancellationException
import java.io.Closeable

/**
 * The "verified on both drives" gate for [FreeUpRepository.recheck]: a connected [store] for the second location and
 * the SecondCopy table. Closing it closes the store.
 */
class SecondGate(val store: RemoteStore, val copies: SecondCopyDao) : Closeable {
    override fun close() = store.close()
}

sealed interface SecondGateOpen {
    /** No second location, or "Allow freeing up with only the main drive" is on: no gate, no connection. */
    data object Off : SecondGateOpen

    /** The gate is on but the second location could not be connected (offline, login refused, ...). */
    data object Unreachable : SecondGateOpen

    /** The gate is on and connected; the caller closes [gate]. */
    class Open(val gate: SecondGate) : SecondGateOpen
}

/** Decides whether the gate is on from fresh settings and, if so, opens and connects the second store. */
class SecondGateOpener(
    private val settings: suspend () -> AppSettings,
    private val openStore: (SmbConfig) -> RemoteStore,
    private val copies: SecondCopyDao,
) {
    suspend fun open(): SecondGateOpen {
        val s = settings()
        val second = s.second ?: return SecondGateOpen.Off
        if (!s.freeUpNeedsBoth) return SecondGateOpen.Off
        var store: RemoteStore? = null
        return try {
            store = openStore(second.smb)
            store.connect()
            SecondGateOpen.Open(SecondGate(store, copies))
        } catch (e: Exception) {
            runCatching { store?.close() }
            if (e is CancellationException) throw e
            SecondGateOpen.Unreachable // never the exception text: it could carry connection details
        }
    }
}
