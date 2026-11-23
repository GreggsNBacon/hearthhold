package dev.photobackup.smb

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FakeRemoteStore(
    var failOn: ((op: String, path: String) -> RemoteException?)? = null,
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
        if (dir.isNotEmpty() && dir !in dirs) throw FileNotFoundException(dir)
        return (files.keys + dirs).filter { parentOf(it) == dir }.sorted().mapNotNull { entry(it) }
    }

    override suspend fun stat(path: String): RemoteEntry? {
        check("stat", path)
        return entry(path)
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
        if (path in files || path in dirs) throw IOException("File exists: $path")
        val parent = parentOf(path)
        if (parent.isNotEmpty() && parent !in dirs) throw FileNotFoundException(parent)
        writes.add(path)
        files[path] = ByteArray(0)
        return object : OutputStream() {
            private var streamWritten = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                check("write", path)
                val limit = truncateWritesAfter
                val allowed =
                    if (limit == null) len else minOf(len.toLong(), maxOf(0L, limit - streamWritten)).toInt()
                if (allowed > 0) {
                    files[path] = files.getValue(path) + b.copyOfRange(off, off + allowed)
                    streamWritten += allowed
                }
                if (allowed < len) throw IOException("Simulated write failure after $limit bytes")
            }
        }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream {
        check("openRead", path)
        val stored = files[path] ?: throw FileNotFoundException(path)
        val data = if (corruptReads && stored.isNotEmpty()) {
            stored.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        } else stored
        val start = offset.coerceIn(0L, data.size.toLong()).toInt()
        return ByteArrayInputStream(data, start, data.size - start)
    }

    override suspend fun rename(from: String, to: String) {
        check("rename", from)
        if (to in files || to in dirs) throw IOException("Target exists: $to")
        val data = files.remove(from) ?: throw FileNotFoundException(from)
        files[to] = data
    }

    override suspend fun delete(path: String) {
        check("delete", path)
        deletes.add(path)
        if (files.remove(path) == null && !dirs.remove(path)) throw FileNotFoundException(path)
    }

    override fun close() {}
}
