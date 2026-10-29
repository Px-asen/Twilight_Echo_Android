package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit account fixtures exercise the real app surface without account credentials or live private data. */
@RunWith(AndroidJUnit4::class)
class StreamingWorkspaceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>
    private var accountRequests = 0
    private var played: CoreTrackView? = null
    private val track = CoreTrackView("provider://ncm/42", title = "暮色里的回声", artist = "测试歌手", album = "夜航",
        source = "provider://ncm/42", duration = 180.0, ncmSongId = "42", queueEntryId = "first")
    private val owned = StreamingCollection("11", "playlist", "为夜晚留一首歌", "测试账号", count = 103, owned = true)
    private val album = StreamingCollection("21", "album", "夜航", "测试歌手", count = 8)
    private val artist = StreamingCollection("31", "artist", "测试歌手", "5 张专辑")

    private fun start(initial: PlayerUiState = PlayerUiState(), scale: Float = 1f) {
        val sections = StreamingRecommendation.entries.associateWith { StreamingSection(listOf(track), loaded = true) }
        ui = mutableStateOf(initial.copy(isLoadingLibrary = false, streaming = initial.streaming.copy(
            sections = sections, nickname = "测试账号", likedPlaylist = owned.copy(count = 206),
            playlists = listOf(owned, owned.copy(id = "12", owned = false)), libraryLoaded = true)))
        val actions = PlayerActions(
            selectTab = { ui.value = ui.value.copy(activeMainTab = it) },
            selectLibraryTab = { ui.value = ui.value.copy(activeLibraryTab = it) },
            streaming = StreamingActions(account = { accountRequests++ }, library = {
                ui.value = ui.value.copy(activeMainTab = MainDestinationTab.LIBRARY, activeLibraryTab = LibrarySectionTab.STREAMING)
            }, recommendation = { kind ->
                detail(StreamingDetail("rec:$kind", kind.title, "网易云音乐 · 界面测试", recommendation = kind,
                    tracks = listOf(track, track.copy(queueEntryId = "second")), total = 2, loading = false))
            }, group = { group ->
                val collections = when (group) {
                    StreamingLibraryGroup.CREATED -> listOf(owned)
                    StreamingLibraryGroup.PLAYLISTS -> listOf(owned.copy(id = "12", title = "收藏的风景", owned = false))
                    StreamingLibraryGroup.ALBUMS -> listOf(album)
                    StreamingLibraryGroup.ARTISTS -> listOf(artist)
                    else -> emptyList()
                }
                detail(StreamingDetail("group:$group", group.title, group = group, collections = collections,
                    tracks = if (collections.isEmpty()) listOf(track) else emptyList(), total = 1, loading = false))
            }, collection = { item ->
                detail(StreamingDetail("${item.kind}:${item.id}", item.title, item.subtitle, collection = item,
                    tracks = listOf(track, track.copy(queueEntryId = "second")), total = 103, nextOffset = 100, loading = false))
            }, back = { detail(null) }, play = { _, selected -> played = selected })
        )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                TwilightEchoTheme(darkTheme = false) { PlayerAppContent(ui.value, actions) }
            }
        }
    }
    private fun detail(value: StreamingDetail?) { ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail = value)) }

    @Test fun homeRecommendationsOpenAndPlayTheSelectedOccurrence() {
        start(PlayerUiState(streaming = StreamingUiState(access = StreamingAccess.READY)))
        rule.onNodeWithTag("streamingHome").assertIsDisplayed()
        screenshot("streaming-home-fixture.png")
        StreamingRecommendation.entries.forEach { kind ->
            rule.onNodeWithTag("home-recommendations").performScrollToNode(hasTestTag("streaming-rec-${kind.name}"))
            rule.onNodeWithTag("streaming-rec-${kind.name}").performClick()
            rule.onNodeWithTag("streamingDetail").assertIsDisplayed()
            rule.onNodeWithTag("streaming-track-1").performClick()
            assertEquals("second", played!!.queueEntryId)
            rule.onNodeWithContentDescription("返回流媒体音乐").performClick()
        }
        rule.onNodeWithTag("streaming-open-library").performClick()
        rule.onNodeWithTag("streamingLibrary").assertIsDisplayed()
        assertEquals(LibrarySource.Phone, ui.value.librarySource)
    }

    @Test fun streamingLibraryDoesNotEnterAnExistingDesktopDetailAndAllGroupsAreReachable() {
        val desktop = LibrarySource.Desktop("desktop-1", "library-1", "http://192.168.1.2:39001", "书房电脑")
        start(PlayerUiState(activeMainTab = MainDestinationTab.LIBRARY, activeLibraryTab = LibrarySectionTab.STREAMING,
            operatingMode = CoreOperatingMode.RemoteDesktopCore("http://192.168.1.2:39001", "书房电脑"),
            librarySource = desktop, desktopLibrary = DesktopBrowseState(selected = CatalogEntity("desktop-artist", "artist", "电脑歌手")),
            streaming = StreamingUiState(access = StreamingAccess.READY)))
        rule.onNodeWithTag("streamingLibrary").assertIsDisplayed()
        screenshot("streaming-library-fixture.png")
        StreamingLibraryGroup.entries.forEach { group ->
            rule.onNodeWithTag("streamingLibrary").performScrollToNode(hasTestTag("streaming-group-${group.name}"))
            // The floating dock can cover a row that was only scrolled to the viewport edge.
            rule.onNodeWithTag("streaming-group-${group.name}").performSemanticsAction(SemanticsActions.OnClick)
            rule.onNodeWithTag("streamingDetail").assertIsDisplayed()
            when (group) {
                StreamingLibraryGroup.CREATED -> {
                    rule.onNodeWithTag("streaming-collection-playlist-11").performClick()
                    rule.onNodeWithText("在此手机播放本页").assertIsDisplayed()
                    screenshot("streaming-playlist-fixture.png")
                }
                StreamingLibraryGroup.ALBUMS -> {
                    rule.onNodeWithTag("streaming-collection-album-21").performClick()
                    rule.onNodeWithTag("streamingDetailList").performScrollToNode(hasTestTag("streaming-track-0"))
                    rule.onNodeWithTag("streaming-track-0").assertIsDisplayed()
                }
                StreamingLibraryGroup.ARTISTS -> {
                    rule.onNodeWithTag("streaming-collection-artist-31").performClick()
                    rule.onNodeWithText("专辑", useUnmergedTree = true).assertIsDisplayed()
                }
                else -> Unit
            }
            rule.onNodeWithContentDescription("返回流媒体音乐").performClick()
            assertEquals(desktop, ui.value.librarySource)
            assertEquals("desktop-artist", ui.value.desktopLibrary.selected!!.id)
        }
        rule.onNodeWithText("歌曲", useUnmergedTree = true).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(LibrarySectionTab.SONGS, ui.value.activeLibraryTab) }
    }

    @Test fun loggedOutHomeAndLibraryHaveDirectAccountEntryAtLargeFont() {
        start(PlayerUiState(streaming = StreamingUiState(access = StreamingAccess.LOGGED_OUT)), scale = 1.3f)
        rule.onNodeWithTag("streaming-rec-DAILY").performScrollTo().performClick()
        assertEquals(1, accountRequests)
        rule.onNodeWithTag("streamingDetail").assertDoesNotExist()
        screenshot("streaming-login-home-large.png")
        rule.onNodeWithTag("streaming-open-library").performClick()
        rule.onNodeWithText("登录网易云音乐").performClick()
        assertEquals(2, accountRequests)
        assertNull(played)
        screenshot("streaming-login-library.png")
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
