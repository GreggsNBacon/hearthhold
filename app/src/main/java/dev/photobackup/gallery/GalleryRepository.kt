package dev.photobackup.gallery

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia

/**
 * Builds the combined Gallery list from what is on the phone now and the backup ledger.
 * - phone item + `VERIFIED` → [Location.BOTH]
 * - `REMOVED_FROM_PHONE` → [Location.DRIVE]
 * - `VERIFIED` but no longer on the phone (deleted outside the app, or its folder was turned off) → [Location.DRIVE]
 * - phone item with any other status, or not in the ledger → [Location.PHONE]
 * Each item carries its ledger status and last error (for the Gallery badges).
 * Ledger rows that are neither on the phone nor (with a remote path) on the drive are left out.
 * Sorted newest first. Pure, so it runs off the main thread and in JVM tests.
 */
object GalleryMerge {
    fun key(mediaId: Long) = "media:$mediaId"

    fun merge(phone: List<PhoneMedia>, ledger: List<LedgerEntry>): List<GalleryItem> {
        val byId = ledger.associateBy { it.mediaId }
        val out = ArrayList<GalleryItem>(phone.size + ledger.size)
        val onPhone = HashSet<Long>(phone.size * 2)
        for (m in phone) {
            val e = byId[m.mediaId]
            // Listed as a drive item from the ledger below.
            if (e?.status == BackupStatus.REMOVED_FROM_PHONE && e.remotePath != null) continue
            onPhone += m.mediaId
            val both = e != null && e.status == BackupStatus.VERIFIED && e.remotePath != null
            out += GalleryItem(
                key = key(m.mediaId),
                displayName = m.displayName,
                dateMs = if (m.dateTakenMs > 0) m.dateTakenMs else m.dateAddedSec * 1000,
                isVideo = m.isVideo,
                sizeBytes = m.sizeBytes,
                location = if (both) Location.BOTH else Location.PHONE,
                phoneUri = m.uri,
                remotePath = if (both) e!!.remotePath else null,
                status = e?.status,
                lastError = e?.lastError,
            )
        }
        for (e in ledger) {
            if (e.mediaId in onPhone) continue
            val remote = e.remotePath ?: continue
            if (e.status != BackupStatus.REMOVED_FROM_PHONE && e.status != BackupStatus.VERIFIED) continue
            out += GalleryItem(
                key = key(e.mediaId),
                displayName = e.displayName,
                dateMs = if (e.dateTakenMs > 0) e.dateTakenMs else e.dateModifiedSec * 1000,
                isVideo = e.isVideo,
                sizeBytes = e.sizeBytes,
                location = Location.DRIVE,
                phoneUri = null,
                remotePath = remote,
                status = e.status,
                lastError = e.lastError,
            )
        }
        out.sortWith(compareByDescending<GalleryItem> { it.dateMs }.thenBy { it.key })
        return out
    }
}
