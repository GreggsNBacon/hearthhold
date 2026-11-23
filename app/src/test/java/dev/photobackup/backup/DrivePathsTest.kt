package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivePathsTest {
    private val root = DrivePaths.deviceRoot("B/", "Dev")

    private fun row(path: String?, movingTo: String? = null, part: String? = null) = LedgerEntry(
        1, "content://media/1", "n.jpg", "DCIM/Camera/", 100, 0, 10, false, "sha", path, part,
        BackupStatus.VERIFIED, null, 0, movingTo = movingTo,
    )

    @Test fun rootJoinsDestinationAndDevice() {
        assertEquals("B\\Dev", root)
        assertEquals("Dev", DrivePaths.deviceRoot(null, "Dev"))
    }

    @Test fun parsesTodaysShapeIgnoringCase() {
        assertEquals(DriveFile("Dog", "2026", "n.jpg"), DrivePaths.parse(row("b\\dev\\Dog\\2026\\n.jpg"), root))
        assertEquals(DriveFile("2024", "2024", "n.jpg"), DrivePaths.parse(row("B\\Dev\\2024\\2024\\n.jpg"), root))
    }

    @Test fun rejectsOtherShapes() {
        assertNull(DrivePaths.parse(row("B\\Dev\\2026\\03\\n.jpg"), root))
        assertNull(DrivePaths.parse(row("B\\Dev\\Dog\\n.jpg"), root))
        assertNull(DrivePaths.parse(row("B\\Dev\\Dog\\2026\\x\\n.jpg"), root))
        assertNull(DrivePaths.parse(row("Other\\Dev\\Dog\\2026\\n.jpg"), root))
        assertNull(DrivePaths.parse(row(null), root))
    }

    @Test fun rejectsPendingMoveOrPart() {
        assertNull(DrivePaths.parse(row("B\\Dev\\Dog\\2026\\n.jpg", movingTo = "B\\Dev\\Cat\\2026\\n.jpg"), root))
        assertNull(DrivePaths.parse(row("B\\Dev\\Dog\\2026\\n.jpg", part = "x.part"), root))
    }

    // ---- .Bin ----

    @Test fun binRootIsUnderTheDeviceRoot() = assertEquals("B\\Dev\\.Bin", DrivePaths.binRoot(root))

    @Test fun binTargetKeepsFolderYearAndName() {
        assertEquals("B\\Dev\\.Bin\\Dog\\2026" to "n.jpg", DrivePaths.binTarget("b\\dev\\Dog\\2026\\n.jpg", root))
    }

    @Test fun binTargetRefusesOtherShapesAndPlaces() {
        assertNull(DrivePaths.binTarget("B\\Dev\\2026\\03\\n.jpg", root)) // old YYYY\\MM layout
        assertNull(DrivePaths.binTarget("Old\\Dev\\Dog\\2026\\n.jpg", root)) // another destination
        assertNull(DrivePaths.binTarget("B\\Other\\Dog\\2026\\n.jpg", root)) // another device
        assertNull(DrivePaths.binTarget("B\\Dev\\.Bin\\Dog\\2026\\n.jpg", root)) // already binned
        assertNull(DrivePaths.binTarget("B\\Dev\\.bin\\2026\\n.jpg", root)) // .Bin is never a folder
    }

    @Test fun isInBinAcceptsFilesUnderTheCurrentBinIgnoringCase() {
        assertTrue(DrivePaths.isInBin("B\\Dev\\.Bin\\Dog\\2026\\n.jpg", root))
        assertTrue(DrivePaths.isInBin("b\\DEV\\.bin\\Dog\\2026\\n (1).jpg", root))
        assertTrue(DrivePaths.isInBin("B\\Dev\\.Bin\\n.jpg", root))
    }

    @Test fun isInBinRefusesAnythingElse() {
        for (p in listOf(
            null,
            "",
            "B\\Dev\\.Bin",
            "B\\Dev\\.Bin\\\\",
            "B\\Dev\\.Bins\\Dog\\2026\\n.jpg",
            "B\\Dev\\.Bin\\..\\Dog\\2026\\n.jpg",
            "B\\Dev\\.Bin\\Dog\\..\\..\\n.jpg",
            "B\\Dev\\.Bin\\.\\n.jpg",
            "B\\Dev\\.Bin\\\\n.jpg",
            "B\\Dev\\.Bin\\Dog\\...\\n.jpg",
            "B\\Dev\\.Bin\\Dog\\. \\n.jpg",
            "B\\Dev\\.Bin/../Dog/2026/n.jpg",
            "B\\Dev\\.Bin\\Dog/../../x.jpg",
            "B\\Dev\\Dog\\2026\\n.jpg",
            "B\\Devx\\.Bin\\Dog\\2026\\n.jpg",
            "Old\\Dev\\.Bin\\Dog\\2026\\n.jpg",
            "X\\B\\Dev\\.Bin\\Dog\\2026\\n.jpg",
        )) assertFalse(p.toString(), DrivePaths.isInBin(p, root))
        assertFalse(DrivePaths.isInBin("..\\.Bin\\n.jpg", ".."))
        assertFalse(DrivePaths.isInBin("\\.Bin\\n.jpg", ""))
    }

    @Test fun parseNeverOffersBinRowsOrTheBinFolder() {
        val p = "B\\Dev\\Dog\\2026\\n.jpg"
        assertNull(DrivePaths.parse(row(p).copy(binRequestedAt = 5), root))
        assertNull(DrivePaths.parse(row(p).copy(status = BackupStatus.BINNED), root))
        assertNull(DrivePaths.parse(row("B\\Dev\\.Bin\\2026\\n.jpg"), root))
        assertNull(DrivePaths.parse(row("B\\Dev\\.Bin\\Dog\\2026\\n.jpg"), root))
    }
}
