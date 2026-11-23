package dev.photobackup.gallery

import java.util.Locale

/** Where a gallery item lives. */
enum class Location { PHONE, DRIVE, BOTH }

/**
 * One photo or video in the Gallery or Browse list (and the viewer opened from them).
 * [phoneUri] is a `content://` URI when a phone copy exists; [remotePath] is share-relative (`\`-separated)
 * when a drive copy exists.
 */
data class GalleryItem(
    val key: String,
    val displayName: String,
    val dateMs: Long,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val location: Location,
    val phoneUri: String?,
    val remotePath: String?,
)

/** What a drive file is, judged by its extension. */
enum class MediaKind {
    DIR, IMAGE, VIDEO, OTHER;

    val isMedia: Boolean get() = this == IMAGE || this == VIDEO

    companion object {
        private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif")
        private val VIDEOS = setOf("mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm", "avi", "ts")

        fun extension(name: String): String =
            if ('.' in name) name.substringAfterLast('.').lowercase(Locale.ROOT) else ""

        fun of(name: String): MediaKind {
            val ext = extension(name)
            return when (ext) {
                in IMAGES -> IMAGE
                in VIDEOS -> VIDEO
                else -> OTHER
            }
        }
    }
}

/**
 * Hands the list a viewer was opened from to the viewer screen (Task 11): `viewer/{source}/{index}` names the
 * list by [source] ("gallery" or "browse") and the position in it. Lives in AppContainer, so it outlives screens
 * but not the process; the viewer should pop back if its list is gone.
 */
class ViewerListHolder {
    @Volatile private var lists: Map<String, List<GalleryItem>> = emptyMap()

    fun set(source: String, items: List<GalleryItem>) {
        lists = lists + (source to items)
    }

    fun get(source: String): List<GalleryItem>? = lists[source]

    companion object {
        const val GALLERY = "gallery"
        const val BROWSE = "browse"
    }
}
