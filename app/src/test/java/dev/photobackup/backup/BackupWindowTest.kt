package dev.photobackup.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalTime

class BackupWindowTest {
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test fun sameDayWindow() {
        val s = t(1); val e = t(6)
        assertFalse(BackupWindow.isInWindow(t(0, 59), s, e))
        assertTrue(BackupWindow.isInWindow(t(1), s, e)) // start inclusive
        assertTrue(BackupWindow.isInWindow(t(5, 59), s, e))
        assertFalse(BackupWindow.isInWindow(t(6), s, e)) // end exclusive
        assertFalse(BackupWindow.isInWindow(t(13), s, e))
    }

    @Test fun windowSpanningMidnight() {
        val s = t(23); val e = t(5)
        assertTrue(BackupWindow.isInWindow(t(23), s, e))
        assertTrue(BackupWindow.isInWindow(t(0), s, e))
        assertTrue(BackupWindow.isInWindow(t(4, 59), s, e))
        assertFalse(BackupWindow.isInWindow(t(5), s, e))
        assertFalse(BackupWindow.isInWindow(t(12), s, e))
        assertFalse(BackupWindow.isInWindow(t(22, 59), s, e))
    }

    @Test fun equalStartAndEndMeansAllDay() {
        assertTrue(BackupWindow.isInWindow(t(3), t(2), t(2)))
        assertTrue(BackupWindow.isInWindow(t(2), t(2), t(2)))
    }

    @Test fun delayIsZeroInsideTheWindow() {
        assertEquals(Duration.ZERO, BackupWindow.nextWindowStartDelay(t(2), t(1), t(6)))
        assertEquals(Duration.ZERO, BackupWindow.nextWindowStartDelay(t(0, 30), t(23), t(5)))
    }

    @Test fun delayRunsToTheNextStart() {
        assertEquals(Duration.ofHours(1), BackupWindow.nextWindowStartDelay(t(0), t(1), t(6)))
        assertEquals(Duration.ofHours(19), BackupWindow.nextWindowStartDelay(t(6), t(1), t(6)))
        assertEquals(Duration.ofMinutes(30), BackupWindow.nextWindowStartDelay(t(22, 30), t(23), t(5)))
        assertEquals(Duration.ofHours(18), BackupWindow.nextWindowStartDelay(t(5), t(23), t(5)))
    }

    @Test fun untilNextStartIsAlwaysInTheFuture() {
        assertEquals(Duration.ofHours(24), BackupWindow.untilNextStart(t(1), t(1)))
        assertEquals(Duration.ofHours(23), BackupWindow.untilNextStart(t(2), t(1)))
        assertEquals(Duration.ofHours(2), BackupWindow.untilNextStart(t(21), t(23)))
    }

    @Test fun labelAndWaitingText() {
        assertEquals("1:00–6:00", OvernightWindow(1, 6).label)
        assertEquals("23:00–5:00", OvernightWindow(23, 5).label)
        assertEquals("Waiting for overnight window (1:00–6:00)", BackupWindow.waitingMessage(OvernightWindow(1, 6)))
    }

    @Test fun windowContains() {
        assertTrue(OvernightWindow(23, 5).contains(t(1)))
        assertFalse(OvernightWindow(1, 6).contains(t(7)))
    }
}
