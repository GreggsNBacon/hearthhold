package dev.photobackup.review

import dev.photobackup.ledger.ReviewDecision

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

    /** Ready for the user's confirmation: [request] is the platform's delete request (opaque here). */
    data class Ready(
        val request: Any?,
        val passed: List<ReviewDecision>,
        val failed: List<FailedRow>,
    ) : PrepareResult
}

sealed interface ApplyResult {
    data class Done(val freedBytes: Long, val junkCount: Int, val keptCount: Int) : ApplyResult
    data class Failed(val message: String) : ApplyResult
}

/** Applies the decisions to the phone and the drive. Platform-free so the ViewModel is unit-testable. */
interface ReviewFinisher {
    suspend fun prepare(decisions: List<ReviewDecision>): PrepareResult
    suspend fun apply(prepared: PrepareResult.Ready): ApplyResult
}
