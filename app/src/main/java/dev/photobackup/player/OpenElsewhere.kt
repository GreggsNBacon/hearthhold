package dev.photobackup.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import dev.photobackup.gallery.CopyToPhone
import dev.photobackup.smb.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

const val MSG_FORMAT_UNSUPPORTED = "This format can't play here"
const val MSG_SOUND_UNSUPPORTED = "This video's sound can't play here"
const val LABEL_OPEN_ELSEWHERE = "Open in another app"
const val MSG_NO_APP = "No app on this phone can open this video"
const val MSG_TOO_BIG = "This video is over 2 GB: too big to copy for another app"
const val MSG_NO_SPACE = "Not enough space on the phone to copy this video"
const val MSG_COPY_FAILED = "Couldn't copy the video from the drive"

/** A drive video grew past [OpenElsewhere.MAX_COPY_BYTES] while copying (its size was not known up front). */
class TooBigException : IOException("Too big to copy")

/** What the player's tracks say once known. */
enum class TrackVerdict { OK, NO_VIDEO, NO_AUDIO }

/** When the player gives up on a file's format and offers "Open in another app" instead. Pure. */
object PlaybackFallback {
    private val UNSUPPORTED = setOf(
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        // e.g. 4K HEVC on a phone whose decoder tops out lower: another app (software decoder) may manage
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    )

    /** True for a [PlaybackException.errorCode] meaning "this phone can't play this format" (not a drive error). */
    fun wantsFallback(errorCode: Int): Boolean = errorCode in UNSUPPORTED

    /**
     * [hasVideo]/[hasAudio]: the file has such a track; [videoOk]/[audioOk]: a renderer here can play one of them.
     * A video track nobody can decode means no picture: the fallback. Unplayable sound only earns a notice.
     */
    fun verdict(hasVideo: Boolean, videoOk: Boolean, hasAudio: Boolean, audioOk: Boolean): TrackVerdict = when {
        hasVideo && !videoOk -> TrackVerdict.NO_VIDEO
        hasAudio && !audioOk -> TrackVerdict.NO_AUDIO
        else -> TrackVerdict.OK
    }

    fun verdict(tracks: Tracks): TrackVerdict {
        val groups = tracks.groups
        val video = groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        return verdict(video.isNotEmpty(), video.any { it.isSupported }, audio.isNotEmpty(), audio.any { it.isSupported })
    }
}

/**
 * "Open in another app" for a video this player can't play: a phone file goes straight to the other app; a drive
 * file is first copied into the app's cache ([DIR], shared through the FileProvider) and cleaned up a day later.
 */
object OpenElsewhere {
    const val DIR = "open-with"
    const val MAX_COPY_BYTES = 2L * 1024 * 1024 * 1024
    /** Room left on the phone after a copy, so it never fills the phone to the last byte. */
    const val SPACE_RESERVE = 200L * 1024 * 1024
    const val DAY_MS = 24L * 60 * 60 * 1000
    private const val PART = ".part"

    /** Why a drive video of [sizeBytes] (negative: unknown) can't be copied with [freeBytes] free, or null. */
    fun refusal(sizeBytes: Long, freeBytes: Long): String? = when {
        sizeBytes > MAX_COPY_BYTES -> MSG_TOO_BIG
        sizeBytes >= 0 && freeBytes - sizeBytes < SPACE_RESERVE -> MSG_NO_SPACE
        else -> null
    }

    /** A cache copy last touched more than a day before [nowMs] is removed on app start. */
    fun isStale(lastModifiedMs: Long, nowMs: Long): Boolean = nowMs - lastModifiedMs > DAY_MS

    /** Deletes stale copies (and stale half-copies) in [dir]; returns how many. Never throws. */
    fun cleanup(dir: File, nowMs: Long): Int = runCatching {
        dir.listFiles().orEmpty().count { f -> f.isFile && isStale(f.lastModified(), nowMs) && f.delete() }
    }.getOrDefault(0)

    /** The cache file name for [remotePath]: a short hash of the whole path (two `clip.flv` stay apart) + its name. */
    fun cacheName(remotePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(remotePath.lowercase().toByteArray())
        val hash = digest.take(6).joinToString("") { "%02x".format(it) }
        val name = remotePath.substringAfterLast('\\').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_")
        return "$hash-$name"
    }

    fun cacheDir(context: Context): File = File(context.cacheDir, DIR)

    /**
     * Copies [remotePath] into [dir] (reusing a finished copy of the same size), reporting bytes copied to
     * [onProgress]. Writes a `.part` file and renames it when done; any failure or cancel deletes the part. A copy
     * passing [maxBytes] stops with [TooBigException].
     */
    suspend fun copyToCache(
        store: RemoteStore,
        remotePath: String,
        sizeBytes: Long,
        dir: File,
        maxBytes: Long = MAX_COPY_BYTES,
        onProgress: (Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, cacheName(remotePath))
        if (target.isFile && sizeBytes >= 0 && target.length() == sizeBytes) {
            target.setLastModified(System.currentTimeMillis()) // keep it another day
            onProgress(sizeBytes)
            return@withContext target
        }
        val part = File(dir, target.name + PART)
        try {
            store.openRead(remotePath).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw TooBigException()
                        out.write(buf, 0, n)
                        onProgress(total)
                    }
                }
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn't finish the copy")
            target
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    /** MIME for the other app: the phone's own type for a content URI, else by extension. */
    fun mimeFor(context: Context, uri: Uri, displayName: String): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()?.takeIf { it.startsWith("video/") }
            ?: CopyToPhone.mimeType(displayName, isVideo = true)

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Opens [uri] in another app; returns null when it started, else the message to show. Never throws. */
    fun launch(context: Context, uri: Uri, mime: String): String? {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            MSG_NO_APP
        } catch (_: RuntimeException) {
            MSG_NO_APP // e.g. a SecurityException from an app that can't take the URI
        }
    }
}
