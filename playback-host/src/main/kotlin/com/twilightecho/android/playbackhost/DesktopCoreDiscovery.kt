package com.twilightecho.android.playbackhost

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.twilightecho.android.coreclient.DesktopCoreDevice
import com.twilightecho.android.coreclient.DesktopDiscoveryState
import com.twilightecho.android.coreclient.selectDesktopIpv4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/** Android's DNS-SD daemon owns multicast sockets. No subnet/port sweep or location permission. */
class DesktopCoreDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(DesktopDiscoveryState())
    val state = mutable.asStateFlow()
    private var listener: NsdManager.DiscoveryListener? = null
    private var generation = 0L
    private var resolving = false
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val found = linkedMapOf<String, DesktopCoreDevice>()
    private val present = mutableSetOf<String>()

    fun start() { main.post { startOnMain() } }
    fun stop() { main.post { stopOnMain() } }

    private fun startOnMain() {
        if (listener != null) return
        val origin = ++generation
        mutable.value = DesktopDiscoveryState(scanning = true)
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { main.post {
                if (origin != generation || !info.serviceType.contains("_twilight-core._tcp")) return@post
                present += info.serviceName
                pending.add(info); resolveNext(origin)
            } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post {
                if (origin != generation) return@post
                present -= info.serviceName
                found.remove(info.serviceName); publish()
            } }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post {
                if (origin == generation) { stopOnMain(); mutable.value = DesktopDiscoveryState(error = "无法搜索局域网，请检查网络后重试（$code）") }
            } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        listener = discovery
        try { nsd.discoverServices("_twilight-core._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery) }
        catch (_: Exception) { listener = null; mutable.value = DesktopDiscoveryState(error = "无法搜索局域网，请检查网络权限") }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(origin: Long) {
        if (resolving || pending.isEmpty() || origin != generation) return
        val next = pending.removeFirst()
        resolving = true
        try { nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { main.post {
                resolving = false
                // An old resolve must finish before the next scan uses the legacy NSD resolver.
                resolveNext(generation)
            } }
            override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                resolving = false
                if (origin == generation && info.serviceName in present) {
                    fun attribute(key: String) = info.attributes[key]?.toString(Charsets.UTF_8)
                    val id = attribute("id")
                    val network = if (Build.VERSION.SDK_INT >= 33) info.network ?: connectivity.activeNetwork else connectivity.activeNetwork
                    val links = network?.let { connectivity.getLinkProperties(it)?.linkAddresses }
                        .orEmpty().map { it.address to it.prefixLength }
                    val hosts = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                    val host = selectDesktopIpv4Address(hosts, links)
                    if (attribute("v") == "1" && attribute("bridge") == "1" &&
                        id != null && Regex("[a-fA-F0-9-]{36}").matches(id) && host is Inet4Address && info.port in 1..65535) {
                        found[info.serviceName] = DesktopCoreDevice(id, attribute("name")?.take(80) ?: info.serviceName,
                            "https://${host.hostAddress}:${info.port}")
                        publish()
                    }
                }
                resolveNext(generation)
            } }
        }) } catch (_: Exception) { resolving = false; resolveNext(origin) }
    }

    private fun publish() { mutable.value = mutable.value.copy(devices = found.values.distinctBy { it.id }.sortedBy { it.name }) }
    private fun stopOnMain() {
        generation++
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null; pending.clear(); present.clear(); found.clear()
        mutable.value = DesktopDiscoveryState()
    }
}
