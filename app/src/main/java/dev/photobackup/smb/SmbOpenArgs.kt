package dev.photobackup.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.util.EnumSet

/**
 * Arguments for smbj's DiskShare.open/openFile. Every set is a real [EnumSet]: smbj calls `EnumSet.copyOf(set)`,
 * which throws "Collection is empty" for an empty non-EnumSet (e.g. Kotlin's `emptySet()`). Build them only here.
 */
internal data class SmbOpenArgs(
    val access: Set<AccessMask>,
    val attributes: Set<FileAttributes>,
    val shareAccess: Set<SMB2ShareAccess>,
    val disposition: SMB2CreateDisposition,
    val createOptions: Set<SMB2CreateOptions>,
) {
    fun openFile(share: DiskShare, path: String): File =
        share.openFile(path, access, attributes, shareAccess, disposition, createOptions)

    fun open(share: DiskShare, path: String): DiskEntry =
        share.open(path, access, attributes, shareAccess, disposition, createOptions)

    companion object {
        private fun normal() = EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL)
        private fun shareAll() = EnumSet.allOf(SMB2ShareAccess::class.java)
        private fun fileOnly() = EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        private fun noOptions() = EnumSet.noneOf(SMB2CreateOptions::class.java)

        /** FILE_CREATE, never OVERWRITE: fails if the file exists. */
        fun write() = SmbOpenArgs(
            EnumSet.of(AccessMask.GENERIC_WRITE), normal(), shareAll(), SMB2CreateDisposition.FILE_CREATE, fileOnly(),
        )

        fun read() = SmbOpenArgs(
            EnumSet.of(AccessMask.GENERIC_READ), normal(), shareAll(), SMB2CreateDisposition.FILE_OPEN, fileOnly(),
        )

        fun rename() = SmbOpenArgs(
            EnumSet.of(AccessMask.DELETE), normal(), shareAll(), SMB2CreateDisposition.FILE_OPEN, noOptions(),
        )

        /** Attributes only: the content cannot be touched through this handle. */
        fun setTimes() = SmbOpenArgs(
            EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES), normal(), shareAll(), SMB2CreateDisposition.FILE_OPEN,
            fileOnly(),
        )
    }
}
