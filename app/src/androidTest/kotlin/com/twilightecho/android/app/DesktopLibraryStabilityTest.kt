package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DesktopLibraryStabilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val artist = CatalogEntity("artist", "artist", "北岸")
    private fun state(detail: Boolean) = PlayerUiState(homeAvailable = false,
        activeMainTab = MainDestinationTab.LIBRARY, activeLibraryTab = LibrarySectionTab.ARTISTS,
        isLoadingLibrary = false, libraryDevice = "desktop",
        librarySource = LibrarySource.Desktop("desktop", "library", "https://desktop", "书房电脑"),
        desktopLibrary = DesktopBrowseState(page = CatalogPage(1, 1, items = listOf(artist)),
            selected = artist.takeIf { detail }, metadata = EntityMetadataDetail("artist", 1, artist.title,
                MetadataIntroduction("这段简介来自电脑已整理的音乐资料。"),
                facts = List(8) { MetadataFact("资料 $it", "已整理的歌手资料 $it") })))

    @Test fun refreshingTheCatalogDoesNotMoveItsRows() {
        val ui = mutableStateOf(state(false))
        rule.setContent { PlayerAppContent(ui.value, PlayerActions()) }
        val row = rule.onNodeWithTag("catalog-artist")
        val bounds = row.fetchSemanticsNode().boundsInRoot
        repeat(3) {
            rule.runOnIdle { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(loading = true)) }
            assertEquals(bounds, row.fetchSemanticsNode().boundsInRoot)
            rule.runOnIdle { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(loading = false)) }
            assertEquals(bounds, row.fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun sourceArrivalAndBackgroundRefreshKeepTheArtistScrollPosition() {
        val ui = mutableStateOf(state(true))
        rule.setContent { PlayerAppContent(ui.value, PlayerActions()) }
        rule.onNodeWithTag("desktopEntity").performScrollToIndex(3)
        rule.onNodeWithTag("desktopEntity").performScrollToNode(hasTestTag("artistSongsTab"))
        val tab = rule.onNodeWithTag("artistSongsTab")
        val before = tab.fetchSemanticsNode().boundsInRoot
        // The detail must actually be scrolled before the first source page arrives.
        assertTrue(rule.onNodeWithTag("desktopEntity").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        rule.runOnIdle { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(
            sources = CatalogSourcePage(1, 20, items = List(20) { CatalogSource("source-$it", "local", "recording-$it",
                availability = "indexed", track = CatalogSourceTrack("电脑歌曲 $it", "北岸")) }))) }
        assertEquals(before, tab.fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("desktopEntity").performScrollToNode(hasContentDescription("在此手机播放 电脑歌曲 0"))
        val source = rule.onNodeWithContentDescription("在此手机播放 电脑歌曲 0")
        val bounds = source.fetchSemanticsNode().boundsInRoot
        repeat(3) {
            rule.runOnIdle { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(detailLoading = true)) }
            assertEquals(bounds, source.fetchSemanticsNode().boundsInRoot)
            rule.runOnIdle { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(detailLoading = false)) }
            assertEquals(bounds, source.fetchSemanticsNode().boundsInRoot)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "desktop-artist-stable-refresh.png")
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
