package dev.photobackup.gallery

import dev.photobackup.ledger.BackupStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeTest {
    private fun item(location: Location, status: BackupStatus?) = GalleryItem(
        key = "media:1", displayName = "IMG_1.jpg", dateMs = 0, isVideo = false, sizeBytes = 1,
        location = location, phoneUri = null, remotePath = null, status = status, lastError = null,
    )

    @Test fun bothIsBackedUp() = assertEquals(Badge.BACKED_UP, badgeFor(item(Location.BOTH, BackupStatus.VERIFIED)))

    @Test fun driveOnlyIsDrive() {
        assertEquals(Badge.DRIVE_ONLY, badgeFor(item(Location.DRIVE, BackupStatus.REMOVED_FROM_PHONE)))
        assertEquals(Badge.DRIVE_ONLY, badgeFor(item(Location.DRIVE, BackupStatus.VERIFIED)))
    }

    @Test fun phoneNotYetBackedUpHasNoBadge() {
        assertEquals(Badge.NONE, badgeFor(item(Location.PHONE, null)))
        assertEquals(Badge.NONE, badgeFor(item(Location.PHONE, BackupStatus.PENDING)))
        assertEquals(Badge.NONE, badgeFor(item(Location.PHONE, BackupStatus.UPLOADING)))
    }

    @Test fun failedPhoneItemIsFailed() = assertEquals(Badge.FAILED, badgeFor(item(Location.PHONE, BackupStatus.FAILED)))
}
