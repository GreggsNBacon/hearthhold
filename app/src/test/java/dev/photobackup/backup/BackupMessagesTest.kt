package dev.photobackup.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupMessagesTest {
    @Test fun summaryShowsOnlyUploadsAndFailuresWhenNothingMoved() =
        assertEquals("Backed up 3, failed 1", BackupMessages.summary(3, 1, 0))

    @Test fun summaryAddsMovedFiles() =
        assertEquals("Backed up 0, failed 0 · 2 moved", BackupMessages.summary(0, 0, 2))

    @Test fun summaryAddsFilesThatCouldNotMove() =
        assertEquals("Backed up 0, failed 0 · 1 couldn't move", BackupMessages.summary(0, 0, 0, moveFailed = 1))

    @Test fun summaryShowsMovedAndCouldNotMoveTogether() =
        assertEquals(
            "Backed up 1, failed 0 · 2 moved · 3 couldn't move",
            BackupMessages.summary(1, 0, 2, moveFailed = 3),
        )
}
