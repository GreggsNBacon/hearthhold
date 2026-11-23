package dev.photobackup.ui.auth

import dev.photobackup.smb.DriveInfo
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRetryingStoreTest {
    private val prompt = AuthPrompt(load = { SmbConfig("nas", "usb", "greg", "pw") }, save = { _, _ -> })

    private class GatedStore(
        private val gate: CompletableDeferred<Unit>?,
        private val inner: FakeRemoteStore = FakeRemoteStore(),
    ) : RemoteStore by inner {
        var closed = false
        override suspend fun connect(): DriveInfo {
            gate?.await()
            return inner.connect()
        }
        override fun close() { closed = true }
    }

    @Test fun closeDuringConnectClosesTheNewConnection() = runTest {
        val gate = CompletableDeferred<Unit>()
        val created = mutableListOf<GatedStore>()
        val store = AuthRetryingStore(prompt) { GatedStore(if (created.isEmpty()) gate else null).also { created += it } }

        val listing = async { runCatching { store.list("") } }
        runCurrent()
        store.close()
        gate.complete(Unit)
        assertTrue(listing.await().isFailure)
        assertTrue("connection opened during close() must not leak", created.single().closed)

        // The store reconnects on the next call.
        store.list("")
        assertEquals(2, created.size)
        assertFalse(created[1].closed)
        store.close()
        assertTrue(created[1].closed)
    }
}
