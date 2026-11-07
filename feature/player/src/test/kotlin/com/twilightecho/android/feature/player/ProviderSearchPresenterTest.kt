package com.twilightecho.android.feature.player

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSearchPresenterTest {
    private val provider = SearchProviderOption("plugin.qq", "qqmusic", "QQ 音乐", listOf("searchSongs", "searchArtists", "searchAlbums",
        "fetchArtistTopSongs", "fetchArtistAlbums", "fetchAlbumTracks"))
    private val calls = mutableListOf<Triple<String, String, JsonArray>>()
    private fun json(value: String) = Json.parseToJsonElement(value)

    @Test fun clearingTheQueryRestoresSongsForTheNextSearch() = runTest {
        val presenter = ProviderSearchPresenter(backgroundScope, ::respond)
        presenter.select(provider); presenter.query("夜航"); presenter.category(SearchCategory.ALBUMS); runCurrent()
        presenter.query(""); runCurrent()
        assertEquals(SearchCategory.SONGS, presenter.state.value.category)
        assertFalse(presenter.state.value.searched)
        assertTrue(presenter.state.value.collections.isEmpty())
    }
    private suspend fun respond(id: String, method: String, args: JsonArray): JsonElement {
        calls += Triple(id, method, args)
        return when (method) {
            "searchSongs" -> json("""{"items":[{"id":"42","title":"${args[0].jsonPrimitive.content}"},{"id":"42","title":"重复条目"}],"total":65}""")
            "searchArtists" -> json("""{"items":[{"id":"s1","name":"北岸"}],"total":1}""")
            "searchAlbums", "fetchArtistAlbums" -> json("""{"items":[{"id":"a1","name":"夜航","artist":"北岸"}],"total":1}""")
            "fetchArtistTopSongs", "fetchAlbumTracks" -> json("""[{"id":"99","title":"晚风"}]""")
            else -> error(method)
        }
    }

    @Test fun categoriesDispatchTheirOwnMethodsAndResetPaging() = runTest {
        val presenter = ProviderSearchPresenter(backgroundScope, ::respond)
        presenter.query("夜航"); runCurrent(); assertTrue(calls.isEmpty())
        presenter.select(provider); runCurrent()
        assertEquals(Triple("plugin.qq", "searchSongs", buildJsonArray { add("夜航"); add(30); add(0) }), calls.last())
        presenter.search(30); runCurrent(); assertEquals(30, presenter.state.value.offset)
        presenter.category(SearchCategory.ARTISTS); runCurrent()
        assertEquals("searchArtists", calls.last().second); assertEquals(0, presenter.state.value.offset)
        assertEquals("artist", presenter.state.value.collections.single().kind)
        presenter.category(SearchCategory.ALBUMS); runCurrent()
        assertEquals("searchAlbums", calls.last().second); assertEquals("album", presenter.state.value.collections.single().kind)
    }

    @Test fun typingDebouncesAndHiddenSourcesDoNotSearchUntilReselected() = runTest {
        val presenter = ProviderSearchPresenter(backgroundScope, ::respond)
        presenter.select(provider); presenter.query("夜"); advanceTimeBy(200)
        presenter.query("夜航"); advanceTimeBy(350); runCurrent()
        assertEquals(1, calls.size); assertEquals("夜航", presenter.state.value.tracks.first().title)
        presenter.active(false); presenter.query("晚风"); advanceTimeBy(500); runCurrent()
        assertEquals(1, calls.size)
        presenter.select(provider); runCurrent(); assertEquals("晚风", presenter.state.value.tracks.first().title)
        presenter.query(" "); runCurrent(); assertTrue(presenter.state.value.tracks.isEmpty())
    }

    @Test fun missingMethodsNeverFallBackToSongSearch() = runTest {
        val presenter = ProviderSearchPresenter(backgroundScope, ::respond)
        presenter.select(provider.copy(methods = listOf("searchSongs"))); presenter.query("夜航"); presenter.search(); runCurrent()
        presenter.category(SearchCategory.ALBUMS); runCurrent()
        assertFalse(presenter.state.value.supported); assertTrue(presenter.state.value.tracks.isEmpty())
        assertEquals(1, calls.size)
    }

    @Test fun lateResponsesCannotCrossSourceCategoryOrAvailabilityBoundaries() = runTest {
        val gate = CompletableDeferred<Unit>()
        val presenter = ProviderSearchPresenter(backgroundScope) { id, method, args ->
            if (id == provider.pluginId && method == "searchSongs") withContext(NonCancellable) { gate.await() }
            respond(id, method, args)
        }
        presenter.select(provider); presenter.query("旧关键词"); presenter.search(); runCurrent()
        presenter.select(provider.copy(pluginId = "plugin.kg", providerId = "kugou")); runCurrent()
        assertTrue(presenter.state.value.tracks.first().id.startsWith("provider://kugou/"))
        presenter.category(SearchCategory.ARTISTS); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("plugin.kg", presenter.state.value.provider?.pluginId)
        assertTrue(presenter.state.value.tracks.isEmpty()); assertEquals("artist", presenter.state.value.collections.single().kind)
        presenter.retain(emptyList()); runCurrent()
        assertNull(presenter.state.value.provider); assertTrue(presenter.state.value.collections.isEmpty())
    }

    @Test fun retryKeepsTheFailedOffsetAndDoesNotShowStaleResults() = runTest {
        var fail = true
        val presenter = ProviderSearchPresenter(backgroundScope) { id, method, args ->
            if (args[2].jsonPrimitive.int == 30 && fail) error("网络中断")
            respond(id, method, args)
        }
        presenter.select(provider); presenter.query("夜航"); presenter.search(); runCurrent()
        presenter.search(30); runCurrent()
        assertEquals("网络中断", presenter.state.value.error); assertTrue(presenter.state.value.tracks.isEmpty())
        fail = false; presenter.search(presenter.state.value.offset); runCurrent()
        assertNull(presenter.state.value.error); assertEquals(30, presenter.state.value.offset)
        assertNotEquals(presenter.state.value.tracks[0].queueEntryId, presenter.state.value.tracks[1].queueEntryId)
    }

    @Test fun artistAndAlbumDetailsReturnToTheSameSearchPage() = runTest {
        val presenter = ProviderSearchPresenter(backgroundScope, ::respond)
        presenter.select(provider); presenter.query("北岸"); presenter.category(SearchCategory.ARTISTS); runCurrent()
        val search = presenter.state.value
        presenter.openCollection(search.collections.single()); runCurrent()
        assertEquals("fetchArtistTopSongs", calls.last().second)
        assertEquals("晚风", presenter.state.value.detail?.tracks?.single()?.title)
        presenter.artistTab(true); runCurrent()
        assertEquals("fetchArtistAlbums", calls.last().second)
        presenter.openCollection(presenter.state.value.detail!!.collections.single()); runCurrent()
        assertEquals("fetchAlbumTracks", calls.last().second)
        presenter.back(); assertTrue(presenter.state.value.detail!!.artistAlbums)
        presenter.back(); assertNull(presenter.state.value.detail)
        assertEquals(search.collections, presenter.state.value.collections)
        assertEquals(search.offset, presenter.state.value.offset)
    }

    @Test fun closingADetailRejectsItsLateResponseAndAccountRefreshClearsTheTrail() = runTest {
        val gate = CompletableDeferred<Unit>()
        val presenter = ProviderSearchPresenter(backgroundScope) { id, method, args ->
            if (method == "fetchAlbumTracks") withContext(NonCancellable) { gate.await() }
            respond(id, method, args)
        }
        presenter.select(provider); presenter.query("夜航"); presenter.category(SearchCategory.ALBUMS); runCurrent()
        presenter.openCollection(presenter.state.value.collections.single()); runCurrent()
        presenter.back(); gate.complete(Unit); runCurrent(); assertNull(presenter.state.value.detail)
        presenter.refresh(); runCurrent()
        assertNull(presenter.state.value.detail); assertEquals("searchAlbums", calls.last().second)
    }

    @Test fun failedArtistAlbumTabsNeverDisplayThePreviousSongList() = runTest {
        val presenter = ProviderSearchPresenter(backgroundScope) { id, method, args ->
            if (method == "fetchArtistAlbums") error("专辑暂不可用")
            respond(id, method, args)
        }
        presenter.select(provider); presenter.query("北岸"); presenter.category(SearchCategory.ARTISTS); runCurrent()
        presenter.openCollection(presenter.state.value.collections.single()); runCurrent()
        assertTrue(presenter.state.value.detail!!.tracks.isNotEmpty())
        presenter.artistTab(true); runCurrent()
        assertEquals("专辑暂不可用", presenter.state.value.detail!!.error)
        assertTrue(presenter.state.value.detail!!.tracks.isEmpty())
        assertTrue(presenter.state.value.detail!!.collections.isEmpty())
    }
}
