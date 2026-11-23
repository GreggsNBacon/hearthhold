package dev.photobackup.media

import dev.photobackup.backup.RemotePathPlanner

/** Pure resolver for named drive folders: hand-picked folder > folder rule > default (null = source folder). */
object FolderRules {
    /** Reserved drive folder holding binned photos; never a valid named folder. */
    const val BIN_FOLDER = ".Bin"

    /** A folder name as it is stored and used on the drive, or null when blank or reserved (.Bin). */
    fun canonical(name: String): String? {
        val t = name.trim()
        if (t.isEmpty()) return null
        val c = RemotePathPlanner.sanitise(t).trim().ifEmpty { null } ?: return null
        return if (c.equals(BIN_FOLDER, ignoreCase = true)) null else c
    }

    fun same(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

    /** The folder named by the rule with the longest matching path prefix, or null. */
    fun ruleFor(relativePath: String, rules: Map<String, String>): String? {
        val path = PhoneFolders.normalise(relativePath)
        var best: String? = null
        var bestLen = -1
        for ((k, v) in rules) {
            val prefix = PhoneFolders.normalise(k)
            if (prefix.isEmpty()) continue
            if (path.startsWith(prefix, ignoreCase = true) && prefix.length > bestLen) {
                best = v
                bestLen = prefix.length
            }
        }
        return best
    }

    fun desired(relativePath: String, assignment: String?, rules: Map<String, String>): String? =
        assignment ?: ruleFor(relativePath, rules)
}
