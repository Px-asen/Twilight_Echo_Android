package com.twilightecho.android.coreclient

import java.net.Inet4Address
import java.net.InetAddress

data class DesktopCoreDevice(val id: String, val name: String, val baseUrl: String,
    val discovered: Boolean = true, val trusted: Boolean = false)
data class DesktopDiscoveryState(val scanning: Boolean = false, val devices: List<DesktopCoreDevice> = emptyList(),
    val error: String? = null)

fun mergeDesktopDevices(discovered: List<DesktopCoreDevice>, known: List<DesktopCoreDevice>): List<DesktopCoreDevice> {
    val result = known.associateBy { it.id }.toMutableMap()
    discovered.forEach { device -> result[device.id] = device.copy(trusted = result[device.id]?.trusted == true) }
    return result.values.sortedWith(compareByDescending<DesktopCoreDevice> { it.discovered }.thenBy { it.name })
}

/** A desktop can advertise Wi-Fi, VPN and virtual-adapter addresses for the same hostname. */
fun selectDesktopIpv4Address(addresses: List<InetAddress>, localLinks: List<Pair<InetAddress, Int>>): Inet4Address? {
    val candidates = addresses.filterIsInstance<Inet4Address>()
    fun number(address: Inet4Address) = address.address.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 255) }
    for ((local, prefix) in localLinks.sortedByDescending { it.second }) {
        if (local !is Inet4Address || prefix !in 1..32) continue
        val mask = -1 shl (32 - prefix)
        candidates.firstOrNull { (number(it) and mask) == (number(local) and mask) }?.let { return it }
    }
    return candidates.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress } ?: candidates.firstOrNull()
}
