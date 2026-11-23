package dev.photobackup.gallery

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap

/**
 * The neutral tile shown for a video whose frame can't be read (a format the phone can't decode): a grey square with
 * a play mark, never an error badge. Used for drive thumbnails ([SmbCoilFetcher]) and phone ones ([Factory] around
 * Coil's VideoFrameDecoder).
 */
object VideoPlaceholder {
    const val PX = 256
    private const val BG = 0xFF3A3A3A.toInt()
    private const val MARK = 0xFFBDBDBD.toInt()

    /** Every placeholder made, weakly held, so [isPlaceholder] knows them by identity (no pixel guessing). */
    private val made: MutableSet<Bitmap> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** A new bitmap each time: Coil and [SmbCoilFetcher] may recycle or cache what they are given. */
    fun bitmap(): Bitmap {
        val b = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(BG)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARK; style = Paint.Style.FILL }
        val s = PX / 2f
        val r = PX * 0.16f
        val tri = Path().apply {
            moveTo(s - r * 0.7f, s - r)
            lineTo(s + r, s)
            lineTo(s - r * 0.7f, s + r)
            close()
        }
        c.drawPath(tri, p)
        made += b
        return b
    }

    /** True for a bitmap made by [bitmap]. */
    fun isPlaceholder(b: Bitmap): Boolean = b in made

    /**
     * The frame from [read], or the placeholder when there is none or the retriever gave up on the file. When
     * [sourceFailed] says a drive read failed underneath, it is a drive problem instead: thrown as [IOException] so
     * the thumbnail retries later rather than settling on a placeholder.
     */
    fun frameOrPlaceholder(read: () -> Bitmap?, sourceFailed: () -> Boolean): Bitmap {
        val frame = try {
            read()
        } catch (e: RuntimeException) {
            if (sourceFailed()) throw IOException("Can't read the video from the drive", e)
            null
        }
        if (frame != null) return frame
        if (sourceFailed()) throw IOException("Can't read the video from the drive")
        return bitmap()
    }

    /** Runs [delegate]; any failure but cancellation becomes the placeholder. */
    class OnFailure(private val resources: Resources? = null, private val delegate: () -> Decoder) : Decoder {
        override suspend fun decode(): DecodeResult? = try {
            delegate().decode()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DecodeResult(BitmapDrawable(resources, bitmap()), isSampled = false)
        }
    }

    /** Wraps a decoder factory (Coil's VideoFrameDecoder): what it accepts, it decodes or shows the placeholder. */
    class Factory(private val delegate: Decoder.Factory) : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val d = delegate.create(result, options, imageLoader) ?: return null
            return OnFailure(options.context.resources) { d }
        }
    }
}
