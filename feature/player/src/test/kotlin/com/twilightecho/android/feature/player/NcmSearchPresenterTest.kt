package com.twilightecho.android.feature.player

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NcmSearchPresenterTest {
    private val calls = mutableListOf<Pair<String, JsonArray>>()
    private fun json(value: String) = Json.parseToJsonElement(value)

    @Test fun collectionArtworkPrefersTheFullCoverAndRetainsSmallOnlyCompatibility() {
        val album = streamingCollection(json("""{"id":11,"name":"夜航","cover":"https://p1.music.126.net/full.jpg",
            "coverSmall":"https://p1.music.126.net/small.jpg"}""").jsonObject, "album")
        assertEquals("https://p1.music.126.net/full.jpg", album.cover)
        val artist = streamingCollection(json("""{"id":12,"name":"歌手","picUrlSmall":"https://p1.music.126.net/small.jpg"}""")
            .jsonObject, "artist")
        assertEquals("https://p1.music.126.net/small.jpg", artist.cover)
    }
    private fun songs(title: String = "夜航", total: Int = 65) = json("""{"items":[
        {"ncmSongId":42,"title":"$title","artist":"歌手","album":"专辑","duration":180},
        {"ncmSongId":42,"title":"$title","artist":"歌手"},
        {"ncmSongId":43,"title":"旧唱片","unavailableReason":"版权方暂停提供"}],"total":$total}""")
    private suspend fun respond(method: String, args: JsonArray): JsonElement {
        calls += method to args
        return when (method) {
            "searchSongs" -> songs(args[0].jsonPrimitive.content)
            "searchAlbums" -> json("""{"items":[{"id":11,"name":"夜航","artist":"歌手","coverSmall":"https://p1.music.126.net/album.jpg","trackCount":8}],"total":31}""")
            "searchArtists" -> json("""{"items":[{"id":12,"name":"歌手","picUrl":"https://p1.music.126.net/artist.jpg","musicSize":24,"albumSize":3}],"total":1}""")
            "searchPlaylists" -> json("""{"items":[{"id":13,"name":"夜里的歌","creatorName":"听众","cover":"https://p1.music.126.net/list.jpg","trackCount":40}],"total":1}""")
            else -> error("Unexpected method $method")
        }
    }

    @Test fun onlyAnEnabledAndVisibleSourceSearchesAndWhitespaceDoesNotRequest() = runTest {
        val presenter = NcmSearchPresenter(backgroundScope, ::respond)
        presenter.query("夜航"); runCurrent()
        presenter.availability(true); runCurrent()
        assertTrue(calls.isEmpty())
        presenter.active(true); runCurrent()
        assertEquals("夜航", calls.single().second[0].jsonPrimitive.content)
        presenter.query("  "); advanceTimeBy(400); runCurrent()
        assertEquals(1, calls.size)
        assertTrue(presenter.state.value.tracks.isEmpty())
        assertFalse(presenter.state.value.loading)
        presenter.availability(false); presenter.query("歌手"); advanceTimeBy(400); runCurrent()
        assertEquals(1, calls.size)
        presenter.availability(true); runCurrent()
        assertEquals("歌手", calls.last().second[0].jsonPrimitive.content)
    }

    @Test fun typingDebouncesToTheLatestQueryAndBlankClearsResults() = runTest {
        val presenter = NcmSearchPresenter(backgroundScope, ::respond)
        presenter.availability(true); presenter.active(true)
        presenter.query("夜"); advanceTimeBy(200)
        presenter.query("  夜航  "); advanceTimeBy(349); runCurrent()
        assertTrue(calls.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals("夜航", presenter.state.value.query)
        assertEquals("夜航", presenter.state.value.tracks.first().title)
        assertEquals(1, calls.size)
        presenter.query(""); runCurrent()
        assertTrue(presenter.state.value.tracks.isEmpty())
        assertEquals(0, presenter.state.value.total)
    }

    @Test fun allFourCategoriesUseSharedProviderPagingAndKeepArtworkAndMetadata() = runTest {
        val presenter = NcmSearchPresenter(backgroundScope, ::respond)
        presenter.availability(true); presenter.active(true); presenter.account("42")
        presenter.query("夜航"); presenter.search(); runCurrent()
        assertEquals(listOf("夜航", "30", "0"), calls.last().second.map { it.jsonPrimitive.content })
        assertTrue(presenter.state.value.hasNext)
        presenter.search(30); runCurrent()
        assertEquals(30, presenter.state.value.offset)
        assertEquals("30", calls.last().second[2].jsonPrimitive.content)
        presenter.category(NcmSearchCategory.ALBUMS); runCurrent()
        val album = presenter.state.value.collections.single()
        assertEquals("searchAlbums", calls.last().first)
        assertEquals("album", album.kind)
        assertEquals("https://p1.music.126.net/album.jpg", album.cover)
        assertEquals(8, album.count)
        assertEquals(0, presenter.state.value.offset)
        presenter.category(NcmSearchCategory.ARTISTS); runCurrent()
        assertEquals("24 首歌曲 · 3 张专辑", presenter.state.value.collections.single().subtitle)
        assertEquals("searchArtists", calls.last().first)
        presenter.category(NcmSearchCategory.PLAYLISTS); runCurrent()
        assertEquals("searchPlaylists", calls.last().first)
        assertEquals("听众", presenter.state.value.collections.single().subtitle)
        assertEquals("playlist", presenter.state.value.collections.single().kind)
        assertTrue(presenter.state.value.tracks.isEmpty())
    }

    @Test fun songsPreserveOrderRepeatedOccurrencesAndUnavailableTracks() = runTest {
        val presenter = NcmSearchPresenter(backgroundScope, ::respond)
        presenter.availability(true); presenter.active(true); presenter.query("夜航"); presenter.search(); runCurrent()
        val tracks = presenter.state.value.tracks
        assertEquals(listOf("42", "42", "43"), tracks.map { it.ncmSongId })
        assertNotEquals(tracks[0].queueEntryId, tracks[1].queueEntryId)
        assertEquals("版权方暂停提供", tracks[2].unavailableReason)
        assertEquals("provider://ncm/42", tracks[0].source)
    }

    @Test fun lateResponsesCannotReplaceANewerQueryCategoryOrDisabledSource() = runTest {
        val gate = CompletableDeferred<Unit>()
        val presenter = NcmSearchPresenter(backgroundScope) { method, args ->
            if (method == "searchSongs") withContext(NonCancellable) { gate.await() }
            respond(method, args)
        }
        presenter.availability(true); presenter.active(true); presenter.query("旧关键词"); presenter.search(); runCurrent()
        presenter.query("新关键词"); presenter.category(NcmSearchCategory.ALBUMS); runCurrent()
        assertEquals("album", presenter.state.value.collections.single().kind)
        gate.complete(Unit); runCurrent()
        assertTrue(presenter.state.value.tracks.isEmpty())
        assertEquals("新关键词", presenter.state.value.query)
        presenter.availability(false); runCurrent()
        assertTrue(presenter.state.value.collections.isEmpty())
    }

    @Test fun failedPagesClearEarlierSongsAndRetryTheRequestedOffset() = runTest {
        var fails = true
        val presenter = NcmSearchPresenter(backgroundScope) { method, args ->
            if (args[2].jsonPrimitive.int == 30 && fails) error("网络连接中断")
            respond(method, args)
        }
        presenter.availability(true); presenter.active(true); presenter.query("夜航"); presenter.search(); runCurrent()
        presenter.search(30); runCurrent()
        assertEquals("网络连接中断", presenter.state.value.error)
        assertTrue(presenter.state.value.tracks.isEmpty())
        assertEquals(30, presenter.state.value.offset)
        fails = false; presenter.search(presenter.state.value.offset); runCurrent()
        assertNull(presenter.state.value.error)
        assertEquals(30, calls.last().second[2].jsonPrimitive.int)
        presenter.search(60); runCurrent()
        assertFalse(presenter.state.value.hasNext)
    }

    @Test fun playlistSearchWaitsForLoginAndClearsOnLogoutOrAccountSwitch() = runTest {
        val presenter = NcmSearchPresenter(backgroundScope, ::respond)
        presenter.availability(true); presenter.active(true)
        presenter.category(NcmSearchCategory.PLAYLISTS); presenter.query("夜航"); presenter.search(); runCurrent()
        assertTrue(calls.isEmpty())
        presenter.account("42"); runCurrent()
        assertEquals("searchPlaylists", calls.single().first)
        presenter.account(null); runCurrent()
        assertTrue(presenter.state.value.collections.isEmpty())
        presenter.account("52"); runCurrent()
        assertEquals(2, calls.size)
    }

    @Test fun changingTheQueryInTheLibraryDoesNotRequestUntilNeteaseIsSelected() = runTest {
        val presenter = NcmSearchPresenter(backgroundScope, ::respond)
        presenter.availability(true); presenter.active(true); presenter.query("夜航"); presenter.search(); runCurrent()
        presenter.active(false); presenter.query("回声"); advanceTimeBy(400); runCurrent()
        assertEquals(1, calls.size)
        presenter.active(true); runCurrent()
        assertEquals("回声", calls.last().second[0].jsonPrimitive.content)
    }
}
