package dev.photobackup.review

import dev.photobackup.backup.LocalOpener
import dev.photobackup.freeup.FreeUpRepository
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.ledger.ReviewDecision
import dev.photobackup.ledger.ReviewDecisionDao
import dev.photobackup.ledger.ReviewKeep
import dev.photobackup.ledger.ReviewKeepDao
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteStore
import java.io.IOException

/** A decision that could not be applied at Finish, with a short user-safe reason (never exception text). */
data class FailedRow(val mediaId: Long, val reason: String)

sealed interface PrepareResult {
    /** The drive is not reachable; decisions are kept. */
    data object Offline : PrepareResult

    /** Nothing needs the system delete dialog. [keepOnlyApplied] is true when Keep decisions were applied anyway. */
    data class NothingToDelete(
        val keepOnlyApplied: Boolean,
        val keptCount: Int = 0,
        val failed: List<FailedRow> = emptyList(),
    ) : PrepareResult

    /**
     * Ready for the user's confirmation: [request] is the platform's delete request (opaque here) for the [passed]
     * Drive only / Junk rows. [keeps] are applied together with them once the user confirms.
     */
    data class Ready(
        val request: Any?,
        val passed: List<ReviewDecision>,
        val failed: List<FailedRow>,
        val keeps: List<ReviewDecision> = emptyList(),
    ) : PrepareResult
}

sealed interface ApplyResult {
    /** [leftAlone]: confirmed rows a backup run changed meanwhile; not marked, their decisions kept. */
    data class Done(val freedBytes: Long, val junkCount: Int, val keptCount: Int, val leftAlone: Int = 0) : ApplyResult
    data class Failed(val message: String) : ApplyResult
}

/** Applies the decisions to the phone and the drive. Platform-free so the ViewModel is unit-testable. */
interface ReviewFinisher {
    suspend fun prepare(decisions: List<ReviewDecision>): PrepareResult
    suspend fun apply(prepared: PrepareResult.Ready): ApplyResult
}

/**
 * Finish: re-checks Drive only / Junk rows against the drive (which must be reachable), builds one system delete
 * request for those that passed, and after the user confirms marks them removed (Junk also asks for the bin, in the
 * same conditional write), records Keeps and clears the applied decisions. Cancelling the dialog changes nothing.
 * The drive is used by [prepare] only.
 */
class RealReviewFinisher(
    private val ledger: LedgerStore,
    private val decisions: ReviewDecisionDao,
    private val keeps: ReviewKeepDao,
    private val repo: FreeUpRepository,
    /** Opens a store for the configured drive, or null if no drive is configured. */
    private val openStore: suspend () -> RemoteStore?,
    private val queryPhone: suspend () -> Map<Long, PhoneMedia>,
    private val opener: LocalOpener,
    /** Builds the platform delete request (production: [FreeUpRepository.deleteRequest]). */
    private val makeDeleteRequest: (List<LedgerEntry>) -> Any?,
    private val runNow: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReviewFinisher {

    override suspend fun prepare(decisions: List<ReviewDecision>): PrepareResult {
        val keepList = decisions.filter { it.action == ReviewAction.KEEP }
        val toDelete = decisions.filter { it.action == ReviewAction.DRIVE_ONLY || it.action == ReviewAction.JUNK }
        if (keepList.isEmpty() && toDelete.isEmpty()) return PrepareResult.NothingToDelete(keepOnlyApplied = false)

        val failedIds = LinkedHashMap<Long, String>()
        val phone: Map<Long, PhoneMedia>
        val passed: List<LedgerEntry>
        try {
            val store = openStore() ?: return PrepareResult.Offline
            phone = queryPhone()
            passed = store.use {
                it.connect()
                val rows = toDelete.mapNotNull { d -> ledger.get(d.mediaId) }
                repo.recheck(rows, it, phone, opener, onFailed = { id, reason -> failedIds[id] = reason })
            }
        } catch (e: IOException) { // includes RemoteException; never shown or logged
            return PrepareResult.Offline
        }

        val passedIds = passed.map { it.mediaId }.toSet()
        val failed = toDelete.filter { it.mediaId !in passedIds }.map { d ->
            FailedRow(d.mediaId, failedIds[d.mediaId] ?: if (phone[d.mediaId] == null) GONE_FROM_PHONE else CHANGED)
        }
        if (passed.isEmpty()) {
            applyKeeps(keepList)
            this.decisions.clear(stale(failed))
            return PrepareResult.NothingToDelete(keepList.isNotEmpty(), keepList.size, failed)
        }
        return PrepareResult.Ready(
            request = makeDeleteRequest(passed),
            passed = toDelete.filter { it.mediaId in passedIds },
            failed = failed,
            keeps = keepList,
        )
    }

    override suspend fun apply(prepared: PrepareResult.Ready): ApplyResult {
        val now = clock()
        val marked = ArrayList<Long>()
        var freed = 0L
        var junk = 0
        for (d in prepared.passed) {
            val size = ledger.get(d.mediaId)?.sizeBytes ?: 0L
            val isJunk = d.action == ReviewAction.JUNK
            if (ledger.markRemovedIfSettled(d.mediaId, now, binRequestedAt = if (isJunk) now else null)) {
                marked += d.mediaId
                freed += size
                if (isJunk) junk++
            }
        }
        applyKeeps(prepared.keeps, now)
        decisions.clear(marked + stale(prepared.failed))
        runNow()
        return ApplyResult.Done(freed, junk, prepared.keeps.size, leftAlone = prepared.passed.size - marked.size)
    }

    private suspend fun applyKeeps(list: List<ReviewDecision>, now: Long = clock()) {
        if (list.isEmpty()) return
        list.forEach { keeps.upsert(ReviewKeep(it.mediaId, now)) }
        decisions.clear(list.map { it.mediaId })
    }

    /** Rows whose decision can no longer apply (gone or changed); failed re-checks keep theirs for a retry. */
    private fun stale(failed: List<FailedRow>) =
        failed.filter { it.reason == GONE_FROM_PHONE || it.reason == CHANGED }.map { it.mediaId }

    companion object {
        const val GONE_FROM_PHONE = "No longer on the phone"
        const val CHANGED = "Changed since review"
    }
}
