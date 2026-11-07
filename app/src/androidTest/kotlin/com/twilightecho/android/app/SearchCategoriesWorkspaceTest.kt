package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit catalog fixtures exercise the actual search surface without private accounts. */
@RunWith(AndroidJUnit4::class)
class SearchCategoriesWorkspaceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>
    private var played: CoreTrackView? = null
    private var localQueue: List<CoreTrackView> = emptyList()
    private val local = listOf(
        CoreTrackView("local:1", title = "晚风", artist = "北岸", album = "夜航", duration = 180.0, format = "FLAC"),
        CoreTrackView("local:2", title = "星河", artist = "北岸", album = "夜航", duration = 200.0),
        CoreTrackView("local:3", title = "白昼", artist = "北岸", album = "白昼", duration = 160.0)
    )
    private val song = CoreTrackView("provider://qqmusic/42", title = "暮色里的回声", artist = "北岸", album = "夜航",
        source = "provider://qqmusic/42", duration = 180.0, queueEntryId = "first")
    private val options = listOf(
        SearchProviderOption("plugin.qq", "qqmusic", "QQ 音乐", listOf("searchSongs", "searchArtists", "searchAlbums")),
        SearchProviderOption("plugin.kg", "kugou", "酷狗音乐", listOf("searchSongs", "searchArtists")),
        SearchProviderOption("plugin.qs", "qishui", "汽水音乐", listOf("searchSongs"))
    )

    private fun start(scale: Float = 1f) {
        ui = mutableStateOf(PlayerUiState(activeMainTab = MainDestinationTab.SEARCH, isLoadingLibrary = false,
            libraryTracks = local, allTracks = local, searchResults = local, favoriteTrackIds = listOf("local:1"),
            searchProviders = options))
        val actions = PlayerActions(
            selectTab = { ui.value = ui.value.copy(activeMainTab = it) },
            query = { query -> ui.value = ui.value.copy(searchQuery = query,
                librarySearchCategory = if (query.isBlank()) SearchCategory.SONGS else ui.value.librarySearchCategory,
                ncmSearch = if (query.isBlank()) NcmSearchState() else ui.value.ncmSearch,
                providerSearch = if (query.isBlank()) ProviderSearchState(provider = ui.value.providerSearch.provider) else ui.value.providerSearch,
                searchResults = local.filter {
                it.title.contains(query.trim(), true) || it.artist.contains(query.trim(), true) || it.album.contains(query.trim(), true)
            }) },
            selectFilter = { ui.value = ui.value.copy(activeSearchFilter = it) },
            librarySearchCategory = { ui.value = ui.value.copy(librarySearchCategory = it) },
            searchSource = { ui.value = ui.value.copy(searchSource = it) },
            searchProvider = { id -> ui.value = ui.value.copy(searchSource = SearchSource.PROVIDER,
                providerSearch = ProviderSearchState(provider = options.first { it.pluginId == id }, query = ui.value.searchQuery.trim(),
                    category = ui.value.providerSearch.category, tracks = listOf(song, song.copy(queueEntryId = "second")), searched = true, total = 2)) },
            providerSearchCategory = { category ->
                val collections = when (category) {
                    SearchCategory.SONGS -> emptyList()
                    SearchCategory.ARTISTS -> listOf(StreamingCollection("s1", "artist", "北岸", count = 3))
                    SearchCategory.ALBUMS -> listOf(StreamingCollection("a1", "album", "夜航", "北岸", count = 2))
                }
                ui.value = ui.value.copy(providerSearch = ui.value.providerSearch.copy(category = category,
                    tracks = if (category == SearchCategory.SONGS) listOf(song) else emptyList(), collections = collections, total = 1))
            },
            providerSearchPage = { offset -> ui.value = ui.value.copy(providerSearch = ui.value.providerSearch.copy(offset = offset,
                tracks = if (offset == 0) listOf(song) else emptyList(), nextOffset = if (offset == 0) 30 else null)) },
            playTracks = { tracks, id -> localQueue = tracks; played = tracks.first { it.id == id } },
            providerSearchActions = StreamingActions(
                collection = { item -> ui.value = ui.value.copy(providerSearch = ui.value.providerSearch.copy(detail =
                    StreamingDetail("v2-search:plugin.qq:${item.kind}:${item.id}", item.title, item.subtitle, collection = item,
                        tracks = listOf(song), loading = false, total = 1))) },
                back = { ui.value = ui.value.copy(providerSearch = ui.value.providerSearch.copy(detail = null)) },
                play = { _, track -> played = track }
            )
        )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                TwilightEchoTheme { PlayerAppContent(ui.value, actions) }
            }
        }
        rule.onNodeWithTag("searchInput").performImeAction()
    }

    @Test fun localArtistsAndAlbumsOpenTheirCompleteSongsAndReturnToSearch() {
        start()
        rule.onNodeWithTag("searchInput").performTextReplacement("北岸")
        rule.onNodeWithTag("searchInput").performImeAction()
        rule.onNodeWithTag("local-search-category-ARTISTS").performClick()
        rule.onNodeWithText("3 首歌曲 · 2 张专辑").assertIsDisplayed()
        screenshot("local-artists.png")
        rule.onNodeWithTag("local-search-artist-artist:北岸").performClick()
        rule.onNodeWithTag("collection").performScrollToNode(hasTestTag("track-local:2"))
        rule.onNodeWithTag("track-local:2").performClick()
        assertEquals(3, localQueue.size)
        rule.onNodeWithTag("collection").performScrollToIndex(0)
        rule.onNodeWithContentDescription("返回搜索结果").performClick()
        rule.onNodeWithTag("local-search-category-ARTISTS").assertIsSelected()
        rule.onNodeWithTag("searchInput").assertTextContains("北岸")
        rule.onNodeWithTag("local-search-category-ALBUMS").performClick()
        screenshot("local-albums.png")
        rule.onNodeWithTag("local-search-album-album:夜航").performClick()
        rule.onNodeWithTag("collection").performScrollToNode(hasTestTag("track-local:2"))
        rule.onNodeWithTag("track-local:2").performClick()
        assertEquals(listOf("local:1", "local:2"), localQueue.map { it.id })
        rule.onNodeWithTag("collection").performScrollToIndex(0)
        rule.onNodeWithContentDescription("返回搜索结果").performClick()
        rule.onNodeWithTag("local-search-category-ALBUMS").assertIsSelected()
    }

    @Test fun songFiltersRemainSongOnlyAndSurviveCategorySwitches() {
        start()
        searchFor("北岸")
        rule.onNodeWithText("收藏", useUnmergedTree = false).performClick()
        rule.onNodeWithTag("track-local:1").assertIsDisplayed()
        rule.onNodeWithTag("track-local:2").assertDoesNotExist()
        rule.onNodeWithTag("local-search-category-ARTISTS").performClick()
        rule.onNodeWithText("3 首歌曲 · 2 张专辑").assertIsDisplayed()
        rule.onNodeWithText("收藏").assertDoesNotExist()
        rule.onNodeWithTag("local-search-category-SONGS").performClick()
        rule.onNodeWithTag("track-local:2").assertDoesNotExist()
        screenshot("local-favorites.png")
    }

    @Test fun otherSourcesOfferAllCategoriesPlayTheChosenOccurrenceAndKeepResultsOnReturn() {
        start()
        rule.onNodeWithTag("searchInput").performTextReplacement("北岸")
        rule.onNodeWithTag("searchInput").performImeAction()
        rule.onNodeWithTag("search-source-plugin.qq").performScrollTo().performClick()
        rule.onNodeWithTag("provider-search-track-1").performClick()
        assertEquals("second", played?.queueEntryId)
        rule.onNodeWithTag("provider-search-category-ARTISTS").performClick()
        rule.onNodeWithTag("provider-search-artist-s1").performClick()
        rule.onNodeWithTag("streamingDetail").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回流媒体音乐").performClick()
        rule.onNodeWithTag("provider-search-artist-s1").assertIsDisplayed()
        screenshot("provider-artists.png")
        rule.onNodeWithTag("provider-search-category-ALBUMS").performClick()
        rule.onNodeWithTag("provider-search-album-a1").assertIsDisplayed()
        screenshot("provider-albums.png")
        rule.onNodeWithTag("search-source-LIBRARY").performScrollTo().performClick()
        rule.onNodeWithTag("local-search-category-SONGS").assertIsSelected()
    }

    @Test fun missingCategoryInterfacesAreExplicitAndLargeTextRemainsUsable() {
        start(scale = 1.3f)
        searchFor("北岸")
        rule.onNodeWithTag("search-source-plugin.qs").performScrollTo().performClick()
        rule.onNodeWithTag("provider-search-category-ALBUMS").performScrollTo().performClick()
        rule.onNodeWithText("暂不支持专辑搜索").assertIsDisplayed()
        rule.onNodeWithTag("provider-search-track-0").assertDoesNotExist()
        screenshot("provider-unsupported-large.png")
        rule.onNodeWithTag("search-source-LIBRARY").performScrollTo().performClick()
        rule.onNodeWithTag("local-search-category-ALBUMS").performScrollTo().performClick()
        rule.onNodeWithTag("local-search-album-album:夜航").assertIsDisplayed()
        screenshot("local-albums-large.png")
    }

    @Test fun anEmptyProviderPageStillAllowsReturningToThePreviousPage() {
        start()
        searchFor("北岸")
        rule.onNodeWithTag("search-source-plugin.qq").performScrollTo().performClick()
        rule.runOnIdle { ui.value = ui.value.copy(providerSearch = ui.value.providerSearch.copy(total = 65, nextOffset = 30)) }
        rule.onNodeWithTag("providerSearchResults").performScrollToNode(hasText("下一页"))
        rule.onNodeWithText("下一页").performClick()
        rule.onNodeWithText("没有找到歌曲").assertIsDisplayed()
        rule.onNodeWithText("第 2 页").assertIsDisplayed()
        rule.onNodeWithText("上一页").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(0, ui.value.providerSearch.offset) }
        rule.onNodeWithTag("provider-search-track-0").assertIsDisplayed()
    }

    @Test fun categoriesAreHiddenInitiallyOnEverySource() {
        start()
        rule.onNodeWithTag("local-search-category-SONGS").assertDoesNotExist()
        rule.onNodeWithTag("track-local:1").assertIsDisplayed()
        screenshot("before-search.png")
        rule.onNodeWithTag("search-source-NETEASE").performScrollTo().performClick()
        rule.onNodeWithTag("search-category-SONGS").assertDoesNotExist()
        rule.onNodeWithTag("search-source-plugin.qq").performScrollTo().performClick()
        rule.onNodeWithTag("provider-search-category-SONGS").assertDoesNotExist()
    }

    @Test fun categoriesWaitForAResponseStayDuringReloadAndHideWhenCleared() {
        start()
        val cases = listOf(SearchSource.LIBRARY to "local-search-category-SONGS",
            SearchSource.NETEASE to "search-category-SONGS", SearchSource.PROVIDER to "provider-search-category-SONGS")
        cases.forEach { (source, tag) ->
            rule.runOnIdle { ui.value = ui.value.copy(searchSource = source, searchQuery = "北岸", isSearching = true,
                ncmSearch = NcmSearchState(query = "北岸", loading = true),
                providerSearch = ProviderSearchState(provider = options.first(), query = "北岸", loading = true)) }
            rule.onNodeWithTag(tag).assertDoesNotExist()
            rule.runOnIdle { ui.value = ui.value.copy(isSearching = false,
                ncmSearch = ui.value.ncmSearch.copy(loading = false, searched = true),
                providerSearch = ui.value.providerSearch.copy(loading = false, searched = true)) }
            rule.onNodeWithTag(tag).assertIsDisplayed()
            // Empty online results are still a completed search; changing type must remain possible.
            rule.runOnIdle { ui.value = ui.value.copy(isSearching = true,
                ncmSearch = ui.value.ncmSearch.copy(loading = true, searched = false),
                providerSearch = ui.value.providerSearch.copy(loading = true, searched = false)) }
            rule.onNodeWithTag(tag).assertIsDisplayed()
            rule.onNodeWithTag("searchInput").performTextReplacement("")
            rule.onNodeWithTag(tag).assertDoesNotExist()
            rule.runOnIdle { ui.value = ui.value.copy(isSearching = false) }
        }
        screenshot("after-clear.png")
    }

    private fun searchFor(query: String) {
        rule.onNodeWithTag("searchInput").performTextReplacement(query)
        rule.onNodeWithTag("searchInput").performImeAction()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "search-categories-$name").outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
