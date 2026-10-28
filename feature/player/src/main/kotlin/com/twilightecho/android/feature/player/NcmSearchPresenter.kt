package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreTrackView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class SearchSource(val label: String) { LIBRARY("音乐库"), NETEASE("网易云") }
enum class NcmSearchCategory(val label: String, internal val method: String, internal val kind: String) {
    SONGS("歌曲", "searchSongs", "song"), ALBUMS("专辑", "searchAlbums", "album"),
    ARTISTS("歌手", "searchArtists", "artist"), PLAYLISTS("歌单", "searchPlaylists", "playlist")
}
const val NCM_SEARCH_PAGE_SIZE = 30
data class NcmSearchState(
    val query: String = "", val category: NcmSearchCategory = NcmSearchCategory.SONGS,
    val tracks: List<CoreTrackView> = emptyList(), val collections: List<StreamingCollection> = emptyList(),
    val offset: Int = 0, val total: Int = 0, val loading: Boolean = false,
    val searched: Boolean = false, val error: String? = null
) {
    val count: Int get() = tracks.size + collections.size
    val hasNext: Boolean get() = count > 0 && offset + NCM_SEARCH_PAGE_SIZE < total
}

/** Public search uses the same provider as playback; only playlist search needs an account. */
internal class NcmSearchPresenter(private val scope: CoroutineScope,
    private val call: suspend (String, JsonArray) -> JsonElement) {
    private val mutable = MutableStateFlow(NcmSearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var request = 0L
    private var enabled = false
    private var active = false
    private var accountId: String? = null

    fun availability(value: Boolean) {
        if (enabled == value) return
        enabled = value
        reset()
        if (enabled) search()
    }

    fun active(value: Boolean) {
        if (active == value) return
        active = value
        if (active) { if (!state.value.searched) search() }
        else { request++; job?.cancel(); mutable.update { it.copy(loading = false) } }
    }

    fun account(id: String?) {
        if (accountId == id) return
        accountId = id
        if (state.value.category == NcmSearchCategory.PLAYLISTS) { reset(); search() }
    }

    fun query(value: String) {
        if (state.value.query == value.trim()) return
        reset(query = value.trim())
        search(debounce = true)
    }

    fun category(value: NcmSearchCategory) {
        if (state.value.category == value) return
        reset(category = value)
        search()
    }

    fun search(offset: Int = 0, debounce: Boolean = false) {
        val target = state.value
        if (!enabled || !active || target.query.isBlank() ||
            target.category == NcmSearchCategory.PLAYLISTS && accountId == null) return
        job?.cancel()
        val origin = ++request
        val page = offset.coerceAtLeast(0)
        mutable.value = target.copy(offset = page, tracks = emptyList(), collections = emptyList(),
            total = 0, loading = true, searched = false, error = null)
        job = scope.launch {
            try {
                if (debounce) delay(350)
                val raw = call(target.category.method, buildJsonArray {
                    add(target.query); add(NCM_SEARCH_PAGE_SIZE); add(page)
                }).jsonObject
                val items = raw.getValue("items").jsonArray
                val songs = if (target.category == NcmSearchCategory.SONGS) items.mapIndexed { index, item ->
                    providerTrack(item.jsonObject).copy(queueEntryId = "ncm-search-$page-$index")
                } else emptyList()
                val collections = if (target.category != NcmSearchCategory.SONGS)
                    items.map { streamingCollection(it.jsonObject, target.category.kind) } else emptyList()
                if (origin == request) mutable.update { it.copy(tracks = songs, collections = collections,
                    total = raw["total"]?.jsonPrimitive?.intOrNull ?: items.size, loading = false, searched = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (origin == request) mutable.update { it.copy(loading = false,
                error = e.message ?: "搜索失败，请重试") } }
        }
    }

    private fun reset(query: String = state.value.query, category: NcmSearchCategory = state.value.category) {
        request++; job?.cancel()
        mutable.value = NcmSearchState(query = query, category = category)
    }
}

internal fun streamingCollection(raw: JsonObject, kind: String) = StreamingCollection(raw.text("id"), kind,
    raw.text("name", "未命名合集"), if (kind == "artist")
        listOf(raw["musicSize"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { "$it 首歌曲" },
            raw["albumSize"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { "$it 张专辑" }).filterNotNull().joinToString(" · ")
    else raw.text("artist").ifBlank { raw.text("creatorName") },
    raw.text(if (kind == "artist") "picUrl" else "cover").ifBlank {
        raw.text(if (kind == "artist") "picUrlSmall" else "coverSmall") }.ifBlank { null },
    raw["trackCount"]?.jsonPrimitive?.intOrNull ?: 0, raw["owned"]?.jsonPrimitive?.booleanOrNull)
