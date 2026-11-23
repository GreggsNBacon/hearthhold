package dev.photobackup.ui.home

import dev.photobackup.backup.BackupStatusSnapshot
import dev.photobackup.backup.OvernightWindow
import dev.photobackup.media.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        photoAccess: MediaAccess? = MediaAccess.FULL,
    ) = statusChip(status, reach, waiting, failed, needsPassword, window, now, photoAccess)

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

    @Test fun shortRunErrorShownAsIs() =
        assertEquals(ChipState("Wrong password", ChipKind.PROBLEM), chip(idle.copy(lastError = "Wrong password")))

    @Test fun longRunErrorTruncated() {
        val text = chip(idle.copy(lastError = "The drive is full — free some space and try again")).text
        assertEquals("The drive is full — free…", text)
        assertTrue(text.length <= 25)
    }

    @Test fun multiLineRunErrorUsesFirstLine() =
        assertEquals("Drive is full", chip(idle.copy(lastError = "Drive is full\nmore detail")).text)

    @Test fun unreadablePasswordAsksToReenter() =
        assertEquals(ChipState("Re-enter drive password", ChipKind.PROBLEM), chip(needsPassword = true))

    @Test fun unreadablePasswordWinsOverRunError() =
        assertEquals("Re-enter drive password", chip(idle.copy(lastError = "x"), needsPassword = true).text)

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

    @Test fun checkingDriveIsNotUpToDate() =
        assertEquals(ChipState("Checking…", ChipKind.BUSY), chip(reach = Reachability.CHECKING))

    @Test fun checkingDriveStillShowsWaitingCount() = assertEquals("5 waiting", chip(reach = Reachability.CHECKING, waiting = 5).text)

    @Test fun reachUnknownDoesNotBlockUpToDate() = assertEquals("Up to date", chip(reach = Reachability.UNKNOWN).text)

    @Test fun statusNotLoadedYet() = assertEquals(ChipState("Checking…", ChipKind.BUSY), chip(status = null))

    @Test fun neverBackedUpIsNotUpToDate() =
        assertEquals(ChipState("Not backed up yet", ChipKind.WAITING), chip(status = idle.copy(lastRunMs = null)))

    @Test fun waitingBeatsNeverBackedUp() =
        assertEquals("5 waiting", chip(status = idle.copy(lastRunMs = null), waiting = 5).text)

    @Test fun missingPhotoAccessIsAProblem() =
        assertEquals(ChipState("Photos permission needed", ChipKind.PROBLEM), chip(photoAccess = MediaAccess.NONE))

    @Test fun partialPhotoAccessAsksForAll() =
        assertEquals(ChipState("Allow access to all photos", ChipKind.PROBLEM), chip(photoAccess = MediaAccess.PARTIAL))

    @Test fun unknownPhotoAccessDoesNotBlock() = assertEquals("Up to date", chip(photoAccess = null).text)


    @Test fun driveLimitsShowFreeSpaceAndFat32Count() {
        assertEquals(listOf("1.5 GB free on the drive"), driveLimitLines(1536L * 1024 * 1024, 0))
        assertEquals(
            listOf("2 files are too big for this drive's format (FAT32, 4 GB limit)"),
            driveLimitLines(null, 2),
        )
        assertEquals(emptyList<String>(), driveLimitLines(null, 0))
    }
}
