package dev.photobackup.ledger

import dev.photobackup.media.PhoneMedia

object LedgerRules {
    /** The phone file was edited since this row was recorded. */
    fun changed(e: LedgerEntry, p: PhoneMedia) =
        e.sizeBytes != p.sizeBytes || e.dateModifiedSec != p.dateModifiedSec

    fun needsUpload(existing: LedgerEntry?, phone: PhoneMedia): Boolean = when {
        existing == null -> true
        existing.status == BackupStatus.VERIFIED -> changed(existing, phone)
        existing.status == BackupStatus.BINNED -> false
        existing.binRequestedAt != null -> false // Junk on its way to the bin: not waiting
        else -> true
    }

    fun isSafeToRemove(e: LedgerEntry, phone: PhoneMedia?): Boolean =
        e.status == BackupStatus.VERIFIED && phone != null && !changed(e, phone)
}
