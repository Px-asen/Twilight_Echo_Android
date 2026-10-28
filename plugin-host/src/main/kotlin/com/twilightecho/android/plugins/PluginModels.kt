package com.twilightecho.android.plugins

import kotlinx.serialization.Serializable
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable data class AndroidPluginRuntime(val apiVersion: Int, val entry: String,
    val permissions: List<String>, val networkHosts: List<String>)
@Serializable data class PluginEntry(
    val id: String, val name: String, val version: String, val description: String,
    val author: String = "", val license: String = "", val type: List<String> = emptyList(),
    val androidRuntime: AndroidPluginRuntime? = null, val sourceUrl: String = "", val checksumSha256: String = "",
    val unsupportedReason: String? = null, val installedVersion: String? = null,
    val enabled: Boolean = false, val updateAvailable: Boolean = false
)
@Serializable data class InstalledPlugin(val id: String, val version: String, val enabled: Boolean = false,
    val origin: String = "", val checksumSha256: String, val error: String? = null)
data class PluginRepositoryState(val entries: List<PluginEntry> = emptyList(),
    val installed: List<InstalledPlugin> = emptyList(), val busy: Boolean = false,
    val message: String? = null, val indexUrl: String = DEFAULT_PLUGIN_INDEX,
    val sourceLabel: String = indexSourceLabel(DEFAULT_PLUGIN_INDEX, null, live = false), val providers: List<MobileProvider> = emptyList(),
    /** False until installed.json has been read, so "nothing installed" is never reported early. */
    val ready: Boolean = false)
@Serializable data class MobileProvider(val id: String, val name: String, val capabilities: List<String> = emptyList(),
    val methods: List<String> = emptyList(), val pluginId: String = "")
/** Desktop's official index (`OFFICIAL_PLUGIN_INDEX_URL`): Cloudflare R2 behind the CDN, packages content-addressed. */
const val DEFAULT_PLUGIN_INDEX = "https://plugins.aaapi.fun/plugins.json"
/** Background index checks run at most this often per process; "刷新仓库" always fetches. */
const val INDEX_REVALIDATE_INTERVAL_MS = 10 * 60 * 1000L

/** Where the visible list came from: [fetchedAt] is null for the APK's bundled catalogue. */
internal fun indexSourceLabel(indexUrl: String, fetchedAt: Long?, live: Boolean): String {
    if (fetchedAt == null) return "随应用分发 · 联网后自动更新"
    val source = if (indexUrl == DEFAULT_PLUGIN_INDEX) "官方索引 · 与桌面端同一 CDN" else runCatching { URI(indexUrl).host }.getOrNull() ?: indexUrl
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(fetchedAt))
    return if (live) "$source · $time 已更新" else "$source · 缓存于 $time"
}
