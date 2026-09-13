package com.yarden.universalremote.protocol

import com.yarden.universalremote.discovery.DiscoveredDevice
import com.yarden.universalremote.discovery.TvBrand

object ProtocolFactory {
    fun create(device: DiscoveredDevice): TvProtocol = when (device.brand) {
        TvBrand.ROKU -> RokuProtocol(device)
        TvBrand.SAMSUNG -> SamsungProtocol(device)
        TvBrand.LG_WEBOS -> LgWebOsProtocol(device)
        TvBrand.SONY_BRAVIA -> SonyBraviaProtocol(device)
        TvBrand.VIZIO -> VizioProtocol(device)
        TvBrand.UNKNOWN -> RokuProtocol(device) // best-effort fallback; unlikely to be reached
    }
}
