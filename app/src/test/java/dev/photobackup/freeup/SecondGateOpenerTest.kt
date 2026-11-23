package dev.photobackup.freeup

import dev.photobackup.backup.MemorySecondCopies
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.SecondLocation
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondGateOpenerTest {
    private val copies = MemorySecondCopies()
    private val secondSmb = SmbConfig("nas2", "Backup", "u", "p")
    private val location = SecondLocation(secondSmb, "Hearthhold2")
    private val opened = mutableListOf<SmbConfig>()
    private var closed = 0
    private val store = object : dev.photobackup.smb.RemoteStore by FakeRemoteStore() {
        var connectError: Exception? = null
        override suspend fun connect() = connectError?.let { throw it } ?: dev.photobackup.smb.DriveInfo(0, "EXFAT")
        override fun close() { closed++ }
    }

    private fun opener(settings: AppSettings) = SecondGateOpener(
        settings = { settings },
        openStore = { cfg -> opened += cfg; store },
        copies = copies,
    )

    private fun settings(second: SecondLocation?, needsBoth: Boolean = true) =
        AppSettings(null, null, deviceName = "Dev", second = second, freeUpNeedsBoth = needsBoth)

    @Test fun offWithoutASecondLocationOpensNoStore() = runBlocking {
        assertSame(SecondGateOpen.Off, opener(settings(null)).open())
        assertTrue(opened.isEmpty())
    }

    @Test fun offWhenFreeingUpWithOnlyTheMainDriveIsAllowed() = runBlocking {
        assertSame(SecondGateOpen.Off, opener(settings(location, needsBoth = false)).open())
        assertTrue(opened.isEmpty())
    }

    @Test fun onConnectsTheSecondStore() = runBlocking {
        val r = opener(settings(location)).open()
        assertTrue(r is SecondGateOpen.Open)
        assertEquals(listOf(secondSmb), opened)
        val gate = (r as SecondGateOpen.Open).gate
        assertSame(store, gate.store)
        assertSame(copies, gate.copies)
        assertEquals(0, closed)
        gate.close()
        assertEquals(1, closed)
    }

    @Test fun unreachableOrRefusedLoginIsUnreachableAndClosesTheStore() = runBlocking {
        for (err in listOf(RemoteException.Unreachable(), RemoteException.AuthFailed(), java.io.IOException("x"))) {
            closed = 0
            store.connectError = err
            assertSame(SecondGateOpen.Unreachable, opener(settings(location)).open())
            assertEquals(1, closed)
        }
    }
}
