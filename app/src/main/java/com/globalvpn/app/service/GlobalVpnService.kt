package com.globalvpn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.globalvpn.app.MainActivity
import com.globalvpn.app.R
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Socket

class GlobalVpnService : Service() {

    enum class State { IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    inner class LocalBinder : Binder() {
        fun getService(): GlobalVpnService = this@GlobalVpnService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var backend: GoBackend
    private var currentState = State.IDLE
    private var currentCountry: String = ""
    var errorMessage: String = ""

    private val _stateFlow = MutableStateFlow(State.IDLE)
    val stateFlow: StateFlow<State> get() = _stateFlow

    private val tunnel = object : Tunnel {
        override fun getName() = "GlobalVPN"
        override fun onStateChange(newState: Tunnel.State) {
            val mapped = when (newState) {
                Tunnel.State.UP -> State.CONNECTED
                Tunnel.State.DOWN -> if (currentState == State.DISCONNECTING) State.IDLE else currentState
                else -> currentState
            }
            updateState(mapped)
        }
    }

    override fun onCreate() {
        super.onCreate()
        backend = GoBackend(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: return START_NOT_STICKY
                val country = intent.getStringExtra(EXTRA_COUNTRY) ?: ""
                connect(config, country)
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    fun connect(configString: String, country: String) {
        currentCountry = country
        updateState(State.CONNECTING)
        startForeground(NOTIFICATION_ID, buildNotification())

        scope.launch {
            // Cloudflare WARP supports multiple UDP ports. Try each in order
            // until one passes a live connectivity check. UDP 2408 is often
            // blocked on corporate/hotel/restrictive networks.
            val ports = listOf(2408, 500, 1701, 4500)
            var connected = false

            for (port in ports) {
                val cfg = configString.withPort(port)
                try {
                    val parsed = Config.parse(BufferedReader(StringReader(cfg)))
                    backend.setState(tunnel, Tunnel.State.UP, parsed)
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Config parse error"
                    continue
                }

                // Give the WireGuard handshake up to 7 seconds to complete
                delay(7000)

                if (tunnelIsAlive()) {
                    connected = true
                    break
                }

                // Tear down before trying next port
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                delay(500)
            }

            if (!connected) {
                errorMessage = if (errorMessage.isNotEmpty()) errorMessage
                               else "No UDP port responded (tried 2408, 500, 1701, 4500). " +
                                    "Try a different network."
                updateState(State.ERROR)
                ServiceCompat.stopForeground(this@GlobalVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // Test whether the tunnel is actually forwarding traffic by opening a
    // TCP connection to 1.1.1.1:80 through the VPN interface.
    private fun tunnelIsAlive(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("1.1.1.1", 80), 4000)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // Replace the Endpoint port in a WireGuard config string.
    private fun String.withPort(port: Int): String {
        // Matches "Endpoint = host:oldport" and replaces the port.
        return replace(Regex("""(Endpoint\s*=\s*\S+):(\d+)""")) { mr ->
            "${mr.groupValues[1]}:$port"
        }
    }

    fun disconnect() {
        updateState(State.DISCONNECTING)
        scope.launch {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
            } finally {
                updateState(State.IDLE)
                ServiceCompat.stopForeground(this@GlobalVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    fun getCurrentState(): State = currentState
    fun getErrorMessage(): String = errorMessage

    private fun updateState(state: State) {
        currentState = state
        _stateFlow.tryEmit(state)
        if (state == State.CONNECTED || state == State.CONNECTING) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GlobalVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (currentState) {
            State.CONNECTING -> "Connecting to $currentCountry…"
            State.CONNECTED  -> "Connected · $currentCountry"
            State.DISCONNECTING -> "Disconnecting…"
            else -> "GlobalVPN"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText("Tap to open app")
            .setContentIntent(mainIntent)
            .addAction(R.drawable.ic_shield, "Disconnect", disconnectIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows active VPN connection status" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_CONNECT = "com.globalvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.globalvpn.DISCONNECT"
        const val EXTRA_CONFIG = "extra_config"
        const val EXTRA_COUNTRY = "extra_country"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1001

        fun connectIntent(context: Context, config: String, country: String): Intent =
            Intent(context, GlobalVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CONFIG, config)
                putExtra(EXTRA_COUNTRY, country)
            }

        fun disconnectIntent(context: Context): Intent =
            Intent(context, GlobalVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
    }
}
