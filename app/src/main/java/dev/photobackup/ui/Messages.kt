package dev.photobackup.ui

import dev.photobackup.smb.AccessDeniedException
import dev.photobackup.smb.MSG_ACCESS_DENIED
import dev.photobackup.smb.RemoteException
import dev.photobackup.ui.auth.DriveNotConfiguredException
import java.io.IOException

const val MSG_AUTH = "Wrong username or password"
const val MSG_UNREACHABLE = "Can't reach the drive — check you're on home Wi-Fi"
const val MSG_SHARE = "Shared folder not found on the drive"
const val MSG_SMB1 = dev.photobackup.smb.MSG_SMB_VERSION
const val MSG_FULL = "The drive is full"

/** User-facing text for a drive error. Never includes the exception text (it can carry connection details). */
fun userMessage(e: Throwable): String = when (e) {
    is RemoteException.AuthFailed -> MSG_AUTH
    is RemoteException.Unreachable -> MSG_UNREACHABLE
    is RemoteException.ShareNotFound -> MSG_SHARE
    is RemoteException.Smb1Only -> MSG_SMB1
    is RemoteException.DriveFull -> MSG_FULL
    is DriveNotConfiguredException -> "Finish setup first"
    is AccessDeniedException -> MSG_ACCESS_DENIED
    is java.io.FileNotFoundException ->"Folder not found on the drive"
    is IOException -> MSG_UNREACHABLE
    is SecurityException -> "Photo access is needed"
    else -> "Something went wrong"
}
