package dev.photobackup.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCheckTest {
    @Test fun firstCheckRuns() = assertEquals(AutoCheck.CHECK, autoCheck(false, false, null, 0))

    @Test fun busySkips() = assertEquals(AutoCheck.SKIP_BUSY, autoCheck(false, true, null, 0))

    @Test fun unreadablePasswordSkips() = assertEquals(AutoCheck.SKIP_PASSWORD, autoCheck(true, false, null, 0))

    @Test fun recentCheckSkips() {
        assertEquals(AutoCheck.SKIP_RECENT, autoCheck(false, false, 1_000, 10_999))
        assertEquals(AutoCheck.CHECK, autoCheck(false, false, 1_000, 11_000))
    }

    @Test fun clockGoingBackwardsChecks() = assertEquals(AutoCheck.CHECK, autoCheck(false, false, 50_000, 1_000))
}
