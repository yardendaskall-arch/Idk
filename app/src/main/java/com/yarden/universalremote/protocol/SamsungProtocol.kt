package com.yarden.universalremote.protocol

import android.util.Base64
import com.yarden.universalremote.discovery.DiscoveredDevice
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

/**
 * Samsung Tizen "Remote Control" WebSocket API, used by all Samsung Smart TVs since ~2016.
 * The first connection makes the TV show an on-screen "Allow this app to connect?" prompt;
 * the user needs to accept it there once. Which of the two ports actually answers (plain
 * WS on 8001, or TLS on 8002) varies by model and can even be flaky moment to moment on the
 * same TV, so this tries the port discovery found first and falls back to the other one
 * rather than failing outright. The TLS trust-all config is inert for plain ws:// connections,
 * so one client handles both -- there's no real CA to validate a TV's own LAN-only IP against
 * anyway, same reasoning as VizioProtocol's.
 */
class SamsungProtocol(override val device: DiscoveredDevice) : TvProtocol {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
        .sslSocketFactory(trustAllSslSocketFactory(), trustAllManager())
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
        .build()

    private var socket: WebSocket? = null

    override suspend fun connect(pairing: PairingCallback): ConnectResult {
        val primaryPort = device.port
        val fallbackPort = if (primaryPort == 8002) 8001 else 8002

        val first = attemptConnect(primaryPort, pairing)
        if (first is ConnectResult.Success) return first

        val second = attemptConnect(fallbackPort, pairing)
        return if (second is ConnectResult.Success) second else first
    }

    private suspend fun attemptConnect(port: Int, pairing: PairingCallback): ConnectResult =
        suspendCancellableCoroutine { cont ->
            val appName = Base64.encodeToString("UniversalRemote".toByteArray(), Base64.NO_WRAP)
            val scheme = if (port == 8002) "wss" else "ws"
            val url = "$scheme://${device.ip}:$port/api/v2/channels/samsung.remote.control?name=$appName"
            val request = Request.Builder().url(url).build()

            var resumed = false
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (resumed) return
                    try {
                        val json = JSONObject(text)
                        when (json.optString("event")) {
                            "ms.channel.connect" -> {
                                resumed = true
                                socket = webSocket
                                cont.resume(ConnectResult.Success)
                            }
                            "ms.channel.unauthorized" -> {
                                resumed = true
                                cont.resume(ConnectResult.Failed("Connection was denied on the TV"))
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    if (!resumed) {
                        resumed = true
                        cont.resume(ConnectResult.Failed(t.message ?: "Could not reach the Samsung TV"))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!resumed) {
                        resumed = true
                        cont.resume(ConnectResult.Failed("Connection closed before pairing completed"))
                    }
                }
            })
            pairing.showMessage("If your Samsung TV shows an 'Allow connection?' popup, select Allow.")

            cont.invokeOnCancellation { ws.cancel() }
        }

    override fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
    }

    override fun sendKey(key: RemoteKey) {
        val keyCode = mapKey(key) ?: return
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", keyCode)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        }
        socket?.send(payload.toString())
    }

    private fun mapKey(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "KEY_POWER"
        RemoteKey.VOLUME_UP -> "KEY_VOLUP"
        RemoteKey.VOLUME_DOWN -> "KEY_VOLDOWN"
        RemoteKey.MUTE -> "KEY_MUTE"
        RemoteKey.CHANNEL_UP -> "KEY_CHUP"
        RemoteKey.CHANNEL_DOWN -> "KEY_CHDOWN"
        RemoteKey.DPAD_UP -> "KEY_UP"
        RemoteKey.DPAD_DOWN -> "KEY_DOWN"
        RemoteKey.DPAD_LEFT -> "KEY_LEFT"
        RemoteKey.DPAD_RIGHT -> "KEY_RIGHT"
        RemoteKey.DPAD_SELECT -> "KEY_ENTER"
        RemoteKey.BACK -> "KEY_RETURN"
        RemoteKey.HOME -> "KEY_HOME"
        RemoteKey.MENU -> "KEY_MENU"
        RemoteKey.INPUT_SOURCE -> "KEY_SOURCE"
        RemoteKey.PLAY_PAUSE -> "KEY_PLAY"
        RemoteKey.REWIND -> "KEY_REWIND"
        RemoteKey.FAST_FORWARD -> "KEY_FF"
        RemoteKey.NUM_0 -> "KEY_0"
        RemoteKey.NUM_1 -> "KEY_1"
        RemoteKey.NUM_2 -> "KEY_2"
        RemoteKey.NUM_3 -> "KEY_3"
        RemoteKey.NUM_4 -> "KEY_4"
        RemoteKey.NUM_5 -> "KEY_5"
        RemoteKey.NUM_6 -> "KEY_6"
        RemoteKey.NUM_7 -> "KEY_7"
        RemoteKey.NUM_8 -> "KEY_8"
        RemoteKey.NUM_9 -> "KEY_9"
    }

    private fun trustAllManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun trustAllSslSocketFactory() = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAllManager()), SecureRandom())
    }.socketFactory
}
