package dev.photobackup.discovery

import dev.photobackup.discovery.SubnetScan.hostsIn
import org.junit.Assert.assertEquals
import org.junit.Test

class SubnetScanTest {
    @Test fun hostsInSlash24() {
        val h = hostsIn("192.168.50.17", 24)
        assertEquals(254, h.size)
        assertEquals("192.168.50.1", h.first())
        assertEquals("192.168.50.254", h.last())
    }

    @Test fun widerPrefixCappedAt24() {
        val h = hostsIn("10.0.5.9", 16)
        assertEquals(254, h.size)
        assertEquals("10.0.5.1", h.first())
    }

    @Test fun narrowerPrefixStaysInsideSubnet() {
        // /30 of .17 => network .16, hosts .17 and .18
        assertEquals(listOf("192.168.50.17", "192.168.50.18"), hostsIn("192.168.50.17", 30))
    }

    @Test fun invalidInputGivesEmpty() {
        assertEquals(emptyList<String>(), hostsIn("not-an-ip", 24))
    }
}
