package dev.photobackup.backup

import dev.photobackup.media.PhoneMedia
import java.time.Instant
import java.time.ZoneId

object RemotePathPlanner {
    const val FAT32_MAX_BYTES = 4_294_967_295L

    private const val ILLEGAL = "\\/:*?\"<>|"

    fun targetDir(destination: String, deviceName: String, m: PhoneMedia, zone: ZoneId): String {
        val epochMs = if (m.dateTakenMs > 0) m.dateTakenMs else m.dateAddedSec * 1000
        val date = Instant.ofEpochMilli(epochMs).atZone(zone)
        val dest = destination.replace('/', '\\').trim('\\')
        val parts = listOf(dest, sanitise(deviceName), "%04d".format(date.year), "%02d".format(date.monthValue))
        return parts.filter { it.isNotEmpty() }.joinToString("\\")
    }

    fun sanitise(name: String): String {
        val replaced = buildString {
            for (c in name) append(if (c in ILLEGAL || c.isISOControl()) '_' else c)
        }
        return replaced.trimEnd('.', ' ').ifEmpty { "_" }
    }

    fun clashName(fileName: String, n: Int): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) "$fileName ($n)"
        else "${fileName.substring(0, dot)} ($n)${fileName.substring(dot)}"
    }

    fun partName(fileName: String): String = "$fileName.part"
}
