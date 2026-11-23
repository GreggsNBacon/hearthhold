package dev.photobackup.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFolderFilterTest {
    @Test fun cameraMatches() = assertTrue(SourceFolders().matches("DCIM/Camera/"))
    @Test fun cameraCaseInsensitive() = assertTrue(SourceFolders().matches("dcim/camera/sub/"))
    @Test fun screenshotsMatchAnyParent() {
        assertTrue(SourceFolders().matches("Pictures/Screenshots/"))
        assertTrue(SourceFolders().matches("DCIM/Screenshots/"))
    }
    @Test fun whatsappOffByDefault() =
        assertFalse(SourceFolders().matches("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
    @Test fun extraPathMatches() = assertTrue(SourceFolders(extraRelativePaths = setOf("Movies/")).matches("Movies/"))
    @Test fun extraPathWithoutTrailingSlash() =
        assertTrue(SourceFolders(extraRelativePaths = setOf("Movies")).matches("Movies/Trips/"))
    @Test fun extraPathNotPrefixOfSibling() =
        assertFalse(SourceFolders(extraRelativePaths = setOf("Movies")).matches("MoviesOther/"))
    @Test fun disabledCameraDoesNotMatch() = assertFalse(SourceFolders(camera = false).matches("DCIM/Camera/"))
}
