package com.yarden.universalremote.protocol

import com.yarden.universalremote.discovery.DiscoveredDevice
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * LG webOS "Second Screen" protocol (SSAP over WebSocket).
 *
 * Registering opens an on-screen pairing prompt on the TV the first time; webOS then returns a
 * "client-key" this class stores in memory for the session so later commands don't re-prompt.
 * Discrete button presses (the D-pad, Home, volume, channel, numbers...) are emulated the same
 * way LG's own Magic Remote does it: by opening a secondary "pointer input" socket and sending
 * simple `type:button` text frames to it, rather than JSON SSAP calls.
 */
class LgWebOsProtocol(override val device: DiscoveredDevice) : TvProtocol {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var mainSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var clientKey: String? = null
    private var nextId = 1

    override suspend fun connect(pairing: PairingCallback): ConnectResult = suspendCancellableCoroutine { cont ->
        val url = "ws://${device.ip}:${device.port}"
        val request = Request.Builder().url(url).build()
        var resumed = false

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildRegisterPayload().toString())
                pairing.showMessage("If your LG TV shows a pairing prompt, select Accept.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (_: Exception) { return }
                when (json.optString("type")) {
                    "registered" -> {
                        clientKey = json.optJSONObject("payload")?.optString("client-key")
                        requestPointerSocket(webSocket)
                    }
                    "response" -> {
                        val socketPath = json.optJSONObject("payload")?.optString("socketPath")
                        if (!socketPath.isNullOrEmpty() && !resumed) {
                            openPointerSocket(socketPath)
                            resumed = true
                            cont.resume(ConnectResult.Success)
                        }
                    }
                    "error" -> {
                        if (!resumed) {
                            resumed = true
                            cont.resume(ConnectResult.Failed(json.optString("error", "LG TV rejected the request")))
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!resumed) {
                    resumed = true
                    cont.resume(ConnectResult.Failed(t.message ?: "Could not reach the LG TV"))
                }
            }
        })
        mainSocket = ws
        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun requestPointerSocket(webSocket: WebSocket) {
        val payload = JSONObject().apply {
            put("type", "request")
            put("id", "req_${nextId++}")
            put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
        }
        webSocket.send(payload.toString())
    }

    private fun openPointerSocket(socketPath: String) {
        val request = Request.Builder().url(socketPath).build()
        pointerSocket = client.newWebSocket(request, object : WebSocketListener() {})
    }

    override fun disconnect() {
        pointerSocket?.close(1000, "bye")
        mainSocket?.close(1000, "bye")
        pointerSocket = null
        mainSocket = null
    }

    override fun sendKey(key: RemoteKey) {
        if (key == RemoteKey.POWER) {
            sendSsapRequest("ssap://system/turnOff")
            return
        }
        val buttonName = mapButtonName(key) ?: return
        pointerSocket?.send("type:button\nname:$buttonName\n\n")
    }

    private fun sendSsapRequest(uri: String, payload: JSONObject? = null) {
        val message = JSONObject().apply {
            put("type", "request")
            put("id", "req_${nextId++}")
            put("uri", uri)
            if (payload != null) put("payload", payload)
        }
        mainSocket?.send(message.toString())
    }

    private fun mapButtonName(key: RemoteKey): String? = when (key) {
        RemoteKey.VOLUME_UP -> "VOLUMEUP"
        RemoteKey.VOLUME_DOWN -> "VOLUMEDOWN"
        RemoteKey.MUTE -> "MUTE"
        RemoteKey.CHANNEL_UP -> "CHANNELUP"
        RemoteKey.CHANNEL_DOWN -> "CHANNELDOWN"
        RemoteKey.DPAD_UP -> "UP"
        RemoteKey.DPAD_DOWN -> "DOWN"
        RemoteKey.DPAD_LEFT -> "LEFT"
        RemoteKey.DPAD_RIGHT -> "RIGHT"
        RemoteKey.DPAD_SELECT -> "ENTER"
        RemoteKey.BACK -> "BACK"
        RemoteKey.HOME -> "HOME"
        RemoteKey.MENU -> "MENU"
        RemoteKey.INPUT_SOURCE -> "INPUT"
        RemoteKey.PLAY_PAUSE -> "PLAY"
        RemoteKey.REWIND -> "REWIND"
        RemoteKey.FAST_FORWARD -> "FASTFORWARD"
        RemoteKey.NUM_0 -> "0"
        RemoteKey.NUM_1 -> "1"
        RemoteKey.NUM_2 -> "2"
        RemoteKey.NUM_3 -> "3"
        RemoteKey.NUM_4 -> "4"
        RemoteKey.NUM_5 -> "5"
        RemoteKey.NUM_6 -> "6"
        RemoteKey.NUM_7 -> "7"
        RemoteKey.NUM_8 -> "8"
        RemoteKey.NUM_9 -> "9"
        RemoteKey.POWER -> null
    }

    private fun buildRegisterPayload(): JSONObject {
        val manifest = JSONObject().apply {
            put("manifestVersion", 1)
            put("appVersion", "1.1")
            put(
                "permissions",
                JSONArray(
                    listOf(
                        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE",
                        "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
                        "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK",
                        "CONTROL_INPUT_TV", "CONTROL_POWER", "CONTROL_TV_SCREEN",
                        "CONTROL_TV_STANDBY", "READ_APP_STATUS", "READ_CURRENT_CHANNEL",
                        "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
                        "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST"
                    )
                )
            )
        }
        val payload = JSONObject().apply {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            put("manifest", manifest)
            clientKey?.let { put("client-key", it) }
        }
        return JSONObject().apply {
            put("type", "register")
            put("id", "register_0")
            put("payload", payload)
        }
    }
}
