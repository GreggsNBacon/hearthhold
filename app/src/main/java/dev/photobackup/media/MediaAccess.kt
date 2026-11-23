package dev.photobackup.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** How much of the photo library the app may read. */
enum class MediaAccess {
    /** Every photo and video. */
    FULL,

    /** Android 14+ "Select photos" (or only one of photos/videos): MediaStore returns just some items. */
    PARTIAL,

    /** No access: MediaStore returns only the app's own items (none), without an error. */
    NONE;

    companion object {
        /** Pure: [granted] says whether a permission is granted. */
        fun of(sdk: Int, granted: (String) -> Boolean): MediaAccess {
            if (sdk < 33) return if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) FULL else NONE
            val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
            val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
            val selected = sdk >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            return when {
                images && videos -> FULL
                images || videos || selected -> PARTIAL
                else -> NONE
            }
        }

        fun current(context: Context): MediaAccess = of(Build.VERSION.SDK_INT) {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

/** Only some photos may be read ("Select photos"): a backup would silently miss the rest. */
class PartialMediaAccessException : SecurityException("Partial photo access")
