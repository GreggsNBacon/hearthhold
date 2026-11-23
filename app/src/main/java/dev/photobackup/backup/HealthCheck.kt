package dev.photobackup.backup

import android.util.Log
import dev.photobackup.ledger.LedgerDao
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.SecondCopyDao
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * One health check pass over one drive. [checked]: files judged healthy, repaired or damaged (rows skipped for a
 * per-file error, or left waiting for the second drive, are not counted). [repaired]: damaged copies re-made and their
 * rows repointed. [damaged]: damaged copies with no good source, marked [HealthCheck.NO_GOOD_COPY_ERROR].
 * [completed] false = cut short by a lost connection (progress is kept per row in lastCheckedAt).
 */
data class HealthPass(val checked: Int, val repaired: Int, val damaged: Int, val completed: Boolean)

/** The health check runs at most once per [INTERVAL_MS], recorded as lastHealthCheckMs in the status store. */
object HealthGate {
    const val INTERVAL_MS = 20L * 60 * 60 * 1000

    /** Due when never run, [INTERVAL_MS] or more ago, or "in the future" (the clock went back: never stay stuck). */
    fun isDue(lastMs: Long?, nowMs: Long): Boolean = lastMs == null || nowMs - lastMs >= INTERVAL_MS || nowMs < lastMs
}

/**
 * Re-reads drive copies in full and compares them to the recorded hash, oldest-checked first, [batchSize] per pass.
 *
 * Main drive ([checkMain]): a copy is damaged only when a stat proves it missing / of the wrong size, or a completed
 * full read hashes to something else; nothing is marked damaged because a read failed. A lost connection
 * (RemoteException) stops the pass. Any other drive error on one file (e.g. access denied) skips that row: it gets
 * lastCheckedAt (to the back of the queue, so it can't block the others) and no error. A damaged copy is re-made
 * from, in order:
 * 1. the phone file, when it is there and hashes to the recorded hash (checked before the rename);
 * 2. a VERIFIED second copy of that hash (only when the second location is on: [checkMain]'s secondStore non-null);
 * 3. none: the row keeps its path and gets [NO_GOOD_COPY_ERROR]; it stays in the rotation.
 * The new copy goes next to the bad one under a free name (clash scheme `name (n).ext`, never overwriting), is
 * verified by read-back, and the row is repointed with a conditional write (only while it is still the same settled
 * copy). The bad file is never deleted. The only deletes are a failed repair's own leftovers on the main drive: its
 * `.part`, or the new copy it just made when that failed its read-back or could not be recorded on the row.
 *
 * A second-location pass (re-check of SecondCopy rows) is meant to slot in next to [checkMain] with its own batch.
 * Nothing is logged; stored errors are fixed texts.
 */
class HealthCheck(
    private val ledger: LedgerDao,
    private val copies: SecondCopyDao,
    private val opener: LocalOpener,
    private val clock: () -> Long,
    private val batchSize: Int = BATCH_SIZE,
) {
    companion object {
        const val BATCH_SIZE = 200
        const val NO_GOOD_COPY_ERROR = "Damaged on drive — no good copy left"
    }

    /**
     * SKIPPED: a per-file problem; the row gets lastCheckedAt only (to the back of the queue). WAITING: untouched (the
     * second drive was unavailable, or the row changed meanwhile), so it comes first again next pass.
     */
    private enum class Outcome { HEALTHY, REPAIRED, DAMAGED, SKIPPED, WAITING }

    /** How one repair attempt from one source ended. */
    private sealed interface Attempt {
        class Done(val outcome: Outcome) : Attempt

        /** The source's size or hash is not the recorded one: proven no good. */
        data object BadSource : Attempt

        /** The source could not be read: unknown whether it is good. [network]: the second drive dropped. */
        class Unreadable(val network: Boolean) : Attempt
    }

    /** The second store for one pass: asked for at most once, dropped once it failed. */
    private class SecondSource(private val provider: (suspend () -> RemoteStore?)?) {
        private var asked = false
        private var store: RemoteStore? = null
        val enabled get() = provider != null

        suspend fun get(): RemoteStore? {
            val p = provider ?: return null
            if (!asked) {
                asked = true
                store = try {
                    p()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            }
            return store
        }

        fun drop() {
            store = null
        }
    }

    /** Ledger rows with [NO_GOOD_COPY_ERROR]: the status line's damaged count. */
    suspend fun damagedTotal(): Int = ledger.countWithError(NO_GOOD_COPY_ERROR)

    /**
     * Checks up to [batchSize] main-drive copies on [store] (connected). [secondStore] is null when the second
     * location is off; otherwise it returns a connected second store or null, and is called at most once, only when a
     * repair needs it.
     */
    suspend fun checkMain(store: RemoteStore, secondStore: (suspend () -> RemoteStore?)?): HealthPass =
        withContext(Dispatchers.IO) {
            val mover = DriveMover(store)
            val second = SecondSource(secondStore)
            var checked = 0
            var repaired = 0
            var damaged = 0
            var completed = true
            try {
                for (row in ledger.healthBatch(batchSize)) {
                    coroutineContext.ensureActive()
                    when (checkOne(store, mover, second, row)) {
                        Outcome.HEALTHY -> checked++
                        Outcome.REPAIRED -> { checked++; repaired++ }
                        Outcome.DAMAGED -> { checked++; damaged++ }
                        Outcome.SKIPPED, Outcome.WAITING -> {}
                    }
                }
            } catch (e: RemoteException) {
                // The main drive dropped: stop. Rows not reached keep their old lastCheckedAt.
                completed = false
            }
            HealthPass(checked, repaired, damaged, completed)
        }

    /** A RemoteException from [store] propagates (the pass stops). */
    private suspend fun checkOne(store: RemoteStore, mover: DriveMover, second: SecondSource, row: LedgerEntry): Outcome {
        val path = row.remotePath!!
        val sha = row.localSha256!!
        val ok = try {
            intact(store, row, path, sha)
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            return skip(row, path, sha)
        }
        if (ok) {
            ledger.markCheckedCount(row.mediaId, path, sha, clearError = NO_GOOD_COPY_ERROR, setError = null, nowMs = clock())
            return Outcome.HEALTHY
        }
        return repair(store, mover, second, row, path, sha)
    }

    /** Rotates the row to the back of the queue (lastCheckedAt only; its error is left as it is). */
    private suspend fun skip(row: LedgerEntry, path: String, sha: String): Outcome {
        ledger.markCheckedCount(row.mediaId, path, sha, clearError = null, setError = null, nowMs = clock())
        return Outcome.SKIPPED
    }

    private suspend fun intact(store: RemoteStore, row: LedgerEntry, path: String, sha: String): Boolean {
        val st = store.stat(path)
        if (st == null || st.isDirectory || st.sizeBytes != row.sizeBytes) return false
        return DriveCopy.hash(store, path) == sha
    }

    /** What a full read of a repair source found, before anything is written. */
    private enum class Probe { GOOD, BAD, UNSURE, DROPPED }

    private suspend fun repair(
        store: RemoteStore, mover: DriveMover, second: SecondSource, row: LedgerEntry, path: String, sha: String,
    ): Outcome {
        var unsureFile = false // a source unreadable for a per-file reason
        var waitSecond = false // the second drive was unavailable or dropped
        fun unreadable(a: Attempt.Unreadable) {
            if (a.network) waitSecond = true else unsureFile = true
        }
        // Each source is hashed in full first, so a bad source is never copied.
        // 1. The phone file.
        when (phoneProbe(row, sha)) {
            Probe.GOOD -> when (val a = copyIn(store, mover, second, row, path, sha, openPhone(row), fromSecond = false)) {
                is Attempt.Done -> return a.outcome
                Attempt.BadSource -> {}
                is Attempt.Unreadable -> unreadable(a)
            }
            Probe.BAD -> {}
            Probe.UNSURE, Probe.DROPPED -> unsureFile = true
        }
        // 2. A verified second copy, only with the second location on.
        if (second.enabled) {
            val c = copies.get(row.mediaId)
            val cPath = c?.remotePath
            if (c != null && cPath != null && c.status == SecondStatus.VERIFIED && c.sha256 == sha &&
                c.movingTo == null && c.partPath == null
            ) {
                val s = second.get()
                when (if (s == null) Probe.DROPPED else secondProbe(second, s, cPath, row.sizeBytes, sha)) {
                    Probe.GOOD -> {
                        var network = false
                        val input = try {
                            s!!.openRead(cPath, 0)
                        } catch (e: IOException) {
                            if (e is RemoteException) {
                                second.drop()
                                network = true
                            }
                            null
                        }
                        when (val a = copyIn(store, mover, second, row, path, sha, input, fromSecond = true, network)) {
                            is Attempt.Done -> return a.outcome
                            Attempt.BadSource -> {}
                            is Attempt.Unreadable -> unreadable(a)
                        }
                    }
                    Probe.BAD -> {}
                    Probe.UNSURE -> unsureFile = true
                    Probe.DROPPED -> waitSecond = true
                }
            }
        }
        // 3. No good copy: only when every source is proven absent or bad. Otherwise the row waits (second drive
        //    unavailable: first again next pass) or rotates to the back (a per-file read problem).
        if (waitSecond) return Outcome.WAITING
        if (unsureFile) return skip(row, path, sha)
        ledger.markCheckedCount(row.mediaId, path, sha, clearError = null, setError = NO_GOOD_COPY_ERROR, nowMs = clock())
        return Outcome.DAMAGED
    }

    /** Absent from the phone or a different hash = BAD; any read error = UNSURE. */
    private fun phoneProbe(row: LedgerEntry, sha: String): Probe = try {
        val input = opener.open(media(row))
        if (input == null) Probe.BAD else if (input.use { Hashing.sha256(it) } == sha) Probe.GOOD else Probe.BAD
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Probe.UNSURE
    }

    private fun openPhone(row: LedgerEntry): InputStream? = try {
        opener.open(media(row))
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    /** Missing, wrong size or a different hash = BAD; a read error = UNSURE; dropped store (not asked again) = DROPPED. */
    private suspend fun secondProbe(second: SecondSource, s: RemoteStore, path: String, size: Long, sha: String): Probe =
        try {
            val st = s.stat(path)
            when {
                st == null || st.isDirectory || st.sizeBytes != size -> Probe.BAD
                DriveCopy.hash(s, path) == sha -> Probe.GOOD
                else -> Probe.BAD
            }
        } catch (_: FileNotFoundException) {
            Probe.BAD
        } catch (e: IOException) {
            if (e is RemoteException) {
                second.drop()
                Probe.DROPPED
            } else {
                Probe.UNSURE
            }
        }

    /**
     * Copies [input] (closed here; null = the source could not be opened again, [network] saying why) to a free name
     * next to [path], verified, and repoints the row. A RemoteException from the main store propagates (the pass
     * stops); any other main-drive failure skips the row. A failed attempt removes only what it created itself: its
     * `.part`, or the new copy when that failed its read-back or the row could not be repointed. Never the original.
     */
    private suspend fun copyIn(
        store: RemoteStore, mover: DriveMover, second: SecondSource, row: LedgerEntry, path: String, sha: String,
        input: InputStream?, fromSecond: Boolean, network: Boolean = false,
    ): Attempt {
        if (input == null) return Attempt.Unreadable(network)
        val dir = path.substringBeforeLast('\\', "")
        val name = path.substringAfterLast('\\')
        val target = try {
            store.mkdirs(dir)
            mover.reserveFreeName(dir, name)
        } catch (e: IOException) {
            input.close()
            if (e is RemoteException) throw e
            return Attempt.Done(skip(row, path, sha))
        }
        if (target == null) {
            input.close()
            return Attempt.Done(skip(row, path, sha))
        }
        val part = RemotePathPlanner.partName(target)
        try {
            val r = try {
                DriveCopy.copyVerified(store, DriveCopy.source(input), part, target, row.sizeBytes, expectedSha = sha)
            } catch (e: CancellationException) {
                // Stopped mid-copy: remove our own `.part` (best effort), then stop.
                withContext(NonCancellable) { runCatching { DriveCopy.removeIfPresent(store, part) } }
                throw e
            }
            return when (r) {
                is CopyResult.Verified -> {
                    val ms = if (row.dateTakenMs > 0) row.dateTakenMs else row.dateModifiedSec * 1000
                    DriveCopy.setTimesQuietly(store, target, ms)
                    if (ledger.repoint(row.mediaId, path, target, sha, clock())) {
                        Attempt.Done(Outcome.REPAIRED)
                    } else {
                        // The row changed meanwhile: nothing points at the new copy, so it goes.
                        DriveCopy.removeIfPresent(store, target)
                        Attempt.Done(Outcome.WAITING)
                    }
                }
                is CopyResult.WrongSize, is CopyResult.WrongHash -> {
                    DriveCopy.removeIfPresent(store, part)
                    Attempt.BadSource
                }
                is CopyResult.WriteFailed -> {
                    val e = r.error
                    if (e is RemoteException) throw e // the main drive dropped: nothing can be removed now
                    DriveCopy.removeIfPresent(store, part)
                    if (e is SourceReadException) {
                        val dropped = fromSecond && e.reason is RemoteException
                        if (dropped) second.drop()
                        Attempt.Unreadable(dropped)
                    } else {
                        Attempt.Done(skip(row, path, sha))
                    }
                }
                is CopyResult.OpenFailed ->
                    if (r.error is RemoteException) throw r.error else Attempt.Done(skip(row, path, sha))
                is CopyResult.RenameFailed -> {
                    DriveCopy.removeIfPresent(store, part)
                    Attempt.Done(skip(row, path, sha))
                }
                is CopyResult.ReadBackFailed, is CopyResult.Mismatch -> {
                    DriveCopy.removeIfPresent(store, target)
                    Attempt.Done(skip(row, path, sha))
                }
            }
        } finally {
            mover.release(target)
        }
    }

    /** The phone item as the opener needs it (date added is not recorded; date modified stands in for it). */
    private fun media(row: LedgerEntry) = PhoneMedia(
        row.mediaId, row.localUri, row.displayName, row.relativePath, row.sizeBytes, row.dateTakenMs,
        row.dateModifiedSec, row.dateModifiedSec, row.isVideo, "",
    )
}

/**
 * Runs the health check at the end of a backup run (after uploads and the second step, under the same BackupLock
 * hold), at most once per [HealthGate.INTERVAL_MS]. Owns any second store it opens and closes it. Records the gate
 * only when the pass ran to its end, and the counts either way. A failure here never fails the backup run; nothing is
 * logged but a class name.
 */
class HealthStep(
    private val status: BackupStatusRepository,
    private val clock: () -> Long,
    /** The second location's login, re-read now; null = off (or unreadable): no second-drive fallback. */
    private val secondConfig: suspend () -> SmbConfig?,
    private val openStore: (SmbConfig) -> RemoteStore,
    private val check: HealthCheck,
) {
    suspend fun runIfDue(mainStore: RemoteStore) {
        var opened: RemoteStore? = null
        try {
            val now = clock()
            if (!HealthGate.isDue(status.status.first().lastHealthCheckMs, now)) return
            val cfg = try {
                secondConfig()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            val provider: (suspend () -> RemoteStore?)? = cfg?.let { c ->
                {
                    val s = openStore(c).also { opened = it }
                    s.connect()
                    s
                }
            }
            val pass = check.checkMain(mainStore, provider)
            val damaged = check.damagedTotal()
            status.update {
                it.copy(
                    lastHealthCheckMs = if (pass.completed) now else it.lastHealthCheckMs,
                    healthChecked = pass.checked,
                    healthDamaged = damaged,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Class name only, never the message: it could carry connection details.
            runCatching { Log.w("Hearthhold", "health check failed: " + e.javaClass.simpleName) }
        } finally {
            runCatching { opened?.close() }
        }
    }
}
