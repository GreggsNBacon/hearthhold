package dev.photobackup.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalMediaSourceOpenerTest {
    private val media = PhoneMedia(1, "content://media/external/images/media/1", "a.jpg", "DCIM/Camera/", 1, 1, 1, 1, false, "image/jpeg")

    private fun sourceThrowing(e: () -> Throwable): LocalMediaSource {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(ctx.contentResolver).registerInputStreamSupplier(Uri.parse(media.uri)) { throw e() }
        return LocalMediaSource(ctx)
    }

    @Test fun securityExceptionReturnsNull() =
        assertNull(sourceThrowing { SecurityException("denied") }.opener().open(media))

    @Test fun illegalStateReturnsNull() =
        assertNull(sourceThrowing { IllegalStateException("gone") }.opener().open(media))
}
