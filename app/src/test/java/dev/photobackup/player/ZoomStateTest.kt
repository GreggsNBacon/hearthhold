package dev.photobackup.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomStateTest {
    private fun zoomedIn() = ZoomState().apply {
        setSize(IntSize(1000, 2000))
        toggle(Offset(500f, 1000f)) // double tap in the middle: 2.5x
    }

    /**
     * Lifting the last finger while zoomed delivers one more event with no pointer down; its centroid is
     * [Offset.Unspecified] (NaN). That used to turn the offset into NaN and the photo went black.
     */
    @Test fun releaseEventWithUnspecifiedCentroidKeepsOffset() {
        val z = zoomedIn()
        z.transform(1f, Offset(40f, 0f), Offset(500f, 1000f)) // pan a bit
        val before = z.offset
        z.transform(1f, Offset.Zero, Offset.Unspecified)
        assertTrue("offset must stay finite, was ${z.offset}", z.offset.x.isFinite() && z.offset.y.isFinite())
        assertEquals(before, z.offset)
        assertEquals(2.5f, z.scale)
    }

    @Test fun panStaysInsideTheZoomedPhoto() {
        val z = zoomedIn()
        z.transform(1f, Offset(100_000f, -100_000f), Offset(500f, 1000f))
        assertEquals(Offset(750f, -1500f), z.offset) // (2.5 - 1) * size / 2
    }
}
