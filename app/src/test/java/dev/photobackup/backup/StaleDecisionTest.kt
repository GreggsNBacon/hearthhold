package dev.photobackup.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class StaleDecisionTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Test fun warnsWhenOldEnoughAndItemsWait() {
        assertEquals(StaleDecision.Warn(3, 5), staleDecision(now, now - 3 * day, null, 5, 3))
    }

    @Test fun noWarningWhenNothingWaits() {
        assertEquals(StaleDecision.None, staleDecision(now, now - 10 * day, null, 0, 3))
    }

    @Test fun thresholdBoundary() {
        assertEquals(StaleDecision.None, staleDecision(now, now - 3 * day + 1, null, 5, 3))
        assertEquals(StaleDecision.Warn(3, 5), staleDecision(now, now - 3 * day, null, 5, 3))
    }

    @Test fun unknownCountStillWarnsWhenOld() {
        assertEquals(StaleDecision.Warn(2, null), staleDecision(now, now - 2 * day, null, null, 2))
    }

    @Test fun atMostOnePerDay() {
        val last = now - 5 * day
        assertEquals(StaleDecision.None, staleDecision(now, last, now - day + 1, 5, 3))
        assertEquals(StaleDecision.Warn(3, 5), staleDecision(now, last, now - day, 5, 3))
    }

    @Test fun aWarningBeforeTheLastSuccessDoesNotCount() {
        // Warned, then a success, then stale again: the old warning must not suppress the new one.
        val success = now - 4 * day
        assertEquals(StaleDecision.Warn(3, 5), staleDecision(now, success, success - 1000, 5, 3))
    }
}
