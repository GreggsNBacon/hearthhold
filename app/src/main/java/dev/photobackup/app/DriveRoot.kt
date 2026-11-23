package dev.photobackup.app

import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.SettingsUnreadableException
import kotlinx.coroutines.CancellationException
import dev.photobackup.gallery.SmbRoot

/** The saved drive's root. Unreadable settings count as "no drive" here (screens degrade; they never crash). */
suspend fun driveRootOf(read: suspend () -> AppSettings): SmbRoot? =
    try {
        read().smb?.let { SmbRoot(it.host, it.share) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: SettingsUnreadableException) {
        null
    }
