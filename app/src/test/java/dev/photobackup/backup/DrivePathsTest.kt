package dev.photobackup.backup

import dev.photobackup.ledger.BackupStatus
import dev.photobackup.ledger.LedgerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
