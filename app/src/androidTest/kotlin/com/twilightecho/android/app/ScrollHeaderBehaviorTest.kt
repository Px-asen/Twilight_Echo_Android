package com.twilightecho.android.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises real nested drag/fling input, without changing the saved library or playback session. */
@RunWith(AndroidJUnit4::class)
class ScrollHeaderBehaviorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>

    private fun library(tab: LibrarySectionTab = LibrarySectionTab.SONGS, connected: Boolean = true,
        catalog: Boolean = false): PlayerUiState {
        val tracks = List(180) { CoreTrackView("scroll-$it", title = "暮色旋律 ${it + 1}", artist = "北岸",
            album = "夜色唱片", duration = 180.0) }
        val kind = if (tab == LibrarySectionTab.ARTISTS) "artist" else "album"
        return PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.LIBRARY,
            activeLibraryTab = tab, isLoadingLibrary = false, currentTrack = tracks.first(),
            libraryTracks = tracks, allTracks = tracks, libraryTrackCount = tracks.size,
            libraryDevice = if (catalog) "desktop" else LIBRARY_ALL,
            librarySource = if (connected) LibrarySource.Desktop("desktop", "library", "https://desktop", "书房电脑") else LibrarySource.Phone,
            libraryDevices = if (connected) listOf(LibraryDeviceOption(LIBRARY_ALL, "全部设备"),
                LibraryDeviceOption(LIBRARY_PHONE, "此手机"), LibraryDeviceOption("desktop", "书房电脑")) else emptyList(),
            albums = List(80) { CoreAlbumCollectionView("album-$it", "夜色唱片 ${it + 1}", "北岸", tracks.size,
                32400.0, tracks.map { track -> track.id }) },
            artists = List(80) { CoreArtistCollectionView("artist-$it", "北岸 ${it + 1}", 1, tracks.size, tracks.map { track -> track.id }) },
            desktopLibrary = DesktopBrowseState(kind = kind, page = CatalogPage(1, 200,
                items = List(100) { CatalogEntity("entity-$it", kind, "电脑音乐 ${it + 1}", "北岸") })))
    }

    private fun start(initial: PlayerUiState): StateRestorationTester {
        ui = mutableStateOf(initial)
        val restoration = StateRestorationTester(rule)
        restoration.setContent { PlayerAppContent(ui.value, PlayerActions(
            selectTab = { ui.value = ui.value.copy(activeMainTab = it) },
            selectLibraryTab = { ui.value = ui.value.copy(activeLibraryTab = it) },
            selectLibraryDevice = { ui.value = ui.value.copy(libraryDevice = it) },
            openEntity = { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(selected = it)) },
            closeEntity = { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(selected = null)) })) }
        rule.waitForIdle()
        return restoration
    }

    private fun scrollValue(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun navigationHeight() = rule.onNodeWithTag("libraryNavigation").getUnclippedBoundsInRoot()
        .let { it.bottom - it.top }

    private fun collapse(tag: String) {
        repeat(2) { rule.onNodeWithTag(tag).performTouchInput { swipeUp(durationMillis = 600) } }
        rule.onNodeWithTag("libraryNavigation").assertIsNotDisplayed()
        assertTrue("The content must be away from its top", scrollValue(tag) > 0f)
    }

    private fun reverseBeforeReleasing(tag: String, connected: Boolean) {
        rule.onNodeWithTag(tag).performTouchInput {
            down(center)
            moveBy(Offset(0f, 48.dp.toPx()), delayMillis = 160)
        }
        // Check while the finger is still down: no release, fling or return to item zero is required.
        assertTrue(navigationHeight() > 0.dp)
        assertTrue(scrollValue(tag) > 0f)
        if (connected) rule.onNodeWithTag("playbackHeader").assertIsNotDisplayed()
        rule.onNodeWithTag(tag).performTouchInput {
            moveBy(Offset(0f, 144.dp.toPx()), delayMillis = 500)
            advanceEventTime(200)
            up()
        }
        rule.onNodeWithTag("libraryTabs").assertIsDisplayed()
        if (connected) rule.onNodeWithTag("targetPhone").assertIsNotDisplayed()
    }

    private fun shot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "scroll-headers").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun outputReturnsOnlyAtTopWhileLibraryReturnsOnReverseDrag() {
        start(library())
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()
        rule.onNodeWithTag("targetDesktop").assertIsDisplayed()
        shot("songs-top")
        collapse("librarySongs")
        rule.onNodeWithTag("playbackHeader").assertIsNotDisplayed()
        shot("songs-down")
        reverseBeforeReleasing("librarySongs", connected = true)
        shot("songs-reverse")
        repeat(12) {
            rule.onNodeWithTag("librarySongs").performTouchInput { swipeDown(durationMillis = 600) }
        }
        assertEquals(0f, scrollValue("librarySongs"), 0f)
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()
        rule.onNodeWithTag("targetDesktop").assertIsDisplayed()
        rule.onNodeWithTag("libraryTabs").assertIsDisplayed()
        shot("songs-return-top")
    }

    @Test fun albumGridUsesTheSameDirectionRuleWithoutAComputer() {
        start(library(LibrarySectionTab.ALBUMS, connected = false))
        rule.onNodeWithTag("playbackHeader").assertDoesNotExist()
        collapse("albumGrid")
        reverseBeforeReleasing("albumGrid", connected = false)
        shot("albums-reverse")
        rule.runOnIdle { ui.value = ui.value.copy(libraryDevice = LIBRARY_PHONE) }
        assertEquals(0f, scrollValue("albumGrid"), 0f)
        rule.onNodeWithTag("libraryTabs").assertIsDisplayed()
    }

    @Test fun artistGridUsesTheSameDirectionRule() {
        start(library(LibrarySectionTab.ARTISTS))
        collapse("artistGrid")
        rule.onNodeWithTag("playbackHeader").assertIsNotDisplayed()
        reverseBeforeReleasing("artistGrid", connected = true)
        shot("artists-reverse")
        rule.onNodeWithTag("libraryDevice").performClick()
        rule.onNodeWithTag("libraryDevice-$LIBRARY_PHONE").performClick()
        assertEquals(0f, scrollValue("artistGrid"), 0f)
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()
    }

    @Test fun desktopAlbumsUseBothHeaderRules() {
        start(library(LibrarySectionTab.ALBUMS, catalog = true))
        collapse("desktopCatalog")
        reverseBeforeReleasing("desktopCatalog", connected = true)
        shot("desktop-albums-reverse")
    }

    @Test fun desktopArtistsUseBothHeaderRules() {
        start(library(LibrarySectionTab.ARTISTS, catalog = true))
        collapse("desktopCatalog")
        reverseBeforeReleasing("desktopCatalog", connected = true)
    }

    @Test fun pageAndSavedStateRestorationKeepCollapsedHeadersWithTheirList() {
        val restoration = start(library())
        collapse("librarySongs")
        val before = scrollValue("librarySongs")
        rule.onNodeWithTag("nav-SETTINGS").performClick()
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()
        rule.onNodeWithTag("nav-LIBRARY").performClick()
        assertEquals(before, scrollValue("librarySongs"), 0f)
        rule.onNodeWithTag("libraryNavigation").assertIsNotDisplayed()
        rule.onNodeWithTag("playbackHeader").assertIsNotDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(before, scrollValue("librarySongs"), 0f)
        rule.onNodeWithTag("libraryNavigation").assertIsNotDisplayed()
        rule.onNodeWithTag("playbackHeader").assertIsNotDisplayed()
    }

    @Test fun horizontalLibraryTabScrollingDoesNotCollapseTheHeaders() {
        start(library())
        val height = navigationHeight()
        rule.onNodeWithTag("libraryTabs").performTouchInput { swipeLeft() }
        assertEquals(height, navigationHeight())
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()
        rule.onNodeWithTag("libraryTab-STREAMING").assertIsDisplayed()
    }

    @Test fun changingLibraryDeviceRevealsHeadersAtTheResetListTop() {
        start(library())
        collapse("librarySongs")
        reverseBeforeReleasing("librarySongs", connected = true)
        rule.onNodeWithTag("libraryDevice").performClick()
        rule.onNodeWithTag("libraryDevice-$LIBRARY_PHONE").performClick()
        assertEquals(0f, scrollValue("librarySongs"), 0f)
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()
        rule.onNodeWithTag("libraryTabs").assertIsDisplayed()
    }

    @Test fun aNewDesktopCatalogPageRevealsHeadersAtItsTop() {
        start(library(LibrarySectionTab.ALBUMS, catalog = true))
        collapse("desktopCatalog")
        rule.runOnIdle { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(
            page = ui.value.desktopLibrary.page!!.copy(offset = 100))) }
        assertEquals(0f, scrollValue("desktopCatalog"), 0f)
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()
        rule.onNodeWithTag("libraryTabs").assertIsDisplayed()
    }

    @Test fun desktopDetailStartsWithItsOwnHeaderAndBackRestoresTheCatalog() {
        start(library(LibrarySectionTab.ALBUMS, catalog = true))
        collapse("desktopCatalog")
        rule.onNodeWithTag("desktopCatalog").performScrollToIndex(12)
        val before = scrollValue("desktopCatalog")
        rule.onNodeWithTag("catalog-entity-12").performClick()
        rule.onNodeWithTag("desktopEntity").assertIsDisplayed()
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回电脑曲库").performClick()
        assertEquals(before, scrollValue("desktopCatalog"), 0f)
        rule.onNodeWithTag("libraryNavigation").assertIsNotDisplayed()
        rule.onNodeWithTag("playbackHeader").assertIsNotDisplayed()
    }
}
