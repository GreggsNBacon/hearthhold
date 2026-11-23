package dev.photobackup.ui.home

import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.backup.OvernightWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class StatusChipTest {
    private val idle = BackupStatusSnapshot(lastRunMs = 1, lastResult = "Backed up 3, failed 0", lastError = null,
        inProgress = false, done = 0, total = 0, etaSec = null)
    private val noon = LocalTime.NOON

    private fun chip(
        status: BackupStatusSnapshot? = idle,
        reach: Reachability = Reachability.YES,
        waiting: Int = 0,
        failed: Int = 0,
        needsPassword: Boolean = false,
        window: OvernightWindow? = null,
        now: LocalTime = noon,
    ) = statusChip(status, reach, waiting, failed, needsPassword, window, now)

    @Test fun upToDate() = assertEquals(ChipState("Up to date", ChipKind.OK), chip())

    @Test fun backingUpShowsProgress() {
        val running = idle.copy(inProgress = true, done = 12, total = 40)
        assertEquals(ChipState("Backing up 12 of 40", ChipKind.BUSY), chip(running, waiting = 28))
    }

    @Test fun backingUpBeforeTotalKnown() =
        assertEquals(ChipState("Backing up…", ChipKind.BUSY), chip(idle.copy(inProgress = true)))

    @Test fun progressWinsOverUnreachable() =
        assertEquals(ChipKind.BUSY, chip(idle.copy(inProgress = true, done = 1, total = 2), Reachability.NO).kind)

    @Test fun driveNotReachable() =
        assertEquals(ChipState("Drive not reachable", ChipKind.PROBLEM), chip(reach = Reachability.NO, waiting = 3))

    @Test fun runErrorIsBackupProblem() =
        assertEquals(ChipState("Backup problem", ChipKind.PROBLEM), chip(idle.copy(lastError = "Wrong password")))

    @Test fun unreadablePasswordIsBackupProblem() =
        assertEquals(ChipState("Backup problem", ChipKind.PROBLEM), chip(needsPassword = true))

    @Test fun unreachableWinsOverRunError() =
        assertEquals("Drive not reachable", chip(idle.copy(lastError = "x"), Reachability.NO).text)

    @Test fun waitingForOvernightWindowOutsideIt() = assertEquals(
        ChipState("Waiting for overnight window", ChipKind.WAITING),
        chip(waiting = 5, window = OvernightWindow(1, 6), now = noon),
    )

    @Test fun insideWindowJustWaiting() = assertEquals(
        ChipState("5 waiting", ChipKind.WAITING),
        chip(waiting = 5, window = OvernightWindow(1, 6), now = LocalTime.of(2, 0)),
    )

    @Test fun nothingWaitingIsUpToDateEvenOutsideWindow() =
        assertEquals("Up to date", chip(window = OvernightWindow(1, 6)).text)

    @Test fun waitingWithoutWindow() = assertEquals(ChipState("5 waiting", ChipKind.WAITING), chip(waiting = 5))

    @Test fun failedItems() = assertEquals(ChipState("2 failed", ChipKind.PROBLEM), chip(failed = 2))

    @Test fun reachUnknownOrCheckingDoesNotBlockUpToDate() {
        assertEquals("Up to date", chip(reach = Reachability.CHECKING).text)
        assertEquals("Up to date", chip(reach = Reachability.UNKNOWN).text)
    }

    @Test fun statusNotLoadedYet() = assertEquals(ChipState("Checking…", ChipKind.BUSY), chip(status = null))
}
