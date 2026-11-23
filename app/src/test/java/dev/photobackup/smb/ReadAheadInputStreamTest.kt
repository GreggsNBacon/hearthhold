package dev.photobackup.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
        val single = Executors.newSingleThreadExecutor() // chunk 0 and 1 run one after another; the rest stay queued
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readerReturned = AtomicBoolean(false)
        val invoked = CopyOnWriteArrayList<Long>()
        val blocking = ChunkReader { position, buf, len ->
            invoked.add(position)
            if (position == 65_536L) { // the second chunk is the one in flight during close()
                entered.countDown()
                release.await()
                readerReturned.set(true)
            }
            reader().read(position, buf, len)
        }
        val closedAfterReturn = AtomicReference<Boolean?>(null)
        val s = ReadAheadInputStream(blocking, 0, 65_536, 3, single, onClose = { closedAfterReturn.set(readerReturned.get()) })
        s.read(ByteArray(10), 0, 10) // schedules chunks 0..3 (chunk 0 done, chunk 1 blocks, 2 and 3 never start)
        assertTrue("read in flight", entered.await(5, TimeUnit.SECONDS))
        val closer = Thread { s.close() }
        closer.start()
        closer.join(300)
        assertTrue("close() must wait for the in-flight read", closer.isAlive)
        assertEquals("onClose must not run while a read is in flight", null, closedAfterReturn.get())
        release.countDown()
        closer.join(5_000)
        assertTrue("close() returned", !closer.isAlive)
        assertEquals("onClose ran only after the in-flight read returned", true, closedAfterReturn.get())
        single.shutdown()
        assertTrue(single.awaitTermination(5, TimeUnit.SECONDS)) // queued chunks have now been skipped
        assertEquals("never-started chunks must not reach the reader", listOf(0L, 65_536L), invoked.toList())
    }

    /**
     * Every read comes back short, so each one drops the 3 chunks queued behind it before they ever start. Those
     * must be forgotten at once (not kept until close), and must never reach the reader if their task runs later.
     */
    @Test fun droppedChunksThatNeverStartedAreForgotten() {
        val depth = 4
        var submitted = 0
        val deferred = ArrayList<Runnable>()
        // The first task of each refill runs at once (it is the one read next); the rest wait and get dropped.
        val executor = java.util.concurrent.Executor { r -> if (submitted++ % depth == 0) r.run() else deferred += r }
        val calls = AtomicInteger()
        val shortReader = ChunkReader { position, buf, _ ->
            calls.incrementAndGet()
            if (position >= data.size) return@ChunkReader -1
            System.arraycopy(data, position.toInt(), buf, 0, 10)
            10
        }
        val s = ReadAheadInputStream(shortReader, start = 0, chunkSize = 100, depth = depth, executor = executor)
        val buf = ByteArray(10)
        repeat(50) { i ->
            assertEquals(10, s.read(buf, 0, 10))
            assertArrayEquals(data.copyOfRange(i * 10, i * 10 + 10), buf)
            assertTrue("tracked ${s.trackedCount} after read $i", s.trackedCount <= depth + 1)
        }
        val before = calls.get()
        deferred.dropLast(depth - 1).forEach { it.run() } // dropped tasks that run late skip (the last 3 are live)
        assertEquals(before, calls.get())
        s.close() // returns: nothing started is left waiting
        deferred.takeLast(depth - 1).forEach { it.run() } // queued at close: skip too
        assertEquals(before, calls.get())
    }

    @Test fun closedStreamThrows() {
        val s = ReadAheadInputStream(reader(), start = 0, chunkSize = 65_536, depth = 2, executor = pool)
        s.close()
        try { s.read(ByteArray(4), 0, 4); fail() } catch (_: IOException) {}
    }
}
