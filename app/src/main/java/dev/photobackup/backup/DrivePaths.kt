package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import dev.photobackup.media.FolderRules

/** A drive file at today's layout: `<destination>\<device>\<folder>\<4-digit year>\<name>`. */
data class DriveFile(val folder: String, val year: String, val name: String)

/** The one rule for which drive paths the app may move, re-file, bin or delete. */
object DrivePaths {
    /** `<destination>\<device>`, built exactly as [RemotePathPlanner.targetDir] builds it. */
    fun deviceRoot(destination: String?, deviceName: String): String {
        val dest = (destination ?: "").replace('/', '\\').trim('\\')
        return listOf(dest, RemotePathPlanner.sanitise(deviceName)).filter { it.isNotEmpty() }.joinToString("\\")
    }

    /** `<root>\.Bin`: where review's Junk goes. `.Bin` is reserved and never a named folder. */
    fun binRoot(root: String): String = "$root\\${FolderRules.BIN_FOLDER}"

    /**
     * The folder, year and name of [e]'s drive path when it sits under [root] (case-insensitive) in today's shape and
     * no move or `.part` is pending on it; else null. The earlier `<YYYY>\<MM>\<name>` layout never matches (its
     * 2-digit month fails the year check). A phone folder named like a year is today's layout and does match.
     * Bin rows (binRequestedAt set, or BINNED) and anything in the `.Bin` folder never match, so reconcile, pinning,
     * rule counts and the folder picker never see them.
     */
    fun parse(e: LedgerEntry, root: String): DriveFile? {
        if (e.movingTo != null || e.partPath != null) return null
        if (e.binRequestedAt != null || e.status == BackupStatus.BINNED) return null
        return parsePath(e.remotePath ?: return null, root)
    }

    /** [parse] for a bare path: today's shape under [root], never in the `.Bin` folder. */
    fun parsePath(path: String, root: String): DriveFile? {
        val prefix = "$root\\"
        if (!path.startsWith(prefix, ignoreCase = true)) return null
        val parts = path.substring(prefix.length).split('\\')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        if (parts[0].equals(FolderRules.BIN_FOLDER, ignoreCase = true)) return null
        val year = parts[1]
        if (year.length != 4 || !year.all { it in '0'..'9' }) return null
        return DriveFile(parts[0], year, parts[2])
    }

    /**
     * Where a file at [path] goes in the bin: (`<root>\.Bin\<folder>\<year>`, name). Null unless [path] is in today's
     * shape under [root] (an old layout, another destination or device name is never binned).
     */
    fun binTarget(path: String, root: String): Pair<String, String>? =
        parsePath(path, root)?.let { "${binRoot(root)}\\${it.folder}\\${it.year}" to it.name }

    /**
     * Whether [path] is a file strictly inside the CURRENT `<root>\.Bin\`: case-insensitive prefix, no `/`, and no
     * empty segment or one made only of dots and spaces (`.`, `..`, `...`, `. `) after it, nor in [root] itself. The
     * only paths the bin may ever delete.
     */
    fun isInBin(path: String?, root: String): Boolean {
        if (path == null || root.isEmpty() || '/' in path || '/' in root) return false
        if (root.split('\\').any { dotsOnly(it) }) return false
        val prefix = binRoot(root) + "\\"
        if (path.length <= prefix.length || !path.startsWith(prefix, ignoreCase = true)) return false
        return path.substring(prefix.length).split('\\').none { dotsOnly(it) }
    }

    private fun dotsOnly(segment: String) = segment.trim('.', ' ').isEmpty()
}
