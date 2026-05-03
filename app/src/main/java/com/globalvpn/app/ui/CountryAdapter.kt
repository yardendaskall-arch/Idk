package com.globalvpn.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.globalvpn.app.R
import com.globalvpn.app.data.Country

class CountryAdapter(
    private val onCountryClick: (Country) -> Unit
) : ListAdapter<Country, CountryAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val flag: TextView = view.findViewById(R.id.tvFlag)
        private val name: TextView = view.findViewById(R.id.tvCountryName)
        private val servers: TextView = view.findViewById(R.id.tvServerCount)

        fun bind(country: Country) {
            flag.text = country.flagEmoji
            name.text = country.name
            servers.text = "${country.serverCount} server${if (country.serverCount != 1) "s" else ""}"
            itemView.setOnClickListener { onCountryClick(country) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_country, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Country>() {
            override fun areItemsTheSame(a: Country, b: Country) = a.code == b.code
            override fun areContentsTheSame(a: Country, b: Country) = a == b
        }
    }
}
