package com.yarden.universalremote

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yarden.universalremote.databinding.ActivityRemoteBinding
import com.yarden.universalremote.discovery.DiscoveredDevice
import com.yarden.universalremote.discovery.TvBrand
import com.yarden.universalremote.protocol.ConnectResult
import com.yarden.universalremote.protocol.PairingCallback
import com.yarden.universalremote.protocol.ProtocolFactory
import com.yarden.universalremote.protocol.RemoteKey
import com.yarden.universalremote.protocol.TvProtocol
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class RemoteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IP = "extra_ip"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_BRAND = "extra_brand"
        const val EXTRA_PORT = "extra_port"
    }

    private lateinit var binding: ActivityRemoteBinding
    private lateinit var protocol: TvProtocol

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val device = DiscoveredDevice(
            ip = intent.getStringExtra(EXTRA_IP) ?: return finish(),
            name = intent.getStringExtra(EXTRA_NAME) ?: "TV",
            brand = TvBrand.valueOf(intent.getStringExtra(EXTRA_BRAND) ?: TvBrand.UNKNOWN.name),
            port = intent.getIntExtra(EXTRA_PORT, 0)
        )
        protocol = ProtocolFactory.create(device)

        binding.title.text = device.name
        binding.subtitle.text = "${device.brand.displayName} · ${device.ip}"
        binding.statusText.text = getString(R.string.connecting)
        setControlsEnabled(false)

        wireButtons()
        connect()
    }

    private fun connect() {
        lifecycleScope.launch {
            val result = protocol.connect(dialogPairingCallback())
            when (result) {
                is ConnectResult.Success -> {
                    binding.statusText.text = getString(R.string.connected)
                    setControlsEnabled(true)
                }
                is ConnectResult.Failed -> {
                    binding.statusText.text = getString(R.string.connection_failed, result.reason)
                    setControlsEnabled(false)
                }
                is ConnectResult.NeedsPin -> {
                    // handled inline by protocols via PairingCallback.requestPin
                }
            }
        }
    }

    private fun dialogPairingCallback(): PairingCallback = object : PairingCallback {
        override suspend fun requestPin(prompt: String): String? = suspendCancellableCoroutine { cont ->
            runOnUiThread {
                val input = EditText(this@RemoteActivity)
                val dialog = AlertDialog.Builder(this@RemoteActivity)
                    .setTitle(R.string.pairing_required)
                    .setMessage(prompt)
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        if (cont.isActive) cont.resumeWith(Result.success(input.text.toString()))
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                    .create()
                cont.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }
        }

        override fun showMessage(message: String) {
            runOnUiThread { Toast.makeText(this@RemoteActivity, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        for ((viewId, _) in buttonMap()) {
            findViewById<View>(viewId).isEnabled = enabled
        }
    }

    private fun buttonMap(): List<Pair<Int, RemoteKey>> = listOf(
        R.id.btnPower to RemoteKey.POWER,
        R.id.btnVolUp to RemoteKey.VOLUME_UP,
        R.id.btnVolDown to RemoteKey.VOLUME_DOWN,
        R.id.btnMute to RemoteKey.MUTE,
        R.id.btnChUp to RemoteKey.CHANNEL_UP,
        R.id.btnChDown to RemoteKey.CHANNEL_DOWN,
        R.id.btnDpadUp to RemoteKey.DPAD_UP,
        R.id.btnDpadDown to RemoteKey.DPAD_DOWN,
        R.id.btnDpadLeft to RemoteKey.DPAD_LEFT,
        R.id.btnDpadRight to RemoteKey.DPAD_RIGHT,
        R.id.btnDpadOk to RemoteKey.DPAD_SELECT,
        R.id.btnBack to RemoteKey.BACK,
        R.id.btnHome to RemoteKey.HOME,
        R.id.btnMenu to RemoteKey.MENU,
        R.id.btnInput to RemoteKey.INPUT_SOURCE,
        R.id.btnPlayPause to RemoteKey.PLAY_PAUSE,
        R.id.btnRewind to RemoteKey.REWIND,
        R.id.btnFastForward to RemoteKey.FAST_FORWARD,
        R.id.btnNum0 to RemoteKey.NUM_0,
        R.id.btnNum1 to RemoteKey.NUM_1,
        R.id.btnNum2 to RemoteKey.NUM_2,
        R.id.btnNum3 to RemoteKey.NUM_3,
        R.id.btnNum4 to RemoteKey.NUM_4,
        R.id.btnNum5 to RemoteKey.NUM_5,
        R.id.btnNum6 to RemoteKey.NUM_6,
        R.id.btnNum7 to RemoteKey.NUM_7,
        R.id.btnNum8 to RemoteKey.NUM_8,
        R.id.btnNum9 to RemoteKey.NUM_9
    )

    private fun wireButtons() {
        for ((viewId, key) in buttonMap()) {
            findViewById<View>(viewId).setOnClickListener { protocol.sendKey(key) }
        }
    }

    override fun onDestroy() {
        protocol.disconnect()
        super.onDestroy()
    }
}
