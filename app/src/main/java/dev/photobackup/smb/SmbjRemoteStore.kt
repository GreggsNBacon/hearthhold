package dev.photobackup.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig as SmbjConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** smbj-backed [RemoteStore]. All suspend functions run on Dispatchers.IO. */
class SmbjRemoteStore(private val config: SmbConfig) : RemoteStore {
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private fun requireShare(): DiskShare = share ?: throw IOException("Not connected")

    override suspend fun connect(): DriveInfo = withContext(Dispatchers.IO) {
        closeQuietly()
        try {
            val smbConfig = SmbjConfig.builder()
                .withTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .withSoTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_1_1)
                .build()
            val c = SMBClient(smbConfig).also { client = it }
            val conn = c.connect(config.host).also { connection = it }
            val auth = if (config.username == null) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(config.username, (config.password ?: "").toCharArray(), "")
            }
            val sess = conn.authenticate(auth).also { session = it }
            val ds = sess.connectShare(config.share) as? DiskShare
                ?: throw RemoteException.ShareNotFound("${config.share} is not a disk share")
            share = ds
            DriveInfo(freeBytes = ds.shareInformation.freeSpace, fileSystem = FS_UNKNOWN)
        } catch (e: Throwable) {
            closeQuietly()
            throw mapConnect(e)
        }
    }

    override suspend fun list(dir: String): List<RemoteEntry> = io {
        requireShare().list(dir)
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                RemoteEntry(
                    name = it.fileName,
                    path = if (dir.isEmpty()) it.fileName else "$dir\\${it.fileName}",
                    isDirectory = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                    sizeBytes = it.endOfFile,
                    modifiedMs = it.lastWriteTime.toEpochMillis(),
                )
            }
    }

    override suspend fun stat(path: String): RemoteEntry? = io {
        try {
            val info = requireShare().getFileInformation(path)
            RemoteEntry(
                name = path.substringAfterLast('\\'),
                path = path,
                isDirectory = info.standardInformation.isDirectory,
                sizeBytes = info.standardInformation.endOfFile,
                modifiedMs = info.basicInformation.lastWriteTime.toEpochMillis(),
            )
        } catch (e: SMBApiException) {
            if (e.status in NOT_FOUND) null else throw e
        }
    }

    override suspend fun mkdirs(dir: String) = io {
        val s = requireShare()
        var cur = ""
        for (seg in dir.split('\\').filter { it.isNotEmpty() }) {
            cur = if (cur.isEmpty()) seg else "$cur\\$seg"
            if (!s.folderExists(cur)) {
                try {
                    s.mkdir(cur)
                } catch (e: SMBApiException) {
                    // Lost a race with another creator: fine if it is now a folder.
                    if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION || !s.folderExists(cur)) throw e
                }
            }
        }
    }

    override suspend fun openWrite(path: String): OutputStream = io {
        val file = requireShare().openFile(
            path,
            setOf(AccessMask.GENERIC_WRITE),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE, // never OVERWRITE: fails if the file exists
            emptySet(),
        )
        val out = file.getOutputStream(false)
        object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                try { out.write(b, off, len) } catch (e: Throwable) { throw mapOp(e) }
            }

            override fun write(b: Int) {
                try { out.write(b) } catch (e: Throwable) { throw mapOp(e) }
            }

            override fun flush() {
                try { out.flush() } catch (e: Throwable) { throw mapOp(e) }
            }

            override fun close() {
                try { out.close() } catch (e: Throwable) { throw mapOp(e) } finally { runCatching { file.close() } }
            }
        }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = io {
        val file = requireShare().openFile(
            path,
            setOf(AccessMask.GENERIC_READ),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            emptySet(),
        )
        object : InputStream() {
            private var pos = offset
            private var closed = false
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = try { file.read(b, pos, off, len) } catch (e: Throwable) { throw mapOp(e) }
                if (n <= 0) return -1
                pos += n
                return n
            }

            override fun close() {
                if (!closed) { closed = true; runCatching { file.close() } }
            }
        }
    }

    override suspend fun rename(from: String, to: String) = io {
        val entry = requireShare().open(
            from,
            setOf(AccessMask.DELETE),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            emptySet(),
        )
        try {
            entry.rename(to, false) // replaceIfExists = false: server fails with NAME_COLLISION
        } finally {
            runCatching { entry.close() }
        }
    }

    override suspend fun delete(path: String) = io {
        val s = requireShare()
        if (s.folderExists(path)) s.rmdir(path, false) else s.rm(path)
    }

    override fun close() = closeQuietly()

    private fun closeQuietly() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null; session = null; connection = null; client = null
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: Throwable) { throw mapOp(e) }
    }

    companion object {
        private const val CONNECT_TIMEOUT_S = 5L
        private const val FS_UNKNOWN = "UNKNOWN"
        private val NOT_FOUND = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_NOT_FOUND,
        )

        /** Maps errors from file operations on an established connection. */
        internal fun mapOp(e: Throwable): Throwable = when {
            e is RemoteException -> e
            e is SMBApiException -> when (e.status) {
                NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCESS_DENIED ->
                    RemoteException.AuthFailed("${e.status}: ${e.message}")
                NtStatus.STATUS_BAD_NETWORK_NAME -> RemoteException.ShareNotFound(e.message ?: "Share not found")
                NtStatus.STATUS_DISK_FULL -> RemoteException.DriveFull(e.message ?: "Drive is full")
                in NOT_FOUND -> FileNotFoundException(e.message)
                else -> IOException("${e.status}: ${e.message}", e)
            }
            isNetwork(e) -> RemoteException.Unreachable(e.message ?: "Drive unreachable")
            e is SMBRuntimeException -> IOException(e.message, e)
            else -> e
        }

        /** Maps errors from connect/authenticate/share-connect. */
        internal fun mapConnect(e: Throwable): Throwable {
            if (e is RemoteException) return e
            if (isNegotiationFailure(e)) return RemoteException.Smb1Only(e.message ?: "Negotiation failed")
            val mapped = mapOp(e)
            if (mapped !== e) return mapped
            return if (e is IOException || e is SMBRuntimeException) {
                RemoteException.Unreachable(e.message ?: "Drive unreachable")
            } else e
        }

        /**
         * smbj only speaks SMB2/3. Against an SMB1-only server, SMBProtocolNegotiator fails with
         * "Failure during dialect negotiation" (wrapped in TransportException/SMBRuntimeException),
         * or the server drops the connection mid-negotiate.
         */
        private fun isNegotiationFailure(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(6).any { t ->
                val m = t.message?.lowercase().orEmpty()
                (t is TransportException || t is SMBRuntimeException) &&
                    (m.contains("negotiat") || m.contains("dialect"))
            }

        private fun isNetwork(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(6).any {
                it is ConnectException || it is SocketTimeoutException || it is UnknownHostException ||
                    it is NoRouteToHostException || it is SocketException || it is EOFException ||
                    it is TimeoutException || it is TransportException
            }
    }
}
