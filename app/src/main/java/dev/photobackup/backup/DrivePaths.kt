package dev.photobackup.backup

import dev.photobackup.ledger.LedgerEntry

/** A drive file at today's layout: `<destination>\<device>\<folder>\<4-digit year>\<name>`. */
data class DriveFile(val folder: String, val year: String, val name: String)

/** The one rule for which drive paths the app may move or re-file. */
object DrivePaths {
    /** `<destination>\<device>`, built exactly as [RemotePathPlanner.targetDir] builds it. */
    fun deviceRoot(destination: String?, deviceName: String): String {
        val dest = (destination ?: "").replace('/', '\\').trim('\\')
        return listOf(dest, RemotePathPlanner.sanitise(deviceName)).filter { it.isNotEmpty() }.joinToString("\\")
    }

    /**
     * The folder, year and name of [e]'s drive path when it sits under [root] (case-insensitive) in today's shape and
     * no move or `.part` is pending on it; else null. The earlier `<YYYY>\<MM>\<name>` layout never matches (its
     * 2-digit month fails the year check). A phone folder named like a year is today's layout and does match.
     */
    fun parse(e: LedgerEntry, root: String): DriveFile? {
        if (e.movingTo != null || e.partPath != null) return null
        val path = e.remotePath ?: return null
        val prefix = "$root\\"
        if (!path.startsWith(prefix, ignoreCase = true)) return null
        val parts = path.substring(prefix.length).split('\\')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        val year = parts[1]
        if (year.length != 4 || !year.all { it in '0'..'9' }) return null
        return DriveFile(parts[0], year, parts[2])
    }
}
