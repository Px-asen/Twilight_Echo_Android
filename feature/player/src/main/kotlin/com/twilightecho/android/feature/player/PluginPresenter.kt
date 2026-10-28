package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.plugins.AndroidPluginRepository
import com.twilightecho.android.plugins.PluginEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal data class ProviderPlaylist(val id: String, val title: String, val count: Int)
internal data class ProviderPageState(val plugin: PluginEntry? = null, val title: String = "网易云音乐",
    val query: String = "", val tracks: List<CoreTrackView> = emptyList(), val playlists: List<ProviderPlaylist> = emptyList(),
    val total: Int = 0, val offset: Int = 0, val pageKind: String = "search", val playlistId: String? = null,
    val busy: Boolean = false, val error: String? = null, val profile: NcmProfile? = null,
    val qrKey: String? = null, val qrImage: String? = null, val loginMessage: String? = null)
{
    val nickname: String? get() = profile?.nickname
}

internal class PluginPresenter(private val scope: CoroutineScope, val repository: AndroidPluginRepository) {
    private val mutable = MutableStateFlow(ProviderPageState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    fun initialize() { scope.launch { runCatching { repository.initialize() }; repository.revalidate() } }
    /** Picks up CDN releases while the app stays open; the repository throttles the check. */
    fun revalidate() { scope.launch { repository.revalidate() } }
    fun open(plugin: PluginEntry) {
        generation++; job?.cancel(); mutable.value = ProviderPageState(plugin = plugin, title = plugin.name)
        load { val login = call("checkLogin").jsonObject; update { it.copy(profile =
            (login["profile"] as? JsonObject)?.takeIf { login["loggedIn"]?.jsonPrimitive?.booleanOrNull == true }?.ncmProfile()) } }
    }
    fun close() { generation++; job?.cancel(); mutable.value = ProviderPageState() }
    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun search(offset: Int = 0) {
        val query = state.value.query.trim(); if (query.isEmpty()) return
        load {
            val result = call("searchSongs", JsonPrimitive(query), JsonPrimitive(30), JsonPrimitive(offset)).jsonObject
            val items = result["items"]?.jsonArray.orEmpty().map { providerTrack(it.jsonObject) }
            if (state.value.query.trim() != query) return@load
            update { it.copy(title = "搜索结果", tracks = items, playlists = emptyList(),
                pageKind = "search", offset = offset, total = result["total"]?.jsonPrimitive?.intOrNull ?: items.size) }
        }
    }
    fun section(kind: String, offset: Int = 0) = load {
        when (kind) {
            "discovery", "library" -> {
                val result = if (kind == "library") call("fetchUserLibrary").jsonObject["playlists"] ?: JsonArray(emptyList())
                    else call("fetchDiscoveryPlaylists", JsonPrimitive("全部"), JsonPrimitive("hot"), JsonPrimitive(30), JsonPrimitive(offset))
                val raw = (result as? JsonObject)?.get("items") ?: result
                val lists = (raw as? JsonArray).orEmpty().map { it.jsonObject }.map {
                    ProviderPlaylist(it.text("id"), it.text("name", "未命名歌单"), it["trackCount"]?.jsonPrimitive?.intOrNull ?: 0)
                }
                update { it.copy(title = if (kind == "library") "我的歌单" else "发现歌单", tracks = emptyList(), playlists = lists,
                    total = (result as? JsonObject)?.get("total")?.jsonPrimitive?.intOrNull ?: lists.size, pageKind = kind, offset = offset) }
            }
            else -> {
                val raw = if (kind == "daily") call("fetchRecommendSongs") else call("fetchLikedTracksPage", JsonPrimitive(offset), JsonPrimitive(100))
                val items = ((raw as? JsonObject)?.get("tracks") as? JsonArray ?: raw as? JsonArray).orEmpty().map { providerTrack(it.jsonObject) }
                update { it.copy(title = if (kind == "daily") "每日推荐" else "我喜欢的音乐", tracks = items, playlists = emptyList(),
                    total = (raw as? JsonObject)?.get("total")?.jsonPrimitive?.intOrNull ?: items.size, offset = offset, pageKind = kind) }
            }
        }
    }
    fun playlist(item: ProviderPlaylist, offset: Int = 0) = load {
        // Bounded pages retain the provider's authoritative playlist order (including repeats).
        val raw = call("readPlaylistImportPage", JsonPrimitive(item.id), JsonPrimitive(offset), JsonPrimitive(100)).jsonObject
        val tracks = (raw["songs"] as? JsonArray).orEmpty().mapIndexed { index, song ->
            providerTrack(song.jsonObject).copy(queueEntryId = "provider-playlist-${item.id}-$offset-$index")
        }
        update { it.copy(title = item.title, tracks = tracks, playlists = emptyList(), pageKind = "playlist",
            playlistId = item.id, total = raw["total"]?.jsonPrimitive?.intOrNull ?: tracks.size, offset = offset) }
    }
    fun nextPage(offset: Int) { when (state.value.pageKind) {
        "playlist" -> playlist(ProviderPlaylist(state.value.playlistId!!, state.value.title, state.value.total), offset)
        "liked", "discovery" -> section(state.value.pageKind, offset)
        else -> search(offset)
    } }
    fun qr() = load {
        val data = call("getQrLogin").jsonObject
        update { it.copy(qrKey = data.text("key"), qrImage = data.text("imageDataUrl"), loginMessage = "使用网易云音乐 App 扫描二维码") }
    }
    fun checkQr() = load {
        val key = state.value.qrKey ?: error("请先获取二维码")
        val data = call("checkQrLogin", JsonPrimitive(key)).jsonObject
        when (data["code"]?.jsonPrimitive?.intOrNull) {
            803 -> { refreshProfile(); update { it.copy(qrKey = null, qrImage = null, loginMessage = "登录成功") } }
            800 -> update { it.copy(qrKey = null, qrImage = null, loginMessage = "二维码已过期，请重新获取") }
            802 -> update { it.copy(loginMessage = "已扫码，请在网易云音乐 App 确认") }
            801 -> update { it.copy(loginMessage = "等待扫码") }
            else -> error(data.text("message", "二维码状态检查失败，请稍后重试"))
        }
    }
    fun sendCaptcha(phone: String) = load { call("sendCaptcha", JsonPrimitive(phone)); update { it.copy(loginMessage = "验证码已发送") } }
    fun login(phone: String, secret: String, mode: String) = load {
        val method = when (mode) { "password" -> "loginByPhonePassword"; "email" -> "loginByEmailPassword"; else -> "loginByPhoneCaptcha" }
        call(method, JsonPrimitive(phone), JsonPrimitive(secret)); refreshProfile()
        update { it.copy(loginMessage = "登录成功", qrKey = null, qrImage = null) }
    }
    fun logout() = load { call("logout"); update { it.copy(profile = null, loginMessage = "已退出登录",
        tracks = emptyList(), playlists = emptyList(), qrKey = null, qrImage = null, total = 0, offset = 0) } }
    private suspend fun ProviderRequest.refreshProfile() {
        val profile = (call("getProfile") as? JsonObject)?.ncmProfile() ?: error("请重新登录网易云音乐")
        update { it.copy(profile = profile) }
    }
    private inner class ProviderRequest(val generation: Long, val pluginId: String) {
        suspend fun call(method: String, vararg args: JsonElement): JsonElement {
            val result = repository.call(pluginId, method, JsonArray(args.toList()))
            if (generation != this@PluginPresenter.generation) throw CancellationException("音源页面已切换")
            return result
        }
        fun update(transform: (ProviderPageState) -> ProviderPageState) {
            if (generation == this@PluginPresenter.generation) mutable.update(transform)
        }
    }
    private fun load(action: suspend ProviderRequest.() -> Unit) {
        job?.cancel(); val request = ++generation
        val pluginId = state.value.plugin?.id ?: return
        mutable.update { it.copy(busy = true, error = null) }
        job = scope.launch {
            try { ProviderRequest(request, pluginId).action() } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (request == generation) mutable.update { it.copy(error = error.message ?: "音源请求失败") } }
            finally { if (request == generation) mutable.update { it.copy(busy = false) } }
        }
    }
}
internal fun JsonObject.text(key: String, fallback: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
internal fun providerTrack(track: JsonObject): CoreTrackView {
    val id = track.text("ncmSongId").ifBlank { track.text("platformId").ifBlank { track.text("id").removePrefix("ncm:") } }
    require(id.matches(Regex("[0-9]+"))) { "网易云返回了无效歌曲 ID" }
    return CoreTrackView(id = "provider://ncm/$id", source = "provider://ncm/$id", ncmSongId = id,
        title = track.text("title", "未知歌曲"), artist = track.text("artist"), album = track.text("album"),
        albumId = track.text("albumId").ifBlank { null }, cover = track.text("cover").ifBlank { null },
        unavailableReason = track.text("unavailableReason").ifBlank { null },
        duration = track["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        format = track.text("format").ifBlank { null }, sampleRate = track["sampleRate"]?.jsonPrimitive?.intOrNull,
        bitrate = track["bitrate"]?.jsonPrimitive?.doubleOrNull)
}
