package dev.photobackup.smb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class FakeRemoteStoreTest {
    @Test fun openWriteOnExistingPathThrows() = runBlocking {
        val s = FakeRemoteStore()
        s.files["a.jpg"] = byteArrayOf(1)
        try {
            s.openWrite("a.jpg")
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
        assertArrayEquals(byteArrayOf(1), s.files["a.jpg"])
    }

    @Test fun renameOntoExistingPathThrows() = runBlocking {
        val s = FakeRemoteStore()
        s.files["a.part"] = byteArrayOf(1)
        s.files["a.jpg"] = byteArrayOf(2)
        try {
            s.rename("a.part", "a.jpg")
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
        assertArrayEquals(byteArrayOf(2), s.files["a.jpg"])
        assertArrayEquals(byteArrayOf(1), s.files["a.part"])
    }

    @Test fun truncateWritesAfterLeavesPartialFile() = runBlocking {
        val s = FakeRemoteStore(truncateWritesAfter = 10)
        val out = s.openWrite("a.part")
        assertThrows(IOException::class.java) { out.write(ByteArray(25) { it.toByte() }) }
        assertEquals(10, s.files["a.part"]!!.size)
        assertEquals(listOf("a.part"), s.writes)
    }

    @Test fun mkdirsListStatRenameDeleteAndFailOn() = runBlocking {
        val s = FakeRemoteStore()
        s.mkdirs("B\\P\\2026")
        s.openWrite("B\\P\\2026\\x.part").use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(listOf("x.part"), s.list("B\\P\\2026").map { it.name })
        assertEquals(true, s.stat("B\\P")!!.isDirectory)
        s.rename("B\\P\\2026\\x.part", "B\\P\\2026\\x.jpg")
        assertEquals(3L, s.stat("B\\P\\2026\\x.jpg")!!.sizeBytes)
        assertEquals(listOf<Byte>(3), s.openRead("B\\P\\2026\\x.jpg", 2).readBytes().toList())
        s.delete("B\\P\\2026\\x.jpg")
        assertNull(s.stat("B\\P\\2026\\x.jpg"))
        s.failOn = { op, _ -> if (op == "connect") RemoteException.Unreachable() else null }
        try {
            s.connect()
            throw AssertionError("expected Unreachable")
        } catch (_: RemoteException.Unreachable) {
        }
    }
}
