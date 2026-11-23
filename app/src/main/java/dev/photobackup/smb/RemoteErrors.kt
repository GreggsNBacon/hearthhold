package dev.photobackup.smb

import java.io.IOException

sealed class RemoteException(msg: String) : IOException(msg) {
    class AuthFailed(msg: String = "Authentication failed") : RemoteException(msg)
    class Unreachable(msg: String = "Drive unreachable") : RemoteException(msg)
    class ShareNotFound(msg: String = "Share not found") : RemoteException(msg)
    class Smb1Only(msg: String = "Server only supports SMB1") : RemoteException(msg)
    class DriveFull(msg: String = "Drive is full") : RemoteException(msg)
}
