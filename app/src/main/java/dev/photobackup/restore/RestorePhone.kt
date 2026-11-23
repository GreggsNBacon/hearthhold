package dev.photobackup.restore

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import dev.photobackup.gallery.PhoneMediaWriter
import dev.photobackup.gallery.ResolverMediaWriter
import dev.photobackup.media.PhoneMedia
import java.io.FileNotFoundException
import java.io.InputStream

/** [RestorePhone] on the real MediaStore. Writes need no permission (own items); [findSame] needs photo access. */
class ResolverRestorePhone(private val resolver: ContentResolver) : RestorePhone {
    override val writer: PhoneMediaWriter = ResolverMediaWriter(resolver)

    private val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.MIME_TYPE,
    )

    /**
     * DATE_TAKEN is honoured by MediaStore for items this app owns; DATE_MODIFIED is usually replaced by the file's
     * own time when the item is published. Either being ignored is fine: the ledger records what MediaStore keeps.
     */
    override fun setDates(uri: String, dateTakenMs: Long, dateModifiedSec: Long) {
        val values = ContentValues().apply {
            if (dateTakenMs > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMs)
            if (dateModifiedSec > 0) put(MediaStore.MediaColumns.DATE_MODIFIED, dateModifiedSec)
        }
        if (values.size() > 0) resolver.update(Uri.parse(uri), values, null, null)
    }

    override fun openInput(uri: String): InputStream? = try {
        resolver.openInputStream(Uri.parse(uri))
    } catch (_: FileNotFoundException) {
        null
    }

    override fun item(uri: String, isVideo: Boolean): PhoneMedia? =
        resolver.query(Uri.parse(uri), projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) read(c, uri, isVideo) else null
        }

    override fun findSame(displayName: String, sizeBytes: Long, isVideo: Boolean): List<PhoneMedia> {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        val where = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
        val args = arrayOf(displayName, sizeBytes.toString())
        val out = ArrayList<PhoneMedia>()
        resolver.query(collection, projection, where, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                out += read(c, Uri.withAppendedPath(collection, id.toString()).toString(), isVideo)
            }
        }
        return out
    }

    override fun freeBytes(): Long = StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes

    private fun read(c: android.database.Cursor, uri: String, isVideo: Boolean) = PhoneMedia(
        mediaId = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
        uri = uri,
        displayName = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "",
        relativePath = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)) ?: "",
        sizeBytes = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
        dateTakenMs = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)),
        dateAddedSec = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)),
        dateModifiedSec = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)),
        isVideo = isVideo,
        mimeType = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: "",
    )
}
