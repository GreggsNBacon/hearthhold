package dev.photobackup.freeup

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteStore
import dev.photobackup.backup.Hashing
import dev.photobackup.backup.LocalOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.CoroutineContext

class FreeUpRepository(
    private val ledger: LedgerStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** VERIFIED rows, except those with a drive move in flight (their drive path is not settled). */
    suspend fun verifiedEntries(): List<LedgerEntry> =
        ledger.byStatus(BackupStatus.VERIFIED).filter { it.movingTo == null }

    /**
     * Full integrity check before deletion. For each selected item (re-read from the ledger, must be VERIFIED now):
     * safe against the fresh phone map, drive copy exists with the right size, drive copy re-read in full matches
     * the recorded SHA-256, and the phone file re-hashed matches it too. Items failing a hash/size check are
     * upserted FAILED (so they get backed up again) and never returned. RemoteException/IOException from the
     * drive propagates; cancellation is checked per chunk.
     *
     * With [second] (the "verified on both drives" gate; null = off), an item that passed every main check is
     * returned only if its SecondCopy is VERIFIED with the recorded hash and the second file stats with the right
     * size. A miss is reported as [WAITING_SECOND] and never marks the row FAILED. If the second store fails
     * (IOException) the item and every later one that passes the main pre-checks are reported as
     * [SECOND_UNREACHABLE] without being checked.
     */
    suspend fun recheck(
        selected: List<LedgerEntry>,
        store: RemoteStore,
        phone: Map<Long, PhoneMedia>,
        opener: LocalOpener,
        onProgress: (checked: Int, total: Int, bytesChecked: Long, totalBytes: Long) -> Unit = { _, _, _, _ -> },
        onFailed: (mediaId: Long, reason: String) -> Unit = { _, _ -> },
        second: SecondGate? = null,
    ): List<LedgerEntry> {
        val ctx = currentCoroutineContext()
        val total = selected.size
        val totalBytes = selected.sumOf { it.sizeBytes }
        var doneBytes = 0L
        val ok = ArrayList<LedgerEntry>()
        var secondDown = false
        onProgress(0, total, 0L, totalBytes)
        selected.forEachIndexed { i, sel ->
            ctx.ensureActive()
            val e = ledger.get(sel.mediaId)
            val m = phone[sel.mediaId]
            val path = e?.remotePath
            if (e != null && path != null && e.movingTo == null && LedgerRules.isSafeToRemove(e, m)) {
                if (secondDown) {
                    onFailed(e.mediaId, SECOND_UNREACHABLE) // second store dropped: no point hashing the rest
                    doneBytes += sel.sizeBytes
                    onProgress(i + 1, total, doneBytes, totalBytes)
                    return@forEachIndexed
                }
                val reason = integrityFailure(e, path, m!!, store, opener, ctx) { n ->
                    onProgress(i, total, doneBytes + n, totalBytes)
                }
                when (reason) {
                    null -> {
                        val miss = if (second == null) null else try {
                            secondMiss(e, second)
                        } catch (_: IOException) { // includes RemoteException; never shown or logged
                            secondDown = true
                            SECOND_UNREACHABLE
                        }
                        if (miss == null) ok.add(e) else onFailed(e.mediaId, miss)
                    }
                    GONE -> Unit // vanished from the phone meanwhile: drive copy is still valid, just skip it
                    else -> {
                        markFailed(e, reason)
                        onFailed(e.mediaId, reason)
                    }
                }
            }
            doneBytes += sel.sizeBytes
            onProgress(i + 1, total, doneBytes, totalBytes)
        }
        return ok
    }

    /**
     * Re-queues an item that failed the check. The check can take minutes, and a backup run may have changed the
     * row meanwhile (e.g. started re-uploading an edited file, with a partPath to clean up): so it re-reads the row
     * and writes only if it is still the same VERIFIED copy, never a stale copy over a newer row.
     */
    private suspend fun markFailed(checked: LedgerEntry, reason: String) {
        val now = ledger.get(checked.mediaId) ?: return
        if (now.status != BackupStatus.VERIFIED || now.remotePath != checked.remotePath ||
            now.localSha256 != checked.localSha256
        ) return
        ledger.upsert(now.copy(status = BackupStatus.FAILED, lastError = reason, updatedAtMs = clock()))
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

    /**
     * Null if [e] has a usable second copy: VERIFIED, same hash as the ledger (Task 3's "done" rule), settled (no
     * move in flight) and its file on the second store with the right size. Else [WAITING_SECOND].
     * IOException from the second store propagates.
     */
    private suspend fun secondMiss(e: LedgerEntry, gate: SecondGate): String? {
        val c = gate.copies.get(e.mediaId) ?: return WAITING_SECOND
        if (c.status != SecondStatus.VERIFIED || c.sha256 == null || c.sha256 != e.localSha256 ||
            c.movingTo != null || c.sizeBytes != e.sizeBytes
        ) return WAITING_SECOND
        val path = c.remotePath ?: return WAITING_SECOND
        val remote = gate.store.stat(path)
        return if (remote == null || remote.isDirectory || remote.sizeBytes != e.sizeBytes) WAITING_SECOND else null
    }

    fun deleteRequest(context: Context, items: List<LedgerEntry>): IntentSender =
        MediaStore.createDeleteRequest(context.contentResolver, items.map { Uri.parse(it.localUri) }).intentSender

    /**
     * Marks each entry REMOVED_FROM_PHONE only if it is still VERIFIED with no drive move in flight, as one
     * conditional write (so it can never drop a movingTo marker a backup run sets meanwhile). Returns how many.
     */
    suspend fun markRemoved(items: List<LedgerEntry>): Int {
        val now = clock()
        return items.count { ledger.markRemovedIfSettled(it.mediaId, now) }
    }

    companion object {
        const val DAMAGED = "Drive copy is damaged \u2014 will back up again"
        const val PHONE_CHANGED = "Phone file changed \u2014 will back up again"
        const val WAITING_SECOND = "Waiting for second copy"
        const val SECOND_UNREACHABLE = "Second location not reachable"
        private const val GONE = "gone"
    }
}
