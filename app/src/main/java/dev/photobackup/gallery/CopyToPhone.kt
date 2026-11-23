package dev.photobackup.gallery

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/** The MediaStore steps "Copy to phone" needs; [ResolverMediaWriter] is the real one. URIs are strings for JVM tests. */
interface PhoneMediaWriter {
    /** Inserts a hidden (`IS_PENDING`) row. MediaStore picks a free name if [displayName] is taken: never overwrites. */
    fun createPending(displayName: String, mimeType: String, relativePath: String, isVideo: Boolean): String
    fun openOutput(uri: String): OutputStream
    /** Clears `IS_PENDING`: the file appears in galleries. */
    fun publish(uri: String)
    /** Deletes the half-written row. */
    fun discard(uri: String)
}

class ResolverMediaWriter(private val resolver: ContentResolver) : PhoneMediaWriter {
    override fun createPending(displayName: String, mimeType: String, relativePath: String, isVideo: Boolean): String {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return (resolver.insert(collection, values) ?: throw IOException("Couldn't create the file on the phone"))
            .toString()
    }

    override fun openOutput(uri: String): OutputStream =
        resolver.openOutputStream(Uri.parse(uri), "w") ?: throw IOException("Couldn't write the file on the phone")

    override fun publish(uri: String) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(Uri.parse(uri), values, null, null)
    }

    override fun discard(uri: String) {
        runCatching { resolver.delete(Uri.parse(uri), null, null) }
    }
}

/** "Copy to phone" from Browse: drive file → `Pictures/Hearthhold/` or `Movies/Hearthhold/`. */
object CopyToPhone {
    const val PICTURES_DIR = "Pictures/Hearthhold/"
    const val MOVIES_DIR = "Movies/Hearthhold/"

    private val MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        "avif" to "image/avif",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2", "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
        "ts" to "video/mp2t",
        "flv" to "video/x-flv", "f4v" to "video/mp4", "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
        "vob" to "video/mpeg", "ogv" to "video/ogg", "m2ts" to "video/mp2t", "mts" to "video/mp2t",
        "wmv" to "video/x-ms-wmv", "asf" to "video/x-ms-asf", "divx" to "video/x-msvideo",
    )

    fun mimeType(name: String, isVideo: Boolean): String =
        MIME[MediaKind.extension(name)]?.takeIf { it.startsWith(if (isVideo) "video/" else "image/") }
            ?: if (isVideo) "video/mp4" else "image/jpeg"

    /** Copies [remotePath] through [store] (its saved login) into the phone's MediaStore; returns the new item. */
    suspend fun copy(context: Context, store: RemoteStore, remotePath: String, isVideo: Boolean): Uri =
        Uri.parse(copy(ResolverMediaWriter(context.contentResolver), store, remotePath, isVideo))

    internal suspend fun copy(writer: PhoneMediaWriter, store: RemoteStore, remotePath: String, isVideo: Boolean): String {
        val name = remotePath.substringAfterLast('\\')
        return withContext(Dispatchers.IO) {
            val uri = writer.createPending(name, mimeType(name, isVideo), if (isVideo) MOVIES_DIR else PICTURES_DIR, isVideo)
            try {
                store.openRead(remotePath).use { input ->
                    writer.openOutput(uri).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                writer.publish(uri)
                uri
            } catch (t: Throwable) {
                writer.discard(uri) // also on cancel: never leave a hidden half-copy behind
                throw t
            }
        }
    }
}
