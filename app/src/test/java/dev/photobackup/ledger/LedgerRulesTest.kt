package dev.photobackup.ledger

import dev.photobackup.ledger.BackupStatus.*
import dev.photobackup.media.PhoneMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerRulesTest {
    private fun phone(sizeBytes: Long = 100, dateModifiedSec: Long = 10) = PhoneMedia(
        1, "content://media/1", "a.jpg", "DCIM/Camera/", sizeBytes, 1000, 5, dateModifiedSec, false, "image/jpeg"
    )

    private fun entry(status: BackupStatus) = LedgerEntry(
        1, "content://media/1", "a.jpg", "DCIM/Camera/", 100, 1000, 10, false,
        null, null, null, status, null, 0
    )

    @Test fun newItemNeedsUpload() = assertTrue(LedgerRules.needsUpload(null, phone()))
    @Test fun verifiedUnchangedDoesNot() = assertFalse(LedgerRules.needsUpload(entry(VERIFIED), phone()))
    @Test fun editedAfterVerifyNeedsUpload() = assertTrue(LedgerRules.needsUpload(entry(VERIFIED), phone(dateModifiedSec = 999)))
    @Test fun failedNeedsUpload() = assertTrue(LedgerRules.needsUpload(entry(FAILED), phone()))
    @Test fun binnedNeverNeedsUploadAndIsNeverSafeToRemove() {
        assertFalse(LedgerRules.needsUpload(entry(BINNED), phone()))
        assertFalse(LedgerRules.isSafeToRemove(entry(BINNED), phone()))
    }

    @Test fun safeOnlyWhenVerifiedAndUnchanged() {
        assertTrue(LedgerRules.isSafeToRemove(entry(VERIFIED), phone()))
        assertFalse(LedgerRules.isSafeToRemove(entry(PENDING), phone()))
        assertFalse(LedgerRules.isSafeToRemove(entry(VERIFIED), phone(sizeBytes = 1)))
        assertFalse(LedgerRules.isSafeToRemove(entry(VERIFIED), null))
    }
}
