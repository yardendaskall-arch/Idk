package com.yarden.universalremote.discovery

enum class TvBrand(val displayName: String) {
    ROKU("Roku / Roku TV"),
    SAMSUNG("Samsung (Tizen)"),
    LG_WEBOS("LG (webOS)"),
    SONY_BRAVIA("Sony (Bravia)"),
    VIZIO("Vizio (SmartCast)"),
    UNKNOWN("Unknown TV")
}

data class DiscoveredDevice(
    val ip: String,
    val name: String,
    val brand: TvBrand,
    val port: Int
) {
    val id: String get() = "$ip:$port:${brand.name}"
}
