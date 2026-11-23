package dev.photobackup.ui

import dev.photobackup.backup.BackupMessages
import dev.photobackup.smb.AccessDeniedException
import dev.photobackup.smb.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Test

class MessagesTest {
    private val smbHelp = "Couldn't agree a file-sharing version with the drive. On the router, make sure SMB2 is " +
        "enabled: USB Application → Network Place (Samba) Share"

    @Test fun smbVersionMessageIsTheSameEverywhere() {
        assertEquals(smbHelp, userMessage(RemoteException.Smb1Only()))
        assertEquals(smbHelp, BackupMessages.SMB1)
    }

    @Test fun accessDeniedShowsPermissionMessage() {
        assertEquals("You don't have permission to open this on the drive", userMessage(AccessDeniedException()))
    }
}
