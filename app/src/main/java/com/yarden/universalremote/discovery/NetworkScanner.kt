package com.yarden.universalremote.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Finds smart TVs reachable on the phone's current WiFi subnet.
 *
 * Two complementary strategies are combined because no single one covers every brand:
 *  - SSDP (UPnP) multicast discovery: fast, low-cost, but many TVs answer with a generic
 *    device description that doesn't clearly name the control protocol.
 *  - Direct TCP probing of the well-known control ports for each brand across the local
 *    /24, followed by a brand-specific verification request. This is what actually
 *    confirms "this IP speaks Roku ECP / Samsung remote / webOS / etc."
 */
class NetworkScanner(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(600, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .writeTimeout(600, TimeUnit.MILLISECONDS)
        .build()

    /** Emits each device as soon as it's identified; completes when the scan is done. */
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val seen = HashSet<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun emitOnce(device: DiscoveredDevice) {
            if (seen.add("${device.ip}:${device.brand}")) {
                trySend(device)
            }
        }

        val wifiLock = acquireMulticastLock()
        scope.launch {
            try {
                runSsdpDiscovery { emitOnce(it) }
            } catch (_: Exception) {
                // best-effort; port scan below still covers most devices
            }
        }

        val hosts = localSubnetHosts()
        val semaphore = Semaphore(64)
        scope.launch {
            val jobs = hosts.map { ip ->
                launch {
                    semaphore.withPermit {
                        probeHost(ip)?.let { emitOnce(it) }
                    }
                }
            }
            jobs.forEach { it.join() }
            close()
        }

        awaitClose {
            wifiLock?.let { if (it.isHeld) it.release() }
            scope.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock("universalremote-ssdp")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------
    // SSDP multicast discovery
    // ---------------------------------------------------------------------

    private val ssdpTargets = listOf(
        "ssdp:all",
        "urn:dial-multiscreen-org:service:dial:1",
        "urn:lge-com:service:webos-second-screen:1",
        "urn:schemas-upnp-org:device:MediaRenderer:1"
    )

    private fun runSsdpDiscovery(onFound: (DiscoveredDevice) -> Unit) {
        val socket = DatagramSocket().apply {
            soTimeout = 2500
            reuseAddress = true
        }
        try {
            val group = InetSocketAddress("239.255.255.250", 1900)
            for (target in ssdpTargets) {
                val message = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: $target\r\n\r\n"
                val bytes = message.toByteArray()
                try {
                    socket.send(DatagramPacket(bytes, bytes.size, group))
                } catch (_: IOException) {
                }
            }

            val deadline = System.currentTimeMillis() + 2500
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length)
                    val ip = packet.address.hostAddress ?: continue
                    identifyFromSsdpResponse(ip, response)?.let(onFound)
                } catch (_: IOException) {
                    break
                }
            }
        } finally {
            socket.close()
        }
    }

    private fun identifyFromSsdpResponse(ip: String, response: String): DiscoveredDevice? {
        val lower = response.lowercase()
        val brand = when {
            lower.contains("roku") -> TvBrand.ROKU
            lower.contains("lge") || lower.contains("webos") -> TvBrand.LG_WEBOS
            lower.contains("samsung") -> TvBrand.SAMSUNG
            lower.contains("sony") -> TvBrand.SONY_BRAVIA
            lower.contains("vizio") -> TvBrand.VIZIO
            else -> return null // ambiguous SSDP hit; let the port probe confirm it instead
        }
        val port = defaultPortFor(brand)
        return DiscoveredDevice(ip = ip, name = brand.displayName, brand = brand, port = port)
    }

    private fun defaultPortFor(brand: TvBrand): Int = when (brand) {
        TvBrand.ROKU -> 8060
        TvBrand.SAMSUNG -> 8001
        TvBrand.LG_WEBOS -> 3000
        TvBrand.SONY_BRAVIA -> 80
        TvBrand.VIZIO -> 7345
        TvBrand.UNKNOWN -> 80
    }

    // ---------------------------------------------------------------------
    // Subnet enumeration
    // ---------------------------------------------------------------------

    private fun localSubnetHosts(): List<String> {
        val addr = findIPv4LinkAddress() ?: return emptyList()
        val base = addr.address // 4 bytes
        val prefix = addr.prefixLength.coerceAtLeast(24) // never scan more than a /24 worth of hosts
        val hostBits = 32 - prefix
        val hostCount = (1 shl hostBits).coerceAtMost(254)

        val baseInt = ((base[0].toInt() and 0xFF) shl 24) or
            ((base[1].toInt() and 0xFF) shl 16) or
            ((base[2].toInt() and 0xFF) shl 8) or
            (base[3].toInt() and 0xFF)
        val mask = -1 shl hostBits
        val networkInt = baseInt and mask

        val result = ArrayList<String>(hostCount)
        for (h in 1 until hostCount) {
            val hostInt = networkInt or h
            if (hostInt == baseInt) continue // skip our own IP
            result.add(intToIp(hostInt))
        }
        return result
    }

    /** Just enough of android.net.LinkAddress to compute a subnet -- its own constructor isn't public API. */
    private data class IpPrefix(val address: ByteArray, val prefixLength: Int)

    private fun findIPv4LinkAddress(): IpPrefix? {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // cm.activeNetwork is often the phone's cellular connection even while WiFi is
            // connected (e.g. WiFi has no validated internet access), which would make every
            // computation below scan the carrier's network instead of the TV's LAN. Look for
            // the WiFi-transport network explicitly instead of trusting "active".
            val wifiNetwork = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            val network = wifiNetwork ?: cm.activeNetwork ?: return fallbackLinkAddress()
            val props = cm.getLinkProperties(network) ?: return fallbackLinkAddress()
            props.linkAddresses.firstOrNull { it.address is Inet4Address }
                ?.let { IpPrefix(it.address.address, it.prefixLength) }
                ?: fallbackLinkAddress()
        } catch (_: Exception) {
            fallbackLinkAddress()
        }
    }

    private fun fallbackLinkAddress(): IpPrefix? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.interfaceAddresses.asSequence() }
                .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.let { IpPrefix(it.address.address, it.networkPrefixLength.toInt()) }
        } catch (_: Exception) {
            null
        }
    }

    private fun intToIp(value: Int): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }

    // ---------------------------------------------------------------------
    // Per-host brand probing
    // ---------------------------------------------------------------------

    private fun probeHost(ip: String): DiscoveredDevice? {
        if (isPortOpen(ip, 8060) && looksLikeRoku(ip)) {
            return DiscoveredDevice(ip, "Roku TV", TvBrand.ROKU, 8060)
        }
        if (isPortOpen(ip, 8001) && looksLikeSamsung(ip)) {
            return DiscoveredDevice(ip, "Samsung TV", TvBrand.SAMSUNG, 8001)
        }
        // Newer Samsung models only accept the TLS remote-control port; a plain closed 8001
        // with 8002 open on the same host is a strong enough signal on a home LAN.
        if (!isPortOpen(ip, 8001) && isPortOpen(ip, 8002)) {
            return DiscoveredDevice(ip, "Samsung TV", TvBrand.SAMSUNG, 8002)
        }
        if (isPortOpen(ip, 3000)) {
            return DiscoveredDevice(ip, "LG TV (webOS)", TvBrand.LG_WEBOS, 3000)
        }
        if (isPortOpen(ip, 7345)) {
            return DiscoveredDevice(ip, "Vizio TV", TvBrand.VIZIO, 7345)
        }
        if (isPortOpen(ip, 9000) && !isPortOpen(ip, 80)) {
            return DiscoveredDevice(ip, "Vizio TV", TvBrand.VIZIO, 9000)
        }
        if (looksLikeSonyBravia(ip)) {
            return DiscoveredDevice(ip, "Sony Bravia TV", TvBrand.SONY_BRAVIA, 80)
        }
        return null
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int = 350): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun looksLikeRoku(ip: String): Boolean {
        return try {
            val request = Request.Builder().url("http://$ip:8060/query/device-info").get().build()
            httpClient.newCall(request).execute().use { it.isSuccessful && (it.body?.string()?.contains("roku", true) == true) }
        } catch (_: Exception) {
            false
        }
    }

    private fun looksLikeSamsung(ip: String): Boolean {
        return try {
            val request = Request.Builder().url("http://$ip:8001/api/v2/").get().build()
            httpClient.newCall(request).execute().use {
                it.isSuccessful && (it.body?.string()?.contains("\"device\"") == true)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun looksLikeSonyBravia(ip: String): Boolean {
        if (!isPortOpen(ip, 80)) return false
        return try {
            val xml = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:X_SendIRCC xmlns:u=\"urn:schemas-sony-com:service:IRCC:1\"><IRCCCode></IRCCCode>" +
                "</u:X_SendIRCC></s:Body></s:Envelope>"
            val body = xml.toRequestBody("text/xml; charset=UTF-8".toMediaType())
            val request = Request.Builder().url("http://$ip/sony/IRCC").post(body).build()
            httpClient.newCall(request).execute().use { resp ->
                // Sony replies with a SOAP fault for an empty code, but the response headers confirm it's a Bravia
                resp.header("Server")?.contains("Sony", true) == true ||
                    resp.header("Server")?.contains("DIAL", true) == true
            }
        } catch (_: Exception) {
            false
        }
    }
}
