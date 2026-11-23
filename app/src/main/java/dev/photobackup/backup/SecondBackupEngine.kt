package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.ledger.SecondCopyDao
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.AccessDeniedException
import dev.photobackup.smb.MSG_ACCESS_DENIED
import dev.photobackup.smb.RemoteEntry
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

sealed interface SecondResult {
    /**
     * [copied]: new verified copies made. [adopted]: identical files already there, now recorded. [failed]: items
     * marked FAILED this run (retried next run). [waiting]: items that need the main drive copy, which could not be
     * reached (or is mid-move), plus items whose second-location move is unsettled; left as they were.
     */
    data class Done(val copied: Int, val adopted: Int, val failed: Int, val waiting: Int) : SecondResult

    /** The second location could not be reached, or dropped during the run. The next run carries on. */
    data object Unreachable : SecondResult

    /** The second location refused its login. */
    data object AuthFailed : SecondResult
}

/**
 * The second backup step: copies what is verified on the main drive to the optional second location, and verifies each
 * copy by its own full read-back SHA-256 against the recorded phone hash. An archive: it only grows.
 *
 * - Copies only ledger rows VERIFIED or REMOVED_FROM_PHONE with a recorded hash and no bin request (BINNED rows have
 *   one). An existing second copy is never removed, whatever happens to the main row.
 * - Source: the phone file if it is there and hashes to the recorded hash (checked while copying, before the rename);
 *   else the main drive file at the row's remotePath, through [mainStore] (asked at most once per run, only when such
 *   an item comes up). Main store null or failing: those items wait, unchanged. Main copy reachable but missing, of the
 *   wrong size or hash: the item is FAILED with [DAMAGED_ERROR] and retried each run.
 * - Target: `<dest2>\<device>\<desired folder>\<year>\<name>`, with the year and name of the main copy (the planner's
 *   when the main path is not in today's layout). Existing same-size file: hashed, adopted on a match, else the clash
 *   name. Never overwrites ([DriveMover.pickName], [DriveCopy.copyVerified]).
 * - Deletes only its own tracked `.part` and a copy of its own that failed its read-back. Bin, empty, auto-empty and
 *   restore are never mirrored; a second copy moves only by this step's own folder reconcile.
 *
 * Run order: connect, own `.part` cleanup, move recovery (same four-case rule as the main engine), folder reconcile,
 * then the missing copies one at a time, waiting [itemPauseMs] between items. Cancellable between chunks; a cancelled
 * or dropped copy keeps its `.part` recorded for the next run's cleanup. A [RemoteException] from the second store
 * stops the run (Unreachable, or AuthFailed); a row is VERIFIED only after its file was read back in full.
 *
 * [settings] holds the second destination. [mainStore] must return a store ready to use (connected), or null.
 * Nothing is logged; stored errors are fixed texts, never exception text.
 */
class SecondBackupEngine(
    private val store: RemoteStore,
    private val mainStore: suspend () -> RemoteStore?,
    private val ledger: LedgerStore,
    private val copies: SecondCopyDao,
    private val opener: LocalOpener,
    private val settings: BackupSettings,
    private val clock: () -> Long,
    private val itemPauseMs: Long = BackupEngine.DEFAULT_ITEM_PAUSE_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** The desired folder for an item (null = the default source folder); the runner passes FolderRules.desired. */
    private val folderFor: suspend (mediaId: Long, relativePath: String) -> String? = { _, _ -> null },
) {
    companion object {
        const val DAMAGED_ERROR = "Main copy damaged — can't make a second copy"
        const val COPY_ERROR = "Copy to the second location failed — will try again"
        const val LEFTOVER_ERROR = "Couldn't remove an unfinished copy on the second location — will try again"
        const val NO_NAME_ERROR = "No free file name in the second location folder"

        /** The fixed text stored for a failed item; never the exception text. */
        fun itemError(e: Throwable): String = when (e) {
            is AccessDeniedException -> MSG_ACCESS_DENIED
            is FileNotFoundException -> BackupEngine.NOT_FOUND_ERROR
            else -> COPY_ERROR
        }
    }

    private enum class Outcome { COPIED, ADOPTED, FAILED, WAITING }

    /** How one copy attempt from one source ended. */
    private sealed interface Attempt {
        class Done(val outcome: Outcome) : Attempt

        /** The source is no good (its `.part` is already removed): [error] null = wrong size or hash, else its read error. */
        class BadSource(val error: IOException?) : Attempt
    }

    private val mover = DriveMover(store)
    private val root = DrivePaths.deviceRoot(settings.destination, settings.deviceName)

    // Main store for this run: asked at most once; down once it failed.
    private var mainAsked = false
    private var main: RemoteStore? = null

    suspend fun run(): SecondResult = withContext(Dispatchers.IO) {
        mainAsked = false
        main = null
        try {
            store.connect()
        } catch (e: IOException) {
            return@withContext stopped(e)
        }
        try {
            cleanLeftovers()
            val unsettled = recoverMoves()
            reconcile(unsettled)
            copyMissing()
        } catch (e: RemoteException) {
            stopped(e)
        }
    }

    private fun stopped(e: IOException): SecondResult =
        if (e is RemoteException.AuthFailed) SecondResult.AuthFailed else SecondResult.Unreachable

    /** Deletes each row's own tracked `.part`; the row then waits for a fresh copy. */
    private suspend fun cleanLeftovers() {
        for (row in copies.withPartPath()) {
            val part = row.partPath ?: continue
            if (DriveCopy.removeIfPresent(store, part)) {
                copies.upsert(
                    row.copy(partPath = null, status = SecondStatus.UPLOADING, lastError = null, updatedAtMs = clock())
                )
            } else {
                // Keep partPath so the next run tries again.
                copies.upsert(row.copy(status = SecondStatus.FAILED, lastError = LEFTOVER_ERROR, updatedAtMs = clock()))
            }
        }
    }

    /**
     * Settles second-location moves a crash interrupted, by which path holds the file (as the main engine does):
     * only the new path (recorded size) → point at it; only the old → forget the move; both → keep the old, flag a
     * conflict (movingTo kept); neither → leave it, flag an error. Writes only while the row still records that move.
     * Returns the rows not settled cleanly; their recorded target stays reserved for the run.
     */
    private suspend fun recoverMoves(): Set<Long> {
        val unsettled = mutableSetOf<Long>()
        for (row in copies.withMovingTo()) {
            val to = row.movingTo ?: continue
            val from = row.remotePath
            val newEntry: RemoteEntry?
            val oldExists: Boolean
            try {
                newEntry = store.stat(to)?.takeIf { !it.isDirectory }
                oldExists = from != null && store.stat(from)?.isDirectory == false
            } catch (e: IOException) {
                if (e is RemoteException) throw e
                copies.setLastError(row.mediaId, BackupEngine.MOVE_ERROR, clock())
                unsettled += row.mediaId
                mover.reserveExact(to)
                continue
            }
            val priorMoveError =
                row.lastError == BackupEngine.MOVE_ERROR || row.lastError == BackupEngine.MOVE_CONFLICT_ERROR
            val kept = if (priorMoveError) null else row.lastError
            var path = from
            var moving: String? = null
            val error: String?
            when {
                oldExists && newEntry != null -> { moving = to; error = BackupEngine.MOVE_CONFLICT_ERROR }
                oldExists -> error = kept
                newEntry != null && newEntry.sizeBytes == row.sizeBytes -> { path = to; error = kept }
                else -> { moving = to; error = BackupEngine.MOVE_ERROR }
            }
            if (moving != null) {
                unsettled += row.mediaId
                mover.reserveExact(moving)
            }
            if (!copies.endMoveIf(row.mediaId, to, path, moving, error, clock())) unsettled += row.mediaId
        }
        return unsettled
    }

    /** Records a failed second-location move on its [SecondCopy] row, only while it still records that move. */
    private inner class SecondMoveRecorder(private val mediaId: Long, private val from: String) : MoveRecorder {
        override suspend fun failed() = copies.setLastError(mediaId, BackupEngine.MOVE_ERROR, clock())
        override suspend fun abandoned(target: String, stillAtFrom: Boolean) {
            copies.endMoveIf(
                mediaId, target, remotePath = from, movingTo = if (stillAtFrom) null else target,
                lastError = BackupEngine.MOVE_ERROR, nowMs = clock(),
            )
        }
    }

    /**
     * Moves each VERIFIED second copy whose folder segment differs (ignoring case) from the item's desired folder
     * ([folderFor] on the main row's phone folder; the main row's own path is not followed, so binning or restoring
     * never moves a second copy). Year and name stay. Copies not in today's layout under the second root, rows in
     * [skip], and copies whose main row is gone are left alone.
     */
    private suspend fun reconcile(skip: Set<Long>) {
        var pauseFirst = false
        for (row in copies.byStatus(SecondStatus.VERIFIED).sortedBy { it.mediaId }) {
            coroutineContext.ensureActive()
            if (row.mediaId in skip || row.movingTo != null || row.partPath != null) continue
            val from = row.remotePath ?: continue
            val file = DrivePaths.parsePath(from, root) ?: continue
            val entry = ledger.get(row.mediaId) ?: continue
            val desired = desiredFolder(entry)
            if (desired.equals(file.folder, ignoreCase = true)) continue
            if (pauseFirst && itemPauseMs > 0) pause(itemPauseMs)
            pauseFirst = true
            mover.move(
                from, "$root\\$desired\\${file.year}", file.name, SecondMoveRecorder(row.mediaId, from),
                begin = { target -> copies.beginMove(row.mediaId, from, target, clock()) },
                finish = { target -> copies.endMoveIf(row.mediaId, target, target, null, null, clock()) },
            )
        }
    }

    private suspend fun desiredFolder(e: LedgerEntry): String =
        folderFor(e.mediaId, e.relativePath)?.let { RemotePathPlanner.sanitise(it) }
            ?: RemotePathPlanner.sourceFolder(e.relativePath)

    private suspend fun copyMissing(): SecondResult.Done {
        val rows = (ledger.byStatus(BackupStatus.VERIFIED) + ledger.byStatus(BackupStatus.REMOVED_FROM_PHONE))
            .filter { it.localSha256 != null && it.binRequestedAt == null }
            .sortedBy { it.mediaId }
        var copied = 0
        var adopted = 0
        var failed = 0
        var waiting = 0
        var pauseFirst = false
        for (row in rows) {
            coroutineContext.ensureActive()
            val prior = copies.get(row.mediaId)
            if (prior != null && prior.status == SecondStatus.VERIFIED && prior.sha256 == row.localSha256) continue
            if (prior?.partPath != null) { failed++; continue } // its .part could not be removed; cleanup retries
            if (prior?.movingTo != null) { waiting++; continue } // unsettled move: leave the row alone
            if (pauseFirst && itemPauseMs > 0) pause(itemPauseMs)
            pauseFirst = true
            when (copyOne(row, prior)) {
                Outcome.COPIED -> copied++
                Outcome.ADOPTED -> adopted++
                Outcome.FAILED -> failed++
                Outcome.WAITING -> waiting++
            }
        }
        return SecondResult.Done(copied, adopted, failed, waiting)
    }

    /** `<root>\<desired>\<year>` and the name: the main copy's year and name, else the planner's. */
    private suspend fun target(row: LedgerEntry): Pair<String, String> {
        val folder = folderFor(row.mediaId, row.relativePath)
        val segs = row.remotePath?.split('\\').orEmpty()
        if (segs.size >= 2) {
            val year = segs[segs.size - 2]
            val name = segs.last()
            if (year.length == 4 && year.all { it in '0'..'9' } && name.isNotEmpty()) {
                return "$root\\${desiredFolder(row)}\\$year" to name
            }
        }
        val dir = RemotePathPlanner.targetDir(settings.destination, settings.deviceName, media(row), settings.zone, folder)
        return dir to RemotePathPlanner.sanitise(row.displayName)
    }

    /** The phone item as the opener needs it (date added is not recorded; date modified stands in for it). */
    private fun media(row: LedgerEntry) = PhoneMedia(
        row.mediaId, row.localUri, row.displayName, row.relativePath, row.sizeBytes, row.dateTakenMs,
        row.dateModifiedSec, row.dateModifiedSec, row.isVideo, "",
    )

    private suspend fun copyOne(row: LedgerEntry, prior: SecondCopy?): Outcome {
        val sha = row.localSha256!!
        val (dir, name) = target(row)
        val pick = try {
            mover.pickName(dir, name, row.sizeBytes) { existing ->
                if (DriveCopy.hash(store, existing) == sha) NameCheck.ADOPT else NameCheck.NEXT
            }
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            return fail(row, itemError(e))
        }
        return when (pick) {
            is NamePick.Existing -> {
                copies.upsert(copyRow(row, SecondStatus.VERIFIED, remote = pick.path))
                Outcome.ADOPTED
            }
            NamePick.NoName, NamePick.Aborted -> fail(row, NO_NAME_ERROR)
            is NamePick.Free -> try {
                copyTo(row, prior, pick.path)
            } finally {
                mover.release(pick.path)
            }
        }
    }

    /** Copies to the reserved [final] name: from the phone when it has a good copy, else from the main drive. */
    private suspend fun copyTo(row: LedgerEntry, prior: SecondCopy?, final: String): Outcome {
        val part = RemotePathPlanner.partName(final)
        val phone = try {
            opener.open(media(row))
        } catch (_: IOException) {
            null
        }
        if (phone != null) {
            when (val a = attempt(row, phone, final, part)) {
                is Attempt.Done -> return a.outcome
                is Attempt.BadSource -> {} // edited or unreadable on the phone: use the main copy
            }
        }
        return fromMain(row, prior, final, part)
    }

    private suspend fun fromMain(row: LedgerEntry, prior: SecondCopy?, final: String, part: String): Outcome {
        // A main move or upload in flight: remotePath may be stale. Wait for the main engine to settle it.
        if (row.movingTo != null || row.partPath != null) return waiting(prior)
        val main = mainOrNull() ?: return waiting(prior)
        val path = row.remotePath ?: return fail(row, DAMAGED_ERROR)
        val st = try {
            main.stat(path)
        } catch (e: IOException) {
            if (e is RemoteException) return mainDown(prior)
            return fail(row, itemError(e))
        }
        if (st == null || st.isDirectory || st.sizeBytes != row.sizeBytes) return fail(row, DAMAGED_ERROR)
        val input = try {
            main.openRead(path, 0)
        } catch (_: FileNotFoundException) {
            return fail(row, DAMAGED_ERROR)
        } catch (e: IOException) {
            if (e is RemoteException) return mainDown(prior)
            return fail(row, itemError(e))
        }
        return when (val a = attempt(row, input, final, part)) {
            is Attempt.Done -> a.outcome
            is Attempt.BadSource -> when (val e = a.error) {
                null -> fail(row, DAMAGED_ERROR)
                is RemoteException -> mainDown(prior)
                else -> fail(row, itemError(e))
            }
        }
    }

    /** The main store for this run, asked for at most once; null when it can't be had. */
    private suspend fun mainOrNull(): RemoteStore? {
        if (!mainAsked) {
            mainAsked = true
            main = try {
                mainStore.invoke()
            } catch (_: IOException) {
                null
            }
        }
        return main
    }

    /** The main drive dropped: every later item that needs it waits too. */
    private suspend fun mainDown(prior: SecondCopy?): Outcome {
        main = null
        return waiting(prior)
    }

    /** Leaves the item as it was before this run tried it (no row stays no-row-or-pending, never FAILED). */
    private suspend fun waiting(prior: SecondCopy?): Outcome {
        if (prior != null) copies.upsert(prior)
        return Outcome.WAITING
    }

    /**
     * One copy from one source to `.part` → [final], checked against the recorded size and hash before the rename and
     * verified by read-back after it. [input] is closed here.
     */
    private suspend fun attempt(row: LedgerEntry, input: InputStream, final: String, part: String): Attempt {
        copies.upsert(copyRow(row, SecondStatus.UPLOADING, part = part))
        val r = DriveCopy.copyVerified(
            store, DriveCopy.source(input), part, final, row.sizeBytes, expectedSha = row.localSha256,
            afterRename = { copies.upsert(copyRow(row, SecondStatus.UPLOADING, remote = final)) },
        )
        return when (r) {
            is CopyResult.Verified -> {
                copies.upsert(copyRow(row, SecondStatus.VERIFIED, remote = final))
                DriveCopy.setTimesQuietly(store, final, row.dateTakenMs)
                Attempt.Done(Outcome.COPIED)
            }
            is CopyResult.OpenFailed -> {
                // The .part (if any) is not ours: stop tracking it.
                copies.upsert(copyRow(row, SecondStatus.UPLOADING))
                if (r.error is RemoteException) throw r.error
                Attempt.Done(fail(row, itemError(r.error)))
            }
            is CopyResult.WriteFailed -> {
                val e = r.error
                when {
                    e is SourceReadException -> badSource(row, part, e.reason)
                    e is RemoteException -> throw e // the row keeps its .part for the next run's cleanup
                    else -> Attempt.Done(failRemovePart(row, part, itemError(e)))
                }
            }
            is CopyResult.WrongSize, is CopyResult.WrongHash -> badSource(row, part, null)
            is CopyResult.RenameFailed -> Attempt.Done(failRemovePart(row, part, itemError(r.error)))
            // Left in place unverified; a later run adopts it if it hashes correctly.
            is CopyResult.ReadBackFailed -> Attempt.Done(fail(row, itemError(r.error), remote = final))
            is CopyResult.Mismatch -> Attempt.Done(failRemoveFinal(row, final, BackupEngine.VERIFY_ERROR))
        }
    }

    /** Removes the `.part` of a copy from a bad source; if that fails the item fails, keeping partPath for cleanup. */
    private suspend fun badSource(row: LedgerEntry, part: String, error: IOException?): Attempt {
        if (!DriveCopy.removeIfPresent(store, part)) {
            return Attempt.Done(fail(row, LEFTOVER_ERROR, part = part))
        }
        copies.upsert(copyRow(row, SecondStatus.UPLOADING))
        return Attempt.BadSource(error)
    }

    private suspend fun fail(row: LedgerEntry, msg: String, remote: String? = null, part: String? = null): Outcome {
        copies.upsert(copyRow(row, SecondStatus.FAILED, remote = remote, part = part, error = msg))
        return Outcome.FAILED
    }

    /** Removes this item's own `.part`; if that fails, keeps partPath so the next run cleans it. */
    private suspend fun failRemovePart(row: LedgerEntry, part: String, msg: String): Outcome {
        val removed = DriveCopy.removeIfPresent(store, part)
        return fail(row, msg, part = if (removed) null else part)
    }

    /** Deletes the copy this item just renamed into place (it failed its read-back), then marks FAILED. */
    private suspend fun failRemoveFinal(row: LedgerEntry, final: String, msg: String): Outcome {
        val removed = DriveCopy.delete(store, final)
        return fail(row, msg, remote = if (removed) null else final)
    }

    private fun copyRow(
        row: LedgerEntry,
        status: SecondStatus,
        remote: String? = null,
        part: String? = null,
        error: String? = null,
    ) = SecondCopy(
        mediaId = row.mediaId,
        remotePath = remote,
        partPath = part,
        movingTo = null,
        status = status,
        sizeBytes = row.sizeBytes,
        sha256 = row.localSha256,
        lastError = error,
        updatedAtMs = clock(),
    )
}
