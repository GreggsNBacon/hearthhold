package dev.photobackup.backup

import dev.photobackup.backup.RemotePathPlanner.clashName
import dev.photobackup.backup.RemotePathPlanner.partName
import dev.photobackup.backup.RemotePathPlanner.sanitise
import dev.photobackup.backup.RemotePathPlanner.targetDir
import dev.photobackup.media.PhoneMedia
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePathPlannerTest {
    private val UTC: ZoneId = ZoneOffset.UTC

    private fun ms(y: Int, m: Int, d: Int) = sec(y, m, d) * 1000
    private fun sec(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(UTC).toEpochSecond()

    private fun phone(dateTakenMs: Long = 0, dateAddedSec: Long = 0, dateModifiedSec: Long = 0) = PhoneMedia(
        mediaId = 1, uri = "content://media/1", displayName = "IMG_1.jpg", relativePath = "DCIM/Camera/",
        sizeBytes = 1000, dateTakenMs = dateTakenMs, dateAddedSec = dateAddedSec,
        dateModifiedSec = dateModifiedSec, isVideo = false, mimeType = "image/jpeg",
    )

    @Test fun usesDateTakenYearMonth() = assertEquals("Backups\\Pixel\\2026\\03", targetDir("Backups", "Pixel", phone(dateTakenMs = ms(2026, 3, 14)), UTC))
    @Test fun fallsBackToDateAddedWhenTakenZero() = assertEquals("Backups\\Pixel\\2025\\12", targetDir("Backups", "Pixel", phone(dateTakenMs = 0, dateAddedSec = sec(2025, 12, 1)), UTC))
    @Test fun clashNameKeepsExtension() { assertEquals("IMG_1 (1).jpg", clashName("IMG_1.jpg", 1)); assertEquals("noext (2)", clashName("noext", 2)) }
    @Test fun partNameAppendsPart() = assertEquals("IMG_1.jpg.part", partName("IMG_1.jpg"))
    @Test fun sanitisesIllegalSmbCharacters() { assertEquals("a_b_c_.jpg", sanitise("a:b?c*.jpg")); assertEquals("trail", sanitise("trail. ")); assertEquals("日本.jpg", sanitise("日本.jpg")) }
    @Test fun sanitisesDeviceName() = assertEquals("My_Phone", sanitise("My/Phone"))
    @Test fun normalisesDestinationSeparators() = assertEquals("A\\B\\Pixel\\2026\\03", targetDir("/A/B/", "Pixel", phone(dateTakenMs = ms(2026, 3, 14)), UTC))
}
