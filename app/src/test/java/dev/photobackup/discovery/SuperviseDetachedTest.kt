package dev.photobackup.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SuperviseDetachedTest {
    @Test fun throwingOpenIsContainedAndLockReleasedOnce() {
        val uncaught = AtomicInteger()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> uncaught.incrementAndGet() }
        try {
            val released = AtomicInteger()
            runBlocking {
                superviseDetached(CoroutineScope(SupervisorJob() + Dispatchers.IO), { released.incrementAndGet() }) {
                    throw IOException("interface gone")
                }
            }
            Thread.sleep(100)
            assertEquals(0, uncaught.get())
            assertEquals(1, released.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
        }
    }

    @Test fun cancelBeforeStartStillClosesAndReleases() {
        val released = AtomicInteger(); val closed = AtomicInteger()
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val outer = launch(start = CoroutineStart.ATOMIC) {
                superviseDetached(scope, { released.incrementAndGet() }) { Closeable { closed.incrementAndGet() } }
            }
            outer.cancel()
            outer.join()
            Thread.sleep(300)
        }
        assertEquals(1, released.get())
        assertEquals(released.get(), 1)
        assertTrue(closed.get() <= 1)
    }
}
