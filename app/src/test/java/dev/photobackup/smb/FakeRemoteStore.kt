package dev.photobackup.smb

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * In-memory [RemoteStore]. Thread-safe (state changes under `synchronized(this)`) so the backup engine's parallel
 * workers can share it; [failOn] runs outside the lock, so a test hook may block to line workers up.
 */
class FakeRemoteStore(
    var failOn: ((op: String, path: String) -> IOException?)? = null,
    var freeBytes: Long = Long.MAX_VALUE,
    var fileSystem: String = "EXFAT",
    var truncateWritesAfter: Long? = null,
    /** When true, openRead returns the data with its first byte flipped (simulates a bad read-back). */
    var corruptReads: Boolean = false,
) : RemoteStore {
    val files: MutableMap<String, ByteArray> = linkedMapOf()
    val dirs: MutableSet<String> = linkedSetOf()

    /** Paths opened for write, in order. */
    val writes: MutableList<String> = mutableListOf()

    /** setTimes calls: path -> modifiedMs. */
    val times: MutableMap<String, Long> = linkedMapOf()

    /** Thrown (any type) by setTimes, to check the engine treats it as cosmetic. */
    var setTimesError: Throwable? = null

    /** Paths passed to delete, in order. */
    val deletes: MutableList<String> = mutableListOf()

    private fun check(op: String, path: String) {
        failOn?.invoke(op, path)?.let { throw it }
    }

    private fun nameOf(path: String) = path.substringAfterLast('\\')
    private fun parentOf(path: String) = if ('\\' in path) path.substringBeforeLast('\\') else ""

    private fun entry(path: String): RemoteEntry? = when {
        path in files -> RemoteEntry(nameOf(path), path, false, files.getValue(path).size.toLong(), 0L)
        path in dirs -> RemoteEntry(nameOf(path), path, true, 0L, 0L)
        else -> null
    }

    override suspend fun connect(): DriveInfo {
        check("connect", "")
        return DriveInfo(freeBytes, fileSystem)
    }

    override suspend fun list(dir: String): List<RemoteEntry> {
        check("list", dir)
        synchronized(this) {
            if (dir.isNotEmpty() && dir !in dirs) throw FileNotFoundException(dir)
            return (files.keys + dirs).filter { parentOf(it) == dir }.sorted().mapNotNull { entry(it) }
        }
    }

    override suspend fun stat(path: String): RemoteEntry? {
        check("stat", path)
        return synchronized(this) { entry(path) }
    }

    override suspend fun mkdirs(dir: String) {
        check("mkdirs", dir)
        if (dir.isEmpty()) return
        var cur = ""
        for (seg in dir.split('\\')) {
            cur = if (cur.isEmpty()) seg else "$cur\\$seg"
            if (cur in files) throw IOException("Not a directory: $cur")
            dirs.add(cur)
        }
    }

    override suspend fun openWrite(path: String): OutputStream {
        check("openWrite", path)
        synchronized(this) {
            if (path in files || path in dirs) throw IOException("File exists: $path")
            val parent = parentOf(path)
            if (parent.isNotEmpty() && parent !in dirs) throw FileNotFoundException(parent)
            writes.add(path)
            files[path] = ByteArray(0)
        }
        val store = this
        return object : OutputStream() {
            private var streamWritten = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                check("write", path)
                val limit = truncateWritesAfter
                val allowed =
                    if (limit == null) len else minOf(len.toLong(), maxOf(0L, limit - streamWritten)).toInt()
                if (allowed > 0) {
                    synchronized(store) { files[path] = files.getValue(path) + b.copyOfRange(off, off + allowed) }
                    streamWritten += allowed
                }
                if (allowed < len) throw IOException("Simulated write failure after $limit bytes")
            }
        }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream {
        check("openRead", path)
        val stored = synchronized(this) { files[path] } ?: throw FileNotFoundException(path)
        val data = if (corruptReads && stored.isNotEmpty()) {
            stored.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        } else stored
        val start = offset.coerceIn(0L, data.size.toLong()).toInt()
        return ByteArrayInputStream(data, start, data.size - start)
    }

    override suspend fun rename(from: String, to: String) {
        check("rename", from)
        synchronized(this) {
            if (to in files || to in dirs) throw IOException("Target exists: $to")
            // Like SMB, a rename can move a file into another folder of the share, but the folder must exist.
            val toParent = parentOf(to)
            if (toParent.isNotEmpty() && toParent !in dirs) throw FileNotFoundException(toParent)
            val data = files.remove(from) ?: throw FileNotFoundException(from)
            files[to] = data
        }
    }

    override suspend fun delete(path: String) {
        check("delete", path)
        synchronized(this) {
            deletes.add(path)
            if (files.remove(path) == null && !dirs.remove(path)) throw FileNotFoundException(path)
        }
    }

    override suspend fun setTimes(path: String, modifiedMs: Long) {
        setTimesError?.let { throw it }
        check("setTimes", path)
        synchronized(this) {
            if (path !in files) throw FileNotFoundException(path)
            times[path] = modifiedMs
        }
    }

    /** True once close() was called. */
    var closed = false

    override fun close() {
        closed = true
    }
}
