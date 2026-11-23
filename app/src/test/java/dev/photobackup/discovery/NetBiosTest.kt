package dev.photobackup.discovery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetBiosTest {
    @Test fun queryPacketHasWildcardName() {
        val p = NetBios.wildcardQueryPacket(0x1234)
        assertEquals(50, p.size)
        assertEquals(0x12, p[0].toInt()); assertEquals(0x34, p[1].toInt())
        assertEquals(1, p[5].toInt()) // QDCOUNT = 1
        assertEquals(0x20, p[12].toInt()) // label length 32
        // "*" = 0x2A -> 'C','K'; 15 NULs -> 'A','A' each
        assertEquals("CK" + "AA".repeat(15), String(p, 13, 32, Charsets.US_ASCII))
        assertEquals(0, p[45].toInt()) // name terminator
        assertArrayEquals(byteArrayOf(0x00, 0x21, 0x00, 0x01), p.copyOfRange(46, 50)) // NBSTAT, IN
    }

    /**
     * Hand-built node-status response layout:
     *  0..11   header: txId, flags 0x8400 (response, authoritative), QD=0, AN=1, NS=0, AR=0
     *  12..45  RR name: 0x20 + 32 encoded wildcard bytes + 0x00
     *  46..47  TYPE 0x0021, 48..49 CLASS 1, 50..53 TTL 0, 54..55 RDLENGTH
     *  56      NUM_NAMES
     *  57..    NUM_NAMES x 18 bytes: 15-byte space-padded name, 1-byte suffix, 2-byte flags
     *  then 46 bytes of statistics (zeros)
     */
    private fun reply(vararg names: Triple<String, Int, Int>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v) }
        u16(0x1234); u16(0x8400); u16(0); u16(1); u16(0); u16(0)
        out.write(0x20); out.write(("CK" + "AA".repeat(15)).toByteArray()); out.write(0)
        u16(0x0021); u16(1); u16(0); u16(0)
        u16(1 + names.size * 18 + 46)
        out.write(names.size)
        for ((n, suffix, flags) in names) {
            out.write(n.padEnd(15, ' ').toByteArray(Charsets.US_ASCII)); out.write(suffix); u16(flags)
        }
        out.write(ByteArray(46))
        return out.toByteArray()
    }

    @Test fun parsesNameFromCapturedReply() {
        val p = reply(
            Triple("RT-AX86U", 0x00, 0x0400),
            Triple("RT-AX86U", 0x20, 0x0400),
            Triple("WORKGROUP", 0x00, 0x8400), // group
        )
        assertEquals("RT-AX86U", NetBios.parseNodeStatusName(p))
    }

    @Test fun prefersFileServerSuffixOverWorkstation() {
        val p = reply(Triple("WKSTN", 0x00, 0x0400), Triple("FILESRV", 0x20, 0x0400))
        assertEquals("FILESRV", NetBios.parseNodeStatusName(p))
    }

    @Test fun groupNamesIgnored() {
        assertNull(NetBios.parseNodeStatusName(reply(Triple("WORKGROUP", 0x00, 0x8400))))
    }

    @Test fun garbageGivesNull() {
        assertNull(NetBios.parseNodeStatusName(ByteArray(0)))
        assertNull(NetBios.parseNodeStatusName(ByteArray(30)))
        assertNull(NetBios.parseNodeStatusName(reply().copyOf(57)))
    }
}
