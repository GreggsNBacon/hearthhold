package dev.photobackup.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [PlaybackFallback.verdict] on real media3 [Tracks] (Robolectric: TrackGroup needs android.text). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackVerdictTest {
    private fun group(mime: String, support: Int) = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mime).build()),
        false,
        intArrayOf(support),
        booleanArrayOf(false),
    )

    /** ExoPlayer still tries a track that "exceeds capabilities" (often plays fine): not a reason to divert. */
    @Test fun exceedsCapabilitiesTrackIsNotNoVideo() {
        val tracks = Tracks(listOf(
            group(MimeTypes.VIDEO_H265, C.FORMAT_EXCEEDS_CAPABILITIES),
            group(MimeTypes.AUDIO_AC3, C.FORMAT_EXCEEDS_CAPABILITIES),
        ))
        assertEquals(TrackVerdict.OK, PlaybackFallback.verdict(tracks))
        assertEquals(
            TrackVerdict.NO_VIDEO,
            PlaybackFallback.verdict(Tracks(listOf(group(MimeTypes.VIDEO_H265, C.FORMAT_UNSUPPORTED_SUBTYPE)))),
        )
        assertEquals(
            TrackVerdict.NO_AUDIO,
            PlaybackFallback.verdict(Tracks(listOf(
                group(MimeTypes.VIDEO_H264, C.FORMAT_HANDLED),
                group(MimeTypes.AUDIO_DTS, C.FORMAT_UNSUPPORTED_TYPE),
            ))),
        )
    }
}
