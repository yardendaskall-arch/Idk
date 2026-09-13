package com.yarden.universalremote

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yarden.universalremote.databinding.ItemDeviceBinding
import com.yarden.universalremote.discovery.DiscoveredDevice

class DeviceListAdapter(
    private val onClick: (DiscoveredDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

    private val devices = mutableListOf<DiscoveredDevice>()

    fun submit(device: DiscoveredDevice) {
        if (devices.any { it.id == device.id }) return
        devices.add(device)
        notifyItemInserted(devices.size - 1)
    }

    fun clear() {
        val size = devices.size
        devices.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class ViewHolder(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(device: DiscoveredDevice) {
            binding.deviceName.text = device.name
            binding.deviceBrand.text = device.brand.displayName
            binding.deviceIp.text = device.ip
            binding.root.setOnClickListener { onClick(device) }
        }
    }
}
