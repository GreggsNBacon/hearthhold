package dev.photobackup.gallery

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.media.PhoneMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The outlined ✓ "one copy so far" badge: only with a second location set, for copy-eligible items not copied there. */
class SecondBadgeTest {
    private fun row(
        id: Long = 1,
        status: BackupStatus = BackupStatus.VERIFIED,
        sha: String? = "abc",
        bin: Long? = null,
        remote: String? = "Photos\\Pixel\\Camera\\2026\\IMG_$id.jpg",
    ) = LedgerEntry(
        mediaId = id, localUri = "content://media/external/images/media/$id", displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/", sizeBytes = 100L * id, dateTakenMs = 1_000L * id, dateModifiedSec = 0,
        isVideo = false, localSha256 = sha, remotePath = remote, partPath = null, status = status, lastError = null,
        updatedAtMs = 0, binRequestedAt = bin,
    )

    private fun copy(id: Long = 1, status: SecondStatus = SecondStatus.VERIFIED, sha: String? = "abc") = SecondCopy(
        mediaId = id, remotePath = "x", partPath = null, movingTo = null, status = status, sizeBytes = 100,
        sha256 = sha, lastError = null, updatedAtMs = 0,
    )

    private fun phone(id: Long) = PhoneMedia(
        mediaId = id, uri = "content://media/external/images/media/$id", displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/", sizeBytes = 100L * id, dateTakenMs = 1_000L * id, dateAddedSec = 0,
        dateModifiedSec = 0, isVideo = false, mimeType = "image/jpeg",
    )

    // --- the pure rule ---

    @Test fun offNeverShowsOneCopy() {
        assertFalse(oneCopySoFar(secondOn = false, row(), null))
        assertFalse(oneCopySoFar(secondOn = false, row(status = BackupStatus.REMOVED_FROM_PHONE), null))
    }

    @Test fun eligibleWithoutCopyIsOneCopy() {
        assertTrue(oneCopySoFar(true, row(), null))
        assertTrue(oneCopySoFar(true, row(status = BackupStatus.REMOVED_FROM_PHONE), null))
    }

    @Test fun uploadingOrFailedSecondCopyIsStillOneCopy() {
        assertTrue(oneCopySoFar(true, row(), copy(status = SecondStatus.UPLOADING)))
        assertTrue(oneCopySoFar(true, row(), copy(status = SecondStatus.FAILED)))
    }

    @Test fun verifiedCopyWithMatchingHashIsTwoCopies() = assertFalse(oneCopySoFar(true, row(), copy()))

    @Test fun verifiedCopyOfAnOlderHashIsOneCopy() = assertTrue(oneCopySoFar(true, row(sha = "new"), copy(sha = "old")))

    @Test fun neverCopiedItemsGetNoExtraBadge() {
        assertFalse(oneCopySoFar(true, row(status = BackupStatus.BINNED, bin = 5), null))
        assertFalse(oneCopySoFar(true, row(status = BackupStatus.BINNED), null))
        assertFalse(oneCopySoFar(true, row(status = BackupStatus.REMOVED_FROM_PHONE, bin = 5), null))
        assertFalse(oneCopySoFar(true, row(sha = null), null))
        assertFalse(oneCopySoFar(true, row(status = BackupStatus.PENDING), null))
        assertFalse(oneCopySoFar(true, row(status = BackupStatus.UPLOADING), null))
        assertFalse(oneCopySoFar(true, row(status = BackupStatus.FAILED), null))
    }

    // --- badge state ---

    private fun item(location: Location, status: BackupStatus?, oneCopy: Boolean) = GalleryItem(
        key = "media:1", displayName = "IMG_1.jpg", dateMs = 0, isVideo = false, sizeBytes = 1,
        location = location, phoneUri = null, remotePath = null, status = status, lastError = null, oneCopy = oneCopy,
    )

    @Test fun oneCopyBadgeOnBackedUpItems() {
        assertEquals(Badge.ONE_COPY, badgeFor(item(Location.BOTH, BackupStatus.VERIFIED, true)))
        assertEquals(Badge.ONE_COPY, badgeFor(item(Location.DRIVE, BackupStatus.REMOVED_FROM_PHONE, true)))
    }

    @Test fun phoneItemsKeepTheirBadge() {
        assertEquals(Badge.NONE, badgeFor(item(Location.PHONE, BackupStatus.PENDING, true)))
        assertEquals(Badge.FAILED, badgeFor(item(Location.PHONE, BackupStatus.FAILED, true)))
    }

    @Test fun withoutOneCopyTheBadgeIsTodays() {
        assertEquals(Badge.BACKED_UP, badgeFor(item(Location.BOTH, BackupStatus.VERIFIED, false)))
        assertEquals(Badge.DRIVE_ONLY, badgeFor(item(Location.DRIVE, BackupStatus.REMOVED_FROM_PHONE, false)))
        assertEquals(Badge.NONE, badgeFor(item(Location.PHONE, null, false)))
        assertEquals(Badge.FAILED, badgeFor(item(Location.PHONE, BackupStatus.FAILED, false)))
    }

    // --- merge ---

    @Test fun mergeWithSecondOffIsUnchanged() {
        val phone = listOf(phone(1), phone(3))
        val ledger = listOf(row(1), row(2, status = BackupStatus.REMOVED_FROM_PHONE), row(3, status = BackupStatus.PENDING))
        val before = GalleryMerge.merge(phone, ledger)
        val after = GalleryMerge.merge(phone, ledger, second = null)
        assertEquals(before, after)
        assertTrue(after.none { it.oneCopy })
    }

    @Test fun mergeWithSecondOnMarksItemsWithoutAVerifiedCopy() {
        val phone = listOf(phone(1), phone(3))
        val ledger = listOf(row(1), row(2, status = BackupStatus.REMOVED_FROM_PHONE), row(3))
        val items = GalleryMerge.merge(phone, ledger, second = mapOf(3L to copy(3))).associateBy { it.key }
        assertTrue(items.getValue("media:1").oneCopy)
        assertTrue(items.getValue("media:2").oneCopy)
        assertFalse(items.getValue("media:3").oneCopy)
        assertEquals(Badge.BACKED_UP, badgeFor(items.getValue("media:3")))
    }
}
