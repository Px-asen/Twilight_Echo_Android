package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CollectionSort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal const val NCM_PLUGIN_ID = "com.twilightecho.provider.ncm"

enum class StreamingAccess { LOADING, NOT_INSTALLED, DISABLED, LOGGED_OUT, READY, ERROR }
enum class StreamingRecommendation(val title: String, internal val method: String, val caption: String) {
    DAILY("每日推荐", "fetchRecommendSongs", "今天的心动"),
    FM("私人漫游", "fetchPersonalFm", "随心去听"),
    RADAR("私人雷达", "fetchPrivateContent", "发现新喜欢")
}
enum class StreamingLibraryGroup(val title: String) {
    LIKED("我的收藏"), CREATED("创建的歌单"), PLAYLISTS("收藏的歌单"),
    ALBUMS("收藏的专辑"), ARTISTS("收藏的歌手"), RECENT("最近播放"), RANKING("听歌排行")
}
data class StreamingCollection(val id: String, val kind: String, val title: String,
    val subtitle: String = "", val cover: String? = null, val count: Int = 0, val owned: Boolean? = null)
data class StreamingSection(val tracks: List<CoreTrackView> = emptyList(), val loading: Boolean = false,
    val loaded: Boolean = false, val error: String? = null)
data class StreamingArtistProfile(val name: String = "", val introduction: String = "", val portrait: String? = null,
    val loading: Boolean = true, val error: String? = null)
data class StreamingDetail(val key: String, val title: String, val subtitle: String = "",
    val collection: StreamingCollection? = null, val recommendation: StreamingRecommendation? = null,
    val group: StreamingLibraryGroup? = null, val tracks: List<CoreTrackView> = emptyList(),
    val collections: List<StreamingCollection> = emptyList(), val total: Int = 0, val offset: Int = 0,
    val nextOffset: Int? = null, val loading: Boolean = true, val error: String? = null,
    val artistAlbums: Boolean = false, val artistProfile: StreamingArtistProfile? = null,
    val query: String = "", val sort: CollectionSort = CollectionSort(),
    /** A complete occurrence list, loaded only when whole-playlist search or sorting is requested. */
    val playlistTracks: List<CoreTrackView>? = null, val matchingTotal: Int? = null,
    val loadingPlaylist: Boolean = false)
data class StreamingUiState(val access: StreamingAccess = StreamingAccess.LOADING, val nickname: String = "",
    val accountId: String? = null, val accountError: String? = null,
    val sections: Map<StreamingRecommendation, StreamingSection> = emptyMap(),
    val likedPlaylist: StreamingCollection? = null, val playlists: List<StreamingCollection> = emptyList(),
    val recommendedPlaylists: List<StreamingCollection> = emptyList(),
    val libraryLoading: Boolean = false, val libraryLoaded: Boolean = false, val libraryError: String? = null,
    val detail: StreamingDetail? = null, val favoriteTrackId: String? = null, val favoriteLiked: Boolean? = null,
    val favoriteBusy: Boolean = false, val favoriteError: String? = null, val profile: NcmProfile? = null)
data class StreamingActions(val account: () -> Unit = {}, val library: () -> Unit = {},
    val refresh: () -> Unit = {}, val recommendation: (StreamingRecommendation) -> Unit = {},
    val group: (StreamingLibraryGroup) -> Unit = {}, val collection: (StreamingCollection) -> Unit = {},
    val back: () -> Unit = {}, val retry: () -> Unit = {}, val page: (Int) -> Unit = {},
    /** Closes every open streaming page, so the next one starts a new trail. */
    val root: () -> Unit = {},
    val artistTab: (Boolean) -> Unit = {},
    val playlistQuery: (String) -> Unit = {}, val playlistSort: (CollectionSort) -> Unit = {},
    val retryArtist: () -> Unit = {},
    val play: (List<CoreTrackView>, CoreTrackView) -> Unit = { _, _ -> },
    /** Creates an own playlist; the songs, when given, go straight into it. */
    val createPlaylist: (String, List<CoreTrackView>) -> Unit = { _, _ -> },
    val deletePlaylist: (StreamingCollection) -> Unit = {},
    val addToPlaylist: (StreamingCollection, List<CoreTrackView>) -> Unit = { _, _ -> },
    val removeFromPlaylist: (StreamingCollection, CoreTrackView) -> Unit = { _, _ -> })

/** Presentation only. All NCM reads and normalization remain in the shared desktop provider. */
internal class StreamingPresenter(private val scope: CoroutineScope,
    private val providerCall: suspend (String, JsonArray) -> JsonElement) {
    private val mutable = MutableStateFlow(StreamingUiState())
    val state = mutable.asStateFlow()
    private var accountJob: Job? = null
    private var libraryJob: Job? = null
    private var recommendedJob: Job? = null
    private var detailJob: Job? = null
    private var favoriteJob: Job? = null
    private var artistJob: Job? = null
    private var artistRequest = 0L
    private var savedSorts: Map<String, CollectionSort> = emptyMap()
    private var currentSong: String? = null
    private var favoriteRequest = 0L
    private val sectionJobs = mutableMapOf<StreamingRecommendation, Job>()
    private var session = 0L
    private var detailRequest = 0L
    private var libraryRequest = 0L
    private val sectionRequests = mutableMapOf<StreamingRecommendation, Long>()
    private var enabled = false
    private var available: Pair<Boolean, Boolean>? = null
    private val history = ArrayDeque<StreamingDetail>()

    fun availability(installed: Boolean, enabled: Boolean) {
        if (available == (installed to enabled)) return
        available = installed to enabled
        this.enabled = enabled
        if (enabled) refreshAccount() else {
            invalidate()
            mutable.value = StreamingUiState(access = if (installed) StreamingAccess.DISABLED else StreamingAccess.NOT_INSTALLED)
        }
    }

    private fun invalidate() {
        session++; detailRequest++
        accountJob?.cancel(); libraryJob?.cancel(); recommendedJob?.cancel(); detailJob?.cancel()
        favoriteRequest++; favoriteJob?.cancel()
        artistRequest++; artistJob?.cancel()
        sectionJobs.values.forEach { it.cancel() }; sectionJobs.clear()
        history.clear()
    }

    fun refreshAccount() {
        if (!enabled) return
        invalidate()
        val origin = session
        // Private data is hidden until this account is confirmed, including after an account switch.
        val old = state.value
        mutable.value = StreamingUiState(access = StreamingAccess.LOADING)
        accountJob = scope.launch {
            try {
                val result = call("checkLogin").jsonObject
                if (session != origin) return@launch
                val profile = result["profile"] as? JsonObject
                if (result["loggedIn"]?.jsonPrimitive?.booleanOrNull != true || profile == null) {
                    mutable.value = StreamingUiState(access = StreamingAccess.LOGGED_OUT)
                } else {
                    val account = profile.ncmProfile()
                    val id = account.userId
                    mutable.value = if (id.isNotBlank() && old.accountId == id)
                        old.copy(access = StreamingAccess.READY, nickname = account.nickname, profile = account, accountError = null,
                            libraryLoading = false, detail = null,
                            sections = old.sections.mapValues { it.value.copy(loading = false) })
                    else StreamingUiState(access = StreamingAccess.READY, nickname = account.nickname, accountId = id, profile = account)
                    loadLibrary()
                    loadRecommendedPlaylists()
                    loadHome()
                    readFavorite()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (session == origin) mutable.value = StreamingUiState(access = StreamingAccess.ERROR,
                    accountError = e.message ?: "网易云账号暂时无法连接")
            }
        }
    }

    fun refresh() {
        if (state.value.access != StreamingAccess.READY) { refreshAccount(); return }
        loadHome(force = true); loadLibrary(force = true); loadRecommendedPlaylists()
    }

    private fun loadRecommendedPlaylists() {
        if (state.value.access != StreamingAccess.READY) return
        recommendedJob?.cancel(); val origin = session
        recommendedJob = scope.launch {
            val lists = try { call("fetchRecommendPlaylists").array().map { collection(it.jsonObject, "playlist") } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { return@launch }
            if (origin == session) mutable.update { it.copy(recommendedPlaylists = lists) }
        }
    }

    /** Creates a playlist in the signed-in account. */
    suspend fun createPlaylist(name: String): Result<StreamingCollection> = write {
        val created = collection(call("createPlaylist", JsonPrimitive(name.trim())).jsonObject, "playlist").copy(owned = true)
        loadLibrary(force = true)
        created
    }

    /** Deletes an own playlist, or stops collecting someone else's. */
    suspend fun deletePlaylist(item: StreamingCollection): Result<Unit> = write {
        // The provider only deletes playlists its latest library read listed as owned; the rest are unsubscribed.
        call("fetchUserLibrary", JsonPrimitive(true))
        call("deletePlaylist", JsonPrimitive(item.playlistNumber()))
        mutable.update { it.copy(playlists = it.playlists.filterNot { list -> list.id == item.id }) }
        if (state.value.detail?.collection?.id == item.id) closeDetail()
        loadLibrary(force = true)
    }

    suspend fun addToPlaylist(playlist: StreamingCollection, tracks: List<CoreTrackView>): Result<Unit> = write {
        val songs = tracks.mapNotNull { it.ncmSongId?.toLongOrNull() }.distinct()
        require(songs.isNotEmpty()) { "只能把网易云歌曲加入网易云歌单" }
        call("addTracksToPlaylist", JsonPrimitive(playlist.playlistNumber()), JsonArray(songs.map { JsonPrimitive(it) }))
        loadLibrary(force = true)
        state.value.detail?.takeIf { it.collection?.id == playlist.id }?.let { loadDetail(it.copy(offset = 0, playlistTracks = null)) }
        Unit
    }

    suspend fun removeFromPlaylist(playlist: StreamingCollection, track: CoreTrackView): Result<Unit> = write {
        val song = track.ncmSongId?.toLongOrNull() ?: error("这首歌不是网易云歌曲")
        call("removeTracksFromPlaylist", JsonPrimitive(playlist.playlistNumber()), JsonArray(listOf(JsonPrimitive(song))))
        mutable.update { current -> current.copy(detail = current.detail?.let { detail ->
            if (detail.collection?.id != playlist.id) detail
            else {
                val all = detail.playlistTracks
                val removed = (all ?: detail.tracks).count { it.ncmSongId == track.ncmSongId }
                val updated = detail.copy(tracks = detail.tracks.filterNot { it.ncmSongId == track.ncmSongId },
                    playlistTracks = all?.filterNot { it.ncmSongId == track.ncmSongId }, total = (detail.total - removed).coerceAtLeast(0))
                if (all != null) projectPlaylist(updated) else updated
            }
        }) }
        loadLibrary(force = true)
    }

    /**
     * The same song on NCM, for a computer song that cannot be reached right now. Uses the song's
     * NCM ID when the library knows it, otherwise the best search hit by title, artist and length.
     */
    suspend fun findMatch(track: CoreTrackView): CoreTrackView? {
        track.ncmSongId?.takeIf { it.matches(Regex("[0-9]+")) }?.let { id ->
            return CoreTrackView(id = "provider://ncm/$id", source = "provider://ncm/$id", ncmSongId = id,
                title = track.title, artist = track.artist, album = track.album, duration = track.duration)
        }
        if (track.title.isBlank()) return null
        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val raw = call("searchSongs", JsonPrimitive(query), JsonPrimitive(MATCH_CANDIDATES), JsonPrimitive(0)).jsonObject
        val candidates = raw["items"].array().mapNotNull { runCatching { providerTrack(it.jsonObject) }.getOrNull() }
        return bestStreamingMatch(track, candidates.filter { it.unavailableReason == null })
    }

    private suspend fun <T> write(block: suspend () -> T): Result<T> {
        if (state.value.access != StreamingAccess.READY) return Result.failure(IllegalStateException("请先登录网易云音乐"))
        return try { Result.success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    private fun StreamingCollection.playlistNumber() = id.toLongOrNull() ?: error("歌单无效")

    // Warm all three cards after login, with multi-request FM last on the serial provider runner.
    // Opening a card reuses this batch, including personal FM.
    private fun loadHome(force: Boolean = false) = listOf(StreamingRecommendation.DAILY,
        StreamingRecommendation.RADAR, StreamingRecommendation.FM).forEach { loadSection(it, force) }
    private fun loadSection(kind: StreamingRecommendation, force: Boolean = false) {
        if (state.value.access != StreamingAccess.READY) return
        val previous = state.value.sections[kind] ?: StreamingSection()
        if (!force && (previous.loaded || previous.loading)) return
        sectionJobs.remove(kind)?.cancel()
        val request = (sectionRequests[kind] ?: 0L) + 1
        sectionRequests[kind] = request
        val origin = session
        mutable.update { it.copy(sections = it.sections + (kind to previous.copy(loading = true, error = null))) }
        mutable.update { current -> current.copy(detail = current.detail?.let {
            if (it.recommendation == kind) it.copy(loading = true, error = null) else it
        }) }
        sectionJobs[kind] = scope.launch {
            val section = try { StreamingSection(tracks = trackList(call(kind.method), "rec:$kind"), loaded = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { previous.copy(loading = false, error = e.message ?: "推荐暂时无法载入") }
            if (origin == session && sectionRequests[kind] == request) {
                mutable.update { current -> current.copy(sections = current.sections + (kind to section),
                    detail = current.detail?.let { detail -> if (detail.recommendation == kind)
                        detail.copy(tracks = section.tracks, total = section.tracks.size, loading = false, error = section.error)
                    else detail }) }
            }
        }
    }

    fun loadLibrary(force: Boolean = false) {
        if (state.value.access != StreamingAccess.READY || (!force && (state.value.libraryLoaded || state.value.libraryLoading))) return
        libraryJob?.cancel(); val origin = session; val request = ++libraryRequest
        mutable.update { it.copy(libraryLoading = true, libraryError = null) }
        libraryJob = scope.launch {
            try {
                val library = call("fetchUserLibrary", JsonPrimitive(force)).jsonObject
                val liked = (library["likedPlaylist"] as? JsonObject)?.let { collection(it, "playlist") }
                val lists = library["playlists"].array().map { collection(it.jsonObject, "playlist") }
                if (origin == session && request == libraryRequest) mutable.update { it.copy(likedPlaylist = liked, playlists = lists,
                    libraryLoading = false, libraryLoaded = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (origin == session && request == libraryRequest) mutable.update { it.copy(libraryLoading = false,
                libraryError = e.message ?: "在线音乐库未载入") } }
        }
    }

    fun openRecommendation(kind: StreamingRecommendation) {
        if (state.value.access != StreamingAccess.READY) return
        history.clear()
        artistRequest++; artistJob?.cancel()
        detailRequest++; detailJob?.cancel()
        val section = state.value.sections[kind] ?: StreamingSection()
        mutable.update { it.copy(detail = StreamingDetail("rec:$kind", kind.title, "网易云音乐 · ${kind.caption}",
            recommendation = kind, tracks = section.tracks, total = section.tracks.size,
            loading = section.loading || !section.loaded && section.error == null, error = section.error)) }
        loadSection(kind, force = section.error != null)
    }

    fun openGroup(group: StreamingLibraryGroup) {
        history.clear()
        artistRequest++; artistJob?.cancel()
        val title = if (group == StreamingLibraryGroup.LIKED) "我喜欢的音乐" else group.title
        val detail = StreamingDetail("group:$group", title,
            subtitle = if (group == StreamingLibraryGroup.RANKING) "网易云音乐 · 最近一周" else "网易云音乐", group = group)
        loadDetail(withSavedSort(detail))
    }
    fun openCollection(item: StreamingCollection) {
        state.value.detail?.takeIf { !it.loading }?.let { history.addLast(it) }
        artistRequest++; artistJob?.cancel()
        loadDetail(withSavedSort(StreamingDetail("${item.kind}:${item.id}", item.title, item.subtitle, collection = item)))
        if (item.kind == "artist") loadArtistProfile()
    }
    fun closeDetail() {
        detailRequest++; detailJob?.cancel()
        artistRequest++; artistJob?.cancel()
        val previous = if (history.isEmpty()) null else history.removeLast()
        mutable.update { it.copy(detail = previous) }
        if (previous?.collection?.kind == "artist" && previous.artistProfile?.loading != false) loadArtistProfile()
    }
    fun libraryRoot() { history.clear(); closeDetail(); loadLibrary() }
    fun page(offset: Int) { state.value.detail?.let {
        val target = it.copy(offset = offset.coerceAtLeast(0))
        if (target.playlistTracks != null) mutable.update { s -> s.copy(detail = projectPlaylist(target)) }
        else loadDetail(target)
    } }
    fun artistTab(albums: Boolean) { state.value.detail?.takeIf { it.collection?.kind == "artist" }?.let {
        if (it.artistAlbums == albums) return
        loadDetail(it.copy(artistAlbums = albums, offset = 0))
    } }

    fun restoreSorts(sorts: Map<String, CollectionSort>) { savedSorts = sorts }
    private fun withSavedSort(detail: StreamingDetail) = if (detail.isPlaylist)
        detail.copy(sort = savedSorts[streamingSortKey(state.value.accountId, detail)] ?: CollectionSort()) else detail

    fun playlistQuery(query: String) = updatePlaylistView { it.copy(query = query) }
    fun playlistSort(sort: CollectionSort) = updatePlaylistView { it.copy(sort = sort) }

    private fun updatePlaylistView(edit: (StreamingDetail) -> StreamingDetail) {
        val current = state.value.detail?.takeIf { it.isPlaylist } ?: return
        val target = edit(current).copy(offset = 0)
        if (target == current) return
        when {
            target.playlistTracks != null -> mutable.update { it.copy(detail = projectPlaylist(target)) }
            current.loadingPlaylist -> mutable.update { it.copy(detail = target) }
            else -> loadDetail(target)
        }
    }

    fun retryArtist() = loadArtistProfile()

    /** Profile failure never hides playable songs. Switching content tabs does not refetch the portrait. */
    private fun loadArtistProfile() {
        val target = state.value.detail?.takeIf { it.collection?.kind == "artist" } ?: return
        artistJob?.cancel(); val request = ++artistRequest; val origin = session
        mutable.update { it.copy(detail = it.detail?.copy(artistProfile = (target.artistProfile ?: StreamingArtistProfile()).copy(loading = true, error = null))) }
        artistJob = scope.launch {
            val profile = try {
                val raw = call("fetchArtistMetadata", JsonPrimitive(target.collection!!.id)).jsonObject
                val metadata = raw["metadata"] as? JsonObject
                StreamingArtistProfile(name = metadata?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    introduction = metadata?.get("texts").array().mapNotNull { item ->
                        item.jsonObject.takeIf { it["field"]?.jsonPrimitive?.content == "introduction" }
                            ?.get("value")?.jsonPrimitive?.contentOrNull
                    }.joinToString("\n\n"),
                    portrait = metadata?.get("artwork").array().firstOrNull()?.jsonObject?.get("resource")?.jsonPrimitive?.contentOrNull,
                    loading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { (target.artistProfile ?: StreamingArtistProfile()).copy(loading = false, error = e.message ?: "歌手简介未载入") }
            if (origin == session && request == artistRequest && state.value.detail?.key == target.key)
                mutable.update { it.copy(detail = it.detail?.copy(artistProfile = profile)) }
        }
    }
    fun retryDetail() {
        state.value.detail?.let { detail ->
            if (detail.recommendation != null) loadSection(detail.recommendation, force = true)
            else {
                loadDetail(detail.copy(playlistTracks = null))
                if (detail.collection?.kind == "artist") loadArtistProfile()
            }
        }
    }

    private fun loadDetail(target: StreamingDetail) {
        if (state.value.access != StreamingAccess.READY) return
        detailJob?.cancel(); val request = ++detailRequest; val origin = session
        val wholePlaylist = target.isPlaylist && (target.query.isNotBlank() || target.sort != CollectionSort() || target.playlistTracks != null)
        mutable.update { it.copy(detail = target.copy(loading = true, loadingPlaylist = wholePlaylist, error = null,
            tracks = emptyList(), collections = emptyList())) }
        detailJob = scope.launch {
            try {
                val offset = target.offset
                val result = when {
                    wholePlaylist -> readWholePlaylist(target)
                    target.collection?.kind == "playlist" -> {
                        val raw = call("readPlaylistImportPage", JsonPrimitive(target.collection.id), JsonPrimitive(offset), JsonPrimitive(100)).jsonObject
                        target.copy(tracks = raw["songs"].array().mapIndexed { index, song -> providerTrack(song.jsonObject)
                            .copy(queueEntryId = "provider-playlist-${target.collection.id}-$offset-$index") },
                            total = raw["total"].number(), nextOffset = raw["nextOffset"].number().takeIf { raw["hasMore"]?.jsonPrimitive?.booleanOrNull == true })
                    }
                    target.collection?.kind == "album" -> withTracks(target, call("fetchAlbumTracks", JsonPrimitive(target.collection.id)))
                    target.collection?.kind == "artist" -> {
                        if (target.artistAlbums) withCollections(target, call("fetchArtistAlbums", JsonPrimitive(target.collection.id)), "album")
                        else withTracks(target, call("fetchArtistTopSongs", JsonPrimitive(target.collection.id)))
                    }
                    target.group == StreamingLibraryGroup.LIKED -> {
                        val raw = call("fetchLikedTracksPage", JsonPrimitive(offset), JsonPrimitive(100)).jsonObject
                        target.copy(tracks = trackList(raw["tracks"] ?: JsonArray(emptyList()), "liked:$offset"), total = raw["total"].number(),
                            nextOffset = raw["nextOffset"].number().takeIf { raw["hasMore"]?.jsonPrimitive?.booleanOrNull == true })
                    }
                    target.group == StreamingLibraryGroup.CREATED || target.group == StreamingLibraryGroup.PLAYLISTS -> {
                        val raw = call("fetchUserLibrary").jsonObject
                        val lists = raw["playlists"].array().map { collection(it.jsonObject, "playlist") }
                        if (session == origin) mutable.update { it.copy(playlists = lists, libraryLoaded = true,
                            likedPlaylist = (raw["likedPlaylist"] as? JsonObject)?.let { p -> collection(p, "playlist") }) }
                        val owned = target.group == StreamingLibraryGroup.CREATED
                        val selected = lists.filter { if (owned) it.owned == true else it.owned != true }
                        target.copy(collections = selected, total = selected.size)
                    }
                    target.group == StreamingLibraryGroup.ALBUMS -> withCollections(target, call("fetchSavedAlbums"), "album")
                    target.group == StreamingLibraryGroup.ARTISTS -> withCollections(target, call("fetchSavedArtists"), "artist")
                    target.group == StreamingLibraryGroup.RECENT -> withTracks(target, call("fetchRecentSongs", JsonPrimitive(100)))
                    target.group == StreamingLibraryGroup.RANKING -> withTracks(target, call("fetchPlayRecords", JsonPrimitive(1)))
                    else -> target
                }
                if (origin == session && request == detailRequest) mutable.update { current ->
                    // Text can change while pages load. Project the latest query, not the one that began the read.
                    val projected = if (wholePlaylist) projectPlaylist(result.copy(query = current.detail?.query.orEmpty(),
                        sort = current.detail?.sort ?: target.sort)) else result
                    current.copy(detail = projected.copy(artistProfile = current.detail?.artistProfile,
                        loading = false, loadingPlaylist = false, error = null))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (origin == session && request == detailRequest) mutable.update {
                it.copy(detail = target.copy(query = it.detail?.query ?: target.query, sort = it.detail?.sort ?: target.sort,
                    artistProfile = it.detail?.artistProfile, tracks = emptyList(), collections = emptyList(), loading = false, loadingPlaylist = false,
                    nextOffset = null, error = e.message ?: "内容未载入，请重试"))
            } }
        }
    }

    private suspend fun readWholePlaylist(target: StreamingDetail): StreamingDetail {
        if (target.playlistTracks != null) return target
        val all = mutableListOf<CoreTrackView>()
        var offset = 0
        var total: Int
        var firstTotal: Int? = null
        do {
            currentCoroutineContext().ensureActive()
            val liked = target.group == StreamingLibraryGroup.LIKED
            val raw = if (liked) call("fetchLikedTracksPage", JsonPrimitive(offset), JsonPrimitive(100)).jsonObject
                else call("readPlaylistImportPage", JsonPrimitive(target.collection!!.id), JsonPrimitive(offset), JsonPrimitive(100)).jsonObject
            val songs = raw[if (liked) "tracks" else "songs"].array()
            all += songs.mapIndexed { index, item -> providerTrack(item.jsonObject)
                .copy(queueEntryId = "provider-${target.key}-$offset-$index") }
            total = raw["total"].number()
            check(firstTotal == null || firstTotal == total) { "歌单已更新，请刷新后重试" }
            firstTotal = total
            val more = raw["hasMore"]?.jsonPrimitive?.booleanOrNull == true
            if (!more) break
            val next = raw["nextOffset"].number()
            check(next > offset) { "歌单分页未前进，请刷新后重试" }
            offset = next
        } while (true)
        if (target.collection?.kind == "playlist") check(all.size == total) { "歌单尚未完整载入，请重试" }
        return target.copy(playlistTracks = all, total = total)
    }

    private fun projectPlaylist(target: StreamingDetail): StreamingDetail {
        val matches = browseCollection(target.playlistTracks.orEmpty(), target.query, target.sort)
        val offset = target.offset.coerceAtMost(((matches.size - 1).coerceAtLeast(0) / 100) * 100)
        val page = matches.drop(offset).take(100)
        return target.copy(tracks = page, matchingTotal = matches.size, offset = offset,
            nextOffset = (offset + page.size).takeIf { it < matches.size })
    }

    private fun withTracks(target: StreamingDetail, raw: JsonElement): StreamingDetail {
        val tracks = trackList(raw, target.key); return target.copy(tracks = tracks, collections = emptyList(), total = tracks.size, nextOffset = null)
    }
    private fun withCollections(target: StreamingDetail, raw: JsonElement, kind: String): StreamingDetail {
        val items = raw.array().map { collection(it.jsonObject, kind) }
        return target.copy(collections = items, tracks = emptyList(), total = items.size, nextOffset = null)
    }
    private suspend fun call(method: String, vararg args: JsonElement) = providerCall(method, JsonArray(args.toList()))
    private fun trackList(raw: JsonElement, occurrence: String) = raw.array().mapIndexed { index, item ->
        providerTrack(item.jsonObject).copy(queueEntryId = "provider-$occurrence-$index")
    }

    fun observeTrack(track: CoreTrackView?) {
        val song = track?.source?.takeIf { it.startsWith("provider://ncm/") }?.substringAfterLast('/')
        if (song == currentSong) return
        currentSong = song; readFavorite()
    }
    private fun readFavorite() {
        favoriteJob?.cancel(); val request = ++favoriteRequest; val origin = session
        val song = currentSong
        mutable.update { it.copy(favoriteTrackId = song, favoriteLiked = null, favoriteBusy = false, favoriteError = null) }
        if (song == null || state.value.access != StreamingAccess.READY) return
        mutable.update { it.copy(favoriteBusy = true) }
        favoriteJob = scope.launch {
            try {
                val liked = call("isTrackLiked", JsonPrimitive(song)).jsonPrimitive.boolean
                if (origin == session && request == favoriteRequest) mutable.update { it.copy(favoriteLiked = liked, favoriteBusy = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (origin == session && request == favoriteRequest) mutable.update {
                it.copy(favoriteBusy = false, favoriteError = e.message ?: "网易云喜欢状态未载入")
            } }
        }
    }
    fun toggleFavorite() {
        val song = currentSong ?: return
        if (state.value.access != StreamingAccess.READY) {
            mutable.update { it.copy(favoriteError = "请先登录网易云音乐") }; return
        }
        if (state.value.favoriteBusy) return
        val liked = state.value.favoriteLiked ?: run { readFavorite(); return }
        val origin = session; val request = ++favoriteRequest
        mutable.update { it.copy(favoriteBusy = true, favoriteError = null) }
        favoriteJob = scope.launch {
            try {
                call("likeTrack", JsonPrimitive(song), JsonPrimitive(!liked))
                if (origin == session && request == favoriteRequest) {
                    mutable.update { it.copy(favoriteLiked = !liked, favoriteBusy = false) }
                    loadLibrary(force = true)
                    state.value.detail?.takeIf { it.group == StreamingLibraryGroup.LIKED }?.let { loadDetail(it.copy(offset = 0, playlistTracks = null)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (origin == session && request == favoriteRequest) mutable.update {
                it.copy(favoriteBusy = false, favoriteError = e.message ?: "网易云收藏更新失败")
            } }
        }
    }
    fun clearFavoriteError() { mutable.update { it.copy(favoriteError = null) } }
    private fun collection(raw: JsonObject, kind: String) = streamingCollection(raw, kind)

    private companion object { const val MATCH_CANDIDATES = 5 }
}

/**
 * Picks the search hit that is the same recording: the title must agree, and when an artist is
 * known it must agree too unless the lengths match within a few seconds. Null when nothing fits.
 */
internal fun bestStreamingMatch(track: CoreTrackView, candidates: List<CoreTrackView>): CoreTrackView? {
    val title = matchKey(track.title)
    if (title.isEmpty()) return null
    val artists = artistKeys(track.artist)
    return candidates.mapNotNull { candidate ->
        val other = matchKey(candidate.title)
        val titleScore = when {
            other == title -> 3
            other.isNotEmpty() && (title in other || other in title) -> 1
            else -> return@mapNotNull null
        }
        val artistMatch = artists.isNotEmpty() && artistKeys(candidate.artist).any { it in artists }
        val lengthMatch = track.duration > 0 && candidate.duration > 0 && kotlin.math.abs(track.duration - candidate.duration) <= 3
        if (artists.isNotEmpty() && !artistMatch && !lengthMatch) return@mapNotNull null
        candidate to titleScore + (if (artistMatch) 2 else 0) + (if (lengthMatch) 1 else 0)
    }.maxByOrNull { it.second }?.first
}

private fun matchKey(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
private fun artistKeys(value: String) =
    value.split('/', '、', ',', '，', '&', ';', '；').map(::matchKey).filter { it.isNotEmpty() }.toSet()

private fun JsonElement?.array() = (this as? JsonArray).orEmpty()
private fun JsonElement?.number() = (this as? JsonPrimitive)?.intOrNull ?: 0
