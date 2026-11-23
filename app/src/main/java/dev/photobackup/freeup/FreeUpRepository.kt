package dev.photobackup.freeup

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteStore

class FreeUpRepository(
    private val ledger: LedgerStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun verifiedEntries(): List<LedgerEntry> = ledger.byStatus(BackupStatus.VERIFIED)

    /**
     * Keeps an item only if it is still safe against the current phone media AND its drive copy exists with
     * the recorded size. A RemoteException from the store propagates (drive unreachable).
     */
    suspend fun recheck(
        selected: List<LedgerEntry>,
        store: RemoteStore,
        phone: Map<Long, PhoneMedia>,
    ): List<LedgerEntry> = selected.filter { e ->
        val path = e.remotePath
        if (path == null || !LedgerRules.isSafeToRemove(e, phone[e.mediaId])) {
            false
        } else {
            val remote = store.stat(path)
            remote != null && !remote.isDirectory && remote.sizeBytes == e.sizeBytes
        }
    }

    fun deleteRequest(context: Context, items: List<LedgerEntry>): IntentSender =
        MediaStore.createDeleteRequest(context.contentResolver, items.map { Uri.parse(it.localUri) }).intentSender

    suspend fun markRemoved(items: List<LedgerEntry>) {
        val now = clock()
        for (e in items) {
            ledger.upsert(e.copy(status = BackupStatus.REMOVED_FROM_PHONE, updatedAtMs = now))
        }
    }
}
