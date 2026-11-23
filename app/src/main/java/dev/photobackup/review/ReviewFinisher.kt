package dev.photobackup.review

import dev.photobackup.backup.LocalOpener
import dev.photobackup.freeup.FreeUpRepository
import dev.photobackup.freeup.SecondGateOpen
import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.ledger.ReviewDecision
import dev.photobackup.ledger.ReviewDecisionDao
import dev.photobackup.ledger.ReviewKeep
import dev.photobackup.ledger.ReviewKeepDao
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/** A decision that could not be applied at Finish, with a short user-safe reason (never exception text). */
data class FailedRow(val mediaId: Long, val reason: String)

sealed interface PrepareResult {
    /** The drive is not reachable; decisions are kept. */
    data object Offline : PrepareResult

    /** "Verified on both drives" is on but the second location is not reachable: nothing applied, decisions kept. */
    data object SecondUnreachable : PrepareResult

    /**
     * Nothing needs the system delete dialog. [keepOnlyApplied] is true when Keep decisions were applied anyway.
     * [alreadyGone]: Drive only / Junk decisions whose phone copy was already gone, applied without the dialog.
     */
    data class NothingToDelete(
        val keepOnlyApplied: Boolean,
        val keptCount: Int = 0,
        val failed: List<FailedRow> = emptyList(),
        val alreadyGone: List<ReviewDecision> = emptyList(),
    ) : PrepareResult

    /**
     * Ready for the user's confirmation: [request] is the platform's delete request (opaque here) for the [passed]
     * Drive only / Junk rows. [keeps] are applied together with them once the user confirms. [alreadyGone] were
     * applied already (phone copy gone; see [NothingToDelete.alreadyGone]).
     */
    data class Ready(
        val request: Any?,
        val passed: List<ReviewDecision>,
        val failed: List<FailedRow>,
        val keeps: List<ReviewDecision> = emptyList(),
        val alreadyGone: List<ReviewDecision> = emptyList(),
    ) : PrepareResult
}

sealed interface ApplyResult {
    /**
     * [leftAlone]: confirmed rows a backup run is moving meanwhile; not marked, decisions kept for the next Finish.
     * [dropped]: confirmed rows no longer VERIFIED (or gone from the ledger); they can never apply, decisions cleared.
     */
    data class Done(
        val freedBytes: Long,
        val junkCount: Int,
        val keptCount: Int,
        val leftAlone: Int = 0,
        val dropped: Int = 0,
    ) : ApplyResult
    data class Failed(val message: String) : ApplyResult
}

/** Applies the decisions to the phone and the drive. Platform-free so the ViewModel is unit-testable. */
interface ReviewFinisher {
    /** [onProgress] reports the drive re-check: rows checked of the Drive only / Junk rows being checked. */
    suspend fun prepare(
        decisions: List<ReviewDecision>,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
    ): PrepareResult
    suspend fun apply(prepared: PrepareResult.Ready): ApplyResult
}

/**
 * Finish: re-checks Drive only / Junk rows against the drive (which must be reachable), builds one system delete
 * request for those that passed, and after the user confirms marks them removed (Junk also asks for the bin, in the
 * same conditional write), records Keeps and clears the applied decisions. Cancelling the dialog changes nothing.
 * The drive is used by [prepare] only.
 *
 * A Drive only / Junk row whose phone copy is already gone (checked by [phoneExists], in any folder) while its row is
 * still VERIFIED (Android deleted it but the
 * ledger write never landed: a backup's move in between, the app killed or left during apply) is applied by [prepare]
 * itself with the same conditional write, so Junk still reaches the drive bin.
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
    /**
     * Which of the given MediaStore ids still exist on the phone, in ANY folder (no backup-folder filter): [queryPhone]
     * leaves out items in unticked folders, which are not gone. Throws if it can't tell.
     */
    private val phoneExists: suspend (Collection<Long>) -> Set<Long>,
    /** The "verified on both drives" gate: Off unless a second location is set and freeing up needs both. */
    private val openSecondGate: suspend () -> SecondGateOpen = { SecondGateOpen.Off },
) : ReviewFinisher {

    override suspend fun prepare(
        decisions: List<ReviewDecision>,
        onProgress: (checked: Int, total: Int) -> Unit,
    ): PrepareResult {
        val keepList = decisions.filter { it.action == ReviewAction.KEEP }
        val toDelete = decisions.filter { it.action == ReviewAction.DRIVE_ONLY || it.action == ReviewAction.JUNK }
        if (keepList.isEmpty() && toDelete.isEmpty()) return PrepareResult.NothingToDelete(keepOnlyApplied = false)

        val failedIds = LinkedHashMap<Long, String>()
        val phone: Map<Long, PhoneMedia>
        val rows: Map<Long, LedgerEntry>
        val passed: List<LedgerEntry>
        try {
            val store = openStore() ?: return PrepareResult.Offline
            phone = queryPhone()
            passed = store.use {
                it.connect()
                // Keep-only: nothing leaves the phone, so the second location is not needed (nor asked for).
                val gate = when (val g = if (toDelete.isEmpty()) SecondGateOpen.Off else openSecondGate()) {
                    SecondGateOpen.Off -> null
                    SecondGateOpen.Unreachable -> return PrepareResult.SecondUnreachable
                    is SecondGateOpen.Open -> g.gate
                }
                try {
                    rows = toDelete.mapNotNull { d -> ledger.get(d.mediaId) }.associateBy { e -> e.mediaId }
                    repo.recheck(
                        rows.values.toList(), it, phone, opener,
                        onFailed = { id, reason -> failedIds[id] = reason }, second = gate,
                        onProgress = { checked, total, _, _ -> onProgress(checked, total) },
                    )
                } finally {
                    gate?.let { g -> runCatching { g.close() } }
                }
            }
        } catch (e: IOException) { // includes RemoteException; never shown or logged
            return PrepareResult.Offline
        }

        val passedIds = passed.map { it.mediaId }.toSet()
        val missing = toDelete.filter {
            it.mediaId !in passedIds && it.mediaId !in failedIds && phone[it.mediaId] == null
        }
        // Missing from the folder-filtered query is not gone: ask MediaStore without the filter. If that fails,
        // nothing counts as gone (the rows wait as not ready).
        val stillOnPhone = if (missing.isEmpty()) emptySet() else stillOnPhone(missing.map { it.mediaId })
        val alreadyGone = if (stillOnPhone == null) emptyList() else {
            applyAlreadyGone(missing.filter { it.mediaId !in stillOnPhone })
        }
        val goneIds = alreadyGone.map { it.mediaId }.toSet()
        val failed = toDelete.filter { it.mediaId !in passedIds && it.mediaId !in goneIds }.map { d ->
            val e = ledger.get(d.mediaId)
            val reason = failedIds[d.mediaId] ?: when {
                e != null && stillOnPhone != null && d.mediaId in stillOnPhone -> NOT_IN_FOLDERS
                else -> skipReason(e, phone[d.mediaId])
            }
            FailedRow(d.mediaId, reason)
        }
        if (passed.isEmpty()) {
            applyKeeps(keepList)
            this.decisions.clear(stale(failed))
            return PrepareResult.NothingToDelete(keepList.isNotEmpty(), keepList.size, failed, alreadyGone)
        }
        return PrepareResult.Ready(
            request = makeDeleteRequest(passed),
            passed = toDelete.filter { it.mediaId in passedIds },
            failed = failed,
            keeps = keepList,
            alreadyGone = alreadyGone,
        )
    }

    /** Which of [ids] still exist on the phone in any folder, or null if that can't be told (never shown or logged). */
    private suspend fun stillOnPhone(ids: List<Long>): Set<Long>? = try {
        phoneExists(ids)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * The phone copy of each of [gone] has been deleted already: marks the still-VERIFIED, settled rows removed (Junk
     * with binRequestedAt) and clears their decisions. Returns those applied; the rest are listed by the caller.
     */
    private suspend fun applyAlreadyGone(gone: List<ReviewDecision>): List<ReviewDecision> {
        if (gone.isEmpty()) return emptyList()
        val now = clock()
        val applied = gone.filter { d ->
            val binAt = if (d.action == ReviewAction.JUNK) now else null
            ledger.markRemovedIfSettled(d.mediaId, now, binRequestedAt = binAt)
        }
        if (applied.isNotEmpty()) decisions.clear(applied.map { it.mediaId })
        return applied
    }

    /**
     * Runs to the end even if the caller is cancelled (the user left the screen): Android has already deleted the phone
     * copies, so the marks must land and [runNow] must fire.
     */
    override suspend fun apply(prepared: PrepareResult.Ready): ApplyResult = withContext(NonCancellable) {
        val now = clock()
        val marked = ArrayList<Long>()
        val dropped = ArrayList<Long>()
        var freed = 0L
        var junk = prepared.alreadyGone.count { it.action == ReviewAction.JUNK }
        try {
            for (d in prepared.passed) {
                val size = ledger.get(d.mediaId)?.sizeBytes ?: 0L
                val isJunk = d.action == ReviewAction.JUNK
                if (ledger.markRemovedIfSettled(d.mediaId, now, binRequestedAt = if (isJunk) now else null)) {
                    marked += d.mediaId
                    freed += size
                    if (isJunk) junk++
                } else if (ledger.get(d.mediaId)?.status != BackupStatus.VERIFIED) {
                    dropped += d.mediaId // never applicable again; a VERIFIED row (move in flight) retries next Finish
                }
            }
            applyKeeps(prepared.keeps, now)
            decisions.clear(marked + dropped + stale(prepared.failed))
        } finally {
            runNow() // Android already deleted the phone copies: start the drive-side work even if a write failed
        }
        ApplyResult.Done(
            freed, junk, prepared.keeps.size,
            leftAlone = prepared.passed.size - marked.size - dropped.size, dropped = dropped.size,
        )
    }

    private suspend fun applyKeeps(list: List<ReviewDecision>, now: Long = clock()) {
        if (list.isEmpty()) return
        list.forEach { keeps.upsert(ReviewKeep(it.mediaId, now)) }
        decisions.clear(list.map { it.mediaId })
    }

    /** Why recheck skipped a row without failing it. Only gone or edited rows lose their decision. */
    private fun skipReason(e: LedgerEntry?, m: PhoneMedia?) = when {
        e == null -> GONE_FROM_PHONE
        m == null -> if (e.status == BackupStatus.VERIFIED) NOT_READY else GONE_FROM_PHONE // VERIFIED: a move in flight
        LedgerRules.changed(e, m) -> EDITED
        else -> NOT_READY // not VERIFIED yet (e.g. failed earlier, re-uploading) or a drive move in flight
    }

    /** Rows whose decision can no longer apply; failed or not-ready rows keep theirs for a retry. */
    private fun stale(failed: List<FailedRow>) =
        failed.filter { it.reason == GONE_FROM_PHONE || it.reason == EDITED }.map { it.mediaId }

    companion object {
        const val GONE_FROM_PHONE = "No longer on the phone"
        const val EDITED = "Phone file changed since review"
        const val NOT_IN_FOLDERS = "Not in a backed-up folder any more"
        const val NOT_READY = "Not ready yet — try again later"
    }
}
