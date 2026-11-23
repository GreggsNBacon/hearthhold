package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.FolderAssignmentStore
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.AccessDeniedException
import dev.photobackup.smb.DriveInfo
import dev.photobackup.smb.MSG_ACCESS_DENIED
import dev.photobackup.smb.MSG_DRIVE_ERROR
import dev.photobackup.smb.RemoteEntry
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

fun interface LocalOpener {
    /** Returns null if the item is gone from the phone. */
    fun open(m: PhoneMedia): InputStream?
}

data class BackupSettings(val destination: String, val deviceName: String, val zone: ZoneId)

/**
 * Copies phone media to the drive and verifies every copy by reading it back.
 *
 * Safety rules:
 * - Never overwrites: uploads go to a fresh `.part` and are renamed onto a name that does not exist.
 * - Only marks VERIFIED after the remote SHA-256 matches the local SHA-256.
 * - Only ever deletes the item's own `.part` (tracked in the ledger), a just-renamed copy that
 *   failed verification, or (auto-empty, through [BinFiles]) a BINNED file inside the current `<dest>\<device>\.Bin\`
 *   binned more than 30 days ago.
 *
 * Run order: connect, leftover cleanup, move recovery, bin moves (review's Junk), auto-empty, folder reconcile, uploads,
 * then a late bin + reconcile pass for requests made during the run.
 *
 * Any [RemoteException] (auth, unreachable, share missing, SMB1-only, drive full) stops the run and
 * leaves the in-flight row as-is for the next run's leftover cleanup. Other IOExceptions fail the
 * item and the run continues.
 *
 * Gentle on the router: a worker waits [itemPauseMs] (cancellable) before its next item whenever the previous one
 * went to the drive (upload + verify, adopt, or a failed attempt). A full-speed write + read-back otherwise starves
 * the router's Samba for everyone else on the network.
 *
 * Parallel: up to [parallelUploads] items run at once on the one shared [store] (smbj multiplexes concurrent
 * requests over one session: message ids and credits are handed out under a lock and responses are matched by id).
 * With 1 it behaves exactly like a plain sequential loop. Concurrency rules:
 * - Leftover cleanup runs once, before any worker starts.
 * - A target name (and its `.part`) is reserved under the name lock ([DriveMover]) while the free-name check runs, and released only
 *   after the rename (the name then exists on the drive) or after the item gave up on it. So two items with the
 *   same name in the same folder can never pick the same target.
 * - Each item reserves its size against the free space before it starts; the reservation is returned unless it
 *   uploaded.
 * - A Stop-type [RemoteException] in any worker cancels the others at their next chunk or pause; their rows stay
 *   UPLOADING with partPath for the next run's cleanup, exactly as when a single run is cancelled.
 *
 * [fat32MaxBytes] is injectable only so tests can exercise FAT32 detection without 4 GB of data; [pause] only so
 * tests can observe the pauses.
 */
class BackupEngine(
    private val store: RemoteStore,
    private val ledger: LedgerStore,
    private val opener: LocalOpener,
    private val settings: BackupSettings,
    private val clock: () -> Long,
    private val fat32MaxBytes: Long = RemotePathPlanner.FAT32_MAX_BYTES,
    /** A write failure counts as the FAT32 limit only within this many bytes past it (stream buffering). */
    private val fat32SlackBytes: Long = 8L * 1024 * 1024,
    parallelUploads: Int = 1,
    private val itemPauseMs: Long = DEFAULT_ITEM_PAUSE_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** The desired folder for an item (null = the default source folder). Reused for drive-only rows with no PhoneMedia. */
    private val folderFor: suspend (mediaId: Long, relativePath: String) -> String? = { _, _ -> null },
    /** Hand-picked folders; only used to forget an item's assignment together with its ledger row. */
    private val assignments: FolderAssignmentStore? = null,
) {
    companion object {
        const val FAT32_ERROR = "too big for this drive's format (FAT32, 4 GB limit)"
        const val VERIFY_ERROR = "verification failed"
        const val SIZE_ERROR = "size mismatch"
        const val NO_ROOM_ERROR = "Not enough free space on the drive for this file"
        const val COPY_ERROR = "Copy to the drive failed — will try again"
        const val PHONE_READ_ERROR = "Couldn't read the file on the phone — will try again"
        const val NOT_FOUND_ERROR = "Folder or file not found on the drive — will try again"
        const val NO_NAME_ERROR = "No free file name in the drive folder"
        const val LEFTOVER_ERROR = "Couldn't remove an unfinished copy on the drive — will try again"
        const val MOVE_ERROR = "Couldn't move to the chosen folder — will try again"
        const val MOVE_CONFLICT_ERROR = "An interrupted move left two files with this name — kept the original"
        const val BIN_MOVE_ERROR = "Couldn't move to the bin on the drive — will try again"
        const val CANT_BIN_ERROR =
            "Can't move to the bin: the drive folder or device name changed — Cancel to keep it on the drive"

        /**
         * The text stored as an item's lastError (shown in the Gallery's failed-item dialog). Never the exception
         * text: smbj messages carry the server path and NT status codes (the userMessage policy).
         */
        fun itemError(e: Throwable): String = when {
            e is AccessDeniedException -> MSG_ACCESS_DENIED
            e.message == MSG_DRIVE_ERROR -> MSG_DRIVE_ERROR
            e is FileNotFoundException -> NOT_FOUND_ERROR
            else -> COPY_ERROR
        }
        const val DEFAULT_ITEM_PAUSE_MS = 2000L
        const val MAX_PARALLEL = 4
    }

    private val workers = parallelUploads.coerceIn(1, MAX_PARALLEL)

    /** ADOPTED counts as skipped but did go to the drive (the existing copy was read back in full). */
    private enum class Outcome { UPLOADED, ADOPTED, SKIPPED, FAILED }

    /** Thrown by a worker to stop the whole run; the enclosing coroutineScope cancels its siblings. */
    private class StopRun(val reason: RemoteException) : Exception(null, reason, false, false)

    /** Per-run state shared by the workers. Everything here is guarded by [lock]. */
    private class RunState(freeAtConnect: Long, val total: Int) {
        val lock = Any()
        val freeAtConnect = freeAtConnect
        var free = freeAtConnect
        var done = 0
        var bytesDone = 0L
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var noRoom = 0
        var bytesUploaded = 0L
    }

    /** Moves and the target-name reservations shared by moves and uploads (see [DriveMover]). */
    private val mover = DriveMover(store)

    private val root = DrivePaths.deviceRoot(settings.destination, settings.deviceName)

    suspend fun run(
        items: List<PhoneMedia>,
        onMoving: (count: Int) -> Unit = {},
        onProgress: (done: Int, total: Int, bytes: Long) -> Unit = { _, _, _ -> },
    ): BackupResult = withContext(Dispatchers.IO) {
        // 1. Connect once.
        val drive = try {
            store.connect()
        } catch (e: RemoteException) {
            return@withContext BackupResult.Stopped(e)
        }

        // 2. Clean leftovers before deciding what needs uploading, and before any worker starts.
        try {
            cleanLeftovers()
        } catch (e: RemoteException) {
            return@withContext BackupResult.Stopped(e)
        }

        // 2b. Move drive files into their desired folder (every run, even with nothing to upload), after finishing
        // any move a crash interrupted.
        // Then review's Junk goes into the bin, and bin files older than 30 days are deleted.
        val unsettled: Set<Long>
        val binFailed: Set<Long>
        val moves = try {
            // Rows whose interrupted move is settled now (e.g. a restore that never renamed) are not auto-emptied
            // in this run: the owner's restore intent wins.
            val recovered = ledger.withMovingTo().mapTo(HashSet()) { it.mediaId }
            unsettled = recoverMoves()
            binFailed = binMoves(unsettled)
            autoEmpty(skip = recovered)
            reconcile(unsettled, onMoving)
        } catch (e: RemoteException) {
            return@withContext BackupResult.Stopped(e)
        }

        val work = items.filter { LedgerRules.needsUpload(ledger.get(it.mediaId), it) }
        val state = RunState(drive.freeBytes, work.size)
        val next = AtomicInteger(0)

        // 3. Up to [workers] items at once, each worker taking the next item in order.
        try {
            coroutineScope {
                repeat(minOf(workers, work.size)) {
                    launch {
                        var pauseFirst = false
                        while (true) {
                            ensureActive()
                            val i = next.getAndIncrement()
                            if (i >= work.size) break
                            if (pauseFirst && itemPauseMs > 0) pause(itemPauseMs)
                            pauseFirst = handle(work[i], drive, state, onProgress)
                        }
                    }
                }
            }
        } catch (s: StopRun) {
            return@withContext BackupResult.Stopped(s.reason)
        }

        // 4. Moves asked for while this run was going (a hand-pick or rule whose "Back up now" was kept out by this
        // run, or found the lock held) happen now instead of waiting for the next run. Rows that failed or could not
        // be settled earlier in this run are not tried again.
        val lateMoves = try {
            binMoves(unsettled + binFailed)
            reconcile(unsettled + moves.failedIds, onMoving)
        } catch (e: RemoteException) {
            return@withContext BackupResult.Stopped(e)
        }
        synchronized(state.lock) {
            // Every reservation has been settled, so free = free at connect minus what this run uploaded.
            BackupResult.Done(
                state.uploaded, state.skipped, state.failed, state.bytesUploaded,
                driveFreeBytes = state.free, noRoom = state.noRoom, moved = moves.moved + lateMoves.moved,
                moveFailed = unsettled.size + moves.failedIds.size + lateMoves.failedIds.size,
            )
        }
    }

    /** Runs one item and counts its outcome. Returns whether it went to the drive (so a pause should follow). */
    private suspend fun handle(
        m: PhoneMedia,
        drive: DriveInfo,
        state: RunState,
        onProgress: (Int, Int, Long) -> Unit,
    ): Boolean {
        val existing = ledger.get(m.mediaId)
        var reservedBytes = 0L
        var wentToDrive = false
        val outcome = when {
            // A leftover .part could not be removed; leave it for the next run's cleanup.
            existing?.partPath != null -> Outcome.FAILED

            // Already known to be too big for this drive and unchanged: don't retry.
            existing != null && existing.status == BackupStatus.FAILED &&
                existing.lastError == FAT32_ERROR &&
                existing.sizeBytes == m.sizeBytes && existing.dateModifiedSec == m.dateModifiedSec ->
                Outcome.SKIPPED

            drive.fileSystem == "FAT32" && m.sizeBytes > fat32MaxBytes -> {
                ledger.upsert(entry(m, BackupStatus.FAILED, error = FAT32_ERROR))
                Outcome.FAILED
            }

            // Bigger than all the free space at connect: only this file can't go; smaller ones still might.
            m.sizeBytes > state.freeAtConnect -> {
                ledger.upsert(entry(m, BackupStatus.FAILED, error = NO_ROOM_ERROR))
                synchronized(state.lock) { state.noRoom++ }
                Outcome.FAILED
            }

            else -> {
                // Reserve this item's size before it starts, so parallel items can't jointly overfill the drive.
                // Not fitting now means this run has used the space up: stop with "drive full".
                val fits = synchronized(state.lock) {
                    (m.sizeBytes <= state.free).also { if (it) state.free -= m.sizeBytes }
                }
                if (!fits) throw StopRun(RemoteException.DriveFull())
                reservedBytes = m.sizeBytes
                wentToDrive = true
                try {
                    processItem(m, state, onProgress)
                } catch (e: RemoteException) {
                    throw StopRun(e)
                }
            }
        }
        synchronized(state.lock) {
            when (outcome) {
                Outcome.UPLOADED -> { state.uploaded++; state.bytesUploaded += m.sizeBytes }
                Outcome.ADOPTED, Outcome.SKIPPED -> state.skipped++
                Outcome.FAILED -> state.failed++
            }
            if (outcome != Outcome.UPLOADED) state.free += reservedBytes
            state.done++
            onProgress(state.done, state.total, state.bytesDone)
        }
        return wentToDrive
    }

    /** Deletes each row's own tracked `.part`, then resets interrupted rows to PENDING. */
    private suspend fun cleanLeftovers() {
        for (row in ledger.withPartPath()) {
            val part = row.partPath ?: continue
            if (DriveCopy.removeIfPresent(store, part)) {
                ledger.upsert(row.copy(partPath = null, status = BackupStatus.PENDING, updatedAtMs = clock()))
            } else {
                // Keep partPath so the next run tries again.
                ledger.upsert(
                    row.copy(
                        status = BackupStatus.FAILED,
                        lastError = LEFTOVER_ERROR,
                        updatedAtMs = clock(),
                    )
                )
            }
        }
        for (row in ledger.byStatus(BackupStatus.UPLOADING)) {
            ledger.upsert(row.copy(status = BackupStatus.PENDING, updatedAtMs = clock()))
        }
    }

    /**
     * Settles moves a crash interrupted (rows with movingTo set) by which of the two paths holds the file:
     * - only the new path (with the recorded size): point the row at it;
     * - only the old path: the rename never happened, forget the move;
     * - both: keep the old path, flag a conflict;
     * - neither (or the new path holds something else): leave the row as it is, flag an error.
     * A BINNED row found only at the new path was being restored: [LedgerStore.finishRestore] completes the restore.
     * A bin move found only at the new path just points the row at it; [binMoves] then marks it BINNED.
     * Returns the rows not settled cleanly; this run's moves leave them alone. A path still recorded in movingTo stays
     * reserved for the whole run, so no move or upload takes that name meanwhile.
     */
    private suspend fun recoverMoves(): Set<Long> {
        val unsettled = mutableSetOf<Long>()
        for (row in ledger.withMovingTo()) {
            val to = row.movingTo ?: continue
            val from = row.remotePath
            val newEntry: RemoteEntry?
            val oldExists: Boolean
            try {
                newEntry = store.stat(to)?.takeIf { !it.isDirectory }
                oldExists = from != null && store.stat(from)?.isDirectory == false
            } catch (e: IOException) {
                if (e is RemoteException) throw e
                ledger.setLastError(row.mediaId, MOVE_ERROR, clock())
                unsettled += row.mediaId
                mover.reserveExact(to)
                continue
            }
            // An interrupted restore (BinRepository) whose rename landed: finish it, so the row leaves the bin.
            if (row.status == BackupStatus.BINNED && !oldExists && newEntry != null &&
                newEntry.sizeBytes == row.sizeBytes
            ) {
                if (!ledger.finishRestore(row.mediaId, to, clock())) unsettled += row.mediaId
                continue
            }
            val priorMoveError = row.lastError == MOVE_ERROR || row.lastError == MOVE_CONFLICT_ERROR ||
                row.lastError == BIN_MOVE_ERROR
            val keptError = if (priorMoveError) null else row.lastError
            val fixed = when {
                // movingTo stays: the conflict is re-checked every run until the owner removes one copy, so no
                // later move makes a third copy and the name the other copy holds stays reserved.
                oldExists && newEntry != null -> row.copy(lastError = MOVE_CONFLICT_ERROR)
                oldExists -> row.copy(movingTo = null, lastError = keptError)
                newEntry != null && newEntry.sizeBytes == row.sizeBytes ->
                    row.copy(remotePath = to, movingTo = null, lastError = keptError)
                else -> row.copy(lastError = MOVE_ERROR)
            }
            if (fixed.lastError == MOVE_CONFLICT_ERROR || fixed.lastError == MOVE_ERROR) unsettled += row.mediaId
            fixed.movingTo?.let { mover.reserveExact(it) }
            ledger.endMove(row.mediaId, fixed.remotePath, fixed.movingTo, fixed.lastError, clock())
        }
        return unsettled
    }

    /**
     * Moves each VERIFIED / REMOVED_FROM_PHONE drive file whose folder segment (right after `<destination>\<device>`)
     * differs, ignoring case, from its desired folder. Year and file name stay as recorded. Rows not under the current
     * `<destination>\<device>\` are never moved; nor are rows in [skip]. Returns how many moved and which failed to. A
     * [RemoteException] stops the run.
     */
    private suspend fun reconcile(skip: Set<Long>, onMoving: (Int) -> Unit): Moves {
        val rows = (ledger.byStatus(BackupStatus.VERIFIED) + ledger.byStatus(BackupStatus.REMOVED_FROM_PHONE))
            .sortedBy { it.mediaId }
        val todo = mutableListOf<Triple<LedgerEntry, DriveFile, String>>()
        for (row in rows) {
            coroutineContext.ensureActive()
            if (row.mediaId in skip) continue
            val file = DrivePaths.parse(row, root) ?: continue
            val desired = folderFor(row.mediaId, row.relativePath)?.let { RemotePathPlanner.sanitise(it) }
                ?: RemotePathPlanner.sourceFolder(row.relativePath)
            if (desired.equals(file.folder, ignoreCase = true)) continue
            todo += Triple(row, file, desired)
        }
        if (todo.isNotEmpty()) onMoving(todo.size)
        var moved = 0
        val failed = mutableSetOf<Long>()
        var pauseFirst = false
        for ((row, file, desired) in todo) {
            coroutineContext.ensureActive()
            if (pauseFirst && itemPauseMs > 0) pause(itemPauseMs)
            pauseFirst = true
            val from = row.remotePath!!
            val outcome = mover.move(
                from, "$root\\$desired\\${file.year}", file.name,
                LedgerMoveRecorder(ledger, row.mediaId, from, MOVE_ERROR, clock),
                begin = { target -> ledger.beginMove(row.mediaId, from, target, clock()) },
                finish = { target ->
                    ledger.endMove(row.mediaId, remotePath = target, movingTo = null, lastError = null, nowMs = clock())
                    true
                },
            )
            when (outcome) {
                MoveOutcome.MOVED -> moved++
                MoveOutcome.FAILED -> failed += row.mediaId
                MoveOutcome.SKIPPED -> {}
            }
        }
        return Moves(moved, failed)
    }

    /** A Junk row the engine will never move: say so once, so it is not "Moving to bin…" forever. */
    private suspend fun cantBin(row: LedgerEntry) {
        if (row.lastError != CANT_BIN_ERROR) ledger.setLastError(row.mediaId, CANT_BIN_ERROR, clock())
    }

    /** How many moved, and which rows failed to move in this pass. */
    private class Moves(val moved: Int, val failedIds: Set<Long>)

    /**
     * Moves review's Junk (REMOVED_FROM_PHONE rows with binRequestedAt) into `<root>\.Bin\<folder>\<year>\<name>` with
     * the movingTo sequence: [LedgerStore.beginBinMove] records movingTo and binPath (the original path) in one write,
     * then the rename, endMove, and a conditional mark BINNED (binnedAt = now). A row already sitting in the current bin
     * (a crash between the rename and the mark) is just marked. Rows not in today's shape under the current root (old
     * layout, destination or device name changed, or a legacy `.Bin` named folder) are never moved: they get
     * [CANT_BIN_ERROR] so the Bin screen says why (Cancel keeps them drive-only). Rows in [skip] and rows with a move
     * or `.part` pending are left alone. Returns the rows that failed. A [RemoteException] stops the run.
     */
    private suspend fun binMoves(skip: Set<Long>): Set<Long> {
        val failed = mutableSetOf<Long>()
        var pauseFirst = false
        for (row in ledger.withBinRequest().sortedBy { it.mediaId }) {
            coroutineContext.ensureActive()
            if (row.mediaId in skip || row.movingTo != null || row.partPath != null) continue
            val from = row.remotePath ?: continue
            if (DrivePaths.isInBin(from, root)) {
                if (row.binPath != null) ledger.markBinned(row.mediaId, from, clock()) else cantBin(row)
                continue
            }
            val target = DrivePaths.binTarget(from, root)
            if (target == null) {
                cantBin(row)
                continue
            }
            val (dir, name) = target
            if (pauseFirst && itemPauseMs > 0) pause(itemPauseMs)
            pauseFirst = true
            val there = try {
                store.stat(from)?.isDirectory == false
            } catch (e: IOException) {
                if (e is RemoteException) throw e
                false
            }
            if (!there) {
                ledger.setLastError(row.mediaId, BIN_MOVE_ERROR, clock())
                failed += row.mediaId
                continue
            }
            val outcome = mover.move(
                from, dir, name, LedgerMoveRecorder(ledger, row.mediaId, from, BIN_MOVE_ERROR, clock),
                begin = { target -> ledger.beginBinMove(row.mediaId, from, target, clock()) },
                finish = { target ->
                    ledger.endMove(row.mediaId, remotePath = target, movingTo = null, lastError = null, nowMs = clock())
                    // If this mark is lost (crash), the next run sees the row in the bin and marks it then.
                    ledger.markBinned(row.mediaId, target, clock())
                    true
                },
            )
            if (outcome == MoveOutcome.FAILED) failed += row.mediaId
        }
        return failed
    }

    /**
     * Deletes bin files BINNED more than 30 days ago (strictly), through [BinFiles.empty]: only paths inside the
     * current `<root>\.Bin\`, never anything else, and only cleanly BINNED rows ([BinFiles.isAutoEmptyCandidate])
     * not in [skip]. A failed delete keeps the row for the next run.
     */
    private suspend fun autoEmpty(skip: Set<Long>) {
        val now = clock()
        for (row in ledger.byStatus(BackupStatus.BINNED).sortedBy { it.mediaId }) {
            coroutineContext.ensureActive()
            if (row.mediaId in skip || !BinFiles.isAutoEmptyCandidate(row, now)) continue
            BinFiles.empty(row, root, store, ledger, now) { assignments?.clear(it) }
        }
    }

    private suspend fun processItem(
        m: PhoneMedia,
        state: RunState,
        onProgress: (Int, Int, Long) -> Unit,
    ): Outcome {
        val folder = folderFor(m.mediaId, m.relativePath)
        val dir = RemotePathPlanner.targetDir(settings.destination, settings.deviceName, m, settings.zone, folder)
        val name = RemotePathPlanner.sanitise(m.displayName)

        // 4. Pick and reserve a free name, or adopt an identical existing file.
        val final = when (val pick = pickTarget(m, dir, name)) {
            is Pick.Done -> return pick.outcome
            is Pick.Target -> pick.path
        }
        try {
            return uploadTo(m, final, state, onProgress)
        } finally {
            // The name now exists on the drive (renamed), or this item gave up on it; a .part left behind still
            // blocks the name through the drive itself. Either way, stop holding the reservation.
            release(final)
        }
    }

    private sealed interface Pick {
        class Done(val outcome: Outcome) : Pick
        class Target(val path: String) : Pick
    }

    /**
     * Finds the first name that neither exists on the drive (nor its `.part`) nor is reserved by another in-flight
     * item, and reserves it (name and `.part`) before creating the folder ([DriveMover.pickName]). An identical
     * existing file on the way is adopted; adoption hashing runs outside the name lock.
     */
    private suspend fun pickTarget(m: PhoneMedia, dir: String, name: String): Pick {
        var localSha: String? = null
        val pick = try {
            mover.pickName(dir, name, m.sizeBytes) { candidate ->
                val sha = localSha ?: hashLocal(m)?.also { localSha = it }
                when {
                    sha == null -> NameCheck.ABORT
                    DriveCopy.hash(store, candidate) == sha -> NameCheck.ADOPT
                    else -> NameCheck.NEXT
                }
            }
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            return Pick.Done(fail(m, itemError(e)))
        }
        return when (pick) {
            is NamePick.Free -> Pick.Target(pick.path)
            is NamePick.Existing -> {
                ledger.upsert(entry(m, BackupStatus.VERIFIED, sha = localSha, remote = pick.path))
                Pick.Done(Outcome.ADOPTED)
            }
            NamePick.Aborted -> Pick.Done(missingOnPhone(m))
            NamePick.NoName -> Pick.Done(fail(m, NO_NAME_ERROR))
        }
    }

    private fun release(candidate: String) = mover.release(candidate)

    /** Steps 5-7 for a reserved [final] name: upload to `.part`, rename, verify ([DriveCopy.copyVerified]). */
    private suspend fun uploadTo(
        m: PhoneMedia,
        final: String,
        state: RunState,
        onProgress: (Int, Int, Long) -> Unit,
    ): Outcome {
        val part = RemotePathPlanner.partName(final)

        // 5. Upload to .part while hashing.
        ledger.upsert(entry(m, BackupStatus.UPLOADING, part = part))
        val input = try {
            opener.open(m)
        } catch (_: IOException) {
            return fail(m, PHONE_READ_ERROR)
        } ?: return missingOnPhone(m) // nothing written yet, so no .part to remove

        // 6. Rename onto the final name (fails rather than overwrite), then clear partPath. 7. Verify by reading back.
        // The phone must have delivered exactly the size it reported: checked before the rename, so a short copy
        // never appears under the final name.
        val result = DriveCopy.copyVerified(
            store, input, part, final, m.sizeBytes,
            onChunk = { n ->
                synchronized(state.lock) {
                    state.bytesDone += n
                    onProgress(state.done, state.total, state.bytesDone)
                }
            },
            afterRename = { sha -> ledger.upsert(entry(m, BackupStatus.UPLOADING, sha = sha, remote = final)) },
        )
        return when (result) {
            is CopyResult.Verified -> {
                ledger.upsert(entry(m, BackupStatus.VERIFIED, sha = result.sha, remote = final))
                setDriveTime(m, final)
                Outcome.UPLOADED
            }
            is CopyResult.OpenFailed -> {
                // openWrite itself failed: the .part (if any) is not ours, so stop tracking it.
                val e = result.error
                if (e is RemoteException) {
                    ledger.upsert(entry(m, BackupStatus.PENDING))
                    throw e
                }
                fail(m, itemError(e))
            }
            is CopyResult.WriteFailed -> {
                val e = result.error
                val offered = result.bytes
                // smbj can't report the file system, and FAT32 overflow surfaces as a plain IOException
                // or as DISK_FULL (Samba EFBIG / Windows FAT), so a failure right at the 4 GB mark means FAT32.
                val nearLimit = m.sizeBytes > fat32MaxBytes &&
                    offered >= fat32MaxBytes && offered < fat32MaxBytes + fat32SlackBytes
                val fat32 = nearLimit && when (e) {
                    is RemoteException.DriveFull -> state.freeAtConnect >= m.sizeBytes
                    is RemoteException -> false
                    else -> true
                }
                if (fat32) return failAndRemovePart(m, part, FAT32_ERROR)
                if (e is RemoteException) throw e
                failAndRemovePart(m, part, itemError(e))
            }
            is CopyResult.WrongSize -> failAndRemovePart(m, part, SIZE_ERROR)
            // No expected hash is passed, so this never happens; kept for exhaustiveness.
            is CopyResult.WrongHash -> failAndRemovePart(m, part, VERIFY_ERROR)
            is CopyResult.RenameFailed -> failAndRemovePart(m, part, itemError(result.error))
            // Copy left in place unverified; a later run adopts it if it hashes correctly.
            is CopyResult.ReadBackFailed -> fail(m, itemError(result.error), remote = final)
            is CopyResult.Mismatch -> failAndRemoveFinal(m, final, VERIFY_ERROR)
        }
    }

    /**
     * Gives the copy we just wrote and verified the photo's date (date taken, else date added), so the drive sorts
     * by when it was taken. Metadata only, never content; adopted files are never touched. Cosmetic: any failure
     * is ignored (a real drive problem shows up on the next item).
     */
    private suspend fun setDriveTime(m: PhoneMedia, path: String) {
        val ms = if (m.dateTakenMs > 0) m.dateTakenMs else m.dateAddedSec * 1000
        DriveCopy.setTimesQuietly(store, path, ms)
    }

    /** Deletes the copy this item just renamed into place (it failed a check), then marks FAILED. */
    private suspend fun failAndRemoveFinal(m: PhoneMedia, final: String, msg: String): Outcome {
        val removed = DriveCopy.delete(store, final)
        ledger.upsert(entry(m, BackupStatus.FAILED, error = msg, remote = if (removed) null else final))
        return Outcome.FAILED
    }

    private fun hashLocal(m: PhoneMedia): String? = opener.open(m)?.use { Hashing.sha256(it) }

    private suspend fun missingOnPhone(m: PhoneMedia): Outcome {
        ledger.delete(m.mediaId)
        assignments?.clear(m.mediaId)
        return Outcome.SKIPPED
    }

    private suspend fun fail(m: PhoneMedia, msg: String, remote: String? = null): Outcome {
        ledger.upsert(entry(m, BackupStatus.FAILED, error = msg, remote = remote))
        return Outcome.FAILED
    }

    /** Removes this item's own `.part`; if that fails, keeps partPath so the next run cleans it. */
    private suspend fun failAndRemovePart(m: PhoneMedia, part: String, msg: String): Outcome {
        val removed = DriveCopy.removeIfPresent(store, part)
        ledger.upsert(
            entry(m, BackupStatus.FAILED, error = msg, part = if (removed) null else part)
        )
        return Outcome.FAILED
    }

    private fun entry(
        m: PhoneMedia,
        status: BackupStatus,
        sha: String? = null,
        remote: String? = null,
        part: String? = null,
        error: String? = null,
    ) = LedgerEntry(
        mediaId = m.mediaId,
        localUri = m.uri,
        displayName = m.displayName,
        relativePath = m.relativePath,
        sizeBytes = m.sizeBytes,
        dateTakenMs = m.dateTakenMs,
        dateModifiedSec = m.dateModifiedSec,
        isVideo = m.isVideo,
        localSha256 = sha,
        remotePath = remote,
        partPath = part,
        status = status,
        lastError = error,
        updatedAtMs = clock(),
    )
}
