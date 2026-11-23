package dev.photobackup.smb

import java.io.IOException

/** User-facing text for [RemoteException.Smb1Only]; the single source for the UI and the backup worker. */
const val MSG_SMB_VERSION = "Couldn't agree a file-sharing version with the drive. On the router, make sure SMB2 is " +
    "enabled: USB Application → Network Place (Samba) Share"

/** Item error text for an unexpected (wrapped, non-IO) drive library error; the item is retried next run. */
const val MSG_DRIVE_ERROR = "Drive error — will try again"

const val MSG_ACCESS_DENIED = "You don't have permission to open this on the drive"

/** Errors that stop a backup run: the drive or the login is unusable, not just one file. */
sealed class RemoteException(msg: String) : IOException(msg) {
    class AuthFailed(msg: String = "Authentication failed") : RemoteException(msg)
    class Unreachable(msg: String = "Drive unreachable") : RemoteException(msg)
    class ShareNotFound(msg: String = "Share not found") : RemoteException(msg)
    class Smb1Only(msg: String = "Server only supports SMB1") : RemoteException(msg)
    class DriveFull(msg: String = "Drive is full") : RemoteException(msg)
}

/**
 * The login works but this one file or folder is off limits (e.g. "System Volume Information"). Deliberately not a
 * [RemoteException]: it fails only that item and never prompts for the password. The message is user-facing.
 */
class AccessDeniedException(msg: String = MSG_ACCESS_DENIED) : IOException(msg)
