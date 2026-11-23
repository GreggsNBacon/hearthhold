package dev.photobackup.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbLocation
import dev.photobackup.smb.SmbUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** The file was read but isn't a decodable image (or has no video frame): not a drive problem. */
class ImageDecodeException(msg: String = "Can't decode image") : IOException(msg)

/** Pure thumbnail helpers (JVM-tested). */
object Thumbnails {
    const val DEFAULT_PX = 512

    /** Largest power of two that keeps the decoded image at least [reqW]×[reqH]. */
    fun sampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (reqW <= 0 || reqH <= 0 || srcW <= 0 || srcH <= 0) return 1
        var s = 1
        while (srcW / (s * 2) >= reqW && srcH / (s * 2) >= reqH) s *= 2
        return s
    }

    /** Disk-cache key: the `smb://` URI (host, share, path) plus the requested size. */
    fun cacheKey(uri: String, w: Int, h: Int) = "smb-thumb:$uri@${w}x$h"
}

/**
 * Random-access reads over [open] (a `RemoteStore.openRead(offset)`): keeps one stream while reads are sequential
 * and reopens at the new offset on a seek. Returns -1 at end of file.
 */
class RangeReader(private val open: (offset: Long) -> InputStream) : Closeable {
    private var stream: InputStream? = null
    private var pos = -1L

    @Synchronized
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        var s = stream
        if (s == null || pos != position) {
            runCatching { s?.close() }
            stream = null
            s = open(position)
            stream = s
            pos = position
        }
        var total = 0
        while (total < size) {
            val n = s.read(buffer, offset + total, size - total)
            if (n < 0) break
            total += n
        }
        pos += total
        return if (total == 0) -1 else total
    }

    @Synchronized
    override fun close() {
        runCatching { stream?.close() }
        stream = null
    }
}

/** A drive video for [MediaMetadataRetriever], read through the store (and its saved login). */
private class StoreMediaDataSource(private val size: Long, open: (Long) -> InputStream) : MediaDataSource() {
    private val reader = RangeReader(open)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)

    override fun getSize(): Long = size
    override fun close() = reader.close()
}

/**
 * Coil [Fetcher] for `smb://host/share/path` data (see [SmbUri]): thumbnails of drive photos and videos.
 *
 * - A disk-cache hit (key [Thumbnails.cacheKey]) is served without touching the drive, so cached thumbnails still
 *   show when the drive is not reachable.
 * - A request whose `networkCachePolicy` is disabled is served from the disk cache only: the Gallery uses this while
 *   the drive is not (yet) known to be reachable, so thumbnails never trigger connection attempts or prompts.
 * - Photos are decoded with `inSampleSize` from two reads: the first 256 KiB (bounds + EXIF orientation), then the
 *   file. Videos use [MediaMetadataRetriever] over a [MediaDataSource] backed by `RemoteStore.openRead(offset)`.
 *
 * Concurrency: all thumbnail drive calls go through ONE store (an `AuthRetryingStore`, shared by [Factory]) and
 * are serialised by [lock]. A stream from `openRead` only lives inside one locked fetch, so a reconnect triggered
 * by another fetch can never kill it.
 */
class SmbCoilFetcher(
    private val data: Uri,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val store: RemoteStore,
    private val lock: Mutex,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val uri = data.toString()
        val loc = SmbUri.parse(uri) ?: throw IOException("Not an smb:// URI")
        val w = (options.size.width as? Dimension.Pixels)?.px ?: Thumbnails.DEFAULT_PX
        val h = (options.size.height as? Dimension.Pixels)?.px ?: Thumbnails.DEFAULT_PX
        val key = Thumbnails.cacheKey(uri, w, h)
        val diskCache = imageLoader.diskCache

        fun cached(): FetchResult? {
            if (!options.diskCachePolicy.readEnabled || diskCache == null) return null
            val snap = diskCache.openSnapshot(key) ?: return null
            return SourceResult(
                source = ImageSource(snap.data, diskCache.fileSystem, key, snap),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }

        cached()?.let { return it }
        if (!options.networkCachePolicy.readEnabled) throw IOException("Drive not reachable")

        val bitmap = lock.withLock {
            // Another request for the same thumbnail may have filled the cache while this one waited.
            cached()?.let { return it }
            withRetry { withContext(Dispatchers.IO) { decode(loc, w, h) } }
        }

        if (options.diskCachePolicy.writeEnabled && diskCache != null) {
            withContext(Dispatchers.IO) {
                diskCache.openEditor(key)?.let { editor ->
                    try {
                        diskCache.fileSystem.write(editor.data) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream())
                        }
                        editor.commit()
                    } catch (e: Exception) {
                        runCatching { editor.abort() }
                    }
                }
            }
        }
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.NETWORK,
        )
    }

    /**
     * One retry for a dropped connection (an idle session the server closed): the store has already discarded it
     * and reconnects. Not for a refused login, a missing file or an undecodable image.
     */
    private suspend fun <T> withRetry(block: suspend () -> T): T = try {
        block()
    } catch (_: RemoteException.Unreachable) {
        block()
    }

    private suspend fun decode(loc: SmbLocation, w: Int, h: Int): Bitmap {
        val bmp = if (MediaKind.of(loc.path) == MediaKind.VIDEO) decodeVideo(loc.path, w, h) else decodeImage(loc.path, w, h)
        return shrink(bmp, w, h)
    }

    private suspend fun decodeImage(path: String, w: Int, h: Int): Bitmap {
        val head = store.openRead(path).use { readUpTo(it, HEAD_BYTES) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(head, 0, head.size, bounds)
        val whole = head.size < HEAD_BYTES
        val opts = BitmapFactory.Options().apply {
            inSampleSize = when {
                bounds.outWidth > 0 && bounds.outHeight > 0 -> Thumbnails.sampleSize(bounds.outWidth, bounds.outHeight, w, h)
                whole -> 1
                else -> 4 // size unknown from the header: a big file, don't decode it at full size
            }
        }
        val bmp = if (whole) {
            BitmapFactory.decodeByteArray(head, 0, head.size, opts)
        } else {
            store.openRead(path).use { BitmapFactory.decodeStream(it.buffered(64 * 1024), null, opts) }
        } ?: throw ImageDecodeException()
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(head)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    private suspend fun decodeVideo(path: String, w: Int, h: Int): Bitmap {
        val size = store.stat(path)?.sizeBytes ?: -1L
        // MediaMetadataRetriever calls readAt synchronously from its own thread: block there for each (re)open.
        val source = StoreMediaDataSource(size) { off -> runBlocking { store.openRead(path, off) } }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source)
            return retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
                ?: retriever.frameAtTime
                ?: throw ImageDecodeException("Can't read a video frame")
        } catch (e: RuntimeException) {
            throw IOException("Can't read a video frame", e)
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    /** Keeps cached thumbnails small: at most twice the requested size. */
    private fun shrink(bmp: Bitmap, w: Int, h: Int): Bitmap {
        val scale = minOf(2f * w / bmp.width, 2f * h / bmp.height)
        if (scale >= 1f) return bmp
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = input.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        return if (n == max) buf else buf.copyOf(n)
    }

    /**
     * One per ImageLoader; [store] is the app's shared thumbnail store. A request with parameter [FULL_SIZE] = true
     * (the viewer's full-screen photos) uses [fullSizeStore] and its own lock instead, so a slow full-size read never
     * holds up grid thumbnails and a thumbnail reconnect never kills it.
     */
    class Factory(
        private val store: () -> RemoteStore,
        private val fullSizeStore: () -> RemoteStore = store,
    ) : Fetcher.Factory<Uri> {
        private val lock = Mutex()
        private val fullSizeLock = Mutex()

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.scheme.equals(SmbUri.SCHEME, ignoreCase = true)) return null
            return if (options.parameters.value<Boolean>(FULL_SIZE) == true) {
                SmbCoilFetcher(data, options, imageLoader, fullSizeStore(), fullSizeLock)
            } else {
                SmbCoilFetcher(data, options, imageLoader, store(), lock)
            }
        }
    }

    companion object {
        /** Coil request parameter: a full-screen viewer photo rather than a thumbnail (see [Factory]). */
        const val FULL_SIZE = "smb.fullSize"
        private const val HEAD_BYTES = 256 * 1024
    }
}
