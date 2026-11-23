package dev.photobackup.player

import androidx.compose.runtime.saveable.SaverScope
import androidx.media3.common.ParserException
import dev.photobackup.gallery.ImageDecodeException
import dev.photobackup.smb.AccessDeniedException
import dev.photobackup.smb.MSG_ACCESS_DENIED
import dev.photobackup.smb.RemoteException
import dev.photobackup.ui.MSG_AUTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class PlayerPrefsTest {
    @Test fun speedsMatchSpec() = assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), PlayerPrefs.SPEEDS)

    @Test fun sessionSpeedDefaultsToOne() = assertEquals(1f, PlayerSession().speed)

    @Test fun speedLabels() {
        assertEquals(
            listOf("0.25×", "0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"),
            PlayerPrefs.SPEEDS.map { PlayerPrefs.label(it) },
        )
    }

    @Test fun driveErrorMessages() {
        val fromDrive = { e: Throwable -> viewerErrorMessage(e, fromDrive = true, isVideo = true) }
        assertEquals("Drive not reachable — connect to home Wi-Fi", MSG_DRIVE_UNREACHABLE)
        assertEquals(MSG_DRIVE_UNREACHABLE, fromDrive(IOException(RemoteException.Unreachable())))
        assertEquals(MSG_DRIVE_UNREACHABLE, fromDrive(IOException("socket closed")))
        assertEquals(MSG_AUTH, fromDrive(RuntimeException(RemoteException.AuthFailed())))
        assertEquals(MSG_ACCESS_DENIED, fromDrive(AccessDeniedException()))
        assertEquals(MSG_NOT_FOUND, fromDrive(FileNotFoundException("x")))
        assertEquals(MSG_CANT_PLAY, fromDrive(ParserException.createForMalformedContainer("bad", null)))
        assertEquals(MSG_CANT_PLAY, fromDrive(IllegalStateException("decoder")))
        assertEquals(MSG_CANT_SHOW, viewerErrorMessage(IllegalStateException("decode"), fromDrive = true, isVideo = false))
    }

    @Test fun undecodableDrivePhotoIsNotBlamedOnTheDrive() {
        assertEquals(MSG_CANT_SHOW, viewerErrorMessage(ImageDecodeException(), fromDrive = true, isVideo = false))
        assertEquals(MSG_DRIVE_UNREACHABLE, viewerErrorMessage(IOException("reset"), fromDrive = true, isVideo = false))
    }

    @Test fun missingSourceMessages() {
        // Drive-only item but no drive saved.
        assertEquals(MSG_NO_DRIVE, unavailableMessage(hasPhone = false, hasRemote = true, hasDrive = false, isVideo = true))
        assertEquals(MSG_CANT_SHOW, unavailableMessage(hasPhone = false, hasRemote = false, hasDrive = true, isVideo = false))
        assertEquals(MSG_CANT_PLAY, unavailableMessage(hasPhone = false, hasRemote = false, hasDrive = false, isVideo = true))
        assertNull(unavailableMessage(hasPhone = true, hasRemote = false, hasDrive = false, isVideo = true))
        assertNull(unavailableMessage(hasPhone = false, hasRemote = true, hasDrive = true, isVideo = false))
    }

    @Test fun playbackSnapshotSurvivesSaveAndRestore() {
        val scope = SaverScope { true }
        val snap = PlaybackSnapshot(positionMs = 12_345, playWhenReady = false)
        val saved = with(PlaybackSnapshot.Saver) { scope.save(snap) }!!
        assertEquals(snap, PlaybackSnapshot.Saver.restore(saved))
        assertEquals(PlaybackSnapshot(0, true), PlaybackSnapshot())
        assertEquals(0L, PlaybackSnapshot.capture(positionMs = -5, playWhenReady = true).positionMs)
    }

    @Test fun phoneErrorMessagesNeverBlameTheDrive() {
        assertEquals(MSG_CANT_PLAY, viewerErrorMessage(IOException("x"), fromDrive = false, isVideo = true))
        assertEquals(MSG_CANT_SHOW, viewerErrorMessage(IOException("x"), fromDrive = false, isVideo = false))
    }
}
