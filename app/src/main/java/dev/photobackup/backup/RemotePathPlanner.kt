package dev.photobackup.backup

import dev.photobackup.media.FolderRules
import dev.photobackup.media.PhoneMedia
import java.time.Instant
import java.time.ZoneId

object RemotePathPlanner {
    const val FAT32_MAX_BYTES = 4_294_967_295L

    private const val ILLEGAL = "\\/:*?\"<>|"

    fun targetDir(destination: String, deviceName: String, m: PhoneMedia, zone: ZoneId, folder: String? = null): String {
        val epochMs = if (m.dateTakenMs > 0) m.dateTakenMs else m.dateAddedSec * 1000
        val date = Instant.ofEpochMilli(epochMs).atZone(zone)
        val dest = destination.replace('/', '\\').trim('\\')
        val source = if (folder != null) sanitise(folder) else sourceFolder(m.relativePath)
        val parts = listOf(dest, sanitise(deviceName), source, date.year.toString())
        return parts.filter { it.isNotEmpty() }.joinToString("\\")
    }

    /**
     * The default folder segment: the phone folder's own name, sanitised. A phone folder named `.Bin` becomes `_Bin`:
     * that name is reserved for the drive bin.
     */
    fun sourceFolder(relativePath: String): String {
        val last = relativePath.split('/').lastOrNull { it.isNotBlank() } ?: return "Other"
        val s = sanitise(last)
        return if (s.equals(FolderRules.BIN_FOLDER, ignoreCase = true)) "_" + s.substring(1) else s
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
