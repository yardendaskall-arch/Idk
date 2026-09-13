package com.yarden.universalremote.protocol

import com.yarden.universalremote.discovery.DiscoveredDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Vizio SmartCast pairing + key-command API. Like most SmartCast TVs' local control API, this is
 * HTTPS with a self-signed certificate, so the client below trusts any cert -- there is no CA a
 * phone could otherwise validate against for a TV's own LAN-only IP address. This mirrors what
 * every open-source Vizio remote (e.g. pyvizio, Home Assistant's Vizio integration) does.
 */
class VizioProtocol(override val device: DiscoveredDevice) : TvProtocol {

    private val deviceId: String = UUID.randomUUID().toString()
    private var authToken: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .sslSocketFactory(trustAllSslSocketFactory(), trustAllManager())
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
        .build()

    private val baseUrl get() = "https://${device.ip}:${device.port}"

    override suspend fun connect(pairing: PairingCallback): ConnectResult = withContext(Dispatchers.IO) {
        try {
            val startBody = JSONObject().apply {
                put("DEVICE_NAME", "Universal Remote")
                put("DEVICE_ID", deviceId)
            }
            val startResp = postJson("$baseUrl/pairing/start", startBody, null)
                ?: return@withContext ConnectResult.Failed("Could not reach the Vizio TV")
            val pairingToken = startResp.optJSONObject("ITEM")?.optInt("PAIRING_REQ_TOKEN")
                ?: return@withContext ConnectResult.Failed("Vizio TV did not start pairing")

            val pin = pairing.requestPin("Enter the PIN now shown on your Vizio TV screen")
                ?: return@withContext ConnectResult.Failed("Pairing cancelled")

            val pairBody = JSONObject().apply {
                put("DEVICE_ID", deviceId)
                put("CHALLENGE_TYPE", 1)
                put("RESPONSE_VALUE", pin)
                put("PAIRING_REQ_TOKEN", pairingToken)
            }
            val pairResp = postJson("$baseUrl/pairing/pair_client", pairBody, null)
                ?: return@withContext ConnectResult.Failed("Could not reach the Vizio TV")
            val token = pairResp.optJSONObject("ITEM")?.optString("AUTH_TOKEN")
            if (token.isNullOrEmpty()) {
                return@withContext ConnectResult.Failed("Incorrect PIN")
            }
            authToken = token
            ConnectResult.Success
        } catch (e: Exception) {
            ConnectResult.Failed(e.message ?: "Could not pair with the Vizio TV")
        }
    }

    override fun disconnect() {
        authToken = null
    }

    override fun sendKey(key: RemoteKey) {
        val token = authToken ?: return
        val (codeset, code) = mapKey(key) ?: return
        val body = JSONObject().apply {
            put(
                "KEYLIST",
                JSONArray().put(
                    JSONObject().apply {
                        put("CODESET", codeset)
                        put("CODE", code)
                        put("ACTION", "KEYPRESS")
                    }
                )
            )
        }
        val request = Request.Builder()
            .url("$baseUrl/key_command/")
            .addHeader("AUTH", token)
            .put(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(NoopCallback)
    }

    private fun postJson(url: String, body: JSONObject, token: String?): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .apply { token?.let { addHeader("AUTH", it) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: return null
            return try { JSONObject(text) } catch (_: Exception) { null }
        }
    }

    /** (CODESET, CODE) pairs from Vizio's SmartCast TV key table. */
    private fun mapKey(key: RemoteKey): Pair<Int, Int>? = when (key) {
        RemoteKey.POWER -> 11 to 2
        RemoteKey.VOLUME_UP -> 5 to 1
        RemoteKey.VOLUME_DOWN -> 5 to 0
        RemoteKey.MUTE -> 5 to 4
        RemoteKey.CHANNEL_UP -> 8 to 1
        RemoteKey.CHANNEL_DOWN -> 8 to 0
        RemoteKey.DPAD_UP -> 3 to 8
        RemoteKey.DPAD_DOWN -> 3 to 0
        RemoteKey.DPAD_LEFT -> 3 to 1
        RemoteKey.DPAD_RIGHT -> 3 to 7
        RemoteKey.DPAD_SELECT -> 3 to 2
        RemoteKey.BACK -> 4 to 0
        RemoteKey.HOME -> 4 to 15
        RemoteKey.MENU -> 4 to 8
        RemoteKey.INPUT_SOURCE -> 7 to 1
        RemoteKey.PLAY_PAUSE -> 2 to 3
        RemoteKey.REWIND -> 2 to 1
        RemoteKey.FAST_FORWARD -> 2 to 0
        else -> null // Vizio's published key table has no dedicated number-pad codes
    }

    private fun trustAllManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun trustAllSslSocketFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustAllManager()), SecureRandom())
        return context.socketFactory
    }
}
