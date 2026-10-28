package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistArtistPresenterTest {
    private val calls = mutableListOf<Pair<String, JsonArray>>()
    private fun song(index: Int) = buildJsonObject {
        put("ncmSongId", if (index == 204) 2 else index + 1)
        put("title", if (index == 150) "远处的晚风" else "歌曲 $index")
        put("artist", "北岸"); put("album", "夜航")
        if (index == 204) put("unavailableReason", "已下架")
    }
    private suspend fun respond(method: String, args: JsonArray): JsonElement {
        calls += method to args
        return when (method) {
            "checkLogin" -> Json.parseToJsonElement("""{"loggedIn":true,"profile":{"userId":42}}""")
            "fetchUserLibrary" -> Json.parseToJsonElement("""{"playlists":[]}""")
            "readPlaylistImportPage", "fetchLikedTracksPage" -> {
                val offset = args[if (method == "readPlaylistImportPage") 1 else 0].jsonPrimitive.int
                buildJsonObject {
                    put(if (method == "readPlaylistImportPage") "songs" else "tracks", JsonArray((offset until minOf(offset + 100, 205)).map(::song)))
                    put("total", 205); put("nextOffset", minOf(offset + 100, 205)); put("hasMore", offset + 100 < 205)
                }
            }
            "fetchArtistMetadata" -> Json.parseToJsonElement("""{"status":"ok","metadata":{"name":"北岸","texts":[{"field":"introduction","value":"来自流媒体的完整简介"}],"artwork":[{"resource":"https://p1.music.126.net/portrait.jpg"}]}}""")
            "fetchArtistTopSongs" -> JsonArray(listOf(song(1)))
            "fetchArtistAlbums" -> Json.parseToJsonElement("""[{"id":21,"name":"夜航"}]""")
            else -> JsonArray(emptyList())
        }
    }
    private val playlist = StreamingCollection("11", "playlist", "夜航歌单")

    @Test fun wholePlaylistSearchFindsLaterPagesAndReverseKeepsDuplicatesAndUnavailableItems() = runTest {
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.availability(true, true); runCurrent()
        presenter.openCollection(playlist); runCurrent()
        assertEquals(100, presenter.state.value.detail!!.tracks.size)
        presenter.playlistQuery("晚风 北岸"); runCurrent()
        var detail = presenter.state.value.detail!!
        assertEquals(205, detail.total); assertEquals(1, detail.matchingTotal)
        assertEquals("远处的晚风", detail.tracks.single().title)
        val reads = calls.size
        presenter.playlistQuery(""); presenter.playlistSort(CollectionSort(descending = true)); runCurrent()
        detail = presenter.state.value.detail!!
        assertEquals("已下架", detail.tracks.first().unavailableReason)
        assertEquals(205, detail.playlistTracks!!.size)
        assertEquals(2, detail.playlistTracks!!.count { it.ncmSongId == "2" })
        presenter.page(100); presenter.page(200); runCurrent()
        assertEquals(5, presenter.state.value.detail!!.tracks.size)
        assertEquals(reads, calls.size)
        presenter.playlistSort(CollectionSort()); runCurrent()
        assertEquals("1", presenter.state.value.detail!!.tracks.first().ncmSongId)
    }

    @Test fun likedSearchUsesTheEntireLikedListAndSortingIsScopedToAccountAndCollection() = runTest {
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.restoreSorts(mapOf("ncm:42:group:LIKED" to CollectionSort(descending = true),
            "ncm:other:playlist:11" to CollectionSort(descending = true)))
        presenter.availability(true, true); runCurrent()
        presenter.openGroup(StreamingLibraryGroup.LIKED); runCurrent()
        assertTrue(presenter.state.value.detail!!.sort.descending)
        assertEquals("已下架", presenter.state.value.detail!!.tracks.first().unavailableReason)
        presenter.playlistQuery("晚风"); runCurrent()
        assertEquals(1, presenter.state.value.detail!!.tracks.size)
        presenter.openCollection(playlist); runCurrent()
        assertEquals(CollectionSort(), presenter.state.value.detail!!.sort)
        presenter.closeDetail()
        assertEquals("晚风", presenter.state.value.detail!!.query)
    }

    @Test fun typingDuringWholePlaylistLoadingUsesTheLatestQueryWithoutRestartingTheRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "readPlaylistImportPage" && args[1].jsonPrimitive.int == 100) gate.await()
            respond(method, args)
        }
        presenter.availability(true, true); runCurrent(); presenter.openCollection(playlist); runCurrent()
        presenter.playlistQuery("晚"); runCurrent()
        assertTrue(presenter.state.value.detail!!.loadingPlaylist)
        presenter.playlistQuery("晚风"); presenter.playlistSort(CollectionSort(descending = true))
        gate.complete(Unit); runCurrent()
        assertEquals("晚风", presenter.state.value.detail!!.query)
        assertEquals(1, presenter.state.value.detail!!.matchingTotal)
        assertEquals(1, calls.count { it.first == "readPlaylistImportPage" && it.second[1].jsonPrimitive.int == 100 })
    }

    @Test fun failedWholePlaylistReadDoesNotExposePartialSearchResultsAndCanRetry() = runTest {
        var fail = true
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "readPlaylistImportPage" && args[1].jsonPrimitive.int == 100 && fail) error("网络中断")
            respond(method, args)
        }
        presenter.availability(true, true); runCurrent(); presenter.openCollection(playlist); runCurrent()
        presenter.playlistQuery("晚风"); runCurrent()
        assertEquals("网络中断", presenter.state.value.detail!!.error)
        assertTrue(presenter.state.value.detail!!.tracks.isEmpty())
        assertNull(presenter.state.value.detail!!.playlistTracks)
        fail = false; presenter.retryDetail(); runCurrent()
        assertEquals(1, presenter.state.value.detail!!.matchingTotal)
        assertNull(presenter.state.value.detail!!.error)
    }

    @Test fun lateWholePlaylistReadCannotReopenADisabledOrDifferentCollection() = runTest {
        val gate = CompletableDeferred<Unit>()
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "readPlaylistImportPage" && args[1].jsonPrimitive.int == 100) withContext(NonCancellable) { gate.await() }
            respond(method, args)
        }
        presenter.availability(true, true); runCurrent(); presenter.openCollection(playlist); runCurrent()
        presenter.playlistSort(CollectionSort(descending = true)); runCurrent()
        presenter.availability(true, false); gate.complete(Unit); runCurrent()
        assertEquals(StreamingAccess.DISABLED, presenter.state.value.access)
        assertNull(presenter.state.value.detail)
    }

    @Test fun artistProfileSurvivesContentTabsAndAlbumRoundTripWithoutRefetching() = runTest {
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.availability(true, true); runCurrent()
        presenter.openCollection(StreamingCollection("31", "artist", "北岸")); runCurrent()
        val profile = presenter.state.value.detail!!.artistProfile!!
        assertEquals("来自流媒体的完整简介", profile.introduction)
        assertEquals("https://p1.music.126.net/portrait.jpg", profile.portrait)
        presenter.artistTab(true); runCurrent()
        assertEquals(profile, presenter.state.value.detail!!.artistProfile)
        presenter.openCollection(presenter.state.value.detail!!.collections.single()); runCurrent()
        presenter.closeDetail()
        assertTrue(presenter.state.value.detail!!.artistAlbums)
        assertEquals(profile, presenter.state.value.detail!!.artistProfile)
        assertEquals(1, calls.count { it.first == "fetchArtistMetadata" })
        presenter.retryDetail(); runCurrent()
        assertEquals(2, calls.count { it.first == "fetchArtistMetadata" })
    }

    @Test fun profileFailureDoesNotBlockSongsAndRetryCannotOverwriteANewerArtist() = runTest {
        var fail = true
        val gate = CompletableDeferred<Unit>()
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "fetchArtistMetadata") {
                if (fail) error("资料暂不可用")
                if (args[0].jsonPrimitive.content == "31") withContext(NonCancellable) { gate.await() }
            }
            respond(method, args)
        }
        presenter.availability(true, true); runCurrent()
        presenter.openCollection(StreamingCollection("31", "artist", "北岸")); runCurrent()
        assertEquals(1, presenter.state.value.detail!!.tracks.size)
        assertNull(presenter.state.value.detail!!.error)
        assertEquals("资料暂不可用", presenter.state.value.detail!!.artistProfile!!.error)
        fail = false; presenter.retryArtist(); runCurrent()
        presenter.openCollection(StreamingCollection("32", "artist", "另一位歌手")); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("artist:32", presenter.state.value.detail!!.key)
        assertFalse(presenter.state.value.detail!!.artistProfile!!.loading)
    }
}
