package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DesktopBrowseState(
    val kind: String = "artist", val page: CatalogPage? = null, val loading: Boolean = false,
    val error: String? = null, val selected: CatalogEntity? = null,
    val metadata: EntityMetadataDetail? = null, val sources: CatalogSourcePage? = null,
    val detailLoading: Boolean = false, val detailError: String? = null,
    val artistAlbums: Boolean = false, val albums: List<CatalogEntity> = emptyList(),
    val albumsLoaded: Boolean = false, val albumsLoading: Boolean = false, val albumsError: String? = null,
    val artwork: Map<String, String?> = emptyMap(), val artworkRevision: Long = 0
)

/** One session-scoped read model; catalog IDs never become phone file paths or local library rows. */
internal class DesktopLibraryPresenter(private val scope: CoroutineScope, private val client: DesktopLibraryClient,
    /** Song covers may live on several computers, so the caller can route each ID to its own. */
    private val fetchArtwork: suspend (List<String>) -> Result<TrackArtworkPage> = { client.artwork(it) }) {
    private val mutable = MutableStateFlow(DesktopBrowseState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var pageRequest = 0L
    private var detailRequest = 0L
    private var artworkRequest = 0L
    private var albumRequest = 0L
    private var pageJob: Job? = null
    private var detailJob: Job? = null
    private var artworkJob: Job? = null
    private var albumJob: Job? = null
    private var refreshJob: Job? = null
    private var revisions: Pair<Long?, Long?>? = null
    private val pendingArtwork = linkedSetOf<String>()
    private val attemptedArtwork = linkedSetOf<String>()
    private val artworkAliases = linkedMapOf<String, String>()
    private val history = ArrayDeque<DesktopBrowseState>()
    private var historyInvalidated = false

    fun reset() {
        generation++
        pageRequest++; detailRequest++; artworkRequest++; albumRequest++
        pageJob?.cancel(); detailJob?.cancel(); artworkJob?.cancel()
        albumJob?.cancel(); history.clear(); historyInvalidated = false
        refreshJob?.cancel(); revisions = null
        pendingArtwork.clear(); attemptedArtwork.clear()
        artworkAliases.clear()
        mutable.value = DesktopBrowseState(artworkRevision = mutable.value.artworkRevision + 1)
    }

    /** Domain revisions invalidate read views, not the currently controlled playback session. */
    fun observe(catalogRevision: Long?, metadataRevision: Long?) {
        val next = catalogRevision to metadataRevision
        val previous = revisions
        revisions = next
        if (previous == null || previous == next) return
        if (history.isNotEmpty()) historyInvalidated = true
        refreshJob?.cancel()
        refreshJob = scope.launch { delay(180); refreshCurrent() }
    }

    fun refreshCurrent() {
        val current = mutable.value
        refreshArtwork()
        if (current.selected != null) open(current.selected, refresh = true)
        val origin = generation
        val request = ++pageRequest
        pageJob?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        pageJob = scope.launch {
            val result = client.catalog(current.kind, current.page?.offset ?: 0)
            if (origin != generation || request != pageRequest) return@launch
            result.fold({ page -> mutable.update { it.copy(page = page, loading = false) } },
                { error -> mutable.update { it.copy(error = error.message, loading = false) } })
        }
    }

    fun browse(kind: String = mutable.value.kind, offset: Int = 0) {
        val origin = generation
        val request = ++pageRequest
        detailRequest++
        detailJob?.cancel()
        albumRequest++; albumJob?.cancel(); history.clear(); historyInvalidated = false
        val revision = mutable.value.page?.takeIf { mutable.value.kind == kind && offset > 0 }?.revision
        pageJob?.cancel()
        mutable.update { it.copy(kind = kind, loading = true, error = null, selected = null,
            metadata = null, sources = null, artistAlbums = false, albums = emptyList(), albumsLoaded = false,
            albumsLoading = false, albumsError = null, page = if (kind == it.kind) it.page else null) }
        pageJob = scope.launch {
            val result = client.catalog(kind, offset, revision)
            if (origin != generation || request != pageRequest) return@launch
            result.fold({ page -> mutable.update { it.copy(page = page, loading = false) } },
                { error -> mutable.update { it.copy(error = error.message, loading = false) } })
        }
    }

    fun open(entity: CatalogEntity, refresh: Boolean = false) {
        val current = mutable.value
        val refreshing = refresh || current.selected?.id == entity.id
        if (!refreshing && current.selected?.kind == "artist" && entity.kind == "release") history.addLast(current)
        albumRequest++; albumJob?.cancel()
        val origin = generation
        val request = ++detailRequest
        detailJob?.cancel()
        val offset = if (refreshing) current.sources?.offset ?: 0 else 0
        val refreshAlbums = refreshing && entity.kind == "artist" && (current.artistAlbums || current.albumsLoaded)
        mutable.update { it.copy(selected = entity, metadata = if (refreshing) it.metadata else null,
            sources = if (refreshing) it.sources else null, detailLoading = true, detailError = null,
            artistAlbums = refreshing && it.artistAlbums, albums = if (refreshing) it.albums else emptyList(),
            albumsLoaded = refreshing && it.albumsLoaded,
            albumsLoading = false, albumsError = null) }
        detailJob = scope.launch {
            val metadata = async { client.metadata(entity.id) }
            val sources = async { client.sources(entity.id, offset) }
            val m = metadata.await(); val s = sources.await()
            if (origin != generation || request != detailRequest || mutable.value.selected?.id != entity.id) return@launch
            mutable.update { it.copy(metadata = m.getOrNull() ?: it.metadata, sources = s.getOrNull() ?: it.sources, detailLoading = false,
                detailError = m.exceptionOrNull()?.message ?: s.exceptionOrNull()?.message) }
            if (refreshAlbums) loadArtistAlbums()
        }
    }

    fun closeDetail() {
        detailRequest++; detailJob?.cancel(); albumRequest++; albumJob?.cancel()
        val previous = if (history.isEmpty()) null else history.removeLast()
        mutable.update { if (previous == null) it.copy(selected = null, detailLoading = false, albumsLoading = false) else it.copy(selected = previous.selected,
            metadata = previous.metadata, sources = previous.sources, detailLoading = false, detailError = previous.detailError,
            artistAlbums = previous.artistAlbums, albums = previous.albums, albumsLoaded = previous.albumsLoaded,
            albumsLoading = false, albumsError = previous.albumsError) }
        if (previous?.selected != null && historyInvalidated) {
            historyInvalidated = false
            open(previous.selected, refresh = true)
        } else if (previous?.artistAlbums == true && !previous.albumsLoaded) loadArtistAlbums()
    }

    fun artistTab(albums: Boolean) {
        if (mutable.value.selected?.kind != "artist") return
        mutable.update { it.copy(artistAlbums = albums) }
        if (albums && !mutable.value.albumsLoaded && !mutable.value.albumsLoading) loadArtistAlbums()
    }

    /** Release IDs come from every source belonging to this exact artist, never a fuzzy title search. */
    fun loadArtistAlbums() {
        val selected = mutable.value.selected?.takeIf { it.kind == "artist" } ?: return
        val origin = generation; val request = ++albumRequest
        albumJob?.cancel()
        val keepAlbums = mutable.value.albumsLoaded
        mutable.update { it.copy(albumsLoading = true, albumsError = null) }
        albumJob = scope.launch {
            try {
                // Pacing and rejected-read retries belong to the connection's shared RPC budget.
                val ids = linkedSetOf<String>()
                var offset = 0
                var revision: Long? = null
                do {
                    val page = client.sources(selected.id, offset, revision).getOrThrow()
                    if (revision != null) check(page.revision == revision) { "电脑曲库已更新，请重新载入专辑" }
                    revision = page.revision
                    page.items.mapNotNullTo(ids) { it.releaseId }
                    offset = page.offset + page.items.size
                    check(page.items.isNotEmpty() || offset >= page.total) { "电脑专辑列表读取中断，请重试" }
                } while (offset < page.total)
                val albums = ArrayList<CatalogEntity>()
                for (id in ids) {
                    val detail = client.entity(id, revision!!).getOrThrow()
                    check(detail.revision == revision) { "电脑曲库已更新，请重新载入专辑" }
                    albums += detail.entity ?: error("电脑专辑已更新，请重试")
                    if (!keepAlbums && origin == generation && request == albumRequest && mutable.value.selected?.id == selected.id)
                        mutable.update { it.copy(albums = albums.sortedBy { album -> album.title }) }
                }
                if (origin == generation && request == albumRequest && mutable.value.selected?.id == selected.id)
                    mutable.update { it.copy(albums = albums.sortedBy { album -> album.title }, albumsLoaded = true, albumsLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (origin == generation && request == albumRequest && mutable.value.selected?.id == selected.id)
                    mutable.update { it.copy(albumsLoading = false, albumsError = e.message ?: "专辑未载入") }
            }
        }
    }

    fun sourcePage(offset: Int) {
        val selected = mutable.value.selected ?: return
        val page = mutable.value.sources ?: return
        val origin = generation
        val request = ++detailRequest
        detailJob?.cancel()
        mutable.update { it.copy(detailLoading = true, detailError = null) }
        detailJob = scope.launch {
            val result = client.sources(selected.id, offset, page.revision)
            if (origin != generation || request != detailRequest || mutable.value.selected?.id != selected.id) return@launch
            result.fold({ data -> mutable.update { it.copy(sources = data, detailLoading = false) } },
                { error -> mutable.update { it.copy(detailError = error.message, detailLoading = false) } })
        }
    }

    fun refreshArtwork() {
        artworkRequest++
        artworkJob?.cancel(); pendingArtwork.clear(); attemptedArtwork.clear()
        // Keep the last cover until its replacement arrives, including an explicit null (cleared cover).
        mutable.update { it.copy(artworkRevision = it.artworkRevision + 1) }
    }

    fun aliasArtwork(streamId: String, sessionId: String) {
        if (!sessionId.startsWith("remote-track:")) return
        artworkAliases[streamId] = sessionId
        while (artworkAliases.size > 256) artworkAliases.remove(artworkAliases.keys.first())
        if (sessionId in mutable.value.artwork) mutable.update {
            it.copy(artwork = it.artwork + (streamId to it.artwork[sessionId]))
        }
    }

    fun requestArtwork(requestedId: String) {
        val id = artworkAliases[requestedId] ?: requestedId
        if (!id.startsWith("remote-track:") || id in attemptedArtwork) return
        pendingArtwork += id
        if (artworkJob?.isActive == true) return
        val origin = generation
        val request = artworkRequest
        artworkJob = scope.launch {
            delay(80)
            while (pendingArtwork.isNotEmpty()) {
                val ids = pendingArtwork.take(8)
                pendingArtwork.removeAll(ids.toSet()); attemptedArtwork.addAll(ids)
                val result = fetchArtwork(ids)
                if (origin != generation || request != artworkRequest) return@launch
                result.onSuccess { page ->
                    mutable.update { current ->
                        val covers = page.items.associate { it.id to it.cover }
                        val aliases = artworkAliases.filterValues { it in covers }.mapValues { covers[it.value] }
                        current.copy(artwork = (current.artwork + covers + aliases)
                            .entries.toList().takeLast(256).associate { it.toPair() })
                    }
                    attemptedArtwork.retainAll(mutable.value.artwork.keys)
                }
                // Rate limits and disconnects are retried on the next presentation/reconnect refresh.
                if (result.isFailure) { pendingArtwork.clear(); break }
            }
        }
    }
}
