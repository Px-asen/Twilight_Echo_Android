package com.twilightecho.android.feature.player

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import com.twilightecho.android.coreclient.CoreTrackView
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPresenterTest {
    private val json = Json
    private val calls = mutableListOf<Pair<String, JsonArray>>()
    private var user: String? = "42"
    private fun parse(value: String) = json.parseToJsonElement(value)
    private fun song(id: Int = 1) = """{"ncmSongId":$id,"title":"歌曲 $id","artist":"歌手","album":"专辑","cover":"https://p1.music.126.net/a.jpg","duration":180}"""
    private suspend fun respond(method: String, args: JsonArray): JsonElement {
        calls += method to args
        return when (method) {
            "checkLogin" -> user?.let { parse("""{"loggedIn":true,"profile":{"userId":$it,"nickname":"听众 $it"}}""") }
                ?: parse("""{"loggedIn":false}""")
            "fetchUserLibrary" -> parse("""{"likedPlaylist":{"id":10,"name":"我喜欢的音乐","trackCount":206},"playlists":[
                {"id":11,"name":"我的歌单","owned":true,"trackCount":103},
                {"id":12,"name":"收藏歌单","owned":false,"trackCount":2}]}""")
            "fetchSavedAlbums", "fetchArtistAlbums" -> parse("""[{"id":21,"name":"暮色专辑","artist":"歌手","coverSmall":"https://p1.music.126.net/album.jpg","trackCount":8}]""")
            "fetchSavedArtists" -> parse("""[{"id":31,"name":"收藏歌手","picUrlSmall":"https://p1.music.126.net/artist.jpg","albumSize":5}]""")
            "fetchArtistMetadata" -> parse("""{"status":"ok","metadata":{"name":"收藏歌手","texts":[{"field":"introduction","value":"真实歌手简介"}],"artwork":[{"resource":"https://p1.music.126.net/portrait.jpg"}]}}""")
            "fetchLikedTracksPage" -> {
                val offset = args[0].jsonPrimitive.int
                parse("""{"tracks":[${song(offset + 1)}],"total":206,"nextOffset":${offset + 100},"hasMore":${offset < 200}}""")
            }
            "readPlaylistImportPage" -> parse("""{"songs":[{"platformId":"9","title":"重复"},{"platformId":"9","title":"重复"},
                {"platformId":"8","unavailableReason":"已下架"}],"total":103,"nextOffset":100,"hasMore":true}""")
            "createPlaylist" -> parse("""{"id":99,"name":"${args[0].jsonPrimitive.content}","trackCount":0}""")
            else -> parse("[${song()}]")
        }
    }
    private fun ncmTrack(id: Int) = CoreTrackView("provider://ncm/$id", title = "歌曲 $id", source = "provider://ncm/$id",
        ncmSongId = "$id")

    @Test fun accountProfileKeepsAvatarSignatureAndCountsAndClearsOnLogout() = runTest {
        var signedIn = true
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "checkLogin") if (signedIn) parse("""{"loggedIn":true,"profile":{"userId":42,
                "nickname":"晚风","avatarUrl":"https://p1.music.126.net/avatar.jpg","signature":"把生活听成一首歌。",
                "follows":18,"followeds":26}}""") else parse("""{"loggedIn":false,"profile":null}""")
            else respond(method, args)
        }
        presenter.availability(true, true); runCurrent()
        val profile = presenter.state.value.profile!!
        assertEquals("https://p1.music.126.net/avatar.jpg", profile.avatarUrl)
        assertEquals("把生活听成一首歌。", profile.signature)
        assertEquals(18, profile.follows)
        assertEquals(26, profile.followeds)
        presenter.refreshAccount(); runCurrent()
        assertEquals(profile, presenter.state.value.profile)
        signedIn = false; presenter.refreshAccount(); runCurrent()
        assertNull(presenter.state.value.profile)
        assertEquals("", presenter.state.value.nickname)
    }

    @Test fun absentDisabledAndLoggedOutProvidersNeverReadPrivateContent() = runTest {
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.availability(false, false); runCurrent()
        assertEquals(StreamingAccess.NOT_INSTALLED, presenter.state.value.access)
        presenter.availability(true, false); runCurrent()
        assertEquals(StreamingAccess.DISABLED, presenter.state.value.access)
        assertTrue(calls.isEmpty())
        user = null
        presenter.availability(true, true); runCurrent()
        assertEquals(StreamingAccess.LOGGED_OUT, presenter.state.value.access)
        presenter.openGroup(StreamingLibraryGroup.LIKED); presenter.openRecommendation(StreamingRecommendation.FM); runCurrent()
        assertEquals(listOf("checkLogin"), calls.map { it.first })
        assertNull(presenter.state.value.detail)
    }

    @Test fun homeUsesThreeDesktopMethodsAndKeepsIndependentFailuresRetryable() = runTest {
        var fails = true
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "fetchRecommendSongs" && fails) error("每日推荐请求失败") else respond(method, args)
        }
        presenter.availability(true, true); runCurrent()
        assertEquals(StreamingAccess.READY, presenter.state.value.access)
        assertEquals("听众 42", presenter.state.value.nickname)
        assertEquals(StreamingRecommendation.entries.toSet(), presenter.state.value.sections.keys)
        assertNotNull(presenter.state.value.sections[StreamingRecommendation.DAILY]!!.error)
        assertTrue(presenter.state.value.sections[StreamingRecommendation.FM]!!.loaded)
        assertTrue(presenter.state.value.sections[StreamingRecommendation.RADAR]!!.loaded)
        StreamingRecommendation.entries.forEach { presenter.openRecommendation(it); runCurrent() }
        assertNotNull(presenter.state.value.sections[StreamingRecommendation.DAILY]!!.error)
        assertEquals(1, presenter.state.value.sections[StreamingRecommendation.FM]!!.tracks.size)
        assertEquals(1, presenter.state.value.sections[StreamingRecommendation.RADAR]!!.tracks.size)
        assertTrue(calls.any { it.first == "fetchPersonalFm" })
        assertTrue(calls.any { it.first == "fetchPrivateContent" })
        fails = false
        presenter.openRecommendation(StreamingRecommendation.DAILY); runCurrent()
        val detail = presenter.state.value.detail!!
        assertEquals("每日推荐", detail.title)
        assertEquals("provider://ncm/1", detail.tracks.single().source)
        assertEquals("https://p1.music.126.net/a.jpg", detail.tracks.single().cover)
        assertFalse(detail.loading)
        assertNull(detail.error)
    }

    @Test fun automaticHomeBatchesAreReusedUntilAnExplicitRefresh() = runTest {
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.availability(true, true); runCurrent()
        assertNull(presenter.state.value.detail)
        StreamingRecommendation.entries.forEach { kind ->
            assertEquals("https://p1.music.126.net/a.jpg", presenter.state.value.sections[kind]!!.tracks.single().cover)
            presenter.openRecommendation(kind); runCurrent()
            assertEquals(1, calls.count { it.first == kind.method })
        }
        presenter.refreshAccount(); runCurrent()
        StreamingRecommendation.entries.forEach { kind -> assertEquals(1, calls.count { it.first == kind.method }) }
        presenter.refresh(); runCurrent()
        StreamingRecommendation.entries.forEach { kind -> assertEquals(2, calls.count { it.first == kind.method }) }
    }

    @Test fun lateHomeWarmupCannotRepopulateADisabledAccount() = runTest {
        val gate = CompletableDeferred<Unit>()
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (StreamingRecommendation.entries.any { it.method == method }) withContext(NonCancellable) { gate.await() }
            respond(method, args)
        }
        presenter.availability(true, true); runCurrent()
        assertTrue(presenter.state.value.sections.values.all { it.loading })
        presenter.availability(true, false); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(StreamingAccess.DISABLED, presenter.state.value.access)
        assertTrue(presenter.state.value.sections.isEmpty())
    }

    @Test fun librarySeparatesOwnedAndCollectedAndOpensSavedAlbumsAndArtists() = runTest {
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.availability(true, true); runCurrent()
        assertEquals(206, presenter.state.value.likedPlaylist!!.count)
        presenter.openGroup(StreamingLibraryGroup.CREATED); runCurrent()
        assertEquals(listOf("11"), presenter.state.value.detail!!.collections.map { it.id })
        presenter.openGroup(StreamingLibraryGroup.PLAYLISTS); runCurrent()
        assertEquals(listOf("12"), presenter.state.value.detail!!.collections.map { it.id })
        presenter.openGroup(StreamingLibraryGroup.ALBUMS); runCurrent()
        val album = presenter.state.value.detail!!.collections.single()
        assertEquals("歌手", album.subtitle)
        assertEquals("https://p1.music.126.net/album.jpg", album.cover)
        presenter.openCollection(album); runCurrent()
        assertEquals("fetchAlbumTracks", calls.last().first)
        assertEquals("21", calls.last().second[0].jsonPrimitive.content)
        assertEquals(1, presenter.state.value.detail!!.tracks.size)
        presenter.closeDetail()
        assertEquals(StreamingLibraryGroup.ALBUMS, presenter.state.value.detail!!.group)
        presenter.openGroup(StreamingLibraryGroup.ARTISTS); runCurrent()
        val artist = presenter.state.value.detail!!.collections.single()
        assertEquals("https://p1.music.126.net/artist.jpg", artist.cover)
        presenter.openCollection(artist); runCurrent()
        assertTrue(calls.any { it.first == "fetchArtistTopSongs" })
        assertEquals("真实歌手简介", presenter.state.value.detail!!.artistProfile!!.introduction)
        presenter.artistTab(true); runCurrent()
        assertEquals("fetchArtistAlbums", calls.last().first)
        assertTrue(presenter.state.value.detail!!.tracks.isEmpty())
        presenter.artistTab(false); runCurrent()
        assertTrue(presenter.state.value.detail!!.collections.isEmpty())
        presenter.artistTab(true); runCurrent()
        presenter.openCollection(presenter.state.value.detail!!.collections.single()); runCurrent()
        presenter.closeDetail()
        assertTrue(presenter.state.value.detail!!.artistAlbums)
        presenter.closeDetail()
        assertEquals(StreamingLibraryGroup.ARTISTS, presenter.state.value.detail!!.group)
        presenter.closeDetail()
        assertNull(presenter.state.value.detail)
    }

    @Test fun authoritativePagingRetainsDuplicateOccurrencesAndUnavailableTracks() = runTest {
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.availability(true, true); runCurrent()
        presenter.openGroup(StreamingLibraryGroup.LIKED); runCurrent()
        assertEquals(206, presenter.state.value.detail!!.total)
        assertEquals(100, presenter.state.value.detail!!.nextOffset)
        presenter.page(100); runCurrent()
        assertEquals(100, calls.last().second[0].jsonPrimitive.int)
        assertEquals(100, calls.last().second[1].jsonPrimitive.int)
        assertEquals("101", presenter.state.value.detail!!.tracks.single().ncmSongId)
        presenter.openCollection(StreamingCollection("11", "playlist", "我的歌单")); runCurrent()
        val tracks = presenter.state.value.detail!!.tracks
        assertEquals(listOf("9", "9", "8"), tracks.map { it.ncmSongId })
        assertNotEquals(tracks[0].queueEntryId, tracks[1].queueEntryId)
        assertEquals("已下架", tracks[2].unavailableReason)
        presenter.page(100); runCurrent()
        assertEquals(100, calls.last().second[1].jsonPrimitive.int)
        assertEquals(100, calls.last().second[2].jsonPrimitive.int)
    }

    @Test fun latePrivateResponsesCannotCrossAccountOrDisableBoundaries() = runTest {
        val gate = CompletableDeferred<Unit>()
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "fetchSavedAlbums") withContext(NonCancellable) { gate.await() }
            respond(method, args)
        }
        presenter.availability(true, true); runCurrent()
        presenter.openGroup(StreamingLibraryGroup.ALBUMS); runCurrent()
        user = null
        presenter.refreshAccount(); runCurrent()
        assertEquals(StreamingAccess.LOGGED_OUT, presenter.state.value.access)
        assertTrue(presenter.state.value.playlists.isEmpty())
        assertTrue(presenter.state.value.sections.isEmpty())
        gate.complete(Unit); runCurrent()
        assertNull(presenter.state.value.detail)
        user = "52"
        presenter.refreshAccount(); runCurrent()
        assertEquals("52", presenter.state.value.accountId)
        presenter.availability(true, false); runCurrent()
        assertEquals(StreamingAccess.DISABLED, presenter.state.value.access)
        assertNull(presenter.state.value.likedPlaylist)
        assertTrue(presenter.state.value.sections.isEmpty())
    }

    @Test fun staleDetailAndRefreshCannotReplaceTheNewSelection() = runTest {
        val gate = CompletableDeferred<Unit>()
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "fetchSavedAlbums") withContext(NonCancellable) { gate.await() }
            respond(method, args)
        }
        presenter.availability(true, true); runCurrent()
        presenter.openGroup(StreamingLibraryGroup.ALBUMS); runCurrent()
        presenter.openGroup(StreamingLibraryGroup.RECENT); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(StreamingLibraryGroup.RECENT, presenter.state.value.detail!!.group)
        assertEquals(1, presenter.state.value.detail!!.tracks.size)
        presenter.openGroup(StreamingLibraryGroup.RANKING); runCurrent()
        assertEquals("fetchPlayRecords", calls.last().first)
        assertEquals(1, calls.last().second.single().jsonPrimitive.int)
        presenter.libraryRoot()
        assertNull(presenter.state.value.detail)
    }

    @Test fun aFailedPageCannotRelabelThePreviousPagesTracks() = runTest {
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "fetchLikedTracksPage" && args[0].jsonPrimitive.int > 0) error("分页请求失败")
            respond(method, args)
        }
        presenter.availability(true, true); runCurrent()
        presenter.openGroup(StreamingLibraryGroup.LIKED); runCurrent()
        assertEquals(1, presenter.state.value.detail!!.tracks.size)
        presenter.page(100); runCurrent()
        assertEquals(100, presenter.state.value.detail!!.offset)
        assertTrue(presenter.state.value.detail!!.tracks.isEmpty())
        assertNotNull(presenter.state.value.detail!!.error)
    }

    @Test fun cloudFavoritesUseTheSameProviderAndOnlyChangeAfterAConfirmedWrite() = runTest {
        var failWrite = false
        val presenter = StreamingPresenter(backgroundScope) { method, args -> when (method) {
            "isTrackLiked" -> JsonPrimitive(false)
            "likeTrack" -> { calls += method to args; if (failWrite) error("网易云拒绝收藏") else JsonNull }
            else -> respond(method, args)
        } }
        presenter.availability(true, true); runCurrent()
        presenter.observeTrack(CoreTrackView("provider://ncm/9", title = "网易云歌曲", source = "provider://ncm/9")); runCurrent()
        assertEquals(false, presenter.state.value.favoriteLiked)
        presenter.toggleFavorite(); runCurrent()
        assertEquals(true, presenter.state.value.favoriteLiked)
        val write = calls.single { it.first == "likeTrack" }
        assertEquals("9", write.second[0].jsonPrimitive.content)
        assertTrue(write.second[1].jsonPrimitive.boolean)
        assertTrue(calls.last { it.first == "fetchUserLibrary" }.second.single().jsonPrimitive.boolean)
        failWrite = true
        presenter.toggleFavorite(); runCurrent()
        assertEquals(true, presenter.state.value.favoriteLiked)
        assertEquals("网易云拒绝收藏", presenter.state.value.favoriteError)
        presenter.clearFavoriteError()
        presenter.observeTrack(CoreTrackView("content://phone/1", title = "本机歌曲", source = "content://phone/1")); runCurrent()
        assertNull(presenter.state.value.favoriteTrackId)
        assertNull(presenter.state.value.favoriteLiked)
    }

    @Test fun playlistWritesNeedASignedInAccount() = runTest {
        user = null
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.availability(true, true); runCurrent()
        calls.clear()
        val own = StreamingCollection("11", "playlist", "我的歌单", owned = true)
        assertEquals("请先登录网易云音乐", presenter.createPlaylist("夜里听").exceptionOrNull()?.message)
        assertTrue(presenter.addToPlaylist(own, listOf(ncmTrack(7))).isFailure)
        assertTrue(presenter.removeFromPlaylist(own, ncmTrack(7)).isFailure)
        assertTrue(presenter.deletePlaylist(own).isFailure)
        runCurrent()
        assertTrue(calls.isEmpty())
    }

    @Test fun createdPlaylistsAreOwnedAndReloadTheLibrary() = runTest {
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.availability(true, true); runCurrent()
        val created = presenter.createPlaylist("  夜里听  ").getOrThrow()
        assertEquals("夜里听", calls.last().second.single().jsonPrimitive.content)
        assertEquals("99", created.id)
        assertEquals("夜里听", created.title)
        assertEquals(true, created.owned)
        runCurrent()
        assertEquals("fetchUserLibrary", calls.last().first)
        assertTrue(calls.last().second.single().jsonPrimitive.boolean)
    }

    @Test fun addingSendsEachNcmSongOnceAndRefusesLocalOnlySongs() = runTest {
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.availability(true, true); runCurrent()
        val own = presenter.state.value.playlists.single { it.owned == true }
        val local = CoreTrackView("content://phone/1", title = "本机歌曲", source = "content://phone/1")
        assertTrue(presenter.addToPlaylist(own, listOf(ncmTrack(7), ncmTrack(7).copy(queueEntryId = "again"), local)).isSuccess)
        val write = calls.last { it.first == "addTracksToPlaylist" }.second
        assertEquals(11L, write[0].jsonPrimitive.long)
        assertEquals(listOf(7L), write[1].jsonArray.map { it.jsonPrimitive.long })
        runCurrent(); calls.clear()
        assertEquals("只能把网易云歌曲加入网易云歌单", presenter.addToPlaylist(own, listOf(local)).exceptionOrNull()?.message)
        runCurrent()
        assertTrue(calls.isEmpty())
    }

    @Test fun removingASongTakesItOutOfTheOpenPlaylistAtOnce() = runTest {
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "readPlaylistImportPage") parse("""{"songs":[${song(7)},${song(8)}],"total":2,"hasMore":false}""")
            else respond(method, args)
        }
        presenter.availability(true, true); runCurrent()
        val own = presenter.state.value.playlists.single { it.owned == true }
        presenter.openCollection(own); runCurrent()
        val first = presenter.state.value.detail!!.tracks.first()
        assertTrue(presenter.removeFromPlaylist(own, first).isSuccess)
        val write = calls.last { it.first == "removeTracksFromPlaylist" }.second
        assertEquals(11L, write[0].jsonPrimitive.long)
        assertEquals(listOf(7L), write[1].jsonArray.map { it.jsonPrimitive.long })
        assertEquals(listOf("8"), presenter.state.value.detail!!.tracks.map { it.ncmSongId })
        assertEquals(1, presenter.state.value.detail!!.total)
    }

    @Test fun deletingRereadsTheLibraryFirstAndClosesTheDeletedPlaylist() = runTest {
        val presenter = StreamingPresenter(backgroundScope, ::respond)
        presenter.availability(true, true); runCurrent()
        val collected = presenter.state.value.playlists.single { it.owned == false }
        presenter.openCollection(collected); runCurrent()
        calls.clear()
        assertTrue(presenter.deletePlaylist(collected).isSuccess)
        assertEquals(listOf("fetchUserLibrary", "deletePlaylist"), calls.map { it.first })
        assertEquals(12L, calls.last().second.single().jsonPrimitive.long)
        assertTrue(presenter.state.value.playlists.none { it.id == "12" })
        assertNull(presenter.state.value.detail)
    }

    @Test fun computerSongsFindTheirNcmCopyByIdOrBySearch() = runTest {
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method != "searchSongs") respond(method, args)
            else { calls += method to args; parse("""{"items":[
                {"ncmSongId":5,"title":"晚风","artist":"歌手","duration":200,"unavailableReason":"已下架"},
                {"ncmSongId":6,"title":"晚风 (Live)","artist":"歌手","duration":260},
                {"ncmSongId":7,"title":"晚风","artist":"歌手","duration":201}]}""") }
        }
        presenter.availability(true, true); runCurrent()
        calls.clear()
        assertEquals("provider://ncm/42", presenter.findMatch(CoreTrackView("desktop:1", title = "晚风", ncmSongId = "42"))?.source)
        assertTrue(calls.isEmpty())
        val found = presenter.findMatch(CoreTrackView("desktop:2", title = "晚风", artist = "歌手", duration = 200.0))
        assertEquals("7", found?.ncmSongId)
        assertEquals(listOf("晚风 歌手", "5", "0"), calls.single().second.map { it.jsonPrimitive.content })
    }

    @Test fun repeatedRecentTracksHaveDistinctQueueOccurrences() = runTest {
        val presenter = StreamingPresenter(backgroundScope) { method, args ->
            if (method == "fetchRecentSongs") parse("[${song()},${song()}]") else respond(method, args)
        }
        presenter.availability(true, true); runCurrent()
        presenter.openGroup(StreamingLibraryGroup.RECENT); runCurrent()
        val tracks = presenter.state.value.detail!!.tracks
        assertEquals(tracks[0].id, tracks[1].id)
        assertNotEquals(tracks[0].queueEntryId, tracks[1].queueEntryId)
    }
}
