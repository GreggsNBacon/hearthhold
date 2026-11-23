package dev.photobackup.media

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaAccessTest {
    private val images = Manifest.permission.READ_MEDIA_IMAGES
    private val video = Manifest.permission.READ_MEDIA_VIDEO
    private val selected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private val legacy = Manifest.permission.READ_EXTERNAL_STORAGE

    private fun of(sdk: Int, vararg granted: String) = MediaAccess.of(sdk) { it in granted }

    @Test fun android13PlusNeedsBothPhotosAndVideos() {
        assertEquals(MediaAccess.FULL, of(34, images, video))
        assertEquals(MediaAccess.FULL, of(33, images, video))
        assertEquals(MediaAccess.PARTIAL, of(33, images))
        assertEquals(MediaAccess.NONE, of(33))
    }

    @Test fun selectPhotosOnAndroid14IsPartial() {
        assertEquals(MediaAccess.PARTIAL, of(34, selected))
        assertEquals(MediaAccess.NONE, of(33, selected)) // no such grant before 14
    }

    @Test fun android12AndOlderUsesReadExternalStorage() {
        assertEquals(MediaAccess.FULL, of(32, legacy))
        assertEquals(MediaAccess.NONE, of(30))
        assertEquals(MediaAccess.NONE, of(32, images, video))
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun deniedPermissionReadsAsNone() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).denyPermissions(images, video, selected)
        assertEquals(MediaAccess.NONE, MediaAccess.current(ctx))
    }

    @Test fun grantedPermissionReadsAsFull() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(images, video)
        assertEquals(MediaAccess.FULL, MediaAccess.current(ctx))
    }

    // MediaStore does not fail without permission (it just returns nothing), so the query must.
    @Test fun queryWithoutAccessThrowsInsteadOfReturningNothing() = runBlocking {
        try {
            LocalMediaSource(ctx) { MediaAccess.NONE }.query(SourceFolders())
            fail("expected SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e !is PartialMediaAccessException)
        }
    }

    @Test fun queryWithPartialAccessThrowsPartial() = runBlocking {
        try {
            LocalMediaSource(ctx) { MediaAccess.PARTIAL }.query(SourceFolders())
            fail("expected PartialMediaAccessException")
        } catch (_: PartialMediaAccessException) {
        }
    }

    @Test fun queryWithFullAccessRuns() = runBlocking {
        assertEquals(0, LocalMediaSource(ctx) { MediaAccess.FULL }.query(SourceFolders()).size)
    }
}
