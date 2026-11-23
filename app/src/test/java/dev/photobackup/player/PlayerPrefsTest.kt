package dev.photobackup.player

import androidx.media3.common.ParserException
import dev.photobackup.smb.AccessDeniedException
import dev.photobackup.smb.MSG_ACCESS_DENIED
import dev.photobackup.smb.RemoteException
import dev.photobackup.ui.MSG_AUTH
import org.junit.Assert.assertEquals
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

    @Test fun phoneErrorMessagesNeverBlameTheDrive() {
        assertEquals(MSG_CANT_PLAY, viewerErrorMessage(IOException("x"), fromDrive = false, isVideo = true))
        assertEquals(MSG_CANT_SHOW, viewerErrorMessage(IOException("x"), fromDrive = false, isVideo = false))
    }
}
