package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.ledger.LedgerRules
import dev.photobackup.ledger.LedgerStore
import dev.photobackup.media.PhoneMedia
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId

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
 * - Only ever deletes the item's own `.part` (tracked in the ledger) or a just-renamed copy that
 *   failed verification.
 *
 * Any [RemoteException] (auth, unreachable, share missing, SMB1-only, drive full) stops the run and
 * leaves the in-flight row as-is for the next run's leftover cleanup. Other IOExceptions fail the
 * item and the run continues.
 *
 * [fat32MaxBytes] is injectable only so tests can exercise FAT32 detection without 4 GB of data.
 */
class BackupEngine(
    private val store: RemoteStore,
    private val ledger: LedgerStore,
    private val opener: LocalOpener,
    private val settings: BackupSettings,
    private val clock: () -> Long,
    private val fat32MaxBytes: Long = RemotePathPlanner.FAT32_MAX_BYTES,
) {
    companion object {
        const val FAT32_ERROR = "too big for this drive's format (FAT32, 4 GB limit)"
        const val VERIFY_ERROR = "verification failed"
        private const val MAX_CLASH = 10_000
    }

    private enum class Outcome { UPLOADED, SKIPPED, FAILED }

    private var bytesDone = 0L

    suspend fun run(
        items: List<PhoneMedia>,
        onProgress: (done: Int, total: Int, bytes: Long) -> Unit = { _, _, _ -> },
    ): BackupResult = withContext(Dispatchers.IO) {
        bytesDone = 0L
        // 1. Connect once.
        val drive = try {
            store.connect()
        } catch (e: RemoteException) {
            return@withContext BackupResult.Stopped(e)
        }

        // 2. Clean leftovers before deciding what needs uploading.
        try {
            cleanLeftovers()
        } catch (e: RemoteException) {
            return@withContext BackupResult.Stopped(e)
        }

        val work = items.filter { LedgerRules.needsUpload(ledger.get(it.mediaId), it) }
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var free = drive.freeBytes

        for ((i, m) in work.withIndex()) {
            ensureActive()
            val existing = ledger.get(m.mediaId)
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

                m.sizeBytes > free -> return@withContext BackupResult.Stopped(RemoteException.DriveFull())

                else -> try {
                    processItem(m, i, work.size, onProgress)
                } catch (e: RemoteException) {
                    return@withContext BackupResult.Stopped(e)
                }
            }
            when (outcome) {
                Outcome.UPLOADED -> { uploaded++; free -= m.sizeBytes }
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> failed++
            }
            onProgress(i + 1, work.size, bytesDone)
        }
        BackupResult.Done(uploaded, skipped, failed)
    }

    /** Deletes each row's own tracked `.part`, then resets interrupted rows to PENDING. */
    private suspend fun cleanLeftovers() {
        for (row in ledger.withPartPath()) {
            val part = row.partPath ?: continue
            try {
                if (store.stat(part) != null) store.delete(part)
                ledger.upsert(row.copy(partPath = null, status = BackupStatus.PENDING, updatedAtMs = clock()))
            } catch (e: IOException) {
                if (e is RemoteException) throw e
                // Keep partPath so the next run tries again.
                ledger.upsert(
                    row.copy(
                        status = BackupStatus.FAILED,
                        lastError = "could not remove leftover $part: ${e.message}",
                        updatedAtMs = clock(),
                    )
                )
            }
        }
        for (row in ledger.byStatus(BackupStatus.UPLOADING)) {
            ledger.upsert(row.copy(status = BackupStatus.PENDING, updatedAtMs = clock()))
        }
    }

    private suspend fun processItem(
        m: PhoneMedia,
        index: Int,
        total: Int,
        onProgress: (Int, Int, Long) -> Unit,
    ): Outcome {
        val dir = RemotePathPlanner.targetDir(settings.destination, settings.deviceName, m, settings.zone)
        val name = RemotePathPlanner.sanitise(m.displayName)

        // 4. Pick a free name, or adopt an identical existing file.
        var localSha: String? = null
        var target: String? = null
        try {
            for (n in 0..MAX_CLASH) {
                val candidate = "$dir\\" + if (n == 0) name else RemotePathPlanner.clashName(name, n)
                val st = store.stat(candidate)
                if (st == null) {
                    if (store.stat(RemotePathPlanner.partName(candidate)) == null) {
                        target = candidate
                        break
                    }
                } else if (!st.isDirectory && st.sizeBytes == m.sizeBytes) {
                    val sha = localSha ?: (hashLocal(m) ?: return missingOnPhone(m)).also { localSha = it }
                    if (hashRemote(candidate) == sha) {
                        ledger.upsert(entry(m, BackupStatus.VERIFIED, sha = sha, remote = candidate))
                        return Outcome.SKIPPED
                    }
                }
            }
            if (target == null) return fail(m, "no free file name in $dir")
            store.mkdirs(dir)
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            return fail(m, e.message)
        }
        val final = target!!
        val part = RemotePathPlanner.partName(final)

        // 5. Upload to .part while hashing.
        ledger.upsert(entry(m, BackupStatus.UPLOADING, part = part))
        val input = try {
            opener.open(m)
        } catch (e: IOException) {
            return fail(m, e.message)
        } ?: return missingOnPhone(m) // nothing written yet, so no .part to remove

        var offered = 0L
        var partCreated = false
        val sha: String = try {
            input.use { inp ->
                store.openWrite(part).also { partCreated = true }.use { out ->
                    Hashing.copyWithSha256(inp, out) { n ->
                        offered += n
                        bytesDone += n
                        onProgress(index, total, bytesDone)
                    }
                }
            }
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            // smbj can't report the file system, so a write failing at the 4 GB mark means FAT32.
            val fat32 = m.sizeBytes > fat32MaxBytes && offered >= fat32MaxBytes
            val msg = if (fat32) FAT32_ERROR else e.message
            // If openWrite itself failed, the .part is not ours: never touch it.
            return if (partCreated) failAndRemovePart(m, part, msg) else fail(m, msg)
        }

        // 6. Rename onto the final name (fails rather than overwrite), then clear partPath.
        try {
            store.rename(part, final)
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            return failAndRemovePart(m, part, e.message)
        }
        ledger.upsert(entry(m, BackupStatus.UPLOADING, sha = sha, remote = final))

        // 7. Verify by reading the copy back.
        val remoteSha = try {
            hashRemote(final)
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            // Copy left in place unverified; a later run adopts it if it hashes correctly.
            return fail(m, e.message, remote = final)
        }
        if (remoteSha == sha) {
            ledger.upsert(entry(m, BackupStatus.VERIFIED, sha = sha, remote = final))
            return Outcome.UPLOADED
        }
        val removed = try {
            store.delete(final) // the just-renamed copy that failed verification
            true
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            false
        }
        ledger.upsert(entry(m, BackupStatus.FAILED, error = VERIFY_ERROR, remote = if (removed) null else final))
        return Outcome.FAILED
    }

    private fun hashLocal(m: PhoneMedia): String? = opener.open(m)?.use { Hashing.sha256(it) }

    private suspend fun hashRemote(path: String): String = store.openRead(path, 0).use { Hashing.sha256(it) }

    private suspend fun missingOnPhone(m: PhoneMedia): Outcome {
        ledger.delete(m.mediaId)
        return Outcome.SKIPPED
    }

    private suspend fun fail(m: PhoneMedia, msg: String?, remote: String? = null): Outcome {
        ledger.upsert(entry(m, BackupStatus.FAILED, error = msg ?: "unknown error", remote = remote))
        return Outcome.FAILED
    }

    /** Removes this item's own `.part`; if that fails, keeps partPath so the next run cleans it. */
    private suspend fun failAndRemovePart(m: PhoneMedia, part: String, msg: String?): Outcome {
        val removed = try {
            if (store.stat(part) != null) store.delete(part)
            true
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            false
        }
        ledger.upsert(
            entry(m, BackupStatus.FAILED, error = msg ?: "unknown error", part = if (removed) null else part)
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
