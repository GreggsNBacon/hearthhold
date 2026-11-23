package dev.photobackup.media

import android.database.Cursor

/** A phone folder that directly holds photos or videos, for the "Add folder" browser. */
data class PhoneFolder(
    /** MediaStore relative path with a trailing slash, e.g. "Pictures/WhatsApp/" — the format settings store. */
    val path: String,
    val count: Int,
    /** Already backed up by Camera, Screenshots or an added folder. */
    val included: Boolean,
)

object PhoneFolders {
    /** Normalises a typed or MediaStore path to "A/B/" (no leading slash, one trailing slash); "" for the root. */
    fun normalise(path: String): String {
        val p = path.trim().trim('/')
        return if (p.isEmpty()) "" else "$p/"
    }

    /** Item counts per folder from MediaStore RELATIVE_PATH values. Files at the storage root are skipped. */
    fun count(relativePaths: Sequence<String?>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (raw in relativePaths) {
            val p = normalise(raw ?: continue)
            if (p.isNotEmpty()) out[p] = (out[p] ?: 0) + 1
        }
        return out
    }

    /** Reads the RELATIVE_PATH column of [cursor] (the cursor is not closed). */
    fun count(cursor: Cursor): Map<String, Int> {
        val col = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
        return count(generateSequence { if (cursor.moveToNext()) cursor.getString(col) ?: "" else null })
    }

    /** Merges per-collection counts (images + videos). */
    fun merge(vararg counts: Map<String, Int>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (m in counts) for ((k, v) in m) out[k] = (out[k] ?: 0) + v
        return out
    }

    /** Flat list sorted by path (case-insensitive), marking folders [folders] already backs up. */
    fun list(counts: Map<String, Int>, folders: SourceFolders): List<PhoneFolder> =
        counts.map { (path, n) -> PhoneFolder(path, n, folders.matches(path)) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
}
