package dev.photobackup.backup

import dev.photobackup.backup.RemotePathPlanner.clashName
import dev.photobackup.backup.RemotePathPlanner.partName
import dev.photobackup.backup.RemotePathPlanner.sanitise
import dev.photobackup.backup.RemotePathPlanner.targetDir
import dev.photobackup.media.PhoneMedia
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePathPlannerTest {
    private val UTC: ZoneId = ZoneOffset.UTC

    private fun ms(y: Int, m: Int, d: Int) = sec(y, m, d) * 1000
    private fun sec(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(UTC).toEpochSecond()

    private fun phone(dateTakenMs: Long = 0, dateAddedSec: Long = 0, dateModifiedSec: Long = 0, relativePath: String = "DCIM/Camera/") = PhoneMedia(
        mediaId = 1, uri = "content://media/1", displayName = "IMG_1.jpg", relativePath = relativePath,
        sizeBytes = 1000, dateTakenMs = dateTakenMs, dateAddedSec = dateAddedSec,
        dateModifiedSec = dateModifiedSec, isVideo = false, mimeType = "image/jpeg",
    )

    @Test fun usesDateTakenYear() = assertEquals("Backups\\Pixel\\Camera\\2026", targetDir("Backups", "Pixel", phone(dateTakenMs = ms(2026, 3, 14)), UTC))
    @Test fun fallsBackToDateAddedWhenTakenZero() = assertEquals("Backups\\Pixel\\Camera\\2025", targetDir("Backups", "Pixel", phone(dateTakenMs = 0, dateAddedSec = sec(2025, 12, 1)), UTC))
    @Test fun clashNameKeepsExtension() { assertEquals("IMG_1 (1).jpg", clashName("IMG_1.jpg", 1)); assertEquals("noext (2)", clashName("noext", 2)) }
    @Test fun partNameAppendsPart() = assertEquals("IMG_1.jpg.part", partName("IMG_1.jpg"))
    @Test fun sanitisesIllegalSmbCharacters() { assertEquals("a_b_c_.jpg", sanitise("a:b?c*.jpg")); assertEquals("trail", sanitise("trail. ")); assertEquals("日本.jpg", sanitise("日本.jpg")) }
    @Test fun sanitisesDeviceName() = assertEquals("My_Phone", sanitise("My/Phone"))
    @Test fun normalisesDestinationSeparators() = assertEquals("A\\B\\Pixel\\Camera\\2026", targetDir("/A/B/", "Pixel", phone(dateTakenMs = ms(2026, 3, 14)), UTC))

    @Test fun folderReplacesSourceSegment() = assertEquals(
        "Hearthhold\\Pixel 9\\Memes\\2025",
        targetDir("Hearthhold", "Pixel 9", phone(dateTakenMs = ms(2025, 5, 1), relativePath = "Pictures/WhatsApp/"), UTC, folder = "Memes"),
    )
    @Test fun folderIsSanitised() = assertEquals(
        "B\\P\\a_b\\2025",
        targetDir("B", "P", phone(dateTakenMs = ms(2025, 5, 1)), UTC, folder = "a/b"),
    )
    @Test fun nullFolderKeepsSourceSegment() = assertEquals(
        "Hearthhold\\Pixel 9\\WhatsApp\\2025",
        targetDir("Hearthhold", "Pixel 9", phone(dateTakenMs = ms(2025, 5, 1), relativePath = "Pictures/WhatsApp/"), UTC, folder = null),
    )

    private fun dirFor(rel: String) = targetDir("B", "P", phone(dateTakenMs = ms(2026, 3, 14), relativePath = rel), UTC)

    /** `.Bin` is reserved for the drive bin: a phone folder of that name never uploads into it. */
    @Test fun phoneFolderNamedBinNeverUploadsIntoTheBin() {
        assertEquals("_Bin", RemotePathPlanner.sourceFolder("DCIM/.Bin/"))
        assertEquals("_bin", RemotePathPlanner.sourceFolder("DCIM/.bin/"))
        assertEquals("B\\P\\_Bin\\2026", dirFor("Pictures/.Bin/"))
    }

    @Test fun sourceFolderCamera() = assertEquals("B\\P\\Camera\\2026", dirFor("DCIM/Camera/"))
    @Test fun sourceFolderScreenshots() = assertEquals("B\\P\\Screenshots\\2026", dirFor("Pictures/Screenshots/"))
    @Test fun sourceFolderWhatsApp() = assertEquals("B\\P\\WhatsApp Images\\2026", dirFor("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
    @Test fun sourceFolderEmptyIsOther() = assertEquals("B\\P\\Other\\2026", dirFor(""))
    @Test fun sourceFolderBlankIsOther() = assertEquals("B\\P\\Other\\2026", dirFor("//"))
    @Test fun sourceFolderSanitised() = assertEquals("B\\P\\a_b\\2026", dirFor("Pictures/a:b/"))
    @Test fun yearUsesAsciiDigitsInArabicLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            assertEquals("B\\P\\Camera\\2026", dirFor("DCIM/Camera/"))
        } finally { Locale.setDefault(old) }
    }
}
