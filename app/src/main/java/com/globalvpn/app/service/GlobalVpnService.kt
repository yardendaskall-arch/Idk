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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.StringReader

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
    private var errorMessage: String = ""

    private val _stateFlow = MutableStateFlow(State.IDLE)
    val stateFlow: StateFlow<State> get() = _stateFlow

    private val tunnel = object : Tunnel {
        override fun getName() = "GlobalVPN"
        override fun onStateChange(newState: Tunnel.State) {
            val mapped = when (newState) {
                Tunnel.State.UP -> State.CONNECTED
                Tunnel.State.DOWN -> State.IDLE
                else -> currentState  // TOGGLE or future states
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
            try {
                val config = Config.parse(BufferedReader(StringReader(configString)))
                backend.setState(tunnel, Tunnel.State.UP, config)
            } catch (e: Exception) {
                errorMessage = e.message ?: "Connection failed"
                updateState(State.ERROR)
                stopSelf()
            }
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
            State.CONNECTED -> "Connected · $currentCountry"
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
            CHANNEL_ID,
            "VPN Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active VPN connection status"
        }
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
