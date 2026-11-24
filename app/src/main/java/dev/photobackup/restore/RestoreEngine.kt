package dev.photobackup.restore

import dev.photobackup.backup.DriveCopy
import dev.photobackup.backup.DrivePaths
import dev.photobackup.backup.Hashing
import dev.photobackup.backup.SourceReadException
import dev.photobackup.freeup.formatSize
import dev.photobackup.gallery.CopyToPhone
import dev.photobackup.gallery.PhoneMediaWriter
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.RestoreLedger
import dev.photobackup.ledger.RestoredItem
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** A drive folder and year on the Restore screen; [year] null = [OTHER] (paths not in today's layout). */
data class RestoreGroupKey(val folder: String, val year: String?) {
    companion object {
        val OTHER = RestoreGroupKey("Other", null)
    }
}

data class RestoreGroup(val key: RestoreGroupKey, val count: Int, val bytes: Long)

/** The ticked groups, or [EVERYTHING]. */
data class RestoreSelection(val groups: Set<RestoreGroupKey>, val everything: Boolean = false) {
    fun includes(key: RestoreGroupKey) = everything || key in groups

    companion object {
        val EVERYTHING = RestoreSelection(emptySet(), everything = true)
    }
}

/**
 * [groups]: every group with drive-only items (for the screen). [selectedCount]/[selectedBytes]: what [RestoreEngine.run]
 * would restore. [fits]: [selectedBytes] plus [RestoreEngine.MARGIN_BYTES] is no more than [freeBytes].
 */
data class RestorePlan(
    val groups: List<RestoreGroup>,
    val selectedCount: Int,
    val selectedBytes: Long,
    val freeBytes: Long,
    val fits: Boolean,
)

data class RestoreFailure(val name: String, val reason: String)

/**
 * [restored]: written, verified and re-keyed. [alreadyThere]: the same photo was already on the phone (re-keyed to it,
 * or left alone when it has its own ledger row). [failed]: per item, fixed texts only. [redirected]: names written to
 * [RestoreEngine.PHOTO_FALLBACK] / [RestoreEngine.VIDEO_FALLBACK] because Android refused the original folder.
 * [stopped]: why the whole job ended early or never started (null = it ran to the end).
 */
data class RestoreResult(
    val restored: Int = 0,
    val alreadyThere: Int = 0,
    val failed: List<RestoreFailure> = emptyList(),
    val redirected: List<String> = emptyList(),
    val stopped: String? = null,
)

data class RestoreProgress(val done: Int, val total: Int, val result: RestoreResult)

/** The phone side of a restore. URIs are strings for JVM tests; [ResolverRestorePhone] is the real one. */
interface RestorePhone {
    /** Pending insert (MediaStore picks a free name: never overwrites), write, publish, discard. */
    val writer: PhoneMediaWriter

    /** Best effort: the original capture time (and date modified where Android allows) on a pending item. */
    fun setDates(uri: String, dateTakenMs: Long, dateModifiedSec: Long)

    /** Reads an item (also a pending one this app inserted); null = not there. */
    fun openInput(uri: String): InputStream?

    /** The published item as MediaStore now records it; null = not found. */
    fun item(uri: String, isVideo: Boolean): PhoneMedia?

    /** Phone photos or videos with exactly this name and size (the cheap "already on the phone" check). */
    fun findSame(displayName: String, sizeBytes: Long, isVideo: Boolean): List<PhoneMedia>

    /** Free bytes on the phone's shared storage. */
    fun freeBytes(): Long
}

/**
 * Restore to phone: drive-only ledger rows back into the phone's MediaStore.
 *
 * Each item, in folder/year order:
 * 1. Already on the phone (same name and size, then same SHA-256): no copy is written. The row is re-keyed to that
 *    item, or left alone when that item has its own ledger row.
 * 2. Otherwise read from the main drive at the row's remotePath; when that file is missing, of the wrong size or
 *    hashes to something else, from a VERIFIED second copy of the same hash (only when the second location is on:
 *    [openSecond] non-null). Neither: "No good drive copy".
 * 3. Written as a pending MediaStore item into the row's original relativePath (refused by Android: the Restored
 *    folder, reported) with the original name and capture time, read back and hashed. Only a matching copy is
 *    published; anything else is discarded. Then [RestoreLedger.rekey] in one transaction; if the row changed
 *    meanwhile the new phone file is discarded.
 *
 * A lost connection to the main drive (RemoteException) is retried once per run on a fresh connection. After that
 * main is down: items with a usable second copy come from the second drive, and the first item the second drive can't
 * serve stops the job with [UNREACHABLE] (also when the second location is off). Cancelling keeps
 * finished items and discards the one in flight. Never writes, renames or deletes a drive file; never logs.
 */
class RestoreEngine(
    private val ledger: RestoreLedger,
    private val phone: RestorePhone,
    /** `<destination>\<device>` of the main drive, for grouping by folder and year. */
    private val root: String,
    /** Opens and connects the main drive store; the engine closes it. */
    private val openMain: suspend () -> RemoteStore,
    /** Opens and connects the second location's store, or null when the second location is off. */
    private val openSecond: (suspend () -> RemoteStore)?,
    private val clock: () -> Long,
) {
    companion object {
        const val MARGIN_BYTES = 200L * 1024 * 1024
        const val PHOTO_FALLBACK = "DCIM/Restored/"
        const val VIDEO_FALLBACK = "Movies/Restored/"

        const val NO_GOOD_COPY = "No good drive copy"
        const val UNREACHABLE = "Drive not reachable"
        const val SECOND_UNREACHABLE = "Second drive not reachable"
        const val DRIVE_READ = "Couldn't read it on the drive"
        const val PHONE_WRITE = "Couldn't save it on the phone"
        const val PHONE_MISMATCH = "The phone copy didn't match — removed"
        const val CHANGED = "Changed meanwhile — phone copy removed"
        const val NO_PHOTO_ACCESS = "Photos permission needed"
        const val CHANGED_KEPT = "Changed meanwhile — left as it was"

        fun noRoom(needed: Long, free: Long) =
            "Not enough space on the phone: needs ${formatSize(needed)}, ${formatSize(free.coerceAtLeast(0))} free"
    }

    /**
     * A drive for one run, opened at most [maxOpens] times: [fail] drops the connection, and the next [get] reopens
     * it while opens are left. Once an open fails or none are left it is [down] for the rest of the run.
     */
    private class Source(private val open: suspend () -> RemoteStore, private val maxOpens: Int) {
        private var store: RemoteStore? = null
        private var opens = 0
        var down = false
            private set

        suspend fun get(): RemoteStore? {
            if (down) return null
            store?.let { return it }
            if (opens >= maxOpens) {
                down = true
                return null
            }
            opens++
            store = try {
                open()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (store == null) down = true
            return store
        }

        fun fail() {
            close()
            store = null
        }

        fun close() {
            runCatching { store?.close() }
        }
    }

    private sealed interface Outcome {
        class Restored(val redirected: Boolean) : Outcome
        data object AlreadyThere : Outcome
        class Failed(val reason: String) : Outcome
        data object Stop : Outcome
    }

    /** How reading one source went. */
    private sealed interface Attempt {
        class Done(val outcome: Outcome) : Attempt
        data object BadSource : Attempt
        data object Unreadable : Attempt
        data object Unreachable : Attempt
    }

    fun keyOf(e: LedgerEntry): RestoreGroupKey =
        DrivePaths.parse(e, root)?.let { RestoreGroupKey(it.folder, it.year) } ?: RestoreGroupKey.OTHER

    private val groupOrder = compareBy<RestoreGroupKey> { it.year == null }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.folder }
        .thenByDescending { it.year }

    suspend fun plan(selection: RestoreSelection): RestorePlan = withContext(Dispatchers.IO) {
        planOf(ledger.candidates(), selection)
    }

    private fun planOf(rows: List<LedgerEntry>, selection: RestoreSelection): RestorePlan {
        val byKey = rows.groupBy { keyOf(it) }
        val groups = byKey.map { (k, v) -> RestoreGroup(k, v.size, v.sumOf { it.sizeBytes }) }
            .sortedWith(compareBy(groupOrder) { it.key })
        val chosen = groups.filter { selection.includes(it.key) }
        val bytes = chosen.sumOf { it.bytes }
        val free = try {
            phone.freeBytes()
        } catch (e: Exception) {
            0L
        }
        return RestorePlan(groups, chosen.sumOf { it.count }, bytes, free, bytes + MARGIN_BYTES <= free)
    }

    suspend fun run(selection: RestoreSelection, onProgress: (RestoreProgress) -> Unit): RestoreResult =
        withContext(Dispatchers.IO) {
            val rows = ledger.candidates()
            val plan = planOf(rows, selection)
            if (!plan.fits) return@withContext RestoreResult(stopped = noRoom(plan.selectedBytes + MARGIN_BYTES, plan.freeBytes))
            val items = rows.filter { selection.includes(keyOf(it)) }
                .sortedWith(compareBy<LedgerEntry, RestoreGroupKey>(groupOrder) { keyOf(it) }
                    .thenBy { it.dateTakenMs }.thenBy { it.mediaId })
            // Main: one reopen after a lost connection (a blip); the second drive: no reopen.
            val main = Source(openMain, maxOpens = 2)
            val second = openSecond?.let { Source(it, maxOpens = 1) }
            var restored = 0
            var already = 0
            val failed = mutableListOf<RestoreFailure>()
            val redirected = mutableListOf<String>()
            fun result(stopped: String? = null) =
                RestoreResult(restored, already, failed.toList(), redirected.toList(), stopped)
            try {
                onProgress(RestoreProgress(0, items.size, result()))
                for ((i, row) in items.withIndex()) {
                    coroutineContext.ensureActive()
                    when (val o = restoreOne(row, main, second)) {
                        is Outcome.Restored -> {
                            restored++
                            if (o.redirected) redirected += row.displayName
                        }
                        Outcome.AlreadyThere -> already++
                        is Outcome.Failed -> failed += RestoreFailure(row.displayName, o.reason)
                        Outcome.Stop -> return@withContext result(UNREACHABLE)
                    }
                    onProgress(RestoreProgress(i + 1, items.size, result()))
                }
                result()
            } finally {
                main.close()
                second?.close()
            }
        }

    private suspend fun restoreOne(row: LedgerEntry, main: Source, second: Source?): Outcome {
        val sha = row.localSha256!!
        val path = row.remotePath!!
        val there = try {
            alreadyOnPhone(row, sha)
        } catch (_: SecurityException) {
            // Can't see the phone's photos: restoring could make a duplicate, so leave it.
            return Outcome.Failed(NO_PHOTO_ACCESS)
        }
        there?.let { m ->
            if (m.mediaId != row.mediaId && ledger.hasRow(m.mediaId)) return Outcome.AlreadyThere
            return if (ledger.rekey(row.mediaId, path, sha, m.restored(), clock())) {
                Outcome.AlreadyThere
            } else {
                Outcome.Failed(CHANGED_KEPT)
            }
        }
        var mainUnreachable = false
        var unreadable = false
        var secondUnreachable = false
        // A lost connection drops the store; the next get() reopens it once per run, then main is down for good.
        while (true) {
            val m = main.get()
            if (m == null) {
                mainUnreachable = true
                break
            }
            when (val a = attempt(m, path, row, sha)) {
                is Attempt.Done -> return a.outcome
                Attempt.BadSource -> break
                Attempt.Unreadable -> {
                    unreadable = true
                    break
                }
                Attempt.Unreachable -> main.fail()
            }
        }
        if (second != null) {
            val c = ledger.secondCopy(row.mediaId)
            if (c != null && usable(c, sha)) {
                val s = second.get()
                if (s == null) {
                    secondUnreachable = true
                } else {
                    when (val a = attempt(s, c.remotePath!!, row, sha)) {
                        is Attempt.Done -> return a.outcome
                        Attempt.BadSource -> {}
                        Attempt.Unreadable -> unreadable = true
                        Attempt.Unreachable -> {
                            second.fail()
                            secondUnreachable = true
                        }
                    }
                }
            }
        }
        // Main unreachable and the second drive didn't serve this item: stop the job (finished items are kept).
        if (mainUnreachable) return Outcome.Stop
        return Outcome.Failed(
            when {
                secondUnreachable -> SECOND_UNREACHABLE
                unreadable -> DRIVE_READ
                else -> NO_GOOD_COPY
            }
        )
    }

    private fun usable(c: SecondCopy, sha: String) = c.status == SecondStatus.VERIFIED && c.remotePath != null &&
        c.sha256 == sha && c.movingTo == null && c.partPath == null

    /**
     * A phone item with the row's name and size whose bytes hash to [sha]; any read problem = not that one. A
     * SecurityException from the lookup (no photo access) propagates.
     */
    private fun alreadyOnPhone(row: LedgerEntry, sha: String): PhoneMedia? {
        val same = try {
            phone.findSame(row.displayName, row.sizeBytes, row.isVideo)
        } catch (e: Exception) {
            if (e is SecurityException) throw e
            return null
        }
        return same.firstOrNull { m ->
            try {
                phone.openInput(m.uri)?.use { Hashing.sha256(it) } == sha
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun PhoneMedia.restored() = RestoredItem(mediaId, uri, displayName, sizeBytes, dateModifiedSec)

    /** One copy from [store] at [path] into the phone. A RemoteException never escapes: it is [Attempt.Unreachable]. */
    private suspend fun attempt(store: RemoteStore, path: String, row: LedgerEntry, sha: String): Attempt {
        val st = try {
            store.stat(path)
        } catch (_: FileNotFoundException) {
            null
        } catch (e: IOException) {
            return if (e is RemoteException) Attempt.Unreachable else Attempt.Unreadable
        }
        if (st == null || st.isDirectory || st.sizeBytes != row.sizeBytes) return Attempt.BadSource

        val writer = phone.writer
        val mime = CopyToPhone.mimeType(row.displayName, row.isVideo)
        var redirected = false
        val uri = try {
            try {
                if (row.relativePath.isBlank()) throw IllegalArgumentException()
                writer.createPending(row.displayName, mime, row.relativePath, row.isVideo)
            } catch (_: IllegalArgumentException) {
                redirected = true
                writer.createPending(row.displayName, mime, if (row.isVideo) VIDEO_FALLBACK else PHOTO_FALLBACK, row.isVideo)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Attempt.Done(Outcome.Failed(PHONE_WRITE))
        }
        fun discard() {
            runCatching { writer.discard(uri) }
        }
        try {
            val input = try {
                store.openRead(path, 0)
            } catch (_: FileNotFoundException) {
                discard()
                return Attempt.BadSource
            } catch (e: IOException) {
                discard()
                return if (e is RemoteException) Attempt.Unreachable else Attempt.Unreadable
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                discard() // never leave a hidden half-item, whatever the store throws
                return Attempt.Unreadable
            }
            val job = coroutineContext[Job]
            val streamed = try {
                DriveCopy.source(input).use { src ->
                    writer.openOutput(uri).use { out -> Hashing.copyWithSha256(src, out) { job?.ensureActive() } }
                }
            } catch (e: SourceReadException) {
                discard()
                return if (e.reason is RemoteException) Attempt.Unreachable else Attempt.Unreadable
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                discard()
                return Attempt.Done(Outcome.Failed(PHONE_WRITE))
            }
            if (streamed != sha) {
                discard()
                return Attempt.BadSource
            }
            coroutineContext.ensureActive()
            val back = try {
                phone.openInput(uri)?.use { Hashing.sha256(it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (back != sha) {
                discard()
                return Attempt.Done(Outcome.Failed(PHONE_MISMATCH))
            }
            coroutineContext.ensureActive()
            // From here the item is finished as one step: a cancel now must not leave a published file unrecorded.
            return withContext(NonCancellable) { finish(uri, row, sha, redirected, ::discard) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { discard() }
            throw e
        }
    }

    private suspend fun finish(
        uri: String, row: LedgerEntry, sha: String, redirected: Boolean, discard: () -> Unit,
    ): Attempt {
        runCatching { phone.setDates(uri, row.dateTakenMs, row.dateModifiedSec) }
        val item = try {
            phone.writer.publish(uri)
            phone.item(uri, row.isVideo)
        } catch (e: Exception) {
            null
        }
        if (item == null) {
            discard()
            return Attempt.Done(Outcome.Failed(PHONE_WRITE))
        }
        val ok = try {
            ledger.rekey(row.mediaId, row.remotePath!!, sha, item.restored(), clock())
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            discard()
            return Attempt.Done(Outcome.Failed(CHANGED))
        }
        return Attempt.Done(Outcome.Restored(redirected))
    }
}
