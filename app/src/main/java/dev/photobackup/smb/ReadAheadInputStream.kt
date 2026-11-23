package dev.photobackup.smb

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

/** One positioned read: up to [len] bytes at [position] into the start of [buf]; the count, or -1 at the end. */
fun interface ChunkReader {
    fun read(position: Long, buf: ByteArray, len: Int): Int
}

/**
 * Sequential stream that keeps [depth] reads of [chunkSize] bytes in flight ahead of the reader.
 *
 * SMB reads are request/response: one request at a time leaves the link idle for a round trip per chunk, and
 * routers that only speak SMB 2.0.2 cap each read at 64 KB, so that idle time dominates. Requests at fixed offsets go
 * out together on the shared connection; their bytes are handed out strictly in order, so the result is exactly the
 * file's bytes (backups hash what this returns). A short chunk (fewer bytes than asked, not at the end) drops the
 * prefetched chunks after it and restarts from the first missing byte, so a short read never leaves a gap.
 *
 * Not thread-safe for concurrent [read] calls (an InputStream). [close] cancels queued reads, waits for started ones
 * (they use the file handle) and then runs [onClose], so the handle is closed when [close] returns: callers rename or
 * delete the file right after reading it.
 */
class ReadAheadInputStream(
    private val reader: ChunkReader,
    start: Long,
    private val chunkSize: Int,
    private val depth: Int,
    private val executor: Executor,
    private val onClose: () -> Unit = {},
) : InputStream() {
    private class Chunk(val position: Long, val task: FutureTask<Pair<ByteArray, Int>>)

    private val queue = ArrayDeque<Chunk>()
    private var nextFetch = start
    private var ended = false // a chunk came back at the end of the file: nothing more to schedule
    private var current: ByteArray? = null
    private var currentLen = 0
    private var currentPos = 0
    private var closed = false

    init {
        require(chunkSize > 0 && depth > 0)
    }

    private fun fill() {
        while (!ended && queue.size < depth) {
            val pos = nextFetch
            val task = FutureTask {
                val buf = ByteArray(chunkSize)
                buf to reader.read(pos, buf, chunkSize)
            }
            queue.addLast(Chunk(pos, task))
            nextFetch += chunkSize
            executor.execute(task)
        }
    }

    /** Next chunk's bytes, or false at the end of the file. */
    private fun advance(): Boolean {
        fill()
        val chunk = queue.pollFirst() ?: return false
        val (buf, n) = try {
            chunk.task.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Cancelled")
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw if (cause is IOException) cause else IOException("Drive read failed", cause)
        }
        if (n <= 0) {
            ended = true
            dropQueued()
            return false
        }
        if (n < chunkSize) {
            // Short: the queued chunks start after the gap. Restart right after what we got.
            dropQueued()
            nextFetch = chunk.position + n
        }
        current = buf
        currentLen = n
        currentPos = 0
        return true
    }

    private fun dropQueued() {
        while (true) queue.pollFirst()?.task?.cancel(false) ?: break
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (len == 0) return 0
        if (current == null || currentPos >= currentLen) {
            if (!advance()) return -1
        }
        val n = minOf(len, currentLen - currentPos)
        System.arraycopy(current!!, currentPos, b, off, n)
        currentPos += n
        fill() // keep the pipe full while the caller works on these bytes
        return n
    }

    override fun available(): Int = if (current == null) 0 else currentLen - currentPos

    override fun close() {
        if (closed) return
        closed = true
        val inFlight = queue.toList()
        queue.clear()
        current = null
        inFlight.forEach { it.task.cancel(false) } // not started: never runs
        // Started reads still use the file handle: let them finish (one chunk each), then close it.
        inFlight.forEach { runCatching { if (!it.task.isCancelled) it.task.get() } }
        onClose()
    }
}
