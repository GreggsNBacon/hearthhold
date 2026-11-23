package dev.photobackup.ledger

import dev.photobackup.media.PhoneMedia

object LedgerRules {
    private fun changed(e: LedgerEntry, p: PhoneMedia) =
        e.sizeBytes != p.sizeBytes || e.dateModifiedSec != p.dateModifiedSec

    fun needsUpload(existing: LedgerEntry?, phone: PhoneMedia): Boolean = when {
        existing == null -> true
        existing.status == BackupStatus.VERIFIED -> changed(existing, phone)
        existing.status == BackupStatus.BINNED -> false
        else -> true
    }

    fun isSafeToRemove(e: LedgerEntry, phone: PhoneMedia?): Boolean =
        e.status == BackupStatus.VERIFIED && phone != null && !changed(e, phone)
}
