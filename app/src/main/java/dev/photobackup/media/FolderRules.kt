package dev.photobackup.media

import dev.photobackup.backup.RemotePathPlanner

/** Pure resolver for named drive folders: hand-picked folder > folder rule > default (null = source folder). */
object FolderRules {
    /** A folder name as it is stored and used on the drive, or null when blank. */
    fun canonical(name: String): String? {
        val t = name.trim()
        if (t.isEmpty()) return null
        return RemotePathPlanner.sanitise(t).trim().ifEmpty { null }
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
