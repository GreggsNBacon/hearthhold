package dev.photobackup.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
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

    fun discover(timeoutMs: Long = 4000): Flow<FoundHost> {
        val local = localIpv4() ?: return emptyFlow() // no Wi-Fi IPv4: nothing to find
        val (ip, prefix) = local
        return mergeSources(
            timeoutMs,
            listOf(
                { _, emit -> mdns(ip, emit) },
                { _, emit -> netbios(ip, prefix, emit) },
                { known, emit -> probe445(ip, prefix, known, emit) },
            ),
        )
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

    private suspend fun mdns(localIp: String, emit: suspend (String?, String) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("photobackup-mdns").apply { setReferenceCounted(false); acquire() }
            val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            superviseDetached(detached, release = { lock.release() }) {
                val jmdns = JmDNS.create(InetAddress.getByName(localIp))
                jmdns.addServiceListener("_smb._tcp.local.", object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        event.dns.requestServiceInfo(event.type, event.name, 1000)
                    }

                    override fun serviceRemoved(event: ServiceEvent) {}

                    override fun serviceResolved(event: ServiceEvent) {
                        val addr = event.info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                        detached.launch { emit(event.info.name, addr) }
                    }
                })
                jmdns
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

/**
 * Runs [sources] concurrently for [timeoutMs], deduping by address (re-emitting when a name arrives for a
 * nameless address). A source that throws (other than cancellation) is ignored; the others keep going.
 * The flow completes promptly at the timeout, without waiting on slow source teardown.
 */
internal fun mergeSources(
    timeoutMs: Long,
    sources: List<suspend (known: (String) -> Boolean, emit: suspend (String?, String) -> Unit) -> Unit>,
): Flow<FoundHost> = channelFlow {
    val seen = HashMap<String, String?>()
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

    val running = launch {
        for (source in sources) {
            launch {
                try {
                    source(known, emit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // one failing source must not fail discovery
                }
            }
        }
    }
    delay(timeoutMs)
    running.cancel()
    withTimeoutOrNull(500) { running.join() }
}

/**
 * Runs [open] (blocking, uncancellable, may throw) on [scope] and holds the result until cancelled, then closes it
 * and calls [release] (always, even if [open] throws or the job is cancelled before it starts). Failures are
 * contained: nothing propagates to the scope's uncaught-exception handler. Returns promptly on cancel.
 */
internal suspend fun superviseDetached(scope: CoroutineScope, release: () -> Unit, open: () -> Closeable) {
    // ATOMIC: the body (and its finally) runs even if cancelled before it starts, so release() cannot be skipped.
    val job = scope.launch(start = CoroutineStart.ATOMIC) {
        var handle: Closeable? = null
        try {
            handle = open()
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // mDNS failure (interface gone, etc.): contained; other sources carry on
        } finally {
            withContext(NonCancellable) {
                runCatching { handle?.close() }
                runCatching { release() }
            }
        }
    }
    try {
        job.join()
    } finally {
        job.cancel()
    }
}
