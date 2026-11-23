package dev.photobackup.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import dev.photobackup.smb.AccessDeniedException
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbDataSourceTest {
    private val path = "DCIM\\clip 1.mp4"
    private val data = ByteArray(1000) { (it % 251).toByte() }
    private val uri: Uri = Uri.parse(SmbUri.build("nas", "usb", path))

    private fun store() = FakeRemoteStore().apply {
        dirs += "DCIM"
        files[path] = data
    }

    private fun readAll(src: SmbDataSource, chunk: Int = 64): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(chunk)
        while (true) {
            val n = src.read(buf, 0, chunk)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test fun readsFromRequestedOffset() {
        val src = SmbDataSource(store())
        val len = src.open(DataSpec.Builder().setUri(uri).setPosition(500).setLength(100).build())
        assertEquals(100L, len)
        assertArrayEquals(data.copyOfRange(500, 600), readAll(src, 33))
        assertEquals(C.RESULT_END_OF_INPUT, src.read(ByteArray(10), 0, 10))
        src.close()
    }

    @Test fun unboundedLengthReadsToEnd() {
        val src = SmbDataSource(store())
        val len = src.open(DataSpec.Builder().setUri(uri).setPosition(900).build())
        assertEquals(100L, len) // known from the file size
        assertArrayEquals(data.copyOfRange(900, 1000), readAll(src))
        src.close()
    }

    @Test fun wholeFileFromStart() {
        val src = SmbDataSource(store())
        assertEquals(1000L, src.open(DataSpec(uri)))
        assertEquals(uri, src.uri)
        assertArrayEquals(data, readAll(src, 128))
        src.close()
        assertNull(src.uri)
    }

    @Test fun lengthPastEndIsClampedToFileSize() {
        val src = SmbDataSource(store())
        assertEquals(50L, src.open(DataSpec.Builder().setUri(uri).setPosition(950).setLength(500).build()))
        assertArrayEquals(data.copyOfRange(950, 1000), readAll(src))
        src.close()
    }

    @Test fun positionPastEndFails() {
        val src = SmbDataSource(store())
        try {
            src.open(DataSpec.Builder().setUri(uri).setPosition(1001).build())
            fail()
        } catch (e: DataSourceException) {
            assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, e.reason)
        }
        src.close()
    }

    @Test fun zeroLengthReadReturnsZero() {
        val src = SmbDataSource(store())
        src.open(DataSpec(uri))
        assertEquals(0, src.read(ByteArray(4), 0, 0))
        src.close()
    }

    @Test fun closeClosesTheStream() {
        var closed = 0
        val base = store()
        val tracking = object : RemoteStore by base {
            override suspend fun openRead(path: String, offset: Long): InputStream {
                val s = base.openRead(path, offset)
                return object : InputStream() {
                    override fun read() = s.read()
                    override fun read(b: ByteArray, off: Int, len: Int) = s.read(b, off, len)
                    override fun close() {
                        closed++
                        s.close()
                    }
                }
            }
        }
        val src = SmbDataSource(tracking)
        src.open(DataSpec(uri))
        src.read(ByteArray(10), 0, 10)
        src.close()
        assertEquals(1, closed)
        src.close() // idempotent
        assertEquals(1, closed)
    }

    @Test fun driveErrorsSurfaceAsIOException() {
        val base = store().apply { failOn = { op, _ -> if (op == "openRead") RemoteException.Unreachable() else null } }
        val src = SmbDataSource(base)
        try {
            src.open(DataSpec(uri))
            fail()
        } catch (e: IOException) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is RemoteException.Unreachable })
        }
        src.close()
    }

    @Test fun nonIoErrorsAreWrappedAsIOException() {
        val base = store()
        val throwing = object : RemoteStore by base {
            override suspend fun openRead(path: String, offset: Long): InputStream = throw IllegalStateException("boom")
        }
        try {
            SmbDataSource(throwing).open(DataSpec(uri))
            fail()
        } catch (e: IOException) {
            assertTrue(e.cause is IllegalStateException)
        }
    }

    @Test fun notAnSmbUriFails() {
        try {
            SmbDataSource(store()).open(DataSpec(Uri.parse("content://media/1")))
            fail()
        } catch (_: IOException) {
        }
    }

    @Test fun fileSizeIsLookedUpOncePerSource() {
        var stats = 0
        val base = store().apply { failOn = { op, _ -> if (op == "stat") stats++; null } }
        val src = SmbDataSource(base)
        src.open(DataSpec(uri))
        src.close()
        src.open(DataSpec.Builder().setUri(uri).setPosition(700).build()) // a seek reopens
        assertArrayEquals(data.copyOfRange(700, 1000), readAll(src))
        src.close()
        assertEquals(1, stats)
    }

    @Test fun factoryUsesProvidedStore() {
        val s = store()
        var calls = 0
        val src = SmbDataSource.Factory { calls++; s }.createDataSource()
        assertEquals(1000L, src.open(DataSpec(uri)))
        src.close()
        assertEquals(1, calls)
    }

    @Test fun loginMissingAndForbiddenAreNotRetried() {
        assertFalse(SmbDataSource.isRetryable(IOException(RemoteException.AuthFailed())))
        assertFalse(SmbDataSource.isRetryable(FileNotFoundException("x")))
        assertFalse(SmbDataSource.isRetryable(AccessDeniedException()))
        assertTrue(SmbDataSource.isRetryable(IOException(RemoteException.Unreachable())))
        assertTrue(SmbDataSource.isRetryable(IOException("timeout")))
    }
}
