package com.yarden.universalremote.protocol

import com.yarden.universalremote.discovery.DiscoveredDevice

/** A single remote-control button, independent of which brand's protocol handles it. */
enum class RemoteKey {
    POWER, VOLUME_UP, VOLUME_DOWN, MUTE,
    CHANNEL_UP, CHANNEL_DOWN,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_SELECT,
    BACK, HOME, MENU, INPUT_SOURCE,
    PLAY_PAUSE, REWIND, FAST_FORWARD,
    NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9
}

/** Result of trying to reach/pair with a TV. */
sealed class ConnectResult {
    object Success : ConnectResult()
    data class NeedsPin(val prompt: String) : ConnectResult()
    data class Failed(val reason: String) : ConnectResult()
}

/**
 * Lets a protocol ask the user something mid-pairing, e.g. "enter the PIN shown on your TV"
 * or "confirm the Allow prompt on the TV screen".
 */
interface PairingCallback {
    suspend fun requestPin(prompt: String): String?
    fun showMessage(message: String)
}

/** Common surface every brand-specific TV controller implements. */
interface TvProtocol {
    val device: DiscoveredDevice

    /** Opens the connection and performs any pairing handshake required by this brand. */
    suspend fun connect(pairing: PairingCallback): ConnectResult

    fun disconnect()

    /** Fire-and-forget: send one button press. Safe to call rapidly. */
    fun sendKey(key: RemoteKey)
}
