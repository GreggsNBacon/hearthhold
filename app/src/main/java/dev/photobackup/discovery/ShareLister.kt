package dev.photobackup.discovery

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig as SmbjConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import dev.photobackup.smb.ConnectTimeoutSocketFactory
import dev.photobackup.smb.SmbjRemoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Lists the disk shares of an SMB host (SRVSVC over IPC$). Null [user] means guest. */
class ShareLister {
    suspend fun shares(host: String, user: String?, pass: String?): List<String> = withContext(Dispatchers.IO) {
        var client: SMBClient? = null
        try {
            val config = SmbjConfig.builder()
                .withTimeout(15, TimeUnit.SECONDS)
                .withSocketFactory(ConnectTimeoutSocketFactory(5000))
                .build()
            val c = SMBClient(config).also { client = it }
            val auth = if (user == null) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(user, (pass ?: "").toCharArray(), "")
            }
            c.connect(host).use { conn ->
                conn.authenticate(auth).use { session ->
                    val transport = SMBTransportFactories.SRVSVC.getTransport(session)
                    ServerService(transport).shares1
                        .map { it.netName }
                        .filter { !it.endsWith("$") }
                        .sortedBy { it.lowercase() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw SmbjRemoteStore.mapConnect(e)
        } finally {
            runCatching { client?.close() }
        }
    }
}
