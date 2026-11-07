package com.twilightecho.android.app

import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SyncedArtistUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun allDevicesAndComputerShowOneArtistWithAPortraitAndCompleteMemberSongs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val portrait = instrumentation.context.assets.open("music/01.jpg").use {
            "data:image/jpeg;base64," + Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
        }
        val tracks = listOf(
            CoreTrackView("solo", title = "暮色", artist = "北岸", album = "夜航"),
            CoreTrackView("duet", title = "海风", artist = "北岸 / 南山", artists = listOf("北岸", "南山"), album = "夜航"))
        val north = CoreArtistCollectionView("artist:北岸", "北岸", 1, 2, listOf("solo", "duet"))
        val south = CoreArtistCollectionView("artist:南山", "南山", 1, 1, listOf("duet"))
        val metadata = EntityMetadataDetail("source-artist", 1, "北岸", MetadataIntroduction("来自电脑的歌手资料"),
            manualArtwork = PersonalArtworkPreview(portrait))
        val ui = mutableStateOf(PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.LIBRARY,
            activeLibraryTab = LibrarySectionTab.ARTISTS, isLoadingLibrary = false,
            libraryTracks = tracks, allTracks = tracks, libraryTrackCount = 2,
            artists = listOf(north, south),
            albums = listOf(CoreAlbumCollectionView("album:夜航", "夜航", "北岸", 2, trackIds = north.trackIds)),
            librarySource = LibrarySource.Desktop("desktop", "library", "https://desktop", "书房电脑"),
            libraryDevices = listOf(LibraryDeviceOption(LIBRARY_ALL, "全部设备"), LibraryDeviceOption("desktop", "书房电脑")),
            // A source-level catalog may contain the same person once per recording.
            desktopLibrary = DesktopBrowseState(page = CatalogPage(1, 2, items = listOf(
                CatalogEntity("source-artist", "artist", "北岸"), CatalogEntity("other-source-artist", "artist", "北岸"))))))
        var played = emptyList<String>()
        rule.setContent { PlayerAppContent(ui.value, PlayerActions(
            requestLibraryArtist = { id ->
                if (id !in ui.value.libraryArtistProfiles) ui.value = ui.value.copy(libraryArtistProfiles =
                    ui.value.libraryArtistProfiles + (id to LibraryArtistProfile(id, metadata.copy(name = if (id == north.id) "北岸" else "南山"))))
            },
            loadLibraryArtist = { id, _ -> ui.value = ui.value.copy(libraryArtist = ui.value.libraryArtistProfiles.getValue(id)) },
            playTracks = { songs, _ -> played = songs.map { it.id } })) }

        for (device in listOf(LIBRARY_ALL, "desktop")) {
            rule.runOnIdle { ui.value = ui.value.copy(libraryDevice = device) }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("libraryArtistPortrait-${north.id}").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("北岸", substring = false).assertCountEquals(1)
            rule.onNodeWithTag("desktopCatalog").assertDoesNotExist()
        }
        screenshot("synced-artist-grid.png")
        rule.onNodeWithText("北岸", substring = false).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("artistPortrait", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("来自电脑的歌手资料").assertExists()
        rule.onNodeWithText("播放全部").performScrollTo().performClick()
        assertEquals(listOf("solo", "duet"), played)
        screenshot("synced-artist-detail.png")
        rule.onNodeWithTag("artistAlbumsTab").performScrollTo().performClick()
        rule.onNodeWithText("夜航", substring = false).performScrollTo().performClick()
        rule.onNodeWithContentDescription("返回音乐库").performScrollTo().performClick()
        rule.onNodeWithTag("artistAlbumsTab").performScrollTo().assertIsSelected()
        rule.onNodeWithContentDescription("返回音乐库").performScrollTo().performClick()
        rule.runOnIdle {
            val profile = ui.value.libraryArtistProfiles.getValue(north.id)
            ui.value = ui.value.copy(libraryArtistProfiles = ui.value.libraryArtistProfiles +
                (north.id to profile.copy(metadata = metadata.copy(artworkOverride = ArtworkOverride("clear")))))
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("libraryArtistPlaceholder-${north.id}").fetchSemanticsNodes().isNotEmpty() }
        screenshot("synced-artist-explicit-clear.png")
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "artist-sync-qa").apply { mkdirs() }
        check(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, name)))
    }
}
