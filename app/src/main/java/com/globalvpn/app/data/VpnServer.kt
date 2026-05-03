package com.globalvpn.app.data

data class VpnServer(
    val id: String,
    val countryCode: String,
    val countryName: String,
    val cityName: String,
    val endpoint: String,       // host:port
    val publicKey: String,      // WireGuard server public key
    val ping: Int = -1,
    val load: Int = 0,          // server load 0-100%
    val isPremium: Boolean = false
) {
    val flagEmoji: String get() = countryCodeToFlag(countryCode)
    val displayName: String get() = if (cityName.isNotEmpty()) "$countryName – $cityName" else countryName

    companion object {
        private fun countryCodeToFlag(code: String): String {
            if (code.length != 2) return "🌐"
            val base = 0x1F1E6 - 'A'.code
            return String(Character.toChars(base + code[0].uppercaseChar().code)) +
                    String(Character.toChars(base + code[1].uppercaseChar().code))
        }
    }
}

data class Country(
    val code: String,
    val name: String,
    val servers: List<VpnServer>
) {
    val flagEmoji: String get() = VpnServer("", code, name, "", "", "").flagEmoji
    val serverCount: Int get() = servers.size
    val bestPing: Int get() = servers.filter { it.ping >= 0 }.minOfOrNull { it.ping } ?: -1
}

data class WarpCredentials(
    val privateKey: String,
    val clientAddress: String,
    val serverPublicKey: String,
    val serverEndpoint: String
)
