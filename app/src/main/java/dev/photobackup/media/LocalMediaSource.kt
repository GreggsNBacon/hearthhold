package dev.photobackup.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.photobackup.backup.LocalOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

data class SourceFolders(
    val camera: Boolean = true,
    val screenshots: Boolean = true,
    val extraRelativePaths: Set<String> = emptySet(),
) {
    fun matches(relativePath: String): Boolean {
        val path = relativePath.withTrailingSlash()
        if (camera && path.startsWith("DCIM/Camera/", ignoreCase = true)) return true
        if (screenshots && path.split('/').any { it.equals("Screenshots", ignoreCase = true) }) return true
        return extraRelativePaths.any { it.isNotBlank() && path.startsWith(it.trim().withTrailingSlash()) }
    }

    private fun String.withTrailingSlash() = if (isEmpty() || endsWith("/")) this else "$this/"
}

class LocalMediaSource(
    private val context: Context,
    private val access: () -> MediaAccess = { MediaAccess.current(context) },
) {

    /**
     * Phone photos and videos in [folders]. Without full photo access MediaStore does not fail, it just returns
     * fewer (or no) items, which would look like "nothing to back up". So this checks first and throws
     * [SecurityException] (no access) or [PartialMediaAccessException] ("Select photos" only).
     */
    suspend fun query(folders: SourceFolders): List<PhoneMedia> = withContext(Dispatchers.IO) {
        when (access()) {
            MediaAccess.FULL -> Unit
            MediaAccess.PARTIAL -> throw PartialMediaAccessException()
            MediaAccess.NONE -> throw SecurityException("No photo access")
        }
        queryCollection(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), false, folders) +
            queryCollection(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, folders)
    }

    /**
     * Item counts per phone folder (MediaStore relative path, e.g. "Pictures/WhatsApp/") across photos and videos.
     * Needs full access like [query]: with "Select photos" the list would be incomplete.
     */
    suspend fun folderCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        when (access()) {
            MediaAccess.FULL -> Unit
            MediaAccess.PARTIAL -> throw PartialMediaAccessException()
            MediaAccess.NONE -> throw SecurityException("No photo access")
        }
        PhoneFolders.merge(
            countFolders(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)),
            countFolders(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)),
        )
    }

    private fun countFolders(collection: Uri): Map<String, Int> =
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
            ?.use { PhoneFolders.count(it) }.orEmpty()

    private fun queryCollection(collection: Uri, isVideo: Boolean, folders: SourceFolders): List<PhoneMedia> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val out = ArrayList<PhoneMedia>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val rel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (c.moveToNext()) {
                val relPath = c.getString(rel) ?: ""
                if (!folders.matches(relPath)) continue
                val mediaId = c.getLong(id)
                out += PhoneMedia(
                    mediaId = mediaId,
                    uri = Uri.withAppendedPath(collection, mediaId.toString()).toString(),
                    displayName = c.getString(name) ?: "",
                    relativePath = relPath,
                    sizeBytes = c.getLong(size),
                    dateTakenMs = c.getLong(taken),
                    dateAddedSec = c.getLong(added),
                    dateModifiedSec = c.getLong(modified),
                    isVideo = isVideo,
                    mimeType = c.getString(mime) ?: "",
                )
            }
        }
        return out
    }

    fun opener(): LocalOpener = LocalOpener { m ->
        try {
            context.contentResolver.openInputStream(Uri.parse(m.uri))
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    /** Images and videos on external storage, for WorkManager content-URI triggers. */
    fun contentUris(): List<Uri> = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    )
}
