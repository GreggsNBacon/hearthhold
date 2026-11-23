package dev.photobackup.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

data class FoundHost(val name: String?, val address: String)

/** Finds SMB hosts on the current Wi-Fi: mDNS + NetBIOS + TCP 445 probe, merged and deduped by address. */
class HostDiscovery(context: Context) {
    private val app = context.applicationContext

    fun discover(timeoutMs: Long = 4000): Flow<FoundHost> = channelFlow {
        val local = localIpv4() ?: return@channelFlow // no Wi-Fi IPv4: nothing to find
        val seen = HashMap<String, String?>()

        // Emits on first sight of an address, and again when a name arrives for a previously nameless one.
        val emit: suspend (String?, String) -> Unit = { name, address ->
            val send = synchronized(seen) {
                when {
                    !seen.containsKey(address) -> { seen[address] = name; true }
                    seen[address] == null && name != null -> { seen[address] = name; true }
                    else -> false
                }
            }
            if (send) trySend(FoundHost(name, address))
        }
        val known: (String) -> Boolean = { synchronized(seen) { seen.containsKey(it) } }

        val sources = launch {
            launch { mdns(local.first, emit) }
            launch { netbios(local.first, local.second, emit) }
            launch { probe445(local.first, local.second, known, emit) }
        }
        delay(timeoutMs)
        sources.cancelAndJoin()
    }

    /** (ipv4, prefix) of the active Wi-Fi network, or null. */
    private fun localIpv4(): Pair<String, Int>? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        if (cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
        val la = cm.getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return null
        val ip = la.address.hostAddress ?: return null
        return ip to la.prefixLength
    }

    private suspend fun mdns(localIp: String, emit: suspend (String?, String) -> Unit): Unit = withContext(Dispatchers.IO) {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("photobackup-mdns").apply { setReferenceCounted(false); acquire() }
        var jmdns: JmDNS? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            jmdns = JmDNS.create(InetAddress.getByName(localIp))
            jmdns.addServiceListener("_smb._tcp.local.", object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    event.dns.requestServiceInfo(event.type, event.name, 1000)
                }

                override fun serviceRemoved(event: ServiceEvent) {}

                override fun serviceResolved(event: ServiceEvent) {
                    val addr = event.info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                    scope.launch { emit(event.info.name, addr) }
                }
            })
            awaitCancellation()
        } finally {
            scope.cancel()
            withContext(NonCancellable) {
                runCatching { jmdns?.close() }
                runCatching { lock.release() }
            }
        }
    }

    private suspend fun netbios(ip: String, prefix: Int, emit: suspend (String?, String) -> Unit) =
        withContext(Dispatchers.IO) {
            val sock = runCatching { DatagramSocket().apply { broadcast = true; soTimeout = 250 } }.getOrNull()
                ?: return@withContext
            try {
                val query = NetBios.wildcardQueryPacket(0x4E42)
                // Samba answers node-status only to unicast, so ask every host as well as the broadcast address.
                val targets = listOfNotNull(SubnetScan.broadcastOf(ip, prefix)) + SubnetScan.hostsIn(ip, prefix)
                for (t in targets) {
                    runCatching { sock.send(DatagramPacket(query, query.size, InetAddress.getByName(t), 137)) }
                }
                val buf = ByteArray(1024)
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        break
                    }
                    val name = NetBios.parseNodeStatusName(pkt.data.copyOf(pkt.length)) ?: continue
                    emit(name, pkt.address.hostAddress ?: continue)
                }
            } finally {
                sock.close()
            }
        }

    private suspend fun probe445(
        ip: String,
        prefix: Int,
        known: (String) -> Boolean,
        emit: suspend (String?, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val gate = Semaphore(64)
        SubnetScan.hostsIn(ip, prefix).filter { it != ip }.map { host ->
            async {
                gate.withPermit {
                    if (!isActive || known(host)) return@withPermit
                    val ok = Socket().use { s ->
                        runCatching { s.connect(InetSocketAddress(host, 445), 300) }.isSuccess
                    }
                    if (ok && !known(host)) emit(null, host)
                }
            }
        }.awaitAll()
    }
}
