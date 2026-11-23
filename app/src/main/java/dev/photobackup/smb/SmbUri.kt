package dev.photobackup.smb

import java.io.ByteArrayOutputStream

/** A file on an SMB share. [path] is share-relative and `\`-separated, like every [RemoteStore] path. */
data class SmbLocation(val host: String, val share: String, val path: String)

/**
 * `smb://<host>/<share>/<path with forward slashes>`: how drive files are named outside [RemoteStore]
 * (Coil image data, the viewer). Share and path segments are percent-encoded so names with spaces, `#`, `?` or
 * `%` survive any URI parser. No credentials are ever put in the URI.
 */
object SmbUri {
    const val SCHEME = "smb"
    private const val PREFIX = "$SCHEME://"

    fun build(host: String, share: String, path: String): String {
        val segs = path.split('\\', '/').filter { it.isNotEmpty() }
        return PREFIX + host + "/" + encode(share) + "/" + segs.joinToString("/") { encode(it) }
    }

    fun build(loc: SmbLocation): String = build(loc.host, loc.share, loc.path)

    /** Null when [uri] is not a well-formed `smb://host/share[/path]`. */
    fun parse(uri: String): SmbLocation? {
        if (!uri.startsWith(PREFIX, ignoreCase = true)) return null
        val parts = uri.substring(PREFIX.length).split('/')
        if (parts.size < 2) return null
        val host = parts[0]
        if (host.isEmpty()) return null
        val share = decode(parts[1]) ?: return null
        if (share.isEmpty()) return null
        val segs = parts.drop(2).filter { it.isNotEmpty() }.map { decode(it) ?: return null }
        return SmbLocation(host, share, segs.joinToString("\\"))
    }

    private const val HEX = "0123456789ABCDEF"

    private fun isSafe(c: Int) =
        c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~()!'*,;=@&$"

    private fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (isSafe(c)) sb.append(c.toChar()) else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
        return sb.toString()
    }

    private fun decode(s: String): String? {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                out.write(ch.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
