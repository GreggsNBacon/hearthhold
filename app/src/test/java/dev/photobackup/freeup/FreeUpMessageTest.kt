package dev.photobackup.freeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeUpMessageTest {
    private val summary = "2 items failed the check \u2014 will back up again (2 drive copies damaged)"

    @Test fun unreachableDoesNotHideTheRemovalResult() =
        assertEquals(
            "$summary\n$CONNECT_HOME_MESSAGE",
            freeUpMessage(FreeUpUiState(driveReachable = false, error = summary)),
        )

    @Test fun connectHintIsNotRepeated() =
        assertEquals(
            "$summary\n$CONNECT_HOME_MESSAGE",
            freeUpMessage(FreeUpUiState(driveReachable = false, error = "$summary\n$CONNECT_HOME_MESSAGE")),
        )

    @Test fun reachableShowsOnlyTheError() =
        assertEquals(summary, freeUpMessage(FreeUpUiState(driveReachable = true, error = summary)))

    @Test fun unreachableAloneShowsTheHint() =
        assertEquals(CONNECT_HOME_MESSAGE, freeUpMessage(FreeUpUiState(driveReachable = false)))

    @Test fun nothingToSay() = assertNull(freeUpMessage(FreeUpUiState(driveReachable = true)))
}
