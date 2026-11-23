package dev.photobackup.smb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbConfigTest {
    @Test fun toStringRedactsPassword() {
        val text = SmbConfig("nas", "usb", "greg", "hunter2").toString()
        assertFalse(text.contains("hunter2"))
        assertTrue(text.contains("password=***"))
        assertTrue(text.contains("greg"))
    }

    @Test fun toStringShowsNullPassword() {
        assertTrue(SmbConfig("nas", "usb", null, null).toString().contains("password=null"))
    }
}
