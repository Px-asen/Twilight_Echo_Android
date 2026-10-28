package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import com.twilightecho.android.plugins.AndroidPluginRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NcmSearchWorkspaceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>
    private var selected: CoreTrackView? = null
    private var accountRequests = 0
    private var localPlays = 0
    private val track = CoreTrackView("provider://ncm/42", title = "暮色里的回声", artist = "测试歌手", album = "夜航",
        source = "provider://ncm/42", duration = 180.0, ncmSongId = "42", queueEntryId = "search-first")
    private val local = CoreTrackView("local:1", title = "手机里的歌", artist = "本地歌手")

    private fun start(ready: Boolean = true, scale: Float = 1f) {
        ui = mutableStateOf(PlayerUiState(activeMainTab = MainDestinationTab.SEARCH, isLoadingLibrary = false,
            searchSource = SearchSource.NETEASE, searchQuery = "暮色", searchResults = listOf(local),
            ncmSearch = NcmSearchState(query = "暮色", tracks = listOf(track, track.copy(queueEntryId = "search-second")), total = 65, searched = true),
            streaming = StreamingUiState(access = if (ready) StreamingAccess.READY else StreamingAccess.LOGGED_OUT)))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                TwilightEchoTheme { PlayerAppContent(ui.value, PlayerActions(
                    selectTab = { ui.value = ui.value.copy(activeMainTab = it) },
                    searchSource = { ui.value = ui.value.copy(searchSource = it) },
                    query = { ui.value = ui.value.copy(searchQuery = it) },
                    searchCategory = { category ->
                        val item = when (category) {
                            NcmSearchCategory.ALBUMS -> StreamingCollection("11", "album", "夜航", "测试歌手", count = 8)
                            NcmSearchCategory.ARTISTS -> StreamingCollection("12", "artist", "测试歌手", "3 张专辑")
                            else -> StreamingCollection("13", "playlist", "夜里的歌", "测试账号", count = 40)
                        }
                        ui.value = ui.value.copy(ncmSearch = ui.value.ncmSearch.copy(category = category,
                            tracks = if (category == NcmSearchCategory.SONGS) listOf(track) else emptyList(),
                            collections = if (category == NcmSearchCategory.SONGS) emptyList() else listOf(item), total = 1, offset = 0))
                    }, searchPage = { offset -> ui.value = ui.value.copy(ncmSearch = ui.value.ncmSearch.copy(offset = offset)) },
                    playTracks = { _, _ -> localPlays++ },
                    streaming = StreamingActions(account = { accountRequests++ },
                        play = { _, song -> if (ready) selected = song else accountRequests++ },
                        collection = { item -> ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail =
                            StreamingDetail("${item.kind}:${item.id}", item.title, item.subtitle, collection = item,
                                tracks = listOf(track), total = 1, loading = false))) },
                        back = { ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail = null)) },
                        root = { ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail = null)) })
                )) }
            }
        }
        rule.onNodeWithTag("searchInput").performImeAction()
    }

    @Test fun searchPlaysTheChosenOccurrencePagesAndKeepsLibrarySearch() {
        start()
        rule.onNodeWithTag("ncm-search-track-1").performClick()
        assertEquals("search-second", selected!!.queueEntryId)
        screenshot("ncm-search-songs-fixture.png")
        rule.onNodeWithTag("ncmSearchResults").performScrollToNode(hasText("下一页"))
        rule.onNodeWithText("下一页").performClick()
        rule.runOnIdle { assertEquals(30, ui.value.ncmSearch.offset) }
        rule.onNodeWithTag("search-source-LIBRARY").performClick()
        rule.onNodeWithTag("track-local:1").performClick()
        assertEquals(1, localPlays)
        rule.onNodeWithTag("search-source-NETEASE").performClick()
        rule.runOnIdle { assertEquals(30, ui.value.ncmSearch.offset) }
    }

    @Test fun allCollectionCategoriesOpenInSearchAndReturnToTheirResults() {
        start()
        listOf(NcmSearchCategory.ALBUMS to "album-11", NcmSearchCategory.ARTISTS to "artist-12",
            NcmSearchCategory.PLAYLISTS to "playlist-13").forEach { (category, tag) ->
            rule.onNodeWithTag("search-category-$category").performScrollTo().performClick()
            rule.onNodeWithTag("ncm-search-$tag").performClick()
            rule.onNodeWithTag("streamingDetail").assertIsDisplayed()
            rule.onNodeWithContentDescription("返回流媒体音乐").performClick()
            rule.onNodeWithTag("ncm-search-$tag").assertIsDisplayed()
        }
        screenshot("ncm-search-playlists-fixture.png")
    }

    @Test fun largeFontSearchHasDirectLoginForPlayAndPlaylists() {
        start(ready = false, scale = 1.3f)
        rule.onNodeWithTag("ncm-search-track-0").performClick()
        assertEquals(1, accountRequests)
        rule.onNodeWithTag("search-category-PLAYLISTS").performScrollTo().performClick()
        rule.onNodeWithText("登录网易云").performClick()
        assertEquals(2, accountRequests)
        screenshot("ncm-search-login-large-fixture.png")
    }

    @Test fun profileShowsCompleteSignatureAndCountsAtLargeFont() {
        val profile = NcmProfile("42", "把日子听成歌的人", signature =
            "把生活听成一首歌。\n收藏路上的风声，也收藏每一次与音乐相遇的心情。", follows = 18, followeds = 26)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                TwilightEchoTheme {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        NcmProfileCard(profile, Modifier.statusBarsPadding())
                    }
                }
            }
        }
        rule.onNodeWithText(profile.nickname).assertIsDisplayed()
        rule.onNodeWithText(profile.signature).assertIsDisplayed()
        rule.onNodeWithText("18 关注").assertIsDisplayed()
        rule.onNodeWithText("26 粉丝").assertIsDisplayed()
        screenshot("ncm-profile-large-fixture.png")
    }

    @Test fun profileAvatarLoadsARealCdnImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Public cover bytes stand in for a portrait; the account itself is an explicit UI fixture.
        val avatar = runBlocking {
            AndroidPluginRepository(context).use { repository ->
                repository.initialize()
                val entry = repository.state.value.entries.single { it.id == "com.twilightecho.provider.ncm" }
                if (entry.installedVersion != entry.version) repository.install(entry.id)
                repository.setEnabled(entry.id, true)
                repository.call(entry.id, "searchSongs", buildJsonArray { add("晴天"); add(1); add(0) })
                    .jsonObject.getValue("items").jsonArray.first().jsonObject.getValue("cover").jsonPrimitive.content
            }
        }
        rule.setContent {
            TwilightEchoTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    NcmProfileCard(NcmProfile("42", "头像显示测试", avatar, "把生活听成一首歌。", 18, 26),
                        Modifier.statusBarsPadding().padding(24.dp))
                }
            }
        }
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("ncmAvatarLoaded", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ncmAvatarLoaded", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("头像显示测试的头像").assertIsDisplayed()
        screenshot("ncm-profile-cdn-fixture.png")
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
