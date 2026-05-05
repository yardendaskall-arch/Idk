package com.globalvpn.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ServerRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("globalvpn_prefs", Context.MODE_PRIVATE)
    private val warpApi = WarpApi()

    companion object {
        private const val KEY_WARP_PRIVATE_KEY = "warp_private_key"
        private const val KEY_WARP_ADDRESS = "warp_address"
        private const val KEY_WARP_SERVER_KEY = "warp_server_key"
        private const val KEY_WARP_ENDPOINT = "warp_endpoint"
        private const val KEY_CREDS_VERSION = "creds_version"
        private const val CREDS_VERSION = 3  // bump to invalidate cached credentials

        // Cloudflare PoP locations (city → country code + name)
        val CLOUDFLARE_LOCATIONS = listOf(
            VpnServer("cf-us-1", "US", "United States", "New York", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-us-2", "US", "United States", "Los Angeles", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-us-3", "US", "United States", "Chicago", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-gb-1", "GB", "United Kingdom", "London", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-de-1", "DE", "Germany", "Frankfurt", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-fr-1", "FR", "France", "Paris", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-nl-1", "NL", "Netherlands", "Amsterdam", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-sg-1", "SG", "Singapore", "Singapore", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-jp-1", "JP", "Japan", "Tokyo", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-au-1", "AU", "Australia", "Sydney", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-ca-1", "CA", "Canada", "Toronto", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-br-1", "BR", "Brazil", "São Paulo", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-in-1", "IN", "India", "Mumbai", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-kr-1", "KR", "South Korea", "Seoul", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-hk-1", "HK", "Hong Kong", "Hong Kong", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-se-1", "SE", "Sweden", "Stockholm", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-ch-1", "CH", "Switzerland", "Zurich", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-es-1", "ES", "Spain", "Madrid", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-it-1", "IT", "Italy", "Milan", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-mx-1", "MX", "Mexico", "Mexico City", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-za-1", "ZA", "South Africa", "Johannesburg", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-ae-1", "AE", "UAE", "Dubai", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-tr-1", "TR", "Turkey", "Istanbul", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-pl-1", "PL", "Poland", "Warsaw", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-nz-1", "NZ", "New Zealand", "Auckland", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-no-1", "NO", "Norway", "Oslo", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-fi-1", "FI", "Finland", "Helsinki", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-pt-1", "PT", "Portugal", "Lisbon", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-ar-1", "AR", "Argentina", "Buenos Aires", "engage.cloudflareclient.com:2408", ""),
            VpnServer("cf-id-1", "ID", "Indonesia", "Jakarta", "engage.cloudflareclient.com:2408", "")
        )
    }

    fun getCountries(): List<Country> {
        return CLOUDFLARE_LOCATIONS
            .groupBy { it.countryCode }
            .map { (_, servers) ->
                val first = servers.first()
                Country(first.countryCode, first.countryName, servers)
            }
            .sortedBy { it.name }
    }

    fun hasSavedCredentials(): Boolean =
        prefs.getString(KEY_WARP_PRIVATE_KEY, null) != null &&
        prefs.getInt(KEY_CREDS_VERSION, 0) == CREDS_VERSION

    fun getSavedCredentials(): WarpCredentials? {
        if (prefs.getInt(KEY_CREDS_VERSION, 0) != CREDS_VERSION) return null
        val pk = prefs.getString(KEY_WARP_PRIVATE_KEY, null) ?: return null
        return WarpCredentials(
            privateKey = pk,
            clientAddress = prefs.getString(KEY_WARP_ADDRESS, "") ?: "",
            serverPublicKey = prefs.getString(KEY_WARP_SERVER_KEY, "") ?: "",
            serverEndpoint = prefs.getString(KEY_WARP_ENDPOINT, "") ?: ""
        )
    }

    private fun saveCredentials(creds: WarpCredentials) {
        prefs.edit()
            .putString(KEY_WARP_PRIVATE_KEY, creds.privateKey)
            .putString(KEY_WARP_ADDRESS, creds.clientAddress)
            .putString(KEY_WARP_SERVER_KEY, creds.serverPublicKey)
            .putString(KEY_WARP_ENDPOINT, creds.serverEndpoint)
            .putInt(KEY_CREDS_VERSION, CREDS_VERSION)
            .apply()
    }

    suspend fun getOrRegisterCredentials(): WarpCredentials = withContext(Dispatchers.IO) {
        getSavedCredentials() ?: warpApi.register().also { saveCredentials(it) }
    }

    fun buildConfigString(creds: WarpCredentials): String = warpApi.buildWireGuardConfig(creds)
}
