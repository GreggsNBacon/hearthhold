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
import dev.photobackup.backup.Hashing
import dev.photobackup.backup.LocalOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class FreeUpRepository(
    private val ledger: LedgerStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun verifiedEntries(): List<LedgerEntry> = ledger.byStatus(BackupStatus.VERIFIED)

    /**
     * Full integrity check before deletion. For each selected item (re-read from the ledger, must be VERIFIED now):
     * safe against the fresh phone map, drive copy exists with the right size, drive copy re-read in full matches
     * the recorded SHA-256, and the phone file re-hashed matches it too. Items failing a hash/size check are
     * upserted FAILED (so they get backed up again) and never returned. RemoteException/IOException from the
     * drive propagates; cancellation is checked per chunk.
     */
    suspend fun recheck(
        selected: List<LedgerEntry>,
        store: RemoteStore,
        phone: Map<Long, PhoneMedia>,
        opener: LocalOpener,
        onProgress: (checked: Int, total: Int, bytesChecked: Long, totalBytes: Long) -> Unit = { _, _, _, _ -> },
        onFailed: (reason: String) -> Unit = {},
    ): List<LedgerEntry> {
        val ctx = currentCoroutineContext()
        val total = selected.size
        val totalBytes = selected.sumOf { it.sizeBytes }
        var doneBytes = 0L
        val ok = ArrayList<LedgerEntry>()
        onProgress(0, total, 0L, totalBytes)
        selected.forEachIndexed { i, sel ->
            ctx.ensureActive()
            val e = ledger.get(sel.mediaId)
            val m = phone[sel.mediaId]
            val path = e?.remotePath
            if (e != null && path != null && LedgerRules.isSafeToRemove(e, m)) {
                val reason = integrityFailure(e, path, m!!, store, opener, ctx) { n ->
                    onProgress(i, total, doneBytes + n, totalBytes)
                }
                when (reason) {
                    null -> ok.add(e)
                    GONE -> Unit // vanished from the phone meanwhile: drive copy is still valid, just skip it
                    else -> {
                        ledger.upsert(e.copy(status = BackupStatus.FAILED, lastError = reason, updatedAtMs = clock()))
                        onFailed(reason)
                    }
                }
            }
            doneBytes += sel.sizeBytes
            onProgress(i + 1, total, doneBytes, totalBytes)
        }
        return ok
    }

    /** Null if the item passes every check, else the user-facing reason. */
    private suspend fun integrityFailure(
        e: LedgerEntry,
        path: String,
        m: PhoneMedia,
        store: RemoteStore,
        opener: LocalOpener,
        ctx: CoroutineContext,
        onDriveBytes: (Long) -> Unit,
    ): String? {
        val recorded = e.localSha256 ?: return DAMAGED
        val remote = store.stat(path)
        if (remote == null || remote.isDirectory || remote.sizeBytes != e.sizeBytes) return DAMAGED
        var read = 0L
        val driveHash = store.openRead(path).use { input ->
            withContext(Dispatchers.IO) {
                Hashing.sha256(input) { n -> ctx.ensureActive(); read += n; onDriveBytes(read) }
            }
        }
        if (driveHash != recorded) return DAMAGED
        val phoneHash = withContext(Dispatchers.IO) {
            opener.open(m)?.use { input -> Hashing.sha256(input) { ctx.ensureActive() } }
        } ?: return GONE
        return if (phoneHash != recorded) PHONE_CHANGED else null
    }

    fun deleteRequest(context: Context, items: List<LedgerEntry>): IntentSender =
        MediaStore.createDeleteRequest(context.contentResolver, items.map { Uri.parse(it.localUri) }).intentSender

    /** Re-reads each entry and only marks those still VERIFIED. Returns how many were marked. */
    suspend fun markRemoved(items: List<LedgerEntry>): Int {
        val now = clock()
        var n = 0
        for (item in items) {
            val e = ledger.get(item.mediaId) ?: continue
            if (e.status != BackupStatus.VERIFIED) continue
            ledger.upsert(e.copy(status = BackupStatus.REMOVED_FROM_PHONE, updatedAtMs = now))
            n++
        }
        return n
    }

    companion object {
        const val DAMAGED = "Drive copy is damaged \u2014 will back up again"
        const val PHONE_CHANGED = "Phone file changed \u2014 will back up again"
        private const val GONE = "gone"
    }
}
