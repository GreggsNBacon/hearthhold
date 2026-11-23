package dev.photobackup.player

import androidx.media3.common.ParserException
import dev.photobackup.smb.AccessDeniedException
import dev.photobackup.smb.MSG_ACCESS_DENIED
import dev.photobackup.smb.RemoteException
import dev.photobackup.ui.MSG_AUTH
import java.io.FileNotFoundException
import java.io.IOException

object PlayerPrefs {
    /** Playback speeds offered by the speed chip (spec: "Viewer / player"). Pitch is kept at every speed. */
    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** "0.25×", "1×", "1.5×". */
    fun label(speed: Float): String {
        val s = speed.toString().trimEnd('0').trimEnd('.')
        return "$s×"
    }
}

/**
 * The player speed for this app session. One instance lives in AppContainer (process lifetime), so the speed carries
 * from video to video and resets to 1× on the next launch.
 */
class PlayerSession {
    @Volatile var speed: Float = 1f
}

const val MSG_DRIVE_UNREACHABLE = "Drive not reachable — connect to home Wi-Fi"
const val MSG_NOT_FOUND = "This file is no longer on the drive"
const val MSG_CANT_PLAY = "Can't play this video"
const val MSG_CANT_SHOW = "Can't show this photo"

/**
 * What a viewer page says when its photo or video fails. Looks through the cause chain (ExoPlayer and Coil wrap the
 * drive error). Never includes the exception text, which can carry connection details.
 */
fun viewerErrorMessage(e: Throwable, fromDrive: Boolean, isVideo: Boolean): String {
    val chain = generateSequence(e) { it.cause }.take(16).toList()
    val cantOpen = if (isVideo) MSG_CANT_PLAY else MSG_CANT_SHOW
    if (!fromDrive) return cantOpen
    return when {
        chain.any { it is RemoteException.AuthFailed } -> MSG_AUTH
        chain.any { it is AccessDeniedException } -> MSG_ACCESS_DENIED
        chain.any { it is FileNotFoundException } -> MSG_NOT_FOUND
        chain.any { it is ParserException } -> cantOpen // the file arrived but isn't playable
        chain.any { it is IOException } -> MSG_DRIVE_UNREACHABLE
        else -> cantOpen
    }
}
