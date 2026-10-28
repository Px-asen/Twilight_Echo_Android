package com.twilightecho.android.app

import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CollectionArtistUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val songs = listOf(
        CoreTrackView("1", title = "暮色", artist = "北岸", album = "夜航"),
        CoreTrackView("2", title = "Alpha", artist = "北岸", album = "夜航"),
        CoreTrackView("3", title = "远处的晚风", artist = "北岸", album = "日落"))

    @Test fun localPlaylistSearchSortPlaybackAndIndependentSavedOrder() {
        val ui = mutableStateOf(PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.LIBRARY,
            activeLibraryTab = LibrarySectionTab.PLAYLISTS, isLoadingLibrary = false,
            libraryTracks = songs, allTracks = songs, libraryTrackCount = songs.size,
            playlists = listOf(CorePlaylistView("p1", "夜航歌单", songs.map { it.id }), CorePlaylistView("p2", "白昼歌单", songs.map { it.id }))))
        var played = emptyList<String>()
        rule.setContent { PlayerAppContent(ui.value, PlayerActions(
            editPreferences = { edit -> ui.value = ui.value.copy(preferences = edit(ui.value.preferences)) },
            playTracks = { tracks, _ -> played = tracks.map { it.id } })) }
        rule.onNodeWithText("夜航歌单").performClick()
        rule.onNodeWithTag("playlistSort").performScrollTo().performClick()
        rule.onNodeWithTag("playlistDescending").assertIsDisplayed()
        screenshot("playlist-sort-menu.png")
        rule.onNodeWithTag("playlistDescending").performClick()
        rule.onNodeWithText("播放全部").performScrollTo().performClick()
        assertEquals(listOf("3", "2", "1"), played)
        rule.onNodeWithTag("playlistSearch").performScrollTo().performTextInput("Alpha")
        rule.onNodeWithTag("playlistSearch").performImeAction()
        rule.onNodeWithText("找到 1 首 · 共 3 首").assertExists()
        rule.onNodeWithText("播放全部").performScrollTo().performClick()
        assertEquals(listOf("2"), played)
        screenshot("playlist-search-result.png")
        rule.onNodeWithContentDescription("清除歌单搜索").performScrollTo().performClick()
        rule.onNodeWithText("播放全部").performScrollTo().performClick()
        assertEquals(listOf("3", "2", "1"), played)
        rule.onNodeWithContentDescription("返回音乐库").performScrollTo().performClick()
        rule.onNodeWithText("白昼歌单").performClick()
        rule.onNodeWithTag("playlistSort").performScrollTo().assertTextContains("正序")
        rule.onNodeWithContentDescription("返回音乐库").performScrollTo().performClick()
        rule.onNodeWithText("夜航歌单").performClick()
        rule.onNodeWithTag("playlistSort").performScrollTo().assertTextContains("倒序")
        rule.onNodeWithTag("playlistSearch").performTextInput("不存在")
        rule.onNodeWithTag("playlistSearch").performImeAction()
        rule.onNodeWithTag("collection").performScrollToNode(hasText("没有找到歌曲"))
        rule.onNodeWithText("没有找到歌曲").assertIsDisplayed()
    }

    @Test fun likedPlaylistUsesTheSameSearchAndSortControls() {
        val detail = StreamingDetail("group:LIKED", "我喜欢的音乐", group = StreamingLibraryGroup.LIKED,
            tracks = songs, total = songs.size, loading = false)
        val ui = mutableStateOf(PlayerUiState(activeMainTab = MainDestinationTab.LIBRARY,
            activeLibraryTab = LibrarySectionTab.STREAMING, streaming = StreamingUiState(access = StreamingAccess.READY, detail = detail)))
        var query = ""
        var sort = CollectionSort()
        fun update() {
            val matches = songs.filter { it.title.contains(query, true) }
            ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail = detail.copy(query = query, sort = sort,
                tracks = if (sort.descending) matches.reversed() else matches, matchingTotal = matches.size)))
        }
        rule.setContent { PlayerAppContent(ui.value, PlayerActions(streaming = StreamingActions(
            playlistQuery = { query = it; update() }, playlistSort = { sort = it; update() }))) }
        rule.onNodeWithTag("playlistSort").performScrollTo().performClick()
        rule.onNodeWithTag("playlistDescending").performClick()
        assertTrue(sort.descending)
        rule.onNodeWithTag("playlistSearch").performTextInput("晚风")
        rule.onNodeWithTag("playlistSearch").performImeAction()
        assertEquals("晚风", query)
        rule.onNodeWithTag("streamingDetailList").performScrollToNode(hasTestTag("streaming-track-0"))
        rule.onNodeWithText("远处的晚风").assertIsDisplayed()
        screenshot("liked-search-result.png")
    }

    @Test fun longArtistNameAndMissingPortraitRemainUsableAtLargeFont() {
        val artist = StreamingCollection("large", "artist", "一位名字很长的独立音乐人与他的乐队")
        val detail = StreamingDetail("artist:large", artist.title, collection = artist,
            artistProfile = StreamingArtistProfile(loading = false), tracks = songs, total = songs.size, loading = false)
        val ui = mutableStateOf(PlayerUiState(activeMainTab = MainDestinationTab.LIBRARY,
            activeLibraryTab = LibrarySectionTab.STREAMING, streaming = StreamingUiState(access = StreamingAccess.READY, detail = detail)))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                PlayerAppContent(ui.value, PlayerActions(streaming = StreamingActions(artistTab = { albums ->
                    ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail = detail.copy(artistAlbums = albums,
                        tracks = if (albums) emptyList() else songs)))
                })))
            }
        }
        rule.onNodeWithText("暂无歌手简介").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("artistAlbumsTab").performScrollTo().performClick()
        rule.onNodeWithText("暂无专辑").performScrollTo().assertIsDisplayed()
        screenshot("artist-large-font-no-portrait-fixture.png")
        rule.onNodeWithTag("artistSongsTab").performScrollTo().performClick()
        rule.onNodeWithTag("streaming-track-0").performScrollTo().assertIsDisplayed()
    }

    @Test fun desktopPortraitBiographyAndAlbumNavigationKeepCoreMetadata() {
        val portrait = InstrumentationRegistry.getInstrumentation().context.assets.open("music/01.jpg").use {
            "data:image/jpeg;base64," + Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
        }
        val artist = CatalogEntity("artist-1", "artist", "北岸")
        val album = CatalogEntity("release-1", "release", "夜航", "北岸")
        val biography = (1..8).joinToString("\n\n") { "北岸的音乐从城市与海之间出发，以克制的旋律记录日常的细小回声。这是电脑 Core 提供的第 $it 段完整简介。" }
        val desktop = DesktopBrowseState(selected = artist, metadata = EntityMetadataDetail(artist.id, 9, "北岸",
            MetadataIntroduction(biography, origin = "manual"), manualArtwork = PersonalArtworkPreview(portrait)),
            sources = CatalogSourcePage(9, 1, items = listOf(CatalogSource("source-1", "local", "recording-1", "release-1",
                availability = "indexed", track = CatalogSourceTrack("暮色", "北岸", "夜航")))),
            albums = listOf(album), albumsLoaded = true)
        val ui = mutableStateOf(PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.LIBRARY,
            activeLibraryTab = LibrarySectionTab.ARTISTS, libraryDevice = "computer", isLoadingLibrary = false,
            librarySource = LibrarySource.Desktop("computer", "library", "http://127.0.0.1", "电脑"), desktopLibrary = desktop))
        var played = false
        rule.setContent { PlayerAppContent(ui.value, PlayerActions(
            artistAlbums = { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(artistAlbums = it)) },
            playSource = { source, revision -> assertEquals("source-1", source.id); assertEquals(9L, revision); played = true },
            openEntity = { ui.value = ui.value.copy(desktopLibrary = desktop.copy(selected = it,
                metadata = EntityMetadataDetail(it.id, 9, it.title), artistAlbums = false)) },
            closeEntity = { ui.value = ui.value.copy(desktopLibrary = desktop.copy(artistAlbums = true)) })) }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("desktopMetadataArtwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("desktop-artist-hero-fixture.png")
        rule.onNodeWithTag("artistBiographyToggle").performScrollTo().performClick()
        rule.onNodeWithText("收起").performScrollTo().assertIsDisplayed()
        screenshot("desktop-artist-expanded-fixture.png")
        rule.onNodeWithTag("artistBiographyToggle").performClick()
        rule.onNodeWithContentDescription("在此手机播放 暮色").performScrollTo().performClick()
        assertTrue(played)
        rule.onNodeWithTag("artistAlbumsTab").performScrollTo().performClick()
        rule.onNodeWithTag("catalog-release-1").performScrollTo().performClick()
        rule.onNodeWithText("夜航", substring = false).assertExists()
        rule.onNodeWithContentDescription("返回电脑曲库").performScrollTo().performClick()
        assertTrue(ui.value.desktopLibrary.artistAlbums)
        rule.onNodeWithTag("artistAlbumsTab").performScrollTo().assertIsSelected()
        screenshot("desktop-artist-albums-fixture.png")
    }

    /** Public metadata and portrait go through the actual packaged provider and CDN image loader. */
    @Test fun liveStreamingArtistHasPortraitFullBiographyAndContentTabs() {
        val repository = AndroidPlaybackHost.getInstance(context).plugins
        val raw = runBlocking {
            repository.initialize()
            val entry = repository.state.value.entries.single { it.id == "com.twilightecho.provider.ncm" }
            if (entry.installedVersion != entry.version) repository.install(entry.id)
            repository.setEnabled(entry.id, true)
            repository.call(entry.id, "fetchArtistMetadata", buildJsonArray { add("6452") })
        }.jsonObject
        assertEquals("ok", raw["status"]!!.jsonPrimitive.content)
        val metadata = raw.getValue("metadata").jsonObject
        val intro = metadata.getValue("texts").jsonArray.first().jsonObject.getValue("value").jsonPrimitive.content
        val portrait = metadata.getValue("artwork").jsonArray.first().jsonObject.getValue("resource").jsonPrimitive.content
        val name = metadata.getValue("name").jsonPrimitive.content
        assertTrue(intro.length > 100)
        File(context.getExternalFilesDir(null), "artist-public-metadata.json").writeText(raw.toString())
        val artist = StreamingCollection("6452", "artist", name, "网易云音乐", portrait)
        val profile = StreamingArtistProfile(name, intro, portrait, loading = false)
        val detail = StreamingDetail("artist:6452", name, artist.subtitle, collection = artist,
            artistProfile = profile, tracks = songs, total = songs.size, loading = false)
        val ui = mutableStateOf(PlayerUiState(activeMainTab = MainDestinationTab.LIBRARY,
            activeLibraryTab = LibrarySectionTab.STREAMING, streaming = StreamingUiState(access = StreamingAccess.READY, detail = detail)))
        rule.setContent { PlayerAppContent(ui.value, PlayerActions(streaming = StreamingActions(artistTab = { albums ->
            ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail = detail.copy(artistAlbums = albums,
                tracks = if (albums) emptyList() else songs,
                collections = if (albums) listOf(StreamingCollection("21", "album", "界面测试专辑", name)) else emptyList())))
        }))) }
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("artistPortrait", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("streaming-artist-live-portrait.png")
        rule.onNodeWithTag("artistBiographyToggle").performScrollTo().performClick()
        rule.onNodeWithText("收起").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("artistBiographyToggle").performClick()
        rule.onNodeWithTag("artistAlbumsTab").performScrollTo().performClick()
        rule.onNodeWithTag("streaming-collection-album-21").performScrollTo().assertIsDisplayed()
        screenshot("streaming-artist-albums.png")
        rule.onNodeWithTag("artistSongsTab").performScrollTo().performClick()
        rule.onNodeWithTag("streaming-track-0").performScrollTo().assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "collection-artist-qa").apply { mkdirs() }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle()
        check(device.takeScreenshot(File(directory, name)))
    }
}
