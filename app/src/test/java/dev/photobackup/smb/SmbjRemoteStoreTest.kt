package dev.photobackup.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMBApiException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.EnumSet

/** JVM checks of SmbjRemoteStore's argument construction and error mapping (no server needed). */
class SmbjRemoteStoreTest {

    @Test fun negotiatesSmb202ForOldRouters() {
        // ASUS routers' "SMB2" is SMB 2.0.2; without it negotiation fails and looks like SMB1-only.
        assertTrue(SMB2Dialect.SMB_2_0_2 in SmbjRemoteStore.DIALECTS)
        assertTrue(SMB2Dialect.SMB_3_1_1 in SmbjRemoteStore.DIALECTS)
    }

    /** smbj does EnumSet.copyOf(set), which throws for an empty set that is not an EnumSet. */
    private fun assertEnumSafe(name: String, set: Set<*>) {
        assertTrue("$name must be an EnumSet", set is EnumSet<*>)
        @Suppress("UNCHECKED_CAST")
        EnumSet.copyOf(set as Collection<Nothing>) // must not throw
    }

    @Test fun everyOpenUsesEnumSets() {
        val all = mapOf(
            "write" to SmbOpenArgs.write(),
            "read" to SmbOpenArgs.read(),
            "rename" to SmbOpenArgs.rename(),
            "setTimes" to SmbOpenArgs.setTimes(),
        )
        for ((name, a) in all) {
            assertEnumSafe("$name.access", a.access)
            assertEnumSafe("$name.attributes", a.attributes)
            assertEnumSafe("$name.shareAccess", a.shareAccess)
            assertEnumSafe("$name.createOptions", a.createOptions)
        }
    }

    @Test fun writeNeverOverwrites() {
        assertEquals(com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_CREATE, SmbOpenArgs.write().disposition)
        assertEquals(com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN, SmbOpenArgs.setTimes().disposition)
    }

    @Test fun unexpectedRuntimeErrorsBecomeIOExceptions() {
        val iae = IllegalArgumentException("Collection is empty")
        val mapped = SmbjRemoteStore.mapOp(iae)
        assertTrue(mapped is IOException && mapped !is RemoteException)
        assertSame(iae, mapped.cause)

        val ise = SmbjRemoteStore.mapOp(IllegalStateException("x"))
        assertTrue(ise is IOException && ise !is RemoteException)
    }

    @Test fun unmappedSmbStatusIsPlainIOException() {
        val e = SMBApiException(NtStatus.STATUS_INVALID_PARAMETER.value, null, "bad", null)
        val mapped = SmbjRemoteStore.mapOp(e)
        assertTrue(mapped is IOException && mapped !is RemoteException)
    }

    @Test fun cancellationAndIOExceptionsPassThrough() {
        val c = CancellationException("stop")
        assertSame(c, SmbjRemoteStore.mapOp(c))
        val io = IOException("io")
        assertSame(io, SmbjRemoteStore.mapOp(io))
        val r = RemoteException.DriveFull()
        assertSame(r, SmbjRemoteStore.mapOp(r))
    }

    @Test fun remoteMappingUnchanged() {
        val e = SMBApiException(NtStatus.STATUS_DISK_FULL.value, null, "full", null)
        assertTrue(SmbjRemoteStore.mapOp(e) is RemoteException.DriveFull)
    }
}

class SmbjAccessDeniedMappingTest {
    private fun api(s: NtStatus) = SMBApiException(s.value, null, "x", null)

    @Test fun accessDeniedOnFileOpIsNotAuthFailure() {
        val m = SmbjRemoteStore.mapOp(api(NtStatus.STATUS_ACCESS_DENIED))
        assertTrue(m is AccessDeniedException)
        assertTrue(m !is RemoteException)
        assertEquals("You don't have permission to open this on the drive", m.message)
    }

    @Test fun logonFailureIsStillAuthFailed() {
        assertTrue(SmbjRemoteStore.mapOp(api(NtStatus.STATUS_LOGON_FAILURE)) is RemoteException.AuthFailed)
        assertTrue(SmbjRemoteStore.mapConnect(api(NtStatus.STATUS_LOGON_FAILURE)) is RemoteException.AuthFailed)
    }

    @Test fun accessDeniedDuringConnectIsAuthFailed() {
        assertTrue(SmbjRemoteStore.mapConnect(api(NtStatus.STATUS_ACCESS_DENIED)) is RemoteException.AuthFailed)
    }

    @Test fun wrappedErrorsGetFriendlyText() {
        assertEquals("Drive error — will try again", SmbjRemoteStore.mapOp(IllegalStateException("x")).message)
        assertEquals(MSG_DRIVE_ERROR, SmbjRemoteStore.mapOp(IllegalArgumentException("Collection is empty")).message)
        val rt = SmbjRemoteStore.mapOp(com.hierynomus.smbj.common.SMBRuntimeException("internal"))
        assertTrue(rt is IOException && rt !is RemoteException)
        assertEquals(MSG_DRIVE_ERROR, rt.message)
    }

    private val dontSet = com.hierynomus.msfscc.fileinformation.FileBasicInformation.DONT_SET

    @Test fun setTimesSetsCreationAndLastWrite() {
        val calls = mutableListOf<com.hierynomus.msfscc.fileinformation.FileBasicInformation>()
        SmbjRemoteStore.setTimesWithFallback(1_700_000_000_000L) { calls += it }
        assertEquals(1, calls.size)
        assertEquals(1_700_000_000_000L, calls[0].lastWriteTime.toEpochMillis())
        assertEquals(1_700_000_000_000L, calls[0].creationTime.toEpochMillis())
    }

    @Test fun setTimesRetriesWithLastWriteOnlyWhenCreationIsRefused() {
        val calls = mutableListOf<com.hierynomus.msfscc.fileinformation.FileBasicInformation>()
        SmbjRemoteStore.setTimesWithFallback(1_700_000_000_000L) {
            calls += it
            if (calls.size == 1) throw SMBApiException(NtStatus.STATUS_ACCESS_DENIED.value, null, "no", null)
        }
        assertEquals(2, calls.size)
        assertEquals(1_700_000_000_000L, calls[1].lastWriteTime.toEpochMillis())
        assertEquals(dontSet.windowsTimeStamp, calls[1].creationTime.windowsTimeStamp)
        assertEquals(dontSet.windowsTimeStamp, calls[1].lastAccessTime.windowsTimeStamp)
        assertEquals(dontSet.windowsTimeStamp, calls[1].changeTime.windowsTimeStamp)
    }

    @Test fun setTimesRetryFailurePropagates() {
        var n = 0
        val e = runCatching {
            SmbjRemoteStore.setTimesWithFallback(1L) { n++; throw IllegalStateException("refused $n") }
        }.exceptionOrNull()
        assertEquals(2, n)
        assertEquals("refused 2", e?.message)
    }

    @Test fun setTimesDoesNotRetryANetworkFailure() {
        var n = 0
        val e = runCatching {
            SmbjRemoteStore.setTimesWithFallback(1L) {
                n++; throw com.hierynomus.protocol.transport.TransportException("link down")
            }
        }.exceptionOrNull()
        assertEquals(1, n)
        assertTrue(e is com.hierynomus.protocol.transport.TransportException)
    }
}
