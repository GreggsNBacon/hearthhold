package dev.photobackup.media

import android.database.MatrixCursor
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneFoldersTest {
    @Test fun normaliseMatchesTypedPathFormat() {
        assertEquals("Pictures/WhatsApp/", PhoneFolders.normalise(" /Pictures/WhatsApp "))
        assertEquals("Pictures/WhatsApp/", PhoneFolders.normalise("Pictures/WhatsApp/"))
        assertEquals("", PhoneFolders.normalise(" / "))
    }

    @Test fun countGroupsByFolderAndSkipsRoot() {
        val counts = PhoneFolders.count(
            sequenceOf("DCIM/Camera/", "DCIM/Camera/", "Pictures/WhatsApp/", "", null, "Movies"),
        )
        assertEquals(mapOf("DCIM/Camera/" to 2, "Pictures/WhatsApp/" to 1, "Movies/" to 1), counts)
    }

    @Test fun countReadsRelativePathColumnFromCursor() {
        val c = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH))
        c.addRow(arrayOf<Any?>(1L, "Pictures/WhatsApp/"))
        c.addRow(arrayOf<Any?>(2L, "Pictures/WhatsApp/"))
        c.addRow(arrayOf<Any?>(3L, null))
        c.addRow(arrayOf<Any?>(4L, "Download/"))
        assertEquals(mapOf("Pictures/WhatsApp/" to 2, "Download/" to 1), PhoneFolders.count(c))
    }

    @Test fun mergeAddsImageAndVideoCounts() {
        assertEquals(
            mapOf("DCIM/Camera/" to 5, "Movies/" to 1),
            PhoneFolders.merge(mapOf("DCIM/Camera/" to 3), mapOf("DCIM/Camera/" to 2, "Movies/" to 1)),
        )
    }

    @Test fun listIsSortedCaseInsensitivelyAndMarksIncluded() {
        val counts = mapOf(
            "Pictures/WhatsApp/" to 4,
            "DCIM/Camera/" to 10,
            "Pictures/Screenshots/" to 2,
            "download/" to 1,
            "Movies/Trips/" to 3,
        )
        val rows = PhoneFolders.list(counts, SourceFolders(extraRelativePaths = setOf("Movies/")))
        assertEquals(
            listOf("DCIM/Camera/", "download/", "Movies/Trips/", "Pictures/Screenshots/", "Pictures/WhatsApp/"),
            rows.map { it.path },
        )
        val included = rows.associate { it.path to it.included }
        assertTrue(included.getValue("DCIM/Camera/")) // Camera
        assertTrue(included.getValue("Pictures/Screenshots/")) // Screenshots
        assertTrue(included.getValue("Movies/Trips/")) // under an added folder
        assertFalse(included.getValue("Pictures/WhatsApp/"))
        assertFalse(included.getValue("download/"))
        assertEquals(10, rows.first().count)
    }

    @Test fun cameraAndScreenshotsNotIncludedWhenSwitchedOff() {
        val rows = PhoneFolders.list(
            mapOf("DCIM/Camera/" to 1, "Pictures/Screenshots/" to 1),
            SourceFolders(camera = false, screenshots = false),
        )
        assertTrue(rows.none { it.included })
    }

    @Test fun pickedPathMatchesTheSameMediaAsTypedPath() {
        val picked = PhoneFolders.list(mapOf("Pictures/WhatsApp/" to 1), SourceFolders()).single().path
        val folders = SourceFolders(camera = false, screenshots = false, extraRelativePaths = setOf(picked))
        assertTrue(folders.matches("Pictures/WhatsApp/"))
        assertTrue(folders.matches("Pictures/WhatsApp/Sent/"))
        assertFalse(folders.matches("Pictures/WhatsAppOther/"))
    }
}
