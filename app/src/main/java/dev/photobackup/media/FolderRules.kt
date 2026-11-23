package dev.photobackup.media

import dev.photobackup.backup.RemotePathPlanner

/** Pure resolver for named drive folders: hand-picked folder > folder rule > default (null = source folder). */
object FolderRules {
    /** Reserved drive folder holding binned photos; never a valid named folder. */
    const val BIN_FOLDER = ".Bin"

    /** Shown when a typed folder name is the reserved bin folder. */
    const val RESERVED_MESSAGE = "\".Bin\" is reserved for the bin"

    /** True when [name], as it would be stored on the drive, is the reserved bin folder (any case). */
    fun isReserved(name: String): Boolean =
        RemotePathPlanner.sanitise(name.trim()).trim().equals(BIN_FOLDER, ignoreCase = true)

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

    /**
     * Hand-pick, else rule, else null (source folder). A `.Bin` hand-pick or rule (any case; saved by v1.1.0, before
     * the name was reserved) counts as none, so nothing is ever filed into the bin folder.
     */
    fun desired(relativePath: String, assignment: String?, rules: Map<String, String>): String? =
        assignment?.takeUnless { isReserved(it) } ?: ruleFor(relativePath, rules.filterValues { !isReserved(it) })
}
