package com.globalvpn.app.data

import com.wireguard.crypto.KeyPair
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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

        // Use the exact endpoint the API assigns — it is paired with the server
        // public key. Using any other IP breaks the WireGuard handshake.
        val endpoint = endpointObj.optString("v4").takeIf { it.isNotEmpty() }
            ?: endpointObj.getString("host")

        return WarpCredentials(
            privateKey = privateKey,
            clientAddress = addresses.getString("v4"),
            clientAddressV6 = addresses.optString("v6", ""),
            serverPublicKey = peer.getString("public_key"),
            serverEndpoint = endpoint
        )
    }

    fun buildWireGuardConfig(creds: WarpCredentials): String {
        // Only add ::/0 when we have a proper IPv6 tunnel address.
        // Without it, Happy Eyeballs tries IPv6 first on every dual-stack site,
        // the packet enters the tunnel but has nowhere to go, and the browser
        // waits several seconds before falling back to IPv4 — causing hell latency.
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
