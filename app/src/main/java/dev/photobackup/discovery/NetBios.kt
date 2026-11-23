package dev.photobackup.discovery

/** NetBIOS node-status (NBSTAT) query/response, RFC 1002. */
object NetBios {
    /** 12-byte header + wildcard "*" name (34 bytes) + QTYPE NBSTAT (0x0021) + QCLASS IN. */
    fun wildcardQueryPacket(txId: Short): ByteArray {
        val p = ByteArray(50)
        p[0] = (txId.toInt() shr 8).toByte(); p[1] = txId.toByte()
        p[5] = 1 // QDCOUNT
        p[12] = 0x20
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (i in 0 until 16) {
            p[13 + i * 2] = ('A'.code + ((raw[i].toInt() shr 4) and 0xF)).toByte()
            p[14 + i * 2] = ('A'.code + (raw[i].toInt() and 0xF)).toByte()
        }
        p[45] = 0
        p[47] = 0x21
        p[49] = 0x01
        return p
    }

    /** First unique name with suffix 0x20 (file server), else first unique 0x00 (workstation); null if none. */
    fun parseNodeStatusName(packet: ByteArray): String? {
        if (packet.size < 12 || (packet[2].toInt() and 0x80) == 0) return null // not a response
        if (u16(packet, 6) < 1) return null // no answer RRs
        var pos = 12
        // skip RR name (labels, or a 2-byte compression pointer)
        while (true) {
            if (pos >= packet.size) return null
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if ((len and 0xC0) == 0xC0) { pos += 2; break }
            pos += 1 + len
        }
        pos += 10 // TYPE, CLASS, TTL, RDLENGTH
        if (pos >= packet.size) return null
        val num = packet[pos].toInt() and 0xFF
        pos++
        var workstation: String? = null
        for (i in 0 until num) {
            val at = pos + i * 18
            if (at + 18 > packet.size) break
            val flags = u16(packet, at + 16)
            if ((flags and 0x8000) != 0) continue // group name
            val name = String(packet, at, 15, Charsets.US_ASCII).trim()
            if (name.isEmpty()) continue
            when (packet[at + 15].toInt() and 0xFF) {
                0x20 -> return name
                0x00 -> if (workstation == null) workstation = name
            }
        }
        return workstation
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
