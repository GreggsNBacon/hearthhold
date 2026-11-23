package dev.photobackup.smb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.IOException

/** Integration test; skipped unless PB_SMB_HOST and PB_SMB_SHARE (optional PB_SMB_USER/PB_SMB_PASS) are set. */
class SmbjRemoteStoreIT {
    @Test fun roundTrip() = runBlocking {
        val host = System.getenv("PB_SMB_HOST")
        val shareName = System.getenv("PB_SMB_SHARE")
        Assume.assumeTrue(host != null && shareName != null)
        val store = SmbjRemoteStore(SmbConfig(host, shareName, System.getenv("PB_SMB_USER"), System.getenv("PB_SMB_PASS")))
        val dir = "pb-it-${System.currentTimeMillis()}"
        try {
            val info = store.connect()
            assertTrue(info.freeBytes > 0)
            println("IT drive: free=${info.freeBytes} fs=${info.fileSystem}")

            store.mkdirs("$dir\\a\\b")
            store.mkdirs("$dir\\a\\b") // idempotent
            assertTrue(store.stat("$dir\\a\\b")!!.isDirectory)

            val data = ByteArray(300_000) { (it % 251).toByte() }
            store.openWrite("$dir\\a\\b\\x.part").use { it.write(data) }
            assertEquals(data.size.toLong(), store.stat("$dir\\a\\b\\x.part")!!.sizeBytes)
            try {
                store.openWrite("$dir\\a\\b\\x.part").close()
                throw AssertionError("openWrite on existing file must fail")
            } catch (_: IOException) {
            }

            store.rename("$dir\\a\\b\\x.part", "$dir\\a\\b\\x.jpg")
            assertNull(store.stat("$dir\\a\\b\\x.part"))
            assertNotNull(store.stat("$dir\\a\\b\\x.jpg"))

            store.openWrite("$dir\\a\\b\\y.part").use { it.write(byteArrayOf(1)) }
            try {
                store.rename("$dir\\a\\b\\y.part", "$dir\\a\\b\\x.jpg")
                throw AssertionError("rename onto existing must fail")
            } catch (_: IOException) {
            }
            assertEquals(300_000L, store.stat("$dir\\a\\b\\x.jpg")!!.sizeBytes)

            assertEquals(setOf("x.jpg", "y.part"), store.list("$dir\\a\\b").map { it.name }.toSet())
            val tail = store.openRead("$dir\\a\\b\\x.jpg", 299_990).use { it.readBytes() }
            assertArrayEquals(data.copyOfRange(299_990, 300_000), tail)
        } finally {
            runCatching {
                for (p in listOf("$dir\\a\\b\\x.jpg", "$dir\\a\\b\\y.part", "$dir\\a\\b", "$dir\\a", dir)) {
                    runCatching { store.delete(p) }
                }
            }
            store.close()
        }
    }
}
