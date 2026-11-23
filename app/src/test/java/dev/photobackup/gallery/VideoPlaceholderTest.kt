package dev.photobackup.gallery

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoPlaceholderTest {
    private val frame = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private fun isPlaceholder(b: Bitmap) = VideoPlaceholder.isPlaceholder(b)

    @Test fun frameIsUsedWhenRead() {
        assertSame(frame, VideoPlaceholder.frameOrPlaceholder({ frame }, { false }))
    }

    @Test fun placeholderWhenNoFrame() {
        assertTrue(isPlaceholder(VideoPlaceholder.frameOrPlaceholder({ null }, { false })))
    }

    @Test fun placeholderWhenRetrieverThrows() {
        val b = VideoPlaceholder.frameOrPlaceholder({ throw IllegalArgumentException("unsupported") }, { false })
        assertTrue(isPlaceholder(b))
        assertEquals(VideoPlaceholder.PX, b.width)
    }

    /** A drive read failed under the retriever: that is a drive problem, not an unreadable video. */
    @Test fun driveFailureStaysAnError() {
        try {
            VideoPlaceholder.frameOrPlaceholder({ throw RuntimeException("setDataSource failed") }, { true })
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    private class FakeDecoder(private val result: () -> DecodeResult?) : Decoder {
        override suspend fun decode(): DecodeResult? = result()
    }

    @Test fun phoneDecoderFailureGivesPlaceholder() = runTest {
        val wrapped = VideoPlaceholder.OnFailure { FakeDecoder { throw IllegalStateException("no frame") } }
        val r = wrapped.decode()!!
        assertTrue(isPlaceholder((r.drawable as BitmapDrawable).bitmap))
    }

    @Test fun phoneDecoderSuccessPassesThrough() = runTest {
        val ok = DecodeResult(BitmapDrawable(null as android.content.res.Resources?, frame), isSampled = true)
        val r = VideoPlaceholder.OnFailure { FakeDecoder { ok } }.decode()
        assertSame(ok, r)
    }

    @Test fun factoryDeclinesWhatDelegateDeclines() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val factory = VideoPlaceholder.Factory(Decoder.Factory { _: SourceResult, _: Options, _: ImageLoader -> null })
        val dummy = SourceResult(
            source = coil.decode.ImageSource(okio.Buffer(), context),
            mimeType = "video/x-flv",
            dataSource = coil.decode.DataSource.DISK,
        )
        assertNull(factory.create(dummy, Options(context), ImageLoader(context)))
    }
}
