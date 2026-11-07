package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreTrackView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class ProviderSearchState(val provider: SearchProviderOption? = null, val query: String = "",
    val category: SearchCategory = SearchCategory.SONGS, val tracks: List<CoreTrackView> = emptyList(),
    val collections: List<StreamingCollection> = emptyList(), val offset: Int = 0, val total: Int = 0,
    val pageSize: Int = 30, val nextOffset: Int? = null, val loading: Boolean = false,
    val searched: Boolean = false, val error: String? = null, val detail: StreamingDetail? = null) {
    val supported: Boolean get() = provider?.methods?.contains(category.method) == true
}

/** Search is independent of Home and the account sheet, so opening either cannot replace its results. */
internal class ProviderSearchPresenter(private val scope: CoroutineScope,
    private val call: suspend (String, String, JsonArray) -> JsonElement) {
    private val mutable = MutableStateFlow(ProviderSearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var request = 0L
    private var detailJob: Job? = null
    private var detailRequest = 0L
    private var active = false
    private val history = ArrayDeque<StreamingDetail>()

    fun select(provider: SearchProviderOption) {
        if (state.value.provider != provider) {
            reset(provider = provider)
        }
        active = true
        if (!state.value.searched) search()
    }

    fun retain(options: List<SearchProviderOption>) {
        val current = state.value.provider ?: return
        val next = options.find { it.pluginId == current.pluginId }
        if (next != current) { reset(provider = next); if (next != null) search() }
    }

    fun active(value: Boolean) {
        active = value
        if (!value) { request++; job?.cancel(); mutable.update { it.copy(loading = false) } }
    }

    fun query(value: String) {
        if (state.value.query == value.trim()) return
        reset(query = value.trim(), category = if (value.isBlank()) SearchCategory.SONGS else state.value.category)
        search(debounce = true)
    }

    fun category(value: SearchCategory) {
        if (state.value.category == value) return
        reset(category = value)
        search()
    }

    fun refresh() { reset(); search() }

    fun search(offset: Int = 0, debounce: Boolean = false) {
        val target = state.value
        val provider = target.provider ?: return
        if (!active || target.query.isBlank() || !target.supported) return
        job?.cancel()
        val origin = ++request
        val pageOffset = offset.coerceAtLeast(0)
        mutable.value = target.copy(tracks = emptyList(), collections = emptyList(), offset = pageOffset,
            total = 0, nextOffset = null, loading = true, searched = false, error = null)
        job = scope.launch {
            try {
                if (debounce) delay(350)
                val value = call(provider.pluginId, target.category.method, buildJsonArray {
                    add(target.query); add(30); add(pageOffset)
                })
                val page = providerSearchPage(value, provider.providerId, target.category, pageOffset)
                if (origin == request) mutable.update { it.copy(tracks = page.tracks, collections = page.collections,
                    total = page.total, pageSize = if (pageOffset == 0) page.size.coerceAtLeast(1) else it.pageSize,
                    nextOffset = page.nextOffset, loading = false, searched = true) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (origin == request) mutable.update { it.copy(loading = false,
                error = friendlyProviderError(error.message, provider.name) ?: "搜索失败，请重试") } }
        }
    }

    fun openCollection(item: StreamingCollection) {
        state.value.detail?.let { history.addLast(it) }
        loadDetail(StreamingDetail("v2-search:${state.value.provider?.pluginId}:${item.kind}:${item.id}",
            item.title, item.subtitle, collection = item))
    }

    fun artistTab(albums: Boolean) {
        val detail = state.value.detail?.takeIf { it.collection?.kind == "artist" } ?: return
        if (detail.artistAlbums != albums) loadDetail(detail.copy(artistAlbums = albums))
    }

    fun retryDetail() { state.value.detail?.let(::loadDetail) }

    fun back() {
        detailRequest++; detailJob?.cancel()
        mutable.update { it.copy(detail = if (history.isEmpty()) null else history.removeLast()) }
    }

    private fun loadDetail(requested: StreamingDetail) {
        val provider = state.value.provider ?: return
        val item = requested.collection ?: return
        val target = requested.copy(tracks = emptyList(), collections = emptyList(), total = 0, error = null)
        detailJob?.cancel()
        val origin = ++detailRequest
        val method = when (item.kind) {
            "album" -> "fetchAlbumTracks"
            "artist" -> if (target.artistAlbums) "fetchArtistAlbums" else "fetchArtistTopSongs"
            else -> return
        }
        mutable.update { it.copy(detail = target.copy(loading = true, error = null, tracks = emptyList(), collections = emptyList())) }
        detailJob = scope.launch {
            try {
                check(method in provider.methods) { "${provider.name}暂不支持查看${if (target.artistAlbums) "歌手专辑" else "这些歌曲"}" }
                val value = call(provider.pluginId, method, buildJsonArray { add(item.id) })
                val tracks = if (target.artistAlbums) emptyList() else providerTrackArray(value)
                    .providerTracks(provider.providerId, "${target.key}-tracks")
                val albums = if (target.artistAlbums) providerSearchCollections(value, "album") else emptyList()
                if (origin == detailRequest) mutable.update { it.copy(detail = target.copy(tracks = tracks,
                    collections = albums, total = tracks.size + albums.size, loading = false, error = null)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (origin == detailRequest) mutable.update { it.copy(detail = target.copy(
                loading = false, error = friendlyProviderError(error.message, provider.name) ?: "内容未载入")) } }
        }
    }

    private fun reset(provider: SearchProviderOption? = state.value.provider, query: String = state.value.query,
        category: SearchCategory = state.value.category) {
        request++; job?.cancel(); detailRequest++; detailJob?.cancel(); history.clear()
        mutable.value = ProviderSearchState(provider = provider, query = query, category = category)
    }
}
