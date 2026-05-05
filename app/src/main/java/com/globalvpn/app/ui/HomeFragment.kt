package com.globalvpn.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.globalvpn.app.R
import com.globalvpn.app.service.GlobalVpnService
import com.globalvpn.app.viewmodel.VpnViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private val viewModel: VpnViewModel by activityViewModels()

    private lateinit var tvStatus: TextView
    private lateinit var tvSelectedCountry: TextView
    private lateinit var btnConnect: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvNoSelection: TextView

    private var vpnService: GlobalVpnService? = null
    private var serviceBound = false
    private var vpnState = GlobalVpnService.State.IDLE

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpnService = (service as GlobalVpnService.LocalBinder).getService()
            serviceBound = true
            lifecycleScope.launch {
                vpnService?.stateFlow?.collectLatest { state ->
                    vpnState = state
                    updateUI()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            serviceBound = false
        }
    }

    private val adapter = CountryAdapter { country ->
        val server = country.servers.first()
        viewModel.selectServer(server)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus = view.findViewById(R.id.tvStatus)
        tvSelectedCountry = view.findViewById(R.id.tvSelectedCountry)
        btnConnect = view.findViewById(R.id.btnConnect)
        progressBar = view.findViewById(R.id.progressBar)
        recyclerView = view.findViewById(R.id.rvCountries)
        tvNoSelection = view.findViewById(R.id.tvNoSelection)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        viewModel.countries.observe(viewLifecycleOwner) { adapter.submitList(it) }

        viewModel.selectedServer.observe(viewLifecycleOwner) { server ->
            tvSelectedCountry.text = if (server != null) {
                "${server.flagEmoji}  ${server.displayName}"
            } else {
                "No location selected"
            }
            tvNoSelection.visibility = if (server == null) View.VISIBLE else View.GONE
        }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            btnConnect.isEnabled = !loading
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        btnConnect.setOnClickListener { onConnectClicked() }
        bindVpnService()
    }

    private fun onConnectClicked() {
        when (vpnState) {
            GlobalVpnService.State.CONNECTED, GlobalVpnService.State.CONNECTING -> {
                requireContext().startService(GlobalVpnService.disconnectIntent(requireContext()))
            }
            else -> {
                if (viewModel.selectedServer.value == null) {
                    Toast.makeText(requireContext(), "Please select a country first", Toast.LENGTH_SHORT).show()
                    return
                }
                requestVpnPermissionAndConnect()
            }
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val intent = android.net.VpnService.prepare(requireContext())
        if (intent != null) {
            startActivityForResult(intent, REQUEST_VPN_PERMISSION)
        } else {
            startVpnConnection()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION && resultCode == android.app.Activity.RESULT_OK) {
            startVpnConnection()
        }
    }

    private fun startVpnConnection() {
        viewModel.prepareVpnConfig { config, countryName ->
            requireActivity().runOnUiThread {
                val intent = GlobalVpnService.connectIntent(requireContext(), config, countryName)
                requireContext().startForegroundService(intent)
                bindVpnService()
            }
        }
    }

    private fun bindVpnService() {
        val intent = Intent(requireContext(), GlobalVpnService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUI() {
        requireActivity().runOnUiThread {
            when (vpnState) {
                GlobalVpnService.State.IDLE -> {
                    tvStatus.text = "● Disconnected"
                    tvStatus.setTextColor(requireContext().getColor(R.color.status_disconnected))
                    btnConnect.text = "Connect"
                    btnConnect.isEnabled = true
                }
                GlobalVpnService.State.CONNECTING -> {
                    tvStatus.text = "◌ Connecting…"
                    tvStatus.setTextColor(requireContext().getColor(R.color.status_connecting))
                    btnConnect.text = "Connecting…"
                    btnConnect.isEnabled = false
                }
                GlobalVpnService.State.CONNECTED -> {
                    tvStatus.text = "● Connected"
                    tvStatus.setTextColor(requireContext().getColor(R.color.status_connected))
                    btnConnect.text = "Disconnect"
                    btnConnect.isEnabled = true
                }
                GlobalVpnService.State.DISCONNECTING -> {
                    tvStatus.text = "◌ Disconnecting…"
                    tvStatus.setTextColor(requireContext().getColor(R.color.status_connecting))
                    btnConnect.text = "Disconnecting…"
                    btnConnect.isEnabled = false
                }
                GlobalVpnService.State.ERROR -> {
                    tvStatus.text = "✕ Error"
                    tvStatus.setTextColor(requireContext().getColor(R.color.status_disconnected))
                    btnConnect.text = "Connect"
                    btnConnect.isEnabled = true
                    val msg = vpnService?.getErrorMessage()?.takeIf { it.isNotEmpty() } ?: "Connection failed"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 1001
    }
}
