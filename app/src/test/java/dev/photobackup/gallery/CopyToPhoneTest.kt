package dev.photobackup.gallery

import dev.photobackup.smb.FakeRemoteStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class CopyToPhoneTest {
    private class FakeWriter : PhoneMediaWriter {
        val created = mutableListOf<List<Any>>()
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        val published = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        var failWriteAfter: Int? = null

        override fun createPending(displayName: String, mimeType: String, relativePath: String, isVideo: Boolean): String {
            created += listOf(displayName, mimeType, relativePath, isVideo)
            return "content://media/${created.size}"
        }

        override fun openOutput(uri: String): OutputStream {
            val buf = ByteArrayOutputStream().also { outputs[uri] = it }
            return object : OutputStream() {
                override fun write(b: Int) {
                    val limit = failWriteAfter
                    if (limit != null && buf.size() >= limit) throw IOException("disk full")
                    buf.write(b)
                }
            }
        }

        override fun publish(uri: String) { published += uri }
        override fun discard(uri: String) { discarded += uri }
    }

    private val store = FakeRemoteStore().apply {
        dirs += "Photos"
        files["Photos\\IMG_1.JPG"] = ByteArray(300_000) { (it % 251).toByte() }
        files["Photos\\clip.mov"] = ByteArray(10) { it.toByte() }
    }

    @Test fun copiesPhotoIntoPicturesFolderAsPendingThenPublishes() = runTest {
        val w = FakeWriter()
        val uri = CopyToPhone.copy(w, store, "Photos\\IMG_1.JPG", isVideo = false)
        assertEquals(listOf(listOf("IMG_1.JPG", "image/jpeg", "Pictures/Hearthhold/", false)), w.created)
        assertArrayEquals(store.files["Photos\\IMG_1.JPG"], w.outputs.getValue(uri).toByteArray())
        assertEquals(listOf(uri), w.published)
        assertTrue(w.discarded.isEmpty())
    }

    @Test fun videoGoesToMoviesFolder() = runTest {
        val w = FakeWriter()
        CopyToPhone.copy(w, store, "Photos\\clip.mov", isVideo = true)
        assertEquals(listOf(listOf("clip.mov", "video/quicktime", "Movies/Hearthhold/", true)), w.created)
    }

    @Test fun failedWriteDiscardsPendingRow() = runTest {
        val w = FakeWriter().apply { failWriteAfter = 1000 }
        try {
            CopyToPhone.copy(w, store, "Photos\\IMG_1.JPG", isVideo = false)
            fail("expected failure")
        } catch (_: IOException) {
        }
        assertEquals(listOf("content://media/1"), w.discarded)
        assertTrue(w.published.isEmpty())
    }

    @Test fun failedReadDiscardsPendingRow() = runTest {
        val w = FakeWriter()
        try {
            CopyToPhone.copy(w, store, "Photos\\missing.jpg", isVideo = false)
            fail("expected failure")
        } catch (_: IOException) {
        }
        assertEquals(listOf("content://media/1"), w.discarded)
        assertTrue(w.published.isEmpty())
    }

    @Test fun mimeTypes() {
        assertEquals("image/jpeg", CopyToPhone.mimeType("a.jpeg", false))
        assertEquals("image/png", CopyToPhone.mimeType("a.PNG", false))
        assertEquals("image/heic", CopyToPhone.mimeType("a.heic", false))
        assertEquals("video/mp4", CopyToPhone.mimeType("a.mp4", true))
        assertEquals("video/x-matroska", CopyToPhone.mimeType("a.mkv", true))
        assertEquals("image/jpeg", CopyToPhone.mimeType("noext", false))
        assertEquals("video/mp4", CopyToPhone.mimeType("noext", true))
    }
}
