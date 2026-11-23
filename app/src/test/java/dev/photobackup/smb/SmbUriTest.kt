package dev.photobackup.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbUriTest {
    @Test fun buildsWithForwardSlashes() {
        assertEquals(
            "smb://nas/usb/Photos/Pixel/IMG_1.jpg",
            SmbUri.build("nas", "usb", "Photos\\Pixel\\IMG_1.jpg"),
        )
    }

    @Test fun parseGivesBackslashPath() {
        assertEquals(
            SmbLocation("nas", "usb", "Photos\\Pixel\\IMG_1.jpg"),
            SmbUri.parse("smb://nas/usb/Photos/Pixel/IMG_1.jpg"),
        )
    }

    @Test fun specialCharactersRoundTrip() {
        val path = "My Photos\\WhatsApp Images\\a#b?c%d (1)+é.jpg"
        val uri = SmbUri.build("192.168.1.1", "USB Drive", path)
        assertEquals(SmbLocation("192.168.1.1", "USB Drive", path), SmbUri.parse(uri))
        // No raw characters that would confuse a URI parser.
        assertEquals(-1, uri.indexOf('#'))
        assertEquals(-1, uri.indexOf('?'))
        assertEquals(-1, uri.indexOf(' '))
    }

    @Test fun shareRoot() {
        assertEquals("smb://nas/usb/", SmbUri.build("nas", "usb", ""))
        assertEquals(SmbLocation("nas", "usb", ""), SmbUri.parse("smb://nas/usb/"))
        assertEquals(SmbLocation("nas", "usb", ""), SmbUri.parse("smb://nas/usb"))
    }

    @Test fun leadingAndTrailingSeparatorsIgnored() {
        assertEquals("smb://nas/usb/a/b", SmbUri.build("nas", "usb", "\\a\\b\\"))
    }

    @Test fun rejectsOtherSchemesAndMissingParts() {
        assertNull(SmbUri.parse("content://media/1"))
        assertNull(SmbUri.parse("smb://nas"))
        assertNull(SmbUri.parse("smb:///usb/a"))
        assertNull(SmbUri.parse("smb://nas/usb/%zz"))
    }
}
