package com.yarden.universalremote

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.yarden.universalremote.databinding.ActivityScanBinding
import com.yarden.universalremote.discovery.DiscoveredDevice
import com.yarden.universalremote.discovery.NetworkScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var adapter: DeviceListAdapter
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = DeviceListAdapter { device -> openRemote(device) }
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = adapter

        binding.scanButton.setOnClickListener { startScan() }
        startScan()
    }

    private fun startScan() {
        scanJob?.cancel()
        adapter.clear()
        binding.emptyState.visibility = android.view.View.GONE
        binding.progress.visibility = android.view.View.VISIBLE
        binding.scanButton.isEnabled = false

        scanJob = lifecycleScope.launch {
            NetworkScanner(applicationContext).scan()
                .catch { }
                .onCompletion {
                    binding.progress.visibility = android.view.View.GONE
                    binding.scanButton.isEnabled = true
                    if (adapter.itemCount == 0) {
                        binding.emptyState.visibility = android.view.View.VISIBLE
                    }
                }
                .collect { device -> adapter.submit(device) }
        }
    }

    private fun openRemote(device: DiscoveredDevice) {
        val intent = Intent(this, RemoteActivity::class.java).apply {
            putExtra(RemoteActivity.EXTRA_IP, device.ip)
            putExtra(RemoteActivity.EXTRA_NAME, device.name)
            putExtra(RemoteActivity.EXTRA_BRAND, device.brand.name)
            putExtra(RemoteActivity.EXTRA_PORT, device.port)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }
}
