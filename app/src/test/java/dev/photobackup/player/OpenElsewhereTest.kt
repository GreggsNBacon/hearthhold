package dev.photobackup.player

import androidx.media3.common.PlaybackException
import dev.photobackup.smb.FakeRemoteStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OpenElsewhereTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fallbackOnlyForUnsupportedFormatErrors() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )) assertTrue("code $code", PlaybackFallback.wantsFallback(code))
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )) assertFalse("code $code", PlaybackFallback.wantsFallback(code))
    }

    @Test fun trackVerdict() {
        // video track nobody can decode: the fallback, even though audio may play
        assertEquals(TrackVerdict.NO_VIDEO, PlaybackFallback.verdict(hasVideo = true, videoOk = false, hasAudio = true, audioOk = true))
        assertEquals(TrackVerdict.NO_AUDIO, PlaybackFallback.verdict(hasVideo = true, videoOk = true, hasAudio = true, audioOk = false))
        assertEquals(TrackVerdict.OK, PlaybackFallback.verdict(hasVideo = true, videoOk = true, hasAudio = true, audioOk = true))
        assertEquals(TrackVerdict.OK, PlaybackFallback.verdict(hasVideo = true, videoOk = true, hasAudio = false, audioOk = false))
        // audio-only file (no picture at all) is not "no video renderer"
        assertEquals(TrackVerdict.OK, PlaybackFallback.verdict(hasVideo = false, videoOk = false, hasAudio = true, audioOk = true))
    }

    @Test fun sizeRefusal() {
        val gb = 1024L * 1024 * 1024
        assertNull(OpenElsewhere.refusal(sizeBytes = 100 * 1024 * 1024, freeBytes = 10 * gb))
        assertNull(OpenElsewhere.refusal(sizeBytes = 2 * gb, freeBytes = 10 * gb)) // exactly 2 GB is allowed
        assertEquals(MSG_TOO_BIG, OpenElsewhere.refusal(sizeBytes = 2 * gb + 1, freeBytes = 10 * gb))
        assertEquals(MSG_NO_SPACE, OpenElsewhere.refusal(sizeBytes = gb, freeBytes = gb))
        // the copy must leave some room on the phone
        assertEquals(MSG_NO_SPACE, OpenElsewhere.refusal(sizeBytes = gb, freeBytes = gb + OpenElsewhere.SPACE_RESERVE - 1))
        assertNull(OpenElsewhere.refusal(sizeBytes = gb, freeBytes = gb + OpenElsewhere.SPACE_RESERVE))
        assertNull(OpenElsewhere.refusal(sizeBytes = -1, freeBytes = 10 * gb)) // unknown size: checked while copying
    }

    @Test fun staleAfterOneDay() {
        val now = 10L * OpenElsewhere.DAY_MS
        assertFalse(OpenElsewhere.isStale(now, now))
        assertFalse(OpenElsewhere.isStale(now - OpenElsewhere.DAY_MS, now))
        assertTrue(OpenElsewhere.isStale(now - OpenElsewhere.DAY_MS - 1, now))
    }

    @Test fun cleanupRemovesOnlyOldCopies() {
        val dir = tmp.newFolder("open-with")
        val now = 100L * OpenElsewhere.DAY_MS
        val old = File(dir, "old.mkv").apply { writeText("x"); setLastModified(now - 2 * OpenElsewhere.DAY_MS) }
        val part = File(dir, "half.flv.part").apply { writeText("x"); setLastModified(now - 2 * OpenElsewhere.DAY_MS) }
        val fresh = File(dir, "new.mkv").apply { writeText("x"); setLastModified(now - 1000) }
        assertEquals(2, OpenElsewhere.cleanup(dir, now))
        assertFalse(old.exists())
        assertFalse(part.exists())
        assertTrue(fresh.exists())
        assertEquals(0, OpenElsewhere.cleanup(File(dir, "missing"), now)) // never throws
    }

    @Test fun cacheNameKeepsExtensionAndSeparatesSameNames() {
        val a = OpenElsewhere.cacheName("Videos\\2019\\clip.flv")
        val b = OpenElsewhere.cacheName("Videos\\2020\\clip.flv")
        assertTrue(a.endsWith("clip.flv"))
        assertNotEquals(a, b)
        assertEquals(a, OpenElsewhere.cacheName("Videos\\2019\\clip.flv"))
        assertFalse('\\' in a || '/' in a)
    }

    @Test fun copyToCacheCopiesWithProgressAndReuses() = runTest {
        val data = ByteArray(700_000) { it.toByte() }
        val store = FakeRemoteStore().apply { files["v\\a.wmv"] = data }
        val dir = tmp.newFolder("cache")
        val seen = mutableListOf<Long>()
        val f = OpenElsewhere.copyToCache(store, "v\\a.wmv", data.size.toLong(), dir) { seen += it }
        assertArrayEquals(data, f.readBytes())
        assertEquals(data.size.toLong(), seen.last())
        assertTrue(seen.size > 1)
        assertFalse(File(dir, f.name + ".part").exists())

        // Same file again: not downloaded a second time.
        store.failOn = { _, _ -> IOException("drive gone") }
        val again = OpenElsewhere.copyToCache(store, "v\\a.wmv", data.size.toLong(), dir) {}
        assertEquals(f, again)
    }

    @Test fun copyToCacheLeavesNothingOnFailure() = runTest {
        val store = FakeRemoteStore().apply { files["v\\a.wmv"] = ByteArray(10) }
        store.failOn = { op, _ -> if (op == "openRead") IOException("drive gone") else null }
        val dir = tmp.newFolder("cache")
        try {
            OpenElsewhere.copyToCache(store, "v\\a.wmv", 10, dir) {}
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun copyToCacheStopsWhenUnknownSizeGrowsPastLimit() = runTest {
        val store = FakeRemoteStore().apply { files["v\\a.wmv"] = ByteArray(5000) }
        val dir = tmp.newFolder("cache")
        try {
            OpenElsewhere.copyToCache(store, "v\\a.wmv", -1, dir, maxBytes = 1000) {}
            fail("expected TooBigException")
        } catch (_: TooBigException) {
        }
        assertEquals(0, dir.listFiles()!!.size)
    }
}
