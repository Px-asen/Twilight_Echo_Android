package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CollectionSort
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.plugins.MobileProvider
import com.twilightecho.android.plugins.PluginEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val HOME_PLAYLIST_PAGE = 100

/** A streaming provider selectable on the mobile Home tab. */
data class HomeProviderOption(
    val pluginId: String,
    val providerId: String,
    val name: String,
    val colorHex: String? = null,
    val isNcm: Boolean = pluginId == NCM_PLUGIN_ID
)

/** One recommendation/chart section declared by an ABI v2 provider on the Home tab. */
data class ProviderHomeSectionUi(
    val id: String,
    val title: String,
    val eyebrow: String = "",
    val description: String = "",
    val icon: String = "",
    val requiresLogin: Boolean = false,
    val tracks: List<CoreTrackView> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null
)

/** Complete Home tab state for the active non-NCM (ABI v2) streaming provider. */
data class ProviderHomeUiState(
    val pluginId: String = "",
    val providerId: String = "",
    val providerName: String = "",
    val subtitle: String = "",
    val colorHex: String? = null,
    val homeRequiresLogin: Boolean = true,
    val supportsLibrary: Boolean = false,
    val supportsRecommendPlaylists: Boolean = false,
    val supportsSearch: Boolean = false,
    val access: StreamingAccess = StreamingAccess.LOADING,
    val requiresDisclaimer: Boolean = false,
    val disclaimerNotice: String? = null,
    val profile: NcmProfile? = null,
    val accountError: String? = null,
    val sections: List<ProviderHomeSectionUi> = emptyList(),
    val likedPlaylist: StreamingCollection? = null,
    val playlists: List<StreamingCollection> = emptyList(),
    val recommendedPlaylists: List<StreamingCollection> = emptyList(),
    val libraryLoading: Boolean = false,
    val libraryLoaded: Boolean = false,
    val libraryError: String? = null,
    val recommendedLoading: Boolean = false,
    val detail: StreamingDetail? = null
) {
    val nickname: String get() = profile?.nickname.orEmpty()
}

data class ProviderHomeActions(
    val account: () -> Unit = {},
    val openProviderPage: () -> Unit = {},
    val refresh: () -> Unit = {},
    val openSection: (String) -> Unit = {},
    val openCollection: (StreamingCollection) -> Unit = {},
    val openLiked: () -> Unit = {},
    val openAllPlaylists: () -> Unit = {},
    val play: (List<CoreTrackView>, CoreTrackView) -> Unit = { _, _ -> },
    val back: () -> Unit = {},
    val retryDetail: () -> Unit = {},
    val page: (Int) -> Unit = {},
    val playlistQuery: (String) -> Unit = {},
    val playlistSort: (CollectionSort) -> Unit = {},
    val root: () -> Unit = {},
    val togglePlaylistPin: (StreamingCollection) -> Unit = {}
)

/**
 * Builds the ordered list of Home-selectable streaming providers:
 * 1. Bundled NetEase Cloud Music (`ncm`) when enabled
 * 2. Enabled ABI v2 providers declaring `ui.streamingSections` (QQ 音乐, 酷狗音乐, 汽水音乐)
 * 3. Enabled ABI v2 library providers (`fetchUserLibrary`, e.g. Bilibili, YouTube Music, Navidrome, Jellyfin)
 */
internal fun buildHomeProviderOptions(
    ncmEnabled: Boolean,
    enabledV2Entries: List<PluginEntry>,
    describedProviders: Map<String, MobileProvider>
): List<HomeProviderOption> {
    val options = mutableListOf<HomeProviderOption>()
    if (ncmEnabled) {
        options += HomeProviderOption(
            pluginId = NCM_PLUGIN_ID,
            providerId = "ncm",
            name = "网易云音乐",
            colorHex = "#ec4141",
            isNcm = true
        )
    }
    val withSections = mutableListOf<HomeProviderOption>()
    val libraryOnly = mutableListOf<HomeProviderOption>()
    for (entry in enabledV2Entries) {
        val provider = describedProviders[entry.id]
        if (provider == null) {
            withSections += HomeProviderOption(
                pluginId = entry.id,
                providerId = entry.id.substringAfterLast('.'),
                name = entry.name.removeSuffix("音源").trim().ifBlank { entry.name }
            )
            continue
        }
        if (!provider.homeEligible()) continue
        val meta = provider.homeMeta()
        val option = HomeProviderOption(
            pluginId = entry.id,
            providerId = provider.id,
            name = provider.name.ifBlank { entry.name.removeSuffix("音源").trim().ifBlank { entry.name } },
            colorHex = meta.colorHex
        )
        if (provider.sections().isNotEmpty()) withSections += option else libraryOnly += option
    }
    options += withSections
    options += libraryOnly
    return options
}

/**
 * Multi-provider Home presenter for ABI v2 audio sources.
 * Adapts Desktop's `ProviderMusicHome` + library home for mobile with per-provider caching,
 * proactive disclaimer gating before API calls, and `StreamingDetail` drill-down.
 */
internal class ProviderHomePresenter(
    private val scope: CoroutineScope,
    private val describeProvider: suspend (String) -> MobileProvider,
    private val callProvider: suspend (String, String, JsonArray) -> JsonElement,
    private val runCommand: suspend (String, String, JsonArray) -> JsonElement
) {
    private val mutable = MutableStateFlow(ProviderHomeUiState())
    val state = mutable.asStateFlow()

    private val cache = mutableMapOf<String, ProviderHomeUiState>()
    private val providers = mutableMapOf<String, MobileProvider>()
    private val sectionDefs = mutableMapOf<String, List<ProviderSection>>()
    private var savedSorts: Map<String, CollectionSort> = emptyMap()

    private var activePluginId: String = ""
    private var session = 0L
    private var accountJob: Job? = null
    private var libraryJob: Job? = null
    private var recommendedJob: Job? = null
    private var detailJob: Job? = null
    private var detailRequest = 0L
    private val sectionJobs = mutableMapOf<String, Job>()
    private val sectionRequests = mutableMapOf<String, Long>()
    private val history = ArrayDeque<StreamingDetail>()

    fun restoreSorts(sorts: Map<String, CollectionSort>) {
        savedSorts = sorts
    }

    fun evict(retainedPluginIds: Set<String>) {
        val removed = cache.keys.filter { it !in retainedPluginIds }
        removed.forEach { id ->
            cache.remove(id)
            providers.remove(id)
            sectionDefs.remove(id)
        }
        if (activePluginId.isNotEmpty() && activePluginId !in retainedPluginIds) {
            clearActive()
        }
    }

    fun clearActive() {
        cancelJobs()
        activePluginId = ""
        mutable.value = ProviderHomeUiState()
    }

    fun selectProvider(pluginId: String, fallbackName: String = "", force: Boolean = false) {
        if (pluginId.isBlank() || pluginId == NCM_PLUGIN_ID) {
            clearActive()
            return
        }
        if (!force && pluginId == activePluginId && state.value.pluginId == pluginId && state.value.access != StreamingAccess.ERROR) {
            return
        }
        if (pluginId != activePluginId) {
            saveToCache()
            cancelJobs()
            activePluginId = pluginId
            if (!force) {
                cache[pluginId]?.let { cached ->
                    mutable.value = cached.copy(detail = null)
                    return
                }
            }
        } else {
            cancelJobs()
        }
        loadProvider(pluginId, fallbackName, force)
    }

    fun refreshActive(force: Boolean = true) {
        val id = activePluginId.takeIf { it.isNotBlank() && it != NCM_PLUGIN_ID } ?: return
        cache.remove(id)
        selectProvider(id, state.value.providerName, force = force)
    }

    private fun saveToCache() {
        val current = state.value
        if (current.pluginId.isNotBlank() && current.access != StreamingAccess.LOADING) {
            cache[current.pluginId] = current.copy(
                detail = null,
                libraryLoading = false,
                recommendedLoading = false,
                sections = current.sections.map { it.copy(loading = false) }
            )
        }
    }

    private fun cancelJobs() {
        session++
        detailRequest++
        accountJob?.cancel(); accountJob = null
        libraryJob?.cancel(); libraryJob = null
        recommendedJob?.cancel(); recommendedJob = null
        detailJob?.cancel(); detailJob = null
        sectionJobs.values.forEach { it.cancel() }
        sectionJobs.clear()
        history.clear()
    }

    private fun loadProvider(pluginId: String, fallbackName: String, force: Boolean) {
        val origin = session
        val previous = cache[pluginId]?.takeIf { !force } ?: state.value.takeIf { it.pluginId == pluginId }
        mutable.value = ProviderHomeUiState(
            pluginId = pluginId,
            providerId = previous?.providerId.orEmpty(),
            providerName = previous?.providerName?.ifBlank { fallbackName } ?: fallbackName,
            subtitle = previous?.subtitle.orEmpty(),
            colorHex = previous?.colorHex,
            access = StreamingAccess.LOADING
        )
        accountJob = scope.launch {
            try {
                val provider = describeProvider(pluginId)
                if (origin != session) return@launch
                providers[pluginId] = provider
                val defs = provider.sections()
                sectionDefs[pluginId] = defs
                val meta = provider.homeMeta()
                val displayName = provider.name.ifBlank { fallbackName.ifBlank { "音源" } }
                val baseSections = defs.map { def ->
                    ProviderHomeSectionUi(
                        id = def.id,
                        title = def.title,
                        eyebrow = def.eyebrow,
                        description = def.description,
                        icon = def.icon,
                        requiresLogin = def.requiresLogin
                    )
                }

                // Check disclaimer consent before issuing any provider API requests.
                val disclaimer = readDisclaimer(pluginId, provider)
                if (origin != session) return@launch
                if (disclaimer != null && !disclaimer.accepted) {
                    val gated = ProviderHomeUiState(
                        pluginId = pluginId,
                        providerId = provider.id,
                        providerName = displayName,
                        subtitle = meta.subtitle,
                        colorHex = meta.colorHex,
                        homeRequiresLogin = meta.requiresLogin,
                        supportsLibrary = "fetchUserLibrary" in provider.methods,
                        supportsRecommendPlaylists = "fetchRecommendPlaylists" in provider.methods,
                        supportsSearch = provider.searchable(),
                        access = StreamingAccess.LOGGED_OUT,
                        requiresDisclaimer = true,
                        disclaimerNotice = disclaimer.notice,
                        sections = baseSections
                    )
                    mutable.value = gated
                    cache[pluginId] = gated
                    return@launch
                }

                val profile = if ("checkLogin" in provider.methods) {
                    providerAccount(callProvider(pluginId, "checkLogin", JsonArray(emptyList())))
                } else null
                if (origin != session) return@launch
                val loggedIn = profile != null
                val readyState = ProviderHomeUiState(
                    pluginId = pluginId,
                    providerId = provider.id,
                    providerName = displayName,
                    subtitle = meta.subtitle,
                    colorHex = meta.colorHex,
                    homeRequiresLogin = meta.requiresLogin,
                    supportsLibrary = "fetchUserLibrary" in provider.methods,
                    supportsRecommendPlaylists = "fetchRecommendPlaylists" in provider.methods,
                    supportsSearch = provider.searchable(),
                    access = if (loggedIn) StreamingAccess.READY else StreamingAccess.LOGGED_OUT,
                    requiresDisclaimer = false,
                    profile = profile,
                    sections = baseSections
                )
                mutable.value = readyState
                cache[pluginId] = readyState

                if (loggedIn || !meta.requiresLogin) {
                    defs.filter { loggedIn || !it.requiresLogin }.forEach { def ->
                        loadSection(pluginId, provider, def, origin)
                    }
                    if ("fetchRecommendPlaylists" in provider.methods) {
                        loadRecommendedPlaylists(pluginId, origin)
                    }
                }
                if (loggedIn && "fetchUserLibrary" in provider.methods) {
                    loadUserLibrary(pluginId, force = force, origin = origin)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (origin == session) {
                    val name = mutable.value.providerName.ifBlank { fallbackName.ifBlank { "音源" } }
                    if (isDisclaimerError(e.message)) {
                        mutable.update {
                            it.copy(
                                access = StreamingAccess.LOGGED_OUT,
                                requiresDisclaimer = true,
                                accountError = null
                            )
                        }
                    } else {
                        mutable.update {
                            it.copy(
                                access = StreamingAccess.ERROR,
                                accountError = friendlyProviderError(e.message, name) ?: "${name}暂时无法连接"
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun readDisclaimer(pluginId: String, provider: MobileProvider): ProviderDisclaimer? {
        for (panel in provider.panels) {
            val raw = try {
                runCommand(
                    pluginId,
                    panel.command,
                    buildJsonArray { addJsonObject { put("source", "settingsPanel"); put("panelId", panel.id) } }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            val form = providerForm(raw) ?: continue
            val disclaimer = providerDisclaimer(panel, form) ?: continue
            return disclaimer
        }
        return null
    }

    private fun loadSection(pluginId: String, provider: MobileProvider, def: ProviderSection, origin: Long) {
        sectionJobs.remove(def.id)?.cancel()
        val request = (sectionRequests[def.id] ?: 0L) + 1
        sectionRequests[def.id] = request
        val detailKey = sectionDetailKey(pluginId, def.id)
        updateSection(def.id) { it.copy(loading = true, error = null) }
        mutable.update { current ->
            current.copy(detail = current.detail?.let { d -> if (d.key == detailKey) d.copy(loading = true, error = null) else d })
        }
        sectionJobs[def.id] = scope.launch {
            val outcome = try {
                val raw = callProvider(pluginId, def.method, def.args)
                val tracks = providerTrackArray(raw).providerTracks(provider.id, "home-${provider.id}-${def.id}")
                Result.success(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (origin != session || sectionRequests[def.id] != request) return@launch
            val providerName = mutable.value.providerName.ifBlank { provider.name }
            outcome.fold(
                onSuccess = { tracks ->
                    updateSection(def.id) { it.copy(tracks = tracks, loading = false, loaded = true, error = null) }
                    mutable.update { current ->
                        current.copy(detail = current.detail?.let { d ->
                            if (d.key == detailKey) d.copy(tracks = tracks, total = tracks.size, loading = false, error = null) else d
                        })
                    }
                },
                onFailure = { err ->
                    val msg = friendlyProviderError(err.message, providerName) ?: "推荐暂时无法载入"
                    updateSection(def.id) { it.copy(loading = false, error = msg) }
                    mutable.update { current ->
                        current.copy(detail = current.detail?.let { d ->
                            if (d.key == detailKey) d.copy(loading = false, error = msg) else d
                        })
                    }
                }
            )
            saveToCache()
        }
    }

    private fun updateSection(sectionId: String, transform: (ProviderHomeSectionUi) -> ProviderHomeSectionUi) {
        mutable.update { current ->
            current.copy(sections = current.sections.map { if (it.id == sectionId) transform(it) else it })
        }
    }

    private fun loadRecommendedPlaylists(pluginId: String, origin: Long) {
        recommendedJob?.cancel()
        mutable.update { it.copy(recommendedLoading = true) }
        recommendedJob = scope.launch {
            val lists = try {
                providerPlaylists(callProvider(pluginId, "fetchRecommendPlaylists", JsonArray(emptyList())))
                    .map { it.toStreamingCollection() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (origin == session) {
                mutable.update { it.copy(recommendedPlaylists = lists, recommendedLoading = false) }
                saveToCache()
            }
        }
    }

    private fun loadUserLibrary(pluginId: String, force: Boolean, origin: Long) {
        libraryJob?.cancel()
        mutable.update { it.copy(libraryLoading = true, libraryError = null) }
        val libKey = libraryDetailKey(pluginId)
        libraryJob = scope.launch {
            try {
                val raw = callProvider(pluginId, "fetchUserLibrary", JsonArray(listOf(JsonPrimitive(force))))
                val (liked, lists) = providerUserLibrarySplit(raw)
                if (origin == session) {
                    val likedCollection = liked?.toStreamingCollection(owned = true)
                    val collections = lists.map { it.toStreamingCollection() }
                    mutable.update { current ->
                        current.copy(
                            likedPlaylist = likedCollection,
                            playlists = collections,
                            libraryLoading = false,
                            libraryLoaded = true,
                            libraryError = null,
                            detail = current.detail?.let { d ->
                                if (d.key == libKey) d.copy(collections = collections, total = collections.size, loading = false, error = null)
                                else d
                            }
                        )
                    }
                    saveToCache()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (origin == session) {
                    val msg = friendlyProviderError(e.message, mutable.value.providerName) ?: "歌单列表未载入"
                    mutable.update { current ->
                        current.copy(
                            libraryLoading = false,
                            libraryError = msg,
                            detail = current.detail?.let { d ->
                                if (d.key == libKey) d.copy(loading = false, error = msg) else d
                            }
                        )
                    }
                }
            }
        }
    }

    fun openSection(sectionId: String) {
        val current = state.value
        val pluginId = current.pluginId.takeIf { it.isNotBlank() } ?: return
        val provider = providers[pluginId] ?: return
        val section = current.sections.find { it.id == sectionId } ?: return
        val def = sectionDefs[pluginId]?.find { it.id == sectionId } ?: return
        if (current.requiresDisclaimer || (section.requiresLogin && current.access != StreamingAccess.READY)) return
        history.clear()
        detailRequest++; detailJob?.cancel()
        val subtitle = listOf(current.providerName, section.description.ifBlank { section.eyebrow })
            .filter { it.isNotBlank() }.joinToString(" · ")
        mutable.update {
            it.copy(
                detail = StreamingDetail(
                    key = sectionDetailKey(pluginId, sectionId),
                    title = section.title,
                    subtitle = subtitle,
                    tracks = section.tracks,
                    total = section.tracks.size,
                    loading = section.loading || (!section.loaded && section.error == null),
                    error = section.error
                )
            )
        }
        if (!section.loaded || section.error != null) {
            loadSection(pluginId, provider, def, session)
        }
    }

    fun openLiked() {
        state.value.likedPlaylist?.let(::openCollection)
    }

    fun openAllPlaylists() {
        val current = state.value
        val pluginId = current.pluginId.takeIf { it.isNotBlank() } ?: return
        if (current.access != StreamingAccess.READY) return
        history.clear()
        detailRequest++; detailJob?.cancel()
        mutable.update {
            it.copy(
                detail = StreamingDetail(
                    key = libraryDetailKey(pluginId),
                    title = "我的歌单",
                    subtitle = current.providerName,
                    collections = current.playlists,
                    total = current.playlists.size,
                    loading = current.libraryLoading || (!current.libraryLoaded && current.libraryError == null),
                    error = current.libraryError
                )
            )
        }
        if (!current.libraryLoaded && !current.libraryLoading) {
            loadUserLibrary(pluginId, force = false, origin = session)
        }
    }

    fun openCollection(item: StreamingCollection) {
        val current = state.value
        val pluginId = current.pluginId.takeIf { it.isNotBlank() } ?: return
        current.detail?.takeIf { !it.loading }?.let { history.addLast(it) }
        val key = "v2-playlist:$pluginId:${item.id}"
        val sort = savedSorts[providerSortKey(pluginId, current.profile?.userId, key)] ?: CollectionSort()
        val detail = StreamingDetail(
            key = key,
            title = item.title,
            subtitle = item.subtitle.ifBlank { current.providerName },
            collection = item,
            sort = sort,
            pageSize = HOME_PLAYLIST_PAGE
        )
        loadPlaylistDetail(pluginId, detail)
    }

    fun closeDetail() {
        detailRequest++; detailJob?.cancel()
        val previous = if (history.isEmpty()) null else history.removeLast()
        mutable.update { it.copy(detail = previous) }
    }

    fun root() {
        history.clear()
        closeDetail()
    }

    fun page(offset: Int) {
        val current = state.value
        val pluginId = current.pluginId.takeIf { it.isNotBlank() } ?: return
        val detail = current.detail ?: return
        val target = detail.copy(offset = offset.coerceAtLeast(0))
        if (target.playlistTracks != null) {
            mutable.update { it.copy(detail = projectWholePlaylist(target)) }
        } else if (target.collection != null) {
            loadPlaylistDetail(pluginId, target)
        }
    }

    fun playlistQuery(query: String) = updatePlaylistView { it.copy(query = query) }

    fun playlistSort(sort: CollectionSort) {
        val current = state.value
        val detail = current.detail?.takeIf { it.isPlaylist } ?: return
        val sortKey = providerSortKey(current.pluginId, current.profile?.userId, detail.key)
        savedSorts = savedSorts + (sortKey to sort)
        updatePlaylistView { it.copy(sort = sort) }
    }

    private fun updatePlaylistView(edit: (StreamingDetail) -> StreamingDetail) {
        val current = state.value
        val pluginId = current.pluginId.takeIf { it.isNotBlank() } ?: return
        val detail = current.detail?.takeIf { it.isPlaylist && it.collection != null } ?: return
        val target = edit(detail).copy(offset = 0)
        if (target == detail) return
        when {
            target.playlistTracks != null -> mutable.update { it.copy(detail = projectWholePlaylist(target)) }
            detail.loadingPlaylist -> mutable.update { it.copy(detail = target) }
            else -> loadPlaylistDetail(pluginId, target)
        }
    }

    fun retryDetail() {
        val current = state.value
        val pluginId = current.pluginId.takeIf { it.isNotBlank() } ?: return
        val provider = providers[pluginId] ?: return
        val detail = current.detail ?: return
        val secPrefix = "v2-sec:$pluginId:"
        when {
            detail.key.startsWith(secPrefix) -> {
                val sectionId = detail.key.removePrefix(secPrefix)
                val def = sectionDefs[pluginId]?.find { it.id == sectionId } ?: return
                loadSection(pluginId, provider, def, session)
            }
            detail.key == libraryDetailKey(pluginId) -> loadUserLibrary(pluginId, force = true, origin = session)
            detail.collection != null -> loadPlaylistDetail(pluginId, detail.copy(playlistTracks = null))
        }
    }

    private fun loadPlaylistDetail(pluginId: String, target: StreamingDetail) {
        val provider = providers[pluginId] ?: return
        val item = target.collection ?: return
        detailJob?.cancel()
        val request = ++detailRequest
        val origin = session
        val whole = target.query.isNotBlank() || target.sort != CollectionSort() || target.playlistTracks != null
        mutable.update {
            it.copy(detail = target.copy(loading = true, loadingPlaylist = whole, error = null, tracks = emptyList(), collections = emptyList()))
        }
        detailJob = scope.launch {
            try {
                val loaded = if (whole) {
                    readWholeProviderPlaylist(pluginId, provider, target)
                } else {
                    val page = fetchPlaylistPage(pluginId, provider, item.id, target.offset)
                    val raw = providerTrackArray(page)
                    val paged = page as? JsonObject
                    val total = (paged?.get("total") as? JsonPrimitive)?.intOrNull ?: (target.offset + raw.size)
                    val more = (paged?.get("hasMore") as? JsonPrimitive)?.booleanOrNull ?: (target.offset + raw.size < total)
                    val next = ((paged?.get("nextOffset") as? JsonPrimitive)?.intOrNull ?: (target.offset + raw.size))
                        .takeIf { more && raw.isNotEmpty() && it > target.offset }
                    val pageSize = if (target.offset == 0 && raw.isNotEmpty()) raw.size else target.pageSize.coerceAtLeast(1)
                    target.copy(
                        tracks = raw.providerTracks(provider.id, "home-playlist-${item.id}-${target.offset}"),
                        total = total,
                        pageSize = pageSize,
                        nextOffset = next
                    )
                }
                if (origin == session && request == detailRequest) {
                    mutable.update { current ->
                        val projected = if (whole) {
                            projectWholePlaylist(loaded.copy(
                                query = current.detail?.query.orEmpty(),
                                sort = current.detail?.sort ?: target.sort
                            ))
                        } else loaded
                        current.copy(detail = projected.copy(loading = false, loadingPlaylist = false, error = null))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (origin == session && request == detailRequest) {
                    val msg = friendlyProviderError(e.message, mutable.value.providerName) ?: "歌单未载入，请重试"
                    mutable.update {
                        it.copy(detail = target.copy(
                            query = it.detail?.query ?: target.query,
                            sort = it.detail?.sort ?: target.sort,
                            tracks = emptyList(),
                            loading = false,
                            loadingPlaylist = false,
                            nextOffset = null,
                            error = msg
                        ))
                    }
                }
            }
        }
    }

    private suspend fun fetchPlaylistPage(pluginId: String, provider: MobileProvider, playlistId: String, offset: Int): JsonElement {
        val id = JsonPrimitive(playlistId)
        return when {
            "fetchPlaylistTracksPage" in provider.methods -> callProvider(
                pluginId,
                "fetchPlaylistTracksPage",
                JsonArray(listOf(id, JsonPrimitive(offset), JsonPrimitive(HOME_PLAYLIST_PAGE), JsonPrimitive(false)))
            )
            "readPlaylistImportPage" in provider.methods -> callProvider(
                pluginId,
                "readPlaylistImportPage",
                JsonArray(listOf(id, JsonPrimitive(offset), JsonPrimitive(HOME_PLAYLIST_PAGE)))
            )
            else -> callProvider(pluginId, "fetchPlaylistTracks", JsonArray(listOf(id, JsonPrimitive(false))))
        }
    }

    private suspend fun readWholeProviderPlaylist(pluginId: String, provider: MobileProvider, target: StreamingDetail): StreamingDetail {
        if (target.playlistTracks != null) return target
        val item = target.collection ?: return target
        val pagedMethod = "fetchPlaylistTracksPage" in provider.methods || "readPlaylistImportPage" in provider.methods
        if (!pagedMethod) {
            val raw = providerTrackArray(fetchPlaylistPage(pluginId, provider, item.id, 0))
            val all = raw.providerTracks(provider.id, "home-playlist-${item.id}-all")
            return target.copy(playlistTracks = all, total = all.size, pageSize = HOME_PLAYLIST_PAGE)
        }
        val all = mutableListOf<CoreTrackView>()
        var offset = 0
        var total = 0
        var pages = 0
        while (pages < 50) {
            currentCoroutineContext().ensureActive()
            val page = fetchPlaylistPage(pluginId, provider, item.id, offset)
            val raw = providerTrackArray(page)
            all += raw.providerTracks(provider.id, "home-playlist-${item.id}-$offset")
            val paged = page as? JsonObject
            total = (paged?.get("total") as? JsonPrimitive)?.intOrNull ?: all.size
            val more = (paged?.get("hasMore") as? JsonPrimitive)?.booleanOrNull ?: (all.size < total)
            val next = (paged?.get("nextOffset") as? JsonPrimitive)?.intOrNull ?: (offset + raw.size)
            if (!more || raw.isEmpty() || next <= offset) break
            offset = next
            pages++
        }
        return target.copy(playlistTracks = all, total = maxOf(total, all.size), pageSize = HOME_PLAYLIST_PAGE)
    }

    private fun projectWholePlaylist(target: StreamingDetail): StreamingDetail {
        val size = target.pageSize.coerceAtLeast(1)
        val matches = browseCollection(target.playlistTracks.orEmpty(), target.query, target.sort)
        val offset = target.offset.coerceAtMost(((matches.size - 1).coerceAtLeast(0) / size) * size)
        val page = matches.drop(offset).take(size)
        return target.copy(
            tracks = page,
            matchingTotal = matches.size,
            offset = offset,
            nextOffset = (offset + page.size).takeIf { it < matches.size }
        )
    }

    private fun sectionDetailKey(pluginId: String, sectionId: String) = "v2-sec:$pluginId:$sectionId"
    private fun libraryDetailKey(pluginId: String) = "v2-lib:$pluginId"
}

internal fun providerSortKey(pluginId: String, accountId: String?, detailKey: String): String =
    "provider:$pluginId:${accountId.orEmpty()}:$detailKey"
