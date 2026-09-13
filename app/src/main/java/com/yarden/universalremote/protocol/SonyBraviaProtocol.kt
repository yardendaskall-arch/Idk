package com.yarden.universalremote.protocol

import com.yarden.universalremote.discovery.DiscoveredDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sony Bravia IRCC-IP control. Sony TVs identify each command by a base64 "IRCC code" (the same
 * codes a physical remote would send over infrared) delivered inside a small SOAP envelope.
 *
 * Most Bravia TVs require a Pre-Shared Key configured on the TV under
 * Settings > Network > Home Network Setup > IP Control > Authentication, entered here once.
 * Some older models accept unauthenticated IRCC calls on the local network with no key at all.
 */
class SonyBraviaProtocol(override val device: DiscoveredDevice) : TvProtocol {

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private var psk: String? = null

    override suspend fun connect(pairing: PairingCallback): ConnectResult = withContext(Dispatchers.IO) {
        val enteredPsk = pairing.requestPin(
            "Enter the Pre-Shared Key configured on this Sony TV (Settings > Network > " +
                "Home Network Setup > IP Control). Leave blank to try without one."
        )
        psk = enteredPsk?.takeIf { it.isNotBlank() }

        return@withContext try {
            val response = sendIrccBlocking(IRCC_CONFIRM)
            if (response) ConnectResult.Success
            else ConnectResult.Failed("The TV rejected the request. Check the Pre-Shared Key and try again.")
        } catch (e: Exception) {
            ConnectResult.Failed(e.message ?: "Could not reach the Sony TV")
        }
    }

    override fun disconnect() = Unit

    override fun sendKey(key: RemoteKey) {
        val code = mapKey(key) ?: return
        val request = buildRequest(code)
        client.newCall(request).enqueue(NoopCallback)
    }

    private fun sendIrccBlocking(code: String): Boolean {
        val request = buildRequest(code)
        client.newCall(request).execute().use { return it.isSuccessful || it.code == 500 }
        // Sony returns HTTP 500 with a SOAP fault for a benign no-op code even when auth is fine,
        // so any response at all (rather than a connection failure) counts as "reachable".
    }

    private fun buildRequest(code: String): Request {
        val xml = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:X_SendIRCC xmlns:u=\"urn:schemas-sony-com:service:IRCC:1\">" +
            "<IRCCCode>$code</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>"
        val body = xml.toRequestBody("text/xml; charset=UTF-8".toMediaType())
        val builder = Request.Builder()
            .url("http://${device.ip}:${device.port}/sony/IRCC")
            .addHeader("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
            .post(body)
        psk?.let { builder.addHeader("X-Auth-PSK", it) }
        return builder.build()
    }

    private fun mapKey(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> IRCC_POWER
        RemoteKey.VOLUME_UP -> "AAAAAQAAAAEAAAASAw=="
        RemoteKey.VOLUME_DOWN -> "AAAAAQAAAAEAAAATAw=="
        RemoteKey.MUTE -> "AAAAAQAAAAEAAAAUAw=="
        RemoteKey.CHANNEL_UP -> "AAAAAQAAAAEAAAAQAw=="
        RemoteKey.CHANNEL_DOWN -> "AAAAAQAAAAEAAAARAw=="
        RemoteKey.DPAD_UP -> "AAAAAQAAAAEAAAB0Aw=="
        RemoteKey.DPAD_DOWN -> "AAAAAQAAAAEAAAB1Aw=="
        RemoteKey.DPAD_LEFT -> "AAAAAQAAAAEAAAA0Aw=="
        RemoteKey.DPAD_RIGHT -> "AAAAAQAAAAEAAAAzAw=="
        RemoteKey.DPAD_SELECT -> IRCC_CONFIRM
        RemoteKey.BACK -> "AAAAAgAAAJcAAAAjAw=="
        RemoteKey.HOME -> "AAAAAQAAAAEAAABgAw=="
        RemoteKey.MENU -> "AAAAAQAAAAEAAABiAw=="
        RemoteKey.INPUT_SOURCE -> "AAAAAQAAAAEAAAAlAw=="
        RemoteKey.PLAY_PAUSE -> "AAAAAgAAAJcAAAAaAw=="
        RemoteKey.REWIND -> "AAAAAgAAAJcAAAAbAw=="
        RemoteKey.FAST_FORWARD -> "AAAAAgAAAJcAAAAcAw=="
        RemoteKey.NUM_0 -> "AAAAAQAAAAEAAAAJAw=="
        RemoteKey.NUM_1 -> "AAAAAQAAAAEAAAAAAw=="
        RemoteKey.NUM_2 -> "AAAAAQAAAAEAAAABAw=="
        RemoteKey.NUM_3 -> "AAAAAQAAAAEAAAACAw=="
        RemoteKey.NUM_4 -> "AAAAAQAAAAEAAAADAw=="
        RemoteKey.NUM_5 -> "AAAAAQAAAAEAAAAEAw=="
        RemoteKey.NUM_6 -> "AAAAAQAAAAEAAAAFAw=="
        RemoteKey.NUM_7 -> "AAAAAQAAAAEAAAAGAw=="
        RemoteKey.NUM_8 -> "AAAAAQAAAAEAAAAHAw=="
        RemoteKey.NUM_9 -> "AAAAAQAAAAEAAAAIAw=="
    }

    private companion object {
        const val IRCC_POWER = "AAAAAQAAAAEAAAAVAw=="
        const val IRCC_CONFIRM = "AAAAAQAAAAEAAABlAw=="
    }
}
