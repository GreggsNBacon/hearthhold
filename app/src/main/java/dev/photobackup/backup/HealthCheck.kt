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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * One health check pass over one drive. [checked]: files read in full and judged. [repaired]: damaged copies re-made
 * and their rows repointed. [damaged]: damaged copies with no good source, marked [HealthCheck.NO_GOOD_COPY_ERROR].
 * [completed] false = cut short by a drive error (progress is kept per row in lastCheckedAt).
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
 * full read hashes to something else. Any drive error while judging (RemoteException or other IOException) stops the
 * pass; nothing is marked damaged because a read failed. A damaged copy is re-made from, in order:
 * 1. the phone file, when it is there and hashes to the recorded hash (checked before the rename);
 * 2. a VERIFIED second copy of that hash (only when the second location is on: [checkMain]'s secondStore non-null);
 * 3. none: the row keeps its path and gets [NO_GOOD_COPY_ERROR]; it stays in the rotation.
 * The new copy goes next to the bad one under a free name (clash scheme `name (n).ext`, never overwriting), is
 * verified by read-back, and the row is repointed with a conditional write (only while it is still the same settled
 * copy). The bad file is never deleted; nothing on either drive is ever deleted here.
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

    private enum class Outcome { HEALTHY, REPAIRED, DAMAGED, LEFT }

    /** How one repair attempt from one source ended. */
    private sealed interface Attempt {
        class Done(val outcome: Outcome) : Attempt

        /** The source's size or hash is not the recorded one: proven no good. */
        data object BadSource : Attempt

        /** The source could not be read: unknown whether it is good. */
        data object Unreadable : Attempt
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
                    val outcome = checkOne(store, mover, second, row)
                    checked++
                    when (outcome) {
                        Outcome.REPAIRED -> repaired++
                        Outcome.DAMAGED -> damaged++
                        Outcome.HEALTHY, Outcome.LEFT -> {}
                    }
                }
            } catch (e: IOException) {
                // The main drive dropped or failed a read: stop. Rows not reached keep their old lastCheckedAt.
                completed = false
            }
            HealthPass(checked, repaired, damaged, completed)
        }

    /** An IOException from [store] propagates (the pass stops). */
    private suspend fun checkOne(store: RemoteStore, mover: DriveMover, second: SecondSource, row: LedgerEntry): Outcome {
        val path = row.remotePath!!
        val sha = row.localSha256!!
        if (intact(store, row, path, sha)) {
            ledger.markCheckedCount(row.mediaId, path, sha, clearError = NO_GOOD_COPY_ERROR, setError = null, nowMs = clock())
            return Outcome.HEALTHY
        }
        return repair(store, mover, second, row, path, sha)
    }

    private suspend fun intact(store: RemoteStore, row: LedgerEntry, path: String, sha: String): Boolean {
        val st = store.stat(path)
        if (st == null || st.isDirectory || st.sizeBytes != row.sizeBytes) return false
        return DriveCopy.hash(store, path) == sha
    }

    /** What a full read of a repair source found, before anything is written. */
    private enum class Probe { GOOD, BAD, UNSURE }

    private suspend fun repair(
        store: RemoteStore, mover: DriveMover, second: SecondSource, row: LedgerEntry, path: String, sha: String,
    ): Outcome {
        var unsure = false
        // Each source is hashed in full first, so a bad source never leaves a `.part` behind (nothing is deleted here).
        // 1. The phone file.
        when (phoneProbe(row, sha)) {
            Probe.GOOD -> when (val a = copyIn(store, mover, second, row, path, sha, openPhone(row), fromSecond = false)) {
                is Attempt.Done -> return a.outcome
                Attempt.BadSource -> {}
                Attempt.Unreadable -> unsure = true
            }
            Probe.BAD -> {}
            Probe.UNSURE -> unsure = true
        }
        // 2. A verified second copy, only with the second location on.
        if (second.enabled) {
            val c = copies.get(row.mediaId)
            val cPath = c?.remotePath
            if (c != null && cPath != null && c.status == SecondStatus.VERIFIED && c.sha256 == sha &&
                c.movingTo == null && c.partPath == null
            ) {
                val s = second.get()
                when (if (s == null) Probe.UNSURE else secondProbe(second, s, cPath, row.sizeBytes, sha)) {
                    Probe.GOOD -> {
                        val input = try {
                            s!!.openRead(cPath, 0)
                        } catch (e: IOException) {
                            if (e is RemoteException) second.drop()
                            null
                        }
                        when (val a = copyIn(store, mover, second, row, path, sha, input, fromSecond = true)) {
                            is Attempt.Done -> return a.outcome
                            Attempt.BadSource -> {}
                            Attempt.Unreadable -> unsure = true
                        }
                    }
                    Probe.BAD -> {}
                    Probe.UNSURE -> unsure = true
                }
            }
        }
        // 3. No good copy: only when every source is proven absent or bad. Unsure = leave the row for the next pass.
        if (unsure) return Outcome.LEFT
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

    /** Missing, wrong size or a different hash = BAD; a read error = UNSURE (a dropped second store is not asked again). */
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
            if (e is RemoteException) second.drop()
            Probe.UNSURE
        }

    /**
     * Copies [input] (closed here; null = the source could not be opened again) to a free name next to [path],
     * verified, and repoints the row. A RemoteException
     * from the main store propagates (the pass stops); any other main-drive failure leaves the row as it was (LEFT).
     * Whatever a failed attempt left on the drive (its `.part`, an unverified copy) stays: nothing is deleted.
     */
    private suspend fun copyIn(
        store: RemoteStore, mover: DriveMover, second: SecondSource, row: LedgerEntry, path: String, sha: String,
        input: InputStream?, fromSecond: Boolean,
    ): Attempt {
        if (input == null) return Attempt.Unreadable
        val dir = path.substringBeforeLast('\\', "")
        val name = path.substringAfterLast('\\')
        val target = try {
            store.mkdirs(dir)
            mover.reserveFreeName(dir, name)
        } catch (e: IOException) {
            input.close()
            if (e is RemoteException) throw e
            return Attempt.Done(Outcome.LEFT)
        }
        if (target == null) {
            input.close()
            return Attempt.Done(Outcome.LEFT)
        }
        try {
            val r = DriveCopy.copyVerified(
                store, DriveCopy.source(input), RemotePathPlanner.partName(target), target, row.sizeBytes,
                expectedSha = sha,
            )
            return when (r) {
                is CopyResult.Verified -> {
                    val ms = if (row.dateTakenMs > 0) row.dateTakenMs else row.dateModifiedSec * 1000
                    DriveCopy.setTimesQuietly(store, target, ms)
                    val moved = ledger.repoint(row.mediaId, path, target, sha, clock())
                    Attempt.Done(if (moved) Outcome.REPAIRED else Outcome.LEFT)
                }
                is CopyResult.WrongSize, is CopyResult.WrongHash -> Attempt.BadSource
                is CopyResult.WriteFailed -> {
                    val e = r.error
                    when {
                        e is SourceReadException -> {
                            if (fromSecond && e.reason is RemoteException) second.drop()
                            Attempt.Unreadable
                        }
                        e is RemoteException -> throw e
                        else -> Attempt.Done(Outcome.LEFT)
                    }
                }
                is CopyResult.OpenFailed -> if (r.error is RemoteException) throw r.error else Attempt.Done(Outcome.LEFT)
                is CopyResult.RenameFailed, is CopyResult.ReadBackFailed, is CopyResult.Mismatch ->
                    Attempt.Done(Outcome.LEFT)
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
