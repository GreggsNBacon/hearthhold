package dev.photobackup.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object Hashing {
    const val BUFFER_BYTES = 1 shl 20 // 1 MiB

    fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }

    /** SHA-256 of everything in [input], as lowercase hex. [onChunk] gets each chunk size (it may throw to abort). Does not close [input]. */
    fun sha256(input: InputStream, onChunk: (Int) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            onChunk(n)
        }
        return hex(md.digest())
    }

    /**
     * Copies [input] to [output] while hashing it. [beforeWrite] is called with each chunk size
     * just before that chunk is handed to [output]. Returns the SHA-256 as lowercase hex.
     * Does not close either stream.
     */
    fun copyWithSha256(input: InputStream, output: OutputStream, beforeWrite: (Int) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            beforeWrite(n)
            output.write(buf, 0, n)
        }
        return hex(md.digest())
    }
}
