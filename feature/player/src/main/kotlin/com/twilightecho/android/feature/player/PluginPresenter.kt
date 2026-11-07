package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.plugins.AndroidPluginRepository
import com.twilightecho.android.plugins.MobileProvider
import com.twilightecho.android.plugins.PluginEntry
import com.twilightecho.android.plugins.PluginNotice
import com.twilightecho.android.plugins.ProviderPanel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

private const val SEARCH_PAGE = 30
private const val PLAYLIST_PAGE = 100
private const val QR_POLL_INTERVAL_MS = 5_000L
private const val QR_POLL_LIMIT_MS = 10 * 60_000L

internal data class ProviderPlaylist(val id: String, val title: String, val count: Int,
    val cover: String? = null, val subtitle: String = "")
internal data class ProviderPageState(val plugin: PluginEntry? = null, val title: String = "网易云音乐",
    val query: String = "", val tracks: List<CoreTrackView> = emptyList(), val playlists: List<ProviderPlaylist> = emptyList(),
    val searchCategory: SearchCategory = SearchCategory.SONGS, val collections: List<StreamingCollection> = emptyList(),
    val submittedQuery: String? = null,
    val collection: StreamingCollection? = null,
    val total: Int = 0, val offset: Int = 0, val pageKind: String = "search", val playlistId: String? = null,
    val busy: Boolean = false, val error: String? = null, val profile: NcmProfile? = null,
    val qrKey: String? = null, val qrImage: String? = null, val loginMessage: String? = null,
    /** ABI v2: what the plugin registered, read when the page opens. */
    val provider: MobileProvider? = null,
    /** ABI v2: the HTTPS page an OAuth device flow (`showBrowserButton`) is completed on. */
    val authUrl: String? = null,
    /** ABI v2 paging: a full page's size, and where the next page starts (null on the last one). */
    val pageSize: Int = 0, val nextOffset: Int? = null,
    val panel: ProviderPanelState? = null,
    /** ABI v2: disclaimer/consent confirmation discovered in the plugin's settings form. */
    val disclaimer: ProviderDisclaimer? = null,
    val sectionDescription: String = "",
    /** `twilight.ui.notify()` from the open plugin during its latest call. */
    val notice: PluginNotice? = null)
{
    val nickname: String? get() = profile?.nickname
    /** Plugins-repository providers get the page their `ui` declaration describes; NCM keeps its own. */
    val generic: Boolean get() = plugin?.androidRuntime?.apiVersion == 2
    val requiresDisclaimer: Boolean get() = disclaimer != null && !disclaimer.accepted
    val tabs: List<Pair<String, String>> get() = provider?.tabs(profile != null).orEmpty()
    val resultCount: Int get() = tracks.size + playlists.size + collections.size
}

internal class PluginPresenter(private val scope: CoroutineScope, val repository: AndroidPluginRepository) {
    private val mutable = MutableStateFlow(ProviderPageState())
    val state = mutable.asStateFlow()
    /** Notified when account login or disclaimer consent changes so the main Home tab stays synchronized. */
    var onChanged: (() -> Unit)? = null
    private var job: Job? = null
    private var poll: Job? = null
    private var generation = 0L
    private val browseHistory = ArrayDeque<ProviderPageState>()
    init {
        scope.launch { repository.notices.collect { (pluginId, notice) -> mutable.update { if (it.plugin?.id == pluginId) it.copy(notice = notice) else it } } }
    }
    fun initialize() { scope.launch { runCatching { repository.initialize() }; repository.revalidate() } }
    /** Picks up CDN releases while the app stays open; the repository throttles the check. */
    fun revalidate() { scope.launch { repository.revalidate() } }
    fun open(plugin: PluginEntry) {
        browseHistory.clear()
        generation++; job?.cancel(); stopPolling(); mutable.value = ProviderPageState(plugin = plugin, title = plugin.name)
        if (plugin.androidRuntime?.apiVersion == 2) load {
            describe()
            refreshDisclaimer()
            // A failed account check leaves the page signed out; a public home still loads once consent is accepted.
            val account = try { refreshAccount(); null } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (state.value.requiresDisclaimer || isDisclaimerError(error.message)) null
                    else friendlyProviderError(error.message, plugin.name) ?: "账号状态检查失败"
                }
            if (!state.value.requiresDisclaimer) {
                home()
                account?.let { message -> update { it.copy(error = message) } }
            }
        }
        else load { val login = call("checkLogin").jsonObject; update { it.copy(profile =
            (login["profile"] as? JsonObject)?.takeIf { login["loggedIn"]?.jsonPrimitive?.booleanOrNull == true }?.ncmProfile()) } }
    }
    fun close() { generation++; job?.cancel(); stopPolling(); browseHistory.clear(); mutable.value = ProviderPageState() }
    fun query(value: String) {
        val clearingSearch = value.isBlank() && state.value.submittedQuery != null &&
            (state.value.pageKind == "search" || state.value.collection != null)
        if (clearingSearch) { generation++; job?.cancel(); browseHistory.clear() }
        mutable.update { current ->
            val next = current.copy(query = value, submittedQuery = if (value.isBlank()) null else current.submittedQuery,
                searchCategory = if (value.isBlank()) SearchCategory.SONGS else current.searchCategory)
            if (!clearingSearch) next else next.copy(title = current.plugin?.name ?: current.title, pageKind = "search",
                tracks = emptyList(), collections = emptyList(), playlists = emptyList(), collection = null,
                total = 0, offset = 0, nextOffset = null, busy = false, error = null, sectionDescription = "")
        }
    }
    fun searchCategory(category: SearchCategory) {
        if (state.value.searchCategory == category && state.value.pageKind == "search") return
        generation++; job?.cancel()
        browseHistory.clear()
        mutable.update { it.copy(searchCategory = category, tracks = emptyList(), playlists = emptyList(), collections = emptyList(),
            collection = null, pageKind = "search", title = "搜索结果", offset = 0, total = 0, nextOffset = null, busy = false, error = null) }
        search()
    }
    fun search(offset: Int = 0) {
        val query = state.value.query.trim(); if (query.isEmpty()) return
        val category = state.value.searchCategory
        browseHistory.clear()
        mutable.update { it.copy(tracks = emptyList(), playlists = emptyList(), collections = emptyList(), collection = null,
            pageKind = "search", title = "搜索结果", total = 0, offset = offset, nextOffset = null, submittedQuery = query) }
        load {
            if (state.value.generic) { genericSearch(query, category, offset); return@load }
            val result = call(category.method, JsonPrimitive(query), JsonPrimitive(30), JsonPrimitive(offset)).jsonObject
            val items = if (category == SearchCategory.SONGS) result["items"]?.jsonArray.orEmpty().mapIndexed { index, item ->
                providerTrack(item.jsonObject).copy(queueEntryId = "plugin-search-$offset-$index")
            } else emptyList()
            val collections = if (category == SearchCategory.SONGS) emptyList() else providerSearchCollections(result, category.kind)
            if (state.value.query.trim() != query) return@load
            update { it.copy(title = "搜索结果", tracks = items, playlists = emptyList(), collections = collections,
                pageKind = "search", offset = offset, total = result["total"]?.jsonPrimitive?.intOrNull ?: items.size + collections.size) }
        }
    }
    fun collection(item: StreamingCollection) {
        browseHistory.addLast(state.value)
        load {
            val method = if (item.kind == "artist") "fetchArtistTopSongs" else "fetchAlbumTracks"
            update { it.copy(title = item.title, tracks = emptyList(), playlists = emptyList(), collections = emptyList(),
                collection = item, pageKind = item.kind, offset = 0, total = 0, nextOffset = null, sectionDescription = item.subtitle) }
            if (state.value.generic) check(method in describe().methods) { "此音源暂不支持查看这些歌曲" }
            val raw = call(method, JsonPrimitive(item.id))
            val tracks = if (state.value.generic) providerTrackArray(raw).providerTracks(describe().id, "plugin-${item.kind}-${item.id}")
                else providerTrackArray(raw).mapIndexed { index, track -> providerTrack(track.jsonObject).copy(queueEntryId = "plugin-${item.id}-$index") }
            update { it.copy(tracks = tracks, total = tracks.size) }
        }
    }
    fun backToResults() {
        val previous = if (browseHistory.isEmpty()) return else browseHistory.removeLast()
        generation++; job?.cancel()
        mutable.update { previous.copy(busy = false, error = null) }
    }
    fun section(kind: String, offset: Int = 0) = load {
        browseHistory.clear()
        update { it.copy(collections = emptyList(), collection = null) }
        if (state.value.generic) { genericSection(kind); return@load }
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
        update { it.copy(collections = emptyList(), collection = null) }
        if (state.value.generic) { genericPlaylist(item, offset); return@load }
        // Bounded pages retain the provider's authoritative playlist order (including repeats).
        val raw = fresh { repository.browseNcmPlaylistPage(buildJsonArray { add(item.id); add(offset); add(100) }) }
        val tracks = (raw["tracks"] as? JsonArray).orEmpty().mapIndexed { index, song ->
            providerTrack(song.jsonObject).copy(queueEntryId = "provider-playlist-${item.id}-$offset-$index")
        }
        val total = raw["total"]?.jsonPrimitive?.intOrNull ?: tracks.size
        val reportedTotal = (raw["reportedTotal"] as? JsonPrimitive)?.intOrNull
        update { it.copy(title = item.title, tracks = tracks, playlists = emptyList(), pageKind = "playlist",
            playlistId = item.id, total = total, offset = offset,
            notice = playlistAvailabilityNotice(total, reportedTotal)?.let { message -> PluginNotice("info", message) }) }
    }
    fun nextPage(offset: Int) { when (state.value.pageKind) {
        "playlist" -> playlist(ProviderPlaylist(state.value.playlistId!!, state.value.title, state.value.total), offset)
        "liked", "discovery" -> section(state.value.pageKind, offset)
        else -> search(offset)
    } }
    fun qr() = load {
        if (state.value.generic) { genericQr(); return@load }
        val data = call("getQrLogin").jsonObject
        update { it.copy(qrKey = data.text("key"), qrImage = data.text("imageDataUrl"), loginMessage = "使用网易云音乐 App 扫描二维码") }
    }
    fun checkQr() = load {
        val key = state.value.qrKey ?: error("请先获取二维码")
        val data = call("checkQrLogin", JsonPrimitive(key)).jsonObject
        when (data["code"]?.jsonPrimitive?.intOrNull) {
            803 -> { refreshProfile(); update { it.copy(qrKey = null, qrImage = null, loginMessage = "登录成功") }; onChanged?.invoke() }
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
        onChanged?.invoke()
    }
    /** Desktop's server sign-in for `authType: 'settings'` providers (Jellyfin, Navidrome). */
    fun serverLogin(address: String, username: String, password: String) = load {
        fresh { repository.loginWithServer(pluginId, address, username, password) }
        update { it.copy(loginMessage = "登录成功") }
        refreshAccount(); home()
        onChanged?.invoke()
    }
    fun logout() = load {
        stopPolling()
        browseHistory.clear()
        call("logout"); update { it.copy(profile = null, loginMessage = "已退出登录", tracks = emptyList(), playlists = emptyList(),
            collections = emptyList(), collection = null,
            qrKey = null, qrImage = null, authUrl = null, total = 0, offset = 0, nextOffset = null, sectionDescription = "") }
        if (state.value.generic && !state.value.requiresDisclaimer) home()
        onChanged?.invoke()
    }
    /** Confirms a required disclaimer before QR code generation or browsing, preserving sibling plugin settings. */
    fun acceptDisclaimer(startQr: Boolean = false) {
        val current = state.value.disclaimer ?: return
        load {
            val result = fresh { repository.command(pluginId, current.form.submitCommand, JsonArray(listOf(current.acceptedSubmission()))) }
            val nextForm = providerForm((result as? JsonObject)?.get("form"))
                ?: current.form.copy(fields = current.form.fields.map {
                    if (it.key == current.fieldKey) it.copy(value = current.acceptValue) else it
                })
            val updated = providerDisclaimer(current.panel, nextForm) ?: current.copy(form = nextForm, accepted = true)
            update { s ->
                val panelState = s.panel?.let { open ->
                    if (open.panel.id == current.panel.id) ProviderPanelState(open.panel, nextForm, commandMessage(result, "已确认免责声明")) else open
                }
                s.copy(disclaimer = updated, panel = panelState, error = null)
            }
            onChanged?.invoke()
            if (startQr) {
                genericQr()
            } else {
                try { refreshAccount() } catch (error: CancellationException) { throw error } catch (_: Exception) {}
                home()
            }
        }
    }
    /** Runs a v2 plugin's settings panel command; it answers with a form or a message. */
    fun openPanel(panel: ProviderPanel) = load {
        update { it.copy(panel = ProviderPanelState(panel)) }
        val result = fresh { repository.command(pluginId, panel.command,
            buildJsonArray { addJsonObject { put("source", "settingsPanel"); put("panelId", panel.id) } }) }
        val form = providerForm(result)
        val discovered = form?.let { providerDisclaimer(panel, it) }
        update { it.copy(
            panel = ProviderPanelState(panel, form, if (form == null) commandMessage(result, "已执行") else null),
            disclaimer = discovered ?: it.disclaimer
        ) }
    }
    fun closePanel() { mutable.update { it.copy(panel = null) } }
    /** Sends every field, as strings, to the form's submit command; the plugin may answer with a new form. */
    fun submitPanel(values: Map<String, String>) {
        val open = state.value.panel ?: return
        val form = open.form ?: return
        form.missing(values)?.let { field -> mutable.update { it.copy(error = "请填写${field.label}") }; return }
        load {
            val submitted = JsonObject(form.fields.associate { it.key to JsonPrimitive(values[it.key].orEmpty().take(4096)) })
            val result = fresh { repository.command(pluginId, form.submitCommand, JsonArray(listOf(submitted))) }
            val next = providerForm((result as? JsonObject)?.get("form"))
                ?: form.copy(fields = form.fields.map { it.copy(value = if (it.type == "password") "" else values[it.key] ?: it.value) })
            val updatedDisclaimer = providerDisclaimer(open.panel, next)
                ?: state.value.disclaimer?.takeIf { it.panel.id != open.panel.id }
            update { it.copy(panel = ProviderPanelState(open.panel, next, commandMessage(result, "设置已保存")),
                disclaimer = updatedDisclaimer) }
            // A saved cookie or consent can change what the account may do.
            try { refreshAccount() } catch (error: CancellationException) { throw error } catch (_: Exception) {}
            if (!state.value.requiresDisclaimer && state.value.tracks.isEmpty() && state.value.playlists.isEmpty() && state.value.collections.isEmpty()) {
                try { home() } catch (error: CancellationException) { throw error } catch (_: Exception) {}
            }
            onChanged?.invoke()
        }
    }
    private suspend fun ProviderRequest.refreshProfile() {
        val profile = (call("getProfile") as? JsonObject)?.ncmProfile() ?: error("请重新登录网易云音乐")
        update { it.copy(profile = profile) }
    }

    private suspend fun ProviderRequest.describe(): MobileProvider =
        state.value.provider ?: fresh { repository.provider(pluginId) }.also { provider -> update { it.copy(provider = provider) } }
    private suspend fun ProviderRequest.refreshDisclaimer() {
        val provider = describe()
        for (panel in provider.panels) {
            val result = try {
                fresh { repository.command(pluginId, panel.command,
                    buildJsonArray { addJsonObject { put("source", "settingsPanel"); put("panelId", panel.id) } }) }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { continue }
            val form = providerForm(result) ?: continue
            val disclaimer = providerDisclaimer(panel, form) ?: continue
            update { it.copy(disclaimer = disclaimer) }
            return
        }
    }
    private suspend fun ProviderRequest.refreshAccount() {
        if ("checkLogin" !in describe().methods) return
        val profile = providerAccount(call("checkLogin"))
        update { it.copy(profile = profile) }
    }
    /** Opens the first tab, as Desktop's online home does. */
    private suspend fun ProviderRequest.home() {
        val first = state.value.tabs.firstOrNull()
        if (first != null) genericSection(first.first) else update { it.copy(title = it.plugin?.name ?: it.title, pageKind = "search", sectionDescription = "") }
    }
    private suspend fun ProviderRequest.genericSection(kind: String) {
        browseHistory.clear()
        update { it.copy(collections = emptyList(), collection = null) }
        val provider = describe()
        if (kind == "library") {
            val lists = providerLibrary(call("fetchUserLibrary", JsonPrimitive(false)))
            update { it.copy(title = "我的歌单", tracks = emptyList(), playlists = lists, total = lists.size, offset = 0,
                pageKind = kind, nextOffset = null, sectionDescription = "") }
            return
        }
        if (kind == "recommend") {
            val lists = providerPlaylists(call("fetchRecommendPlaylists"))
            update { it.copy(title = "推荐歌单", tracks = emptyList(), playlists = lists, total = lists.size, offset = 0,
                pageKind = kind, nextOffset = null, sectionDescription = "") }
            return
        }
        val section = provider.sections().firstOrNull { "section:${it.id}" == kind } ?: error("此音源没有这个栏目")
        val tracks = providerTrackArray(callWith(section.method, section.args)).providerTracks(provider.id, "provider-${section.id}")
        update { it.copy(title = section.title, tracks = tracks, playlists = emptyList(), total = tracks.size, offset = 0,
            pageKind = kind, nextOffset = null, sectionDescription = section.description) }
    }
    private suspend fun ProviderRequest.genericSearch(query: String, category: SearchCategory, offset: Int) {
        val provider = describe()
        check(category.method in provider.methods) { "${provider.name}暂不支持${category.label}搜索" }
        val result = call(category.method, JsonPrimitive(query), JsonPrimitive(SEARCH_PAGE), JsonPrimitive(offset))
        val page = providerSearchPage(result, provider.id, category, offset)
        if (state.value.query.trim() != query) return
        update { it.copy(title = "搜索结果", tracks = page.tracks, collections = page.collections, playlists = emptyList(),
            pageKind = "search", offset = offset, total = page.total, pageSize = if (offset == 0) page.size else it.pageSize,
            nextOffset = page.nextOffset, sectionDescription = "") }
    }
    private suspend fun ProviderRequest.genericPlaylist(item: ProviderPlaylist, offset: Int) {
        val provider = describe()
        val id = JsonPrimitive(item.id)
        // A page reader when the provider has one, else the whole list at once.
        val page = when {
            "fetchPlaylistTracksPage" in provider.methods ->
                call("fetchPlaylistTracksPage", id, JsonPrimitive(offset), JsonPrimitive(PLAYLIST_PAGE), JsonPrimitive(false))
            "readPlaylistImportPage" in provider.methods -> call("readPlaylistImportPage", id, JsonPrimitive(offset), JsonPrimitive(PLAYLIST_PAGE))
            else -> call("fetchPlaylistTracks", id, JsonPrimitive(false))
        }
        val raw = providerTrackArray(page)
        val paged = page as? JsonObject
        val total = (paged?.get("total") as? JsonPrimitive)?.intOrNull ?: (offset + raw.size)
        val more = (paged?.get("hasMore") as? JsonPrimitive)?.booleanOrNull ?: (offset + raw.size < total)
        val next = ((paged?.get("nextOffset") as? JsonPrimitive)?.intOrNull ?: (offset + raw.size))
            .takeIf { more && raw.isNotEmpty() && it > offset }
        update { it.copy(title = item.title, tracks = raw.providerTracks(provider.id, "provider-playlist-${item.id}-$offset"),
            playlists = emptyList(), pageKind = "playlist", playlistId = item.id, total = total, offset = offset,
            pageSize = if (offset == 0) raw.size else it.pageSize, nextOffset = next, sectionDescription = item.subtitle) }
    }
    private suspend fun ProviderRequest.genericQr() {
        stopPolling()
        val provider = describe()
        if (state.value.disclaimer == null) refreshDisclaimer()
        check(!state.value.requiresDisclaimer) { "请先阅读并确认免责声明" }
        val login = call("getQrLogin") as? JsonObject ?: error("获取${provider.name}登录信息失败")
        val key = login.text("key").ifBlank { error("获取${provider.name}登录信息失败") }
        // An OAuth device flow (YouTube Music) is completed in the browser; only HTTPS pages are opened.
        val authUrl = login.text("qrContent").takeIf { provider.ui.flag("showBrowserButton") && it.startsWith("https://", ignoreCase = true) }
        update { it.copy(qrKey = key, qrImage = login.text("imageDataUrl").ifBlank { null }, authUrl = authUrl,
            loginMessage = provider.ui.text("loginInstructions").ifBlank { "打开手机 App 扫码登录" }) }
        watchQr(pluginId, provider.qrStatusCodes(), key)
    }

    /** Desktop's QR loop: a check every 5 s until the code expires, fails or signs in; given up after 10 minutes. */
    private fun watchQr(pluginId: String, codes: QrStatusCodes, key: String) {
        stopPolling()
        poll = scope.launch {
            fun current() = state.value.plugin?.id == pluginId && state.value.qrKey == key
            fun finish(transform: (ProviderPageState) -> ProviderPageState) =
                mutable.update { transform(it.copy(qrKey = null, qrImage = null, authUrl = null)) }
            val until = System.currentTimeMillis() + QR_POLL_LIMIT_MS
            while (System.currentTimeMillis() < until) {
                delay(QR_POLL_INTERVAL_MS)
                if (!current()) return@launch
                val result = try { repository.call(pluginId, "checkQrLogin", buildJsonArray { add(key) }) as? JsonObject }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { buildJsonObject { put("code", -1); put("message", error.message.orEmpty()) } }
                val code = (result?.get("code") as? JsonPrimitive)?.intOrNull ?: continue
                if (!current()) return@launch
                when {
                    codes.expired(code) -> { finish { it.copy(loginMessage = "二维码已过期，请重新获取") }; return@launch }
                    code in QR_FAILURES -> {
                        val name = state.value.plugin?.name ?: "音源"
                        finish { it.copy(error = friendlyProviderError(result?.text("message"), name).orEmpty().ifBlank { "二维码登录状态异常，请刷新二维码后重试" }) }
                        return@launch
                    }
                    code == codes.waiting -> Unit
                    code == codes.scanned -> mutable.update { it.copy(loginMessage = "已扫码，请在手机上确认") }
                    code == codes.success -> {
                        finish { it.copy(loginMessage = "登录成功") }
                        load { refreshAccount(); home(); onChanged?.invoke() }
                        return@launch
                    }
                }
            }
            if (current()) finish { it.copy(loginMessage = "二维码已过期，请重新获取") }
        }
    }
    private fun stopPolling() { poll?.cancel(); poll = null }

    private inner class ProviderRequest(val generation: Long, val pluginId: String) {
        suspend fun call(method: String, vararg args: JsonElement): JsonElement = callWith(method, JsonArray(args.toList()))
        suspend fun callWith(method: String, args: JsonArray): JsonElement = fresh { repository.call(pluginId, method, args) }
        /** [request]'s result, unless the page moved on meanwhile. */
        suspend fun <T> fresh(request: suspend () -> T): T {
            val result = request()
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
        val pluginName = state.value.plugin?.name ?: "音源"
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        job = scope.launch {
            try { ProviderRequest(request, pluginId).action() } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (request == generation) mutable.update {
                    it.copy(error = friendlyProviderError(error.message, pluginName) ?: "音源请求失败")
                }
            }
            finally { if (request == generation) mutable.update { it.copy(busy = false) } }
        }
    }
}

/** Older installed NCM packages keep their existing reader until the user updates the bundled source. */
internal suspend fun AndroidPluginRepository.browseNcmPlaylistPage(args: JsonArray): JsonObject {
    val method = if ("fetchPlaylistTracksPage" in provider(NCM_PLUGIN_ID).methods) "fetchPlaylistTracksPage" else "readPlaylistImportPage"
    val page = call(NCM_PLUGIN_ID, method, args).jsonObject
    return if (method == "readPlaylistImportPage") JsonObject(page + ("tracks" to page.getValue("songs"))) else page
}

internal fun playlistAvailabilityNotice(total: Int, reportedTotal: Int?): String? =
    reportedTotal?.takeIf { it != total }?.let {
        "网易云返回 $total 首，歌单标记为 $it 首；部分条目可能已下架或无权访问。"
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
