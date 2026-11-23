package dev.photobackup.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig as SmbjConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
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
            val factory = ConnectTimeoutSocketFactory(CONNECT_TIMEOUT_S.toInt() * 1000)
            val smbConfig = SmbjConfig.builder()
                // Socket soTimeout is left at 0: smbj tears the connection down on a read timeout,
                // which would kill the session during any idle gap. Only TCP connect has a 5 s limit.
                .withReadTimeout(OP_TIMEOUT_S, TimeUnit.SECONDS)
                .withWriteTimeout(OP_TIMEOUT_S, TimeUnit.SECONDS)
                .withTransactTimeout(OP_TIMEOUT_S, TimeUnit.SECONDS)
                .withSocketFactory(factory)
                .withDialects(DIALECTS)
                .build()
            val c = SMBClient(smbConfig).also { client = it }
            val conn = try {
                c.connect(config.host)
            } catch (e: Throwable) {
                // TCP connected but the link dropped during SMB2 negotiate: SMB1-only servers
                // typically just close the socket when they see an SMB2 negotiate.
                if (factory.connected && (isDropped(e) || isNegotiationFailure(e)) && !isTcpConnectFailure(e)) {
                    throw RemoteException.Smb1Only("Connection dropped during SMB2 negotiation: ${e.message}")
                }
                throw e
            }.also { connection = it }
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
        val file = SmbOpenArgs.write().openFile(requireShare(), path) // FILE_CREATE: fails if the file exists
        val out = try {
            file.getOutputStream(false)
        } catch (e: Throwable) {
            runCatching { file.close() }
            throw e
        }
        object : FilterOutputStream(out) {
            private var closed = false
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
                if (closed) return
                closed = true
                try { out.close() } catch (e: Throwable) { throw mapOp(e) } finally { runCatching { file.close() } }
            }
        }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = io {
        val file = SmbOpenArgs.read().openFile(requireShare(), path)
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
        val entry = SmbOpenArgs.rename().open(requireShare(), from)
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

    override suspend fun setTimes(path: String, modifiedMs: Long) = io {
        val t = FileTime.ofEpochMillis(modifiedMs)
        // Handle opened with FILE_WRITE_ATTRIBUTES only; attributes 0 and DONT_SET leave everything else alone.
        val entry = SmbOpenArgs.setTimes().open(requireShare(), path)
        try {
            entry.setFileInformation(
                FileBasicInformation(t, FileBasicInformation.DONT_SET, t, FileBasicInformation.DONT_SET, 0L)
            )
        } finally {
            runCatching { entry.close() }
        }
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
        private const val OP_TIMEOUT_S = 60L
        private const val FS_UNKNOWN = "UNKNOWN"

        /** SMB 2.0.2 is needed for routers whose "SMB2" means 2.0.2 (e.g. ASUS RT-AX86U USB share). */
        internal val DIALECTS = listOf(
            SMB2Dialect.SMB_2_0_2, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_1_1,
        )
        private val NOT_FOUND = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_NOT_FOUND,
        )

        /**
         * Maps errors from file operations on an established connection. Anything that is not already an
         * IOException (unmapped smbj runtime errors, IllegalArgumentException, ...) is wrapped in one, so the backup
         * engine fails just that item instead of the whole worker. Cancellation and VM errors pass through.
         */
        internal fun mapOp(e: Throwable): Throwable {
            val known = mapKnown(e, connecting = false)
            return when (known) {
                is IOException, is CancellationException, is VirtualMachineError -> known
                else -> IOException(known.javaClass.simpleName, known)
            }
        }

        /**
         * ACCESS_DENIED means a bad login only while connecting (session setup / tree connect). On a file or folder
         * it is [AccessDeniedException]: the login is fine, that item is off limits, so no password prompt.
         */
        private fun mapKnown(e: Throwable, connecting: Boolean): Throwable = when {
            e is RemoteException -> e
            e is SMBApiException -> when (e.status) {
                NtStatus.STATUS_LOGON_FAILURE -> RemoteException.AuthFailed("${e.status}: ${e.message}")
                NtStatus.STATUS_ACCESS_DENIED ->
                    if (connecting) RemoteException.AuthFailed("${e.status}: ${e.message}") else AccessDeniedException()
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
            if (e is SMBApiException) return mapKnown(e, connecting = true)
            if (isNegotiationFailure(e)) return RemoteException.Smb1Only(e.message ?: "Negotiation failed")
            val mapped = mapKnown(e, connecting = true)
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

        private fun isTcpConnectFailure(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(6).any {
                it is ConnectException || it is SocketTimeoutException || it is UnknownHostException ||
                    it is NoRouteToHostException
            }

        private fun isDropped(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(6).any {
                it is EOFException || it is SocketException || it is TransportException
            }

        private fun isNetwork(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(6).any {
                it is ConnectException || it is SocketTimeoutException || it is UnknownHostException ||
                    it is NoRouteToHostException || it is SocketException || it is EOFException ||
                    it is TimeoutException || it is TransportException
            }
    }
}

/** Socket factory with a real TCP connect timeout; records whether TCP connect succeeded. */
internal class ConnectTimeoutSocketFactory(private val timeoutMs: Int) : SocketFactory() {
    @Volatile var connected = false
        private set

    private fun open(addr: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        val sock = Socket()
        try {
            local?.let { sock.bind(it) }
            sock.connect(addr, timeoutMs)
        } catch (e: Throwable) {
            runCatching { sock.close() }
            throw e
        }
        connected = true
        return sock
    }

    override fun createSocket(): Socket = Socket()
    override fun createSocket(host: String, port: Int): Socket = open(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        open(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = open(InetSocketAddress(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        open(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))
}
