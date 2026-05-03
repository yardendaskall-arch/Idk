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

        return WarpCredentials(
            privateKey = privateKey,
            clientAddress = addresses.getString("v4"),
            serverPublicKey = peer.getString("public_key"),
            serverEndpoint = "${endpointObj.getString("host")}:2408"
        )
    }

    fun buildWireGuardConfig(creds: WarpCredentials): String = """
        [Interface]
        PrivateKey = ${creds.privateKey}
        Address = ${creds.clientAddress}/32
        DNS = 1.1.1.1, 1.0.0.1

        [Peer]
        PublicKey = ${creds.serverPublicKey}
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = ${creds.serverEndpoint}
        PersistentKeepalive = 25
    """.trimIndent()
}
