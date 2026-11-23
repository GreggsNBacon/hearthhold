package dev.photobackup.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ReadAheadInputStreamTest {
    private val pool = Executors.newFixedThreadPool(8)
    private val data = Random(7).nextBytes(1_000_003) // not a multiple of any chunk size

    /** Fake drive read: at most [max] bytes per request (like SMB's max read size), -1 past the end. */
    private fun reader(max: Int = 65_536, shortAt: Set<Long> = emptySet(), failAt: Long? = null, calls: AtomicInteger? = null) =
        ChunkReader { position, buf, len ->
            calls?.incrementAndGet()
            if (failAt != null && position >= failAt) throw IOException("boom")
            if (position >= data.size) return@ChunkReader -1
            var n = minOf(len, max, data.size - position.toInt())
            if (position in shortAt) n = minOf(n, 1000) // a server may return less than asked
            System.arraycopy(data, position.toInt(), buf, 0, n)
            n
        }

    private fun readAll(s: ReadAheadInputStream, step: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(step)
        while (true) {
            val n = s.read(buf, 0, step)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test fun readsExactBytesForManyReadSizes() {
        for (step in listOf(1, 7, 4096, 65_536, 200_000, 2_000_000)) {
            val s = ReadAheadInputStream(reader(), start = 0, chunkSize = 65_536, depth = 4, executor = pool)
            assertArrayEquals("step $step", data, readAll(s, step))
            s.close()
        }
    }

    @Test fun startsAtOffset() {
        val s = ReadAheadInputStream(reader(), start = 123_456, chunkSize = 65_536, depth = 3, executor = pool)
        assertArrayEquals(data.copyOfRange(123_456, data.size), readAll(s, 10_000))
    }

    @Test fun shortReadsLeaveNoGaps() {
        val shorts = setOf(65_536L, 3 * 65_536L, 9 * 65_536L)
        val s = ReadAheadInputStream(reader(shortAt = shorts), start = 0, chunkSize = 65_536, depth = 4, executor = pool)
        assertArrayEquals(data, readAll(s, 30_000))
    }

    @Test fun singleByteReadMatches() {
        val s = ReadAheadInputStream(reader(), start = data.size - 3L, chunkSize = 65_536, depth = 2, executor = pool)
        assertEquals(data[data.size - 3].toInt() and 0xFF, s.read())
        s.read(); s.read()
        assertEquals(-1, s.read())
    }

    @Test fun errorSurfacesAsIOExceptionAfterGoodData() {
        val s = ReadAheadInputStream(reader(failAt = 200_000), start = 0, chunkSize = 65_536, depth = 4, executor = pool)
        val buf = ByteArray(10_000)
        var total = 0
        try {
            while (true) { val n = s.read(buf, 0, buf.size); if (n < 0) break; total += n }
            fail("expected IOException")
        } catch (_: IOException) {
            assertTrue("good bytes before the failure are delivered", total >= 196_608)
        }
    }

    @Test fun keepsSeveralRequestsInFlight() {
        val calls = AtomicInteger()
        val s = ReadAheadInputStream(reader(calls = calls), start = 0, chunkSize = 65_536, depth = 4, executor = pool)
        s.read(ByteArray(10), 0, 10)
        Thread.sleep(200)
        assertTrue("prefetch requested ${calls.get()}", calls.get() >= 4)
        s.close()
    }

    /** Backups rename or delete the file right after reading it back: the handle must be closed on return. */
    @Test fun closeWaitsForInFlightReadsThenClosesHandle() {
        val running = AtomicInteger()
        val slow = ChunkReader { position, buf, len ->
            running.incrementAndGet()
            Thread.sleep(50)
            val n = reader().read(position, buf, len)
            running.decrementAndGet()
            n
        }
        var closedWhileRunning = -1
        val s = ReadAheadInputStream(slow, 0, 65_536, 4, pool, onClose = { closedWhileRunning = running.get() })
        s.read(ByteArray(10), 0, 10)
        s.close()
        assertEquals("onClose ran before close() returned, with no read in flight", 0, closedWhileRunning)
    }

    @Test fun closedStreamThrows() {
        val s = ReadAheadInputStream(reader(), start = 0, chunkSize = 65_536, depth = 2, executor = pool)
        s.close()
        try { s.read(ByteArray(4), 0, 4); fail() } catch (_: IOException) {}
    }
}
