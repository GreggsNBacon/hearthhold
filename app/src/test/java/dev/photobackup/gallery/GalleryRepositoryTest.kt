package dev.photobackup.gallery

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryRepositoryTest {
    private fun phone(id: Long, takenMs: Long = 1_000L * id, addedSec: Long = 0, video: Boolean = false) = PhoneMedia(
        mediaId = id,
        uri = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/",
        sizeBytes = 100L * id,
        dateTakenMs = takenMs,
        dateAddedSec = addedSec,
        dateModifiedSec = 0,
        isVideo = video,
        mimeType = "image/jpeg",
    )

    private fun ledger(
        id: Long,
        status: BackupStatus,
        takenMs: Long = 1_000L * id,
        remote: String? = "Photos\\Pixel\\Camera\\2026\\IMG_$id.jpg",
    ) = LedgerEntry(
        mediaId = id,
        localUri = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/",
        sizeBytes = 100L * id,
        dateTakenMs = takenMs,
        dateModifiedSec = 5,
        isVideo = false,
        localSha256 = "abc",
        remotePath = remote,
        partPath = null,
        status = status,
        lastError = null,
        updatedAtMs = 0,
    )

    @Test fun verifiedOnPhoneIsBoth() {
        val items = GalleryMerge.merge(listOf(phone(1)), listOf(ledger(1, BackupStatus.VERIFIED)))
        assertEquals(1, items.size)
        val it = items.single()
        assertEquals(Location.BOTH, it.location)
        assertEquals("content://media/external/images/media/1", it.phoneUri)
        assertEquals("Photos\\Pixel\\Camera\\2026\\IMG_1.jpg", it.remotePath)
    }

    @Test fun removedIsDriveOnly() {
        val items = GalleryMerge.merge(emptyList(), listOf(ledger(2, BackupStatus.REMOVED_FROM_PHONE)))
        val it = items.single()
        assertEquals(Location.DRIVE, it.location)
        assertNull(it.phoneUri)
        assertEquals("Photos\\Pixel\\Camera\\2026\\IMG_2.jpg", it.remotePath)
        assertEquals("IMG_2.jpg", it.displayName)
        assertEquals(2000L, it.dateMs)
    }

    @Test fun pendingIsPhoneOnly() {
        val items = GalleryMerge.merge(listOf(phone(3)), listOf(ledger(3, BackupStatus.PENDING, remote = null)))
        val it = items.single()
        assertEquals(Location.PHONE, it.location)
        assertNull(it.remotePath)
    }

    @Test fun phoneItemNotInLedgerIsPhone() {
        val items = GalleryMerge.merge(listOf(phone(4)), emptyList())
        assertEquals(Location.PHONE, items.single().location)
        assertEquals("IMG_4.jpg", items.single().displayName)
        assertEquals(400L, items.single().sizeBytes)
    }

    @Test fun sortedNewestFirst() {
        val items = GalleryMerge.merge(
            listOf(phone(1, takenMs = 10), phone(2, takenMs = 30)),
            listOf(ledger(3, BackupStatus.REMOVED_FROM_PHONE, takenMs = 20)),
        )
        assertEquals(listOf(30L, 20L, 10L), items.map { it.dateMs })
    }

    @Test fun verifiedButGoneFromPhoneIsDriveOnly() {
        val items = GalleryMerge.merge(emptyList(), listOf(ledger(5, BackupStatus.VERIFIED)))
        assertEquals(Location.DRIVE, items.single().location)
        assertNull(items.single().phoneUri)
    }

    @Test fun ledgerRowsNotOnPhoneAndNotOnDriveAreDropped() {
        val items = GalleryMerge.merge(
            emptyList(),
            listOf(
                ledger(6, BackupStatus.PENDING, remote = null),
                ledger(7, BackupStatus.FAILED),
                ledger(8, BackupStatus.UPLOADING),
                ledger(9, BackupStatus.REMOVED_FROM_PHONE, remote = null),
            ),
        )
        assertTrue(items.isEmpty())
    }

    @Test fun phoneDateFallsBackToDateAdded() {
        val items = GalleryMerge.merge(listOf(phone(1, takenMs = 0, addedSec = 7)), emptyList())
        assertEquals(7000L, items.single().dateMs)
    }

    @Test fun keysAreStableAcrossLocations() {
        val both = GalleryMerge.merge(listOf(phone(1)), listOf(ledger(1, BackupStatus.VERIFIED))).single()
        val drive = GalleryMerge.merge(emptyList(), listOf(ledger(1, BackupStatus.REMOVED_FROM_PHONE))).single()
        assertEquals(both.key, drive.key)
    }
}
