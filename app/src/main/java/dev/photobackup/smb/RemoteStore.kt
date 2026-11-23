package dev.photobackup.smb

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

/** [fileSystem] is uppercase: "FAT32", "EXFAT", "NTFS" or "UNKNOWN". */
data class DriveInfo(val freeBytes: Long, val fileSystem: String)

/**
 * All SMB access goes through this. Paths are share-relative, `\`-separated, no leading separator.
 * The app must never overwrite drive files: [openWrite] and [rename] fail if the target exists.
 */
interface RemoteStore : Closeable {
    suspend fun connect(): DriveInfo
    suspend fun list(dir: String): List<RemoteEntry>
    suspend fun stat(path: String): RemoteEntry?
    suspend fun mkdirs(dir: String)

    /** Fails if the file exists. */
    suspend fun openWrite(path: String): OutputStream
    suspend fun openRead(path: String, offset: Long = 0): InputStream

    /** Fails if [to] exists. */
    suspend fun rename(from: String, to: String)
    suspend fun delete(path: String)
}
