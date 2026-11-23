package dev.photobackup.discovery

object SubnetScan {
    /** Usable host addresses of the subnet containing [ip]; prefixes wider than /24 are capped at /24. */
    fun hostsIn(ip: String, prefix: Int): List<String> {
        val parts = ip.split('.')
        if (parts.size != 4) return emptyList()
        val o = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return emptyList() }
        val p = prefix.coerceIn(24, 30)
        val addr = (o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3]
        val mask = -1 shl (32 - p)
        val net = addr and mask
        val bcast = net or mask.inv()
        return ((net + 1) until bcast).map { a ->
            "${(a ushr 24) and 255}.${(a ushr 16) and 255}.${(a ushr 8) and 255}.${a and 255}"
        }
    }

    /** Broadcast address of the subnet (real prefix, not capped). */
    fun broadcastOf(ip: String, prefix: Int): String? {
        val parts = ip.split('.')
        if (parts.size != 4 || prefix !in 1..30) return null
        val o = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return null }
        val addr = (o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3]
        val b = addr or (-1 shl (32 - prefix)).inv()
        return "${(b ushr 24) and 255}.${(b ushr 16) and 255}.${(b ushr 8) and 255}.${b and 255}"
    }
}
