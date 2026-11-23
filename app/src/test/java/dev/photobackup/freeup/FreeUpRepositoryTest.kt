package dev.photobackup.freeup

import dev.photobackup.backup.MemoryLedger
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FreeUpRepositoryTest {
    private fun entry(id: Long, size: Long = 5, remote: String? = "r\\f$id.jpg") =
        LedgerEntry(id, "content://media/$id", "f$id.jpg", "DCIM/", size, 1000, 10, false, "h", remote,
            null, BackupStatus.VERIFIED, null, 0)

    private fun phone(e: LedgerEntry, size: Long = e.sizeBytes) =
        PhoneMedia(e.mediaId, e.localUri, e.displayName, e.relativePath, size, e.dateTakenMs, 0, e.dateModifiedSec,
            false, "image/jpeg")

    @Test fun recheckDropsItemsWhoseRemoteIsMissingOrWrongSize() = runBlocking {
        val ok = entry(1); val missing = entry(2); val wrong = entry(3); val edited = entry(4)
        val store = FakeRemoteStore()
        store.files[ok.remotePath!!] = ByteArray(5)
        store.files[wrong.remotePath!!] = ByteArray(9)
        store.files[edited.remotePath!!] = ByteArray(5)
        val all = listOf(ok, missing, wrong, edited)
        val phone = all.associate { it.mediaId to phone(it) } + (4L to phone(edited, size = 77))
        val kept = FreeUpRepository(MemoryLedger()).recheck(all, store, phone)
        assertEquals(listOf(1L), kept.map { it.mediaId })
    }

    @Test fun recheckPropagatesUnreachableDrive() {
        val e = entry(1)
        val store = FakeRemoteStore(failOn = { op, _ -> if (op == "stat") RemoteException.Unreachable() else null })
        try {
            runBlocking { FreeUpRepository(MemoryLedger()).recheck(listOf(e), store, mapOf(1L to phone(e))) }
            fail("expected RemoteException")
        } catch (ex: RemoteException) {
            assertTrue(ex is RemoteException.Unreachable)
        }
    }

    @Test fun markRemovedSetsStatus() = runBlocking {
        val ledger = MemoryLedger(); val e = entry(1)
        ledger.upsert(e)
        FreeUpRepository(ledger, clock = { 4242L }).markRemoved(listOf(e))
        val got = ledger.get(1)!!
        assertEquals(BackupStatus.REMOVED_FROM_PHONE, got.status)
        assertEquals(4242L, got.updatedAtMs)
        assertEquals(e.remotePath, got.remotePath)
    }
}
