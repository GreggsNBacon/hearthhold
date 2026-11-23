package dev.photobackup.backup

import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** How [DriveCopy.copyVerified] ended. What is left on the drive is said per case. */
sealed interface CopyResult {
    /** The final name holds the copy and its read-back SHA-256 matched. */
    data class Verified(val sha: String) : CopyResult

    /** openWrite failed: nothing of ours is on the drive (a `.part` there, if any, is not ours). */
    data class OpenFailed(val error: IOException) : CopyResult

    /** The write (or the source read) failed after the `.part` was created; [bytes] were offered. The `.part` stays. */
    data class WriteFailed(val error: IOException, val bytes: Long) : CopyResult

    /** The source gave [bytes], not the expected size. Checked before the rename; the `.part` stays. */
    data class WrongSize(val bytes: Long) : CopyResult

    /** The source's SHA-256 is not the expected one. Checked before the rename; the `.part` stays. */
    data class WrongHash(val sha: String) : CopyResult

    /** The rename (never onto an existing name) failed; the `.part` stays. */
    data class RenameFailed(val error: IOException) : CopyResult

    /** Renamed, but reading it back failed: the unverified copy stays under the final name. */
    data class ReadBackFailed(val error: IOException) : CopyResult

    /**
     * Renamed, but the read-back SHA-256 differs from [sha], the SHA-256 of what was written: the bad copy stays under
     * the final name.
     */
    data class Mismatch(val sha: String) : CopyResult
}

/** Thrown through a [DriveCopy.source] stream when reading the source (not writing the drive) failed. */
class SourceReadException(cause: IOException) : IOException("Source read failed", cause) {
    val reason: IOException get() = cause as IOException
}

/**
 * The copy mechanism shared by the main backup and the second location: upload to `.part` while hashing, rename onto
 * a name that does not exist, read the copy back and compare. Callers decide what each outcome means for their rows
 * and what to remove (only their own `.part` or a copy that failed its check).
 */
object DriveCopy {
    /**
     * Copies [input] (closed here) to [part], then renames it to [final] and verifies it by reading it back. Before the
     * rename the copy must be exactly [sizeBytes] and, when [expectedSha] is given, have that SHA-256. Cancellable at
     * every chunk ([onChunk] gets each chunk size after that check). [afterRename] runs right after the rename, before
     * the read-back. A [RemoteException] from the rename or the read-back propagates; those from openWrite or the write
     * come back in [CopyResult.OpenFailed] / [CopyResult.WriteFailed] for the caller to decide.
     */
    suspend fun copyVerified(
        store: RemoteStore,
        input: InputStream,
        part: String,
        final: String,
        sizeBytes: Long,
        expectedSha: String? = null,
        onChunk: (Int) -> Unit = {},
        afterRename: suspend (sha: String) -> Unit = {},
    ): CopyResult {
        val ctx = coroutineContext
        var offered = 0L
        var partCreated = false
        val sha: String = try {
            input.use { inp ->
                store.openWrite(part).also { partCreated = true }.use { out ->
                    Hashing.copyWithSha256(inp, out) { n ->
                        ctx.ensureActive() // cancellable per chunk; the caller's row keeps its .part for cleanup
                        offered += n
                        onChunk(n)
                    }
                }
            }
        } catch (e: IOException) {
            return if (partCreated) CopyResult.WriteFailed(e, offered) else CopyResult.OpenFailed(e)
        }
        if (offered != sizeBytes) return CopyResult.WrongSize(offered)
        if (expectedSha != null && sha != expectedSha) return CopyResult.WrongHash(sha)
        try {
            store.rename(part, final)
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            return CopyResult.RenameFailed(e)
        }
        afterRename(sha)
        val back = try {
            hash(store, final)
        } catch (e: IOException) {
            if (e is RemoteException) throw e
            return CopyResult.ReadBackFailed(e)
        }
        return if (back == sha) CopyResult.Verified(sha) else CopyResult.Mismatch(sha)
    }

    /** SHA-256 of a drive file, read in full. */
    suspend fun hash(store: RemoteStore, path: String): String = store.openRead(path, 0).use { Hashing.sha256(it) }

    /** Deletes [path] if it exists. False if that failed; a [RemoteException] propagates. */
    suspend fun removeIfPresent(store: RemoteStore, path: String): Boolean = try {
        if (store.stat(path) != null) store.delete(path)
        true
    } catch (e: IOException) {
        if (e is RemoteException) throw e
        false
    }

    /** Deletes [path]. False if that failed; a [RemoteException] propagates. */
    suspend fun delete(store: RemoteStore, path: String): Boolean = try {
        store.delete(path)
        true
    } catch (e: IOException) {
        if (e is RemoteException) throw e
        false
    }

    /** Sets a just-verified copy's date. Metadata only, cosmetic: any failure is ignored. */
    suspend fun setTimesQuietly(store: RemoteStore, path: String, ms: Long) {
        if (ms <= 0) return
        try {
            store.setTimes(path, ms)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        }
    }

    /** Wraps a source stream so its read failures surface as [SourceReadException], apart from drive write failures. */
    fun source(input: InputStream): InputStream = object : FilterInputStream(input) {
        override fun read(): Int = wrap { super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = wrap { super.read(b, off, len) }
        override fun skip(n: Long): Long = wrap { super.skip(n) }
        override fun close() = wrap { super.close() }

        private inline fun <T> wrap(f: () -> T): T = try {
            f()
        } catch (e: IOException) {
            throw SourceReadException(e)
        }
    }
}
