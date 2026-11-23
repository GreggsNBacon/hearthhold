package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Gentle pacing between items and parallel uploads ("Files at once"). */
class BackupEngineParallelTest {
    private val dir = "B\\Dev\\Camera\\2026"
    private val march2026 = 1_772_400_000_000L
    private val settings = BackupSettings("B", "Dev", ZoneOffset.UTC)

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun media(id: Long, size: Long, name: String = "IMG$id.jpg") = PhoneMedia(
        id, "content://media/$id", name, "DCIM/Camera/", size, march2026, 5, 10, false, "image/jpeg"
    )

    private fun engine(
        store: FakeRemoteStore,
        ledger: MemoryLedger,
        data: Map<Long, ByteArray>,
        parallel: Int,
        pauseMs: Long = 0,
        pause: suspend (Long) -> Unit = { delay(it) },
    ) = BackupEngine(
        store, ledger, { m -> data[m.mediaId]?.let { ByteArrayInputStream(it) } }, settings, { 1L },
        parallelUploads = parallel, itemPauseMs = pauseMs, pause = pause,
    )

    @Test fun pausesBetweenItems() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val data = (1L..3L).associateWith { bytes(1000, it.toInt()) }
        val pauses = mutableListOf<Long>()
        val r = engine(store, ledger, data, parallel = 1, pauseMs = 2000) { pauses += it }
            .run((1L..3L).map { media(it, 1000) })
        assertEquals(3, (r as BackupResult.Done).uploaded)
        assertEquals(listOf(2000L, 2000L), pauses) // after each item that has a next one
    }

    @Test fun adoptedItemAlsoPauses() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val data = (1L..2L).associateWith { bytes(1000, it.toInt()) }
        store.mkdirs(dir); store.files["$dir\\IMG1.jpg"] = data.getValue(1L).copyOf()
        val pauses = mutableListOf<Long>()
        val r = engine(store, ledger, data, parallel = 1, pauseMs = 500) { pauses += it }
            .run((1L..2L).map { media(it, 1000) })
        assertEquals(BackupResult.Done(1, 1, 0, 1000), r)
        assertEquals(listOf(500L), pauses)
    }

    @Test fun defaultPauseIsTwoSeconds() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val data = (1L..2L).associateWith { bytes(100, it.toInt()) }
        val pauses = mutableListOf<Long>()
        BackupEngine(
            store, ledger, { m -> data[m.mediaId]?.let { ByteArrayInputStream(it) } }, settings, { 1L },
            pause = { pauses += it },
        ).run((1L..2L).map { media(it, 100) })
        assertEquals(listOf(2000L), pauses)
    }

    @Test fun pauseIsCancellable() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val data = (1L..2L).associateWith { bytes(100, it.toInt()) }
        lateinit var job: Job
        job = launch {
            engine(store, ledger, data, parallel = 1, pauseMs = 60_000)
                .run((1L..2L).map { media(it, 100) }) { done, _, _ -> if (done == 1) job.cancel() }
        }
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(BackupStatus.VERIFIED, ledger.get(1)!!.status)
        assertEquals(null, ledger.get(2)) // never started
    }

    @Test fun twoItemsWithSameNameInParallelGetDistinctNamesAndNeverOverwrite() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val a = bytes(4000, 1); val b = bytes(4000, 2) // same size, different content
        val bothWriting = CountDownLatch(2)
        val overlapped = Collections.synchronizedList(mutableListOf<Boolean>())
        store.failOn = { op, _ ->
            if (op == "openWrite") {
                bothWriting.countDown()
                overlapped += bothWriting.await(5, TimeUnit.SECONDS)
            }
            null
        }
        val r = engine(store, ledger, mapOf(1L to a, 2L to b), parallel = 2)
            .run(listOf(media(1, 4000, "IMG.jpg"), media(2, 4000, "IMG.jpg")))
        assertEquals(listOf(true, true), overlapped.toList()) // both were in flight at the same time
        assertEquals(BackupResult.Done(2, 0, 0, 8000), r)
        val p1 = ledger.get(1)!!.remotePath!!; val p2 = ledger.get(2)!!.remotePath!!
        assertNotEquals(p1, p2)
        assertEquals(setOf("$dir\\IMG.jpg", "$dir\\IMG (1).jpg"), setOf(p1, p2))
        assertArrayEquals(a, store.files[p1])
        assertArrayEquals(b, store.files[p2])
        assertTrue(store.deletes.isEmpty())
        assertFalse(store.files.keys.any { it.endsWith(".part") })
    }

    @Test fun stopInOneWorkerStopsTheWholeRun() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val data = mapOf(1L to bytes(1000, 1), 2L to bytes(3 * 1024 * 1024, 2), 3L to bytes(1000, 3), 4L to bytes(1000, 4))
        val bothOpen = CountDownLatch(2)
        val failed = CountDownLatch(1)
        store.failOn = { op, path ->
            when {
                op == "openWrite" && (path.contains("IMG1") || path.contains("IMG2")) -> {
                    bothOpen.countDown(); bothOpen.await(5, TimeUnit.SECONDS); null
                }
                op == "write" && path.contains("IMG1") -> { failed.countDown(); RemoteException.Unreachable() }
                op == "write" && path.contains("IMG2") -> {
                    failed.await(5, TimeUnit.SECONDS); Thread.sleep(200); null // let the cancel arrive
                }
                else -> null
            }
        }
        val r = withTimeout(10_000) {
            engine(store, ledger, data, parallel = 2).run((1L..4L).map { media(it, data.getValue(it).size.toLong()) })
        }
        assertTrue(r is BackupResult.Stopped && r.reason is RemoteException.Unreachable)
        // No other item was started, and nothing but .part files was deleted.
        assertFalse(store.writes.any { it.contains("IMG3") || it.contains("IMG4") })
        assertTrue(store.deletes.all { it.endsWith(".part") })
        // The sibling was cancelled mid-copy: its row stays UPLOADING with partPath for the next run's cleanup.
        val sib = ledger.get(2)!!
        assertEquals(BackupStatus.UPLOADING, sib.status)
        assertEquals("$dir\\IMG2.jpg.part", sib.partPath)
        assertFalse("$dir\\IMG2.jpg" in store.files)

        // Next run cleans both .parts and backs everything up.
        store.failOn = null
        val r2 = engine(store, ledger, data, parallel = 2).run((1L..4L).map { media(it, data.getValue(it).size.toLong()) })
        assertEquals(4, (r2 as BackupResult.Done).uploaded)
        for (id in 1L..4L) assertArrayEquals(data.getValue(id), store.files["$dir\\IMG$id.jpg"])
        assertTrue(store.deletes.all { it.endsWith(".part") })
    }

    @Test fun progressTotalsAreCorrectInParallel() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val sizes = listOf(1500, 2_000_000, 700, 1_200_000)
        val data = sizes.withIndex().associate { (i, n) -> (i + 1).toLong() to bytes(n, i) }
        val calls = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Long>>())
        val r = engine(store, ledger, data, parallel = 3)
            .run((1L..4L).map { media(it, data.getValue(it).size.toLong()) }) { d, t, b -> calls += Triple(d, t, b) }
        val total = sizes.sum().toLong()
        assertEquals(BackupResult.Done(4, 0, 0, total), r)
        assertEquals(Triple(4, 4, total), calls.last())
        assertTrue(calls.all { it.second == 4 })
        assertEquals(calls.map { it.first }.sorted(), calls.map { it.first }) // done never goes backwards
        assertEquals(calls.map { it.third }.sorted(), calls.map { it.third }) // bytes never go backwards
    }

    @Test fun neverMoreThanNItemsAtOnce() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val data = (1L..6L).associateWith { bytes(500, it.toInt()) }
        val inFlight = AtomicInteger(); val max = AtomicInteger()
        store.failOn = { op, _ ->
            if (op == "openWrite") { max.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf); Thread.sleep(50) }
            if (op == "rename") inFlight.decrementAndGet()
            null
        }
        val r = engine(store, ledger, data, parallel = 2).run((1L..6L).map { media(it, 500) })
        assertEquals(6, (r as BackupResult.Done).uploaded)
        assertEquals(2, max.get())
    }

    @Test fun freeSpaceIsReservedBeforeEachParallelItemStarts() = runBlocking {
        val store = FakeRemoteStore(freeBytes = 1500); val ledger = MemoryLedger()
        val data = (1L..2L).associateWith { bytes(1000, it.toInt()) }
        val r = engine(store, ledger, data, parallel = 2).run((1L..2L).map { media(it, 1000) })
        assertTrue(r is BackupResult.Stopped && r.reason is RemoteException.DriveFull)
        assertTrue(store.writes.size <= 1) // the second item never started writing
        assertTrue(store.deletes.all { it.endsWith(".part") })
    }

    @Test fun oneAtATimeMatchesSequentialOrder() = runBlocking {
        val store = FakeRemoteStore(); val ledger = MemoryLedger()
        val data = (1L..4L).associateWith { bytes(300, it.toInt()) }
        engine(store, ledger, data, parallel = 1).run((1L..4L).map { media(it, 300) })
        assertEquals((1L..4L).map { "$dir\\IMG$it.jpg.part" }, store.writes)
    }
}
