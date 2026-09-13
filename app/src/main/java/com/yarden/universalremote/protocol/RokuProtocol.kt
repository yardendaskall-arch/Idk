package com.yarden.universalremote.protocol

import com.yarden.universalremote.discovery.DiscoveredDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Roku External Control Protocol (ECP). Plain HTTP, no pairing required, works out of the
 * box on every Roku TV and Roku streaming player unless the owner disabled ECP in settings.
 * https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md
 */
class RokuProtocol(override val device: DiscoveredDevice) : TvProtocol {

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val base = "http://${device.ip}:${device.port}"
    private val empty = "".toRequestBody(null)

    override suspend fun connect(pairing: PairingCallback): ConnectResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val request = Request.Builder().url("$base/query/device-info").get().build()
            client.newCall(request).execute().use {
                if (it.isSuccessful) ConnectResult.Success
                else ConnectResult.Failed("Roku did not respond (HTTP ${it.code})")
            }
        } catch (e: Exception) {
            ConnectResult.Failed(e.message ?: "Could not reach the Roku")
        }
    }

    override fun disconnect() = Unit

    override fun sendKey(key: RemoteKey) {
        val ecpKey = mapKey(key) ?: return
        val request = Request.Builder().url("$base/keypress/$ecpKey").post(empty).build()
        client.newCall(request).enqueue(NoopCallback)
    }

    private fun mapKey(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "Power"
        RemoteKey.VOLUME_UP -> "VolumeUp"
        RemoteKey.VOLUME_DOWN -> "VolumeDown"
        RemoteKey.MUTE -> "VolumeMute"
        RemoteKey.CHANNEL_UP -> "ChannelUp"
        RemoteKey.CHANNEL_DOWN -> "ChannelDown"
        RemoteKey.DPAD_UP -> "Up"
        RemoteKey.DPAD_DOWN -> "Down"
        RemoteKey.DPAD_LEFT -> "Left"
        RemoteKey.DPAD_RIGHT -> "Right"
        RemoteKey.DPAD_SELECT -> "Select"
        RemoteKey.BACK -> "Back"
        RemoteKey.HOME -> "Home"
        RemoteKey.MENU -> "Info"
        RemoteKey.INPUT_SOURCE -> "InputTuner"
        RemoteKey.PLAY_PAUSE -> "Play"
        RemoteKey.REWIND -> "Rev"
        RemoteKey.FAST_FORWARD -> "Fwd"
        RemoteKey.NUM_0 -> "Lit_0"
        RemoteKey.NUM_1 -> "Lit_1"
        RemoteKey.NUM_2 -> "Lit_2"
        RemoteKey.NUM_3 -> "Lit_3"
        RemoteKey.NUM_4 -> "Lit_4"
        RemoteKey.NUM_5 -> "Lit_5"
        RemoteKey.NUM_6 -> "Lit_6"
        RemoteKey.NUM_7 -> "Lit_7"
        RemoteKey.NUM_8 -> "Lit_8"
        RemoteKey.NUM_9 -> "Lit_9"
    }
}
