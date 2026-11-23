package dev.photobackup.gallery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class SmbCoilFetcherTest {
    @Test fun sampleSizeKeepsAtLeastRequestedSize() {
        assertEquals(1, Thumbnails.sampleSize(400, 300, 400, 300))
        assertEquals(2, Thumbnails.sampleSize(800, 600, 400, 300))
        assertEquals(4, Thumbnails.sampleSize(4000, 3000, 512, 512))
        assertEquals(1, Thumbnails.sampleSize(100, 100, 512, 512))
        assertEquals(1, Thumbnails.sampleSize(4000, 3000, 0, 0))
    }

    /** Pixel photos carry a ~380x500 preview in their first 32 KB: enough for a 384 px grid cell, not the viewer. */
    @Test fun embeddedPreviewUsedOnlyWhenBigEnough() {
        assertTrue(Thumbnails.embeddedIsEnough(362, 481, 384, 384))
        assertTrue(Thumbnails.embeddedIsEnough(510, 384, 384, 384))
        assertFalse(Thumbnails.embeddedIsEnough(160, 120, 384, 384)) // classic tiny EXIF thumbnail
        assertFalse(Thumbnails.embeddedIsEnough(384, 510, 1080, 1080)) // full-screen viewer
        assertFalse(Thumbnails.embeddedIsEnough(0, 0, 384, 384))
    }

    @Test fun cacheKeyIncludesPathAndSize() {
        val a = Thumbnails.cacheKey("smb://nas/usb/a.jpg", 256, 256)
        assertEquals("smb-thumb:smb://nas/usb/a.jpg@256x256", a)
        assertNotEquals(a, Thumbnails.cacheKey("smb://nas/usb/a.jpg", 512, 512))
    }

    private class Opener(private val data: ByteArray) {
        val opens = mutableListOf<Long>()
        fun open(offset: Long): InputStream {
            opens += offset
            return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
        }
    }

    @Test fun rangeReaderReusesStreamForSequentialReads() {
        val data = ByteArray(100) { it.toByte() }
        val o = Opener(data)
        val r = RangeReader(o::open)
        val buf = ByteArray(10)
        assertEquals(10, r.readAt(0, buf, 0, 10))
        assertArrayEquals(data.copyOfRange(0, 10), buf)
        assertEquals(10, r.readAt(10, buf, 0, 10))
        assertArrayEquals(data.copyOfRange(10, 20), buf)
        assertEquals(listOf(0L), o.opens)
    }

    @Test fun rangeReaderReopensOnSeek() {
        val data = ByteArray(100) { it.toByte() }
        val o = Opener(data)
        val r = RangeReader(o::open)
        val buf = ByteArray(5)
        r.readAt(0, buf, 0, 5)
        assertEquals(5, r.readAt(90, buf, 0, 5))
        assertArrayEquals(data.copyOfRange(90, 95), buf)
        assertEquals(5, r.readAt(20, buf, 0, 5))
        assertArrayEquals(data.copyOfRange(20, 25), buf)
        assertEquals(listOf(0L, 90L, 20L), o.opens)
    }

    @Test fun rangeReaderFillsFullRequestAndSignalsEnd() {
        val data = ByteArray(100) { it.toByte() }
        // A stream that hands out at most 3 bytes per read.
        val r = RangeReader { off ->
            object : InputStream() {
                var pos = off.toInt()
                override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
                override fun read(b: ByteArray, o: Int, len: Int): Int {
                    if (pos >= data.size) return -1
                    val n = minOf(3, len, data.size - pos)
                    System.arraycopy(data, pos, b, o, n); pos += n; return n
                }
            }
        }
        val buf = ByteArray(10)
        assertEquals(10, r.readAt(0, buf, 0, 10))
        assertEquals(4, r.readAt(96, buf, 0, 10))
        assertEquals(-1, r.readAt(100, buf, 0, 10))
        r.close()
    }


    @Test fun onlyUrisOnTheSavedDriveAreRead() {
        val loc = dev.photobackup.smb.SmbLocation("NAS", "usb", "a\\b.jpg")
        assertTrue(Thumbnails.onSavedDrive(loc, SmbRoot("nas", "USB")))
        assertFalse(Thumbnails.onSavedDrive(loc, SmbRoot("newhost", "usb")))
        assertFalse(Thumbnails.onSavedDrive(loc, SmbRoot("nas", "other")))
        assertFalse(Thumbnails.onSavedDrive(loc, null))
    }
}
