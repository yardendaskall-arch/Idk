package com.globalvpn.app.data

import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class WarpApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val CANDIDATE_IPS = listOf(
        "162.159.193.1", "162.159.192.1", "162.159.195.1", "162.159.194.1",
        "188.114.96.1",  "188.114.97.1",  "188.114.98.1",  "188.114.99.1"
    )
    private val WARP_PORT = 2408

    suspend fun register(): WarpCredentials {
        val keyPair = KeyPair()
        val privateKey = keyPair.privateKey.toBase64()
        val publicKey = keyPair.publicKey.toBase64()

        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        val body = JSONObject().apply {
            put("install_id", UUID.randomUUID().toString())
            put("tos", tos)
            put("key", publicKey)
            put("model", "Android")
            put("locale", "en_US")
            put("warp_enabled", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.cloudflareclient.com/v0a2158/reg")
            .addHeader("CF-Client-Version", "a-6.38-3734")
            .addHeader("User-Agent", "okhttp/3.12.1")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val json = JSONObject(response.body?.string() ?: throw Exception("Empty response"))

        if (!response.isSuccessful) {
            throw Exception("WARP registration failed: ${response.code} - ${json.optString("message")}")
        }

        val config = json.getJSONObject("config")
        val iface = config.getJSONObject("interface")
        val addresses = iface.getJSONObject("addresses")
        val peers = config.getJSONArray("peers")
        val peer = peers.getJSONObject(0)
        val endpointObj = peer.getJSONObject("endpoint")

        val apiEndpoint = endpointObj.optString("v4").takeIf { it.isNotEmpty() }
            ?: endpointObj.getString("host")
        val bestEndpoint = findFastestEndpoint(apiEndpoint)

        return WarpCredentials(
            privateKey = privateKey,
            clientAddress = addresses.getString("v4"),
            clientAddressV6 = addresses.optString("v6", ""),
            serverPublicKey = peer.getString("public_key"),
            serverEndpoint = bestEndpoint
        )
    }

    private suspend fun findFastestEndpoint(fallback: String): String =
        withContext(Dispatchers.IO) {
            val results = CANDIDATE_IPS.map { ip ->
                async {
                    Pair("$ip:$WARP_PORT", tcpLatency(ip, 80))
                }
            }.awaitAll()

            results.filter { it.second < Long.MAX_VALUE }
                .minByOrNull { it.second }
                ?.first ?: fallback
        }

    private fun tcpLatency(host: String, port: Int): Long {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    fun buildWireGuardConfig(creds: WarpCredentials): String {
        // Only include IPv6 routing when we have a proper IPv6 tunnel address.
        // Without it, ::/0 causes Happy Eyeballs to try IPv6 first on every
        // connection, fail inside the tunnel, and wait seconds before falling
        // back to IPv4 — the main cause of "hell" page-load latency.
        val hasV6 = creds.clientAddressV6.isNotEmpty()
        val addresses = if (hasV6) "${creds.clientAddress}/32, ${creds.clientAddressV6}/128"
                        else "${creds.clientAddress}/32"
        val allowedIPs = if (hasV6) "0.0.0.0/0, ::/0" else "0.0.0.0/0"

        return """
            [Interface]
            PrivateKey = ${creds.privateKey}
            Address = $addresses
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1420

            [Peer]
            PublicKey = ${creds.serverPublicKey}
            AllowedIPs = $allowedIPs
            Endpoint = ${creds.serverEndpoint}
            PersistentKeepalive = 25
        """.trimIndent()
    }
}
