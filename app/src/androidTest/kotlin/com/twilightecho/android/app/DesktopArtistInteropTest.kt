package com.twilightecho.android.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production desktop bridge -> phone host -> merged and desktop-only artist pages, without playback. */
@RunWith(AndroidJUnit4::class)
class DesktopArtistInteropTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun computerProfilesAppearInAllDevicesAndExactCatalogAlbumsOpen() {
        val address = InstrumentationRegistry.getArguments().getString("desktopHost")
        assumeTrue("Requires tools/desktop-bridge-fixture.ts", !address.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = AndroidPlaybackHost.getInstance(context)
        rule.waitUntil(15_000) { host.primaryCoreClient.snapshot.value?.phase == CorePhase.READY }
        lateinit var vm: PlayerViewModel
        rule.runOnUiThread { vm = ViewModelProvider(rule.activity, PlayerViewModel.Factory(host))[PlayerViewModel::class.java] }
        val queue = host.primaryCoreClient.snapshot.value!!.queue
        try {
            runBlocking { host.connectDesktopLibrary(address!!, "123456").getOrThrow() }
            val desktop = host.librarySource.value as LibrarySource.Desktop
            rule.waitUntil(30_000) { host.desktopLibraries.value.any { it.serverId == desktop.serverId && it.complete } }
            rule.runOnUiThread {
                vm.onSelectLibraryDevice(LIBRARY_ALL)
                vm.onSelectMainTab(MainDestinationTab.LIBRARY)
                vm.onSelectLibraryTab(LibrarySectionTab.ARTISTS)
            }
            val artistTile = hasText("共享艺术家", substring = false) and hasAnyAncestor(hasTestTag("library"))
            rule.waitUntil(10_000) { rule.onAllNodes(artistTile).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(artistTile).performClick()
            rule.waitUntil(15_000) { vm.uiState.value.libraryArtist.metadata?.entityId == "artist-1" }
            rule.onNodeWithText("这段简介来自电脑已整理的音乐资料。").performScrollTo().assertIsDisplayed()
            if (InstrumentationRegistry.getArguments().getString("expectMetadataRefresh") == "true") {
                rule.waitUntil(15_000) { vm.uiState.value.libraryArtist.metadata?.name == "电脑更正后的艺术家" }
                rule.onNodeWithText("电脑更正后的艺术家").performScrollTo().assertIsDisplayed()
            }
            rule.onNodeWithTag("artistAlbumsTab").performScrollTo().performClick()
            rule.onNodeWithText("共享专辑", substring = false).performScrollTo().performClick()
            rule.onNodeWithContentDescription("返回音乐库").performScrollTo().performClick()
            rule.onNodeWithTag("artistAlbumsTab").performScrollTo().assertIsSelected()

            rule.runOnUiThread { vm.onSelectLibraryDevice(desktop.serverId) }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("catalog-artist-1").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("catalog-artist-1").performClick()
            rule.waitUntil(15_000) { vm.uiState.value.desktopLibrary.metadata != null }
            rule.onNodeWithTag("artistAlbumsTab").performScrollTo().performClick()
            rule.waitUntil(15_000) { vm.uiState.value.desktopLibrary.albumsLoaded }
            assertEquals(listOf("release-1"), vm.uiState.value.desktopLibrary.albums.map { it.id })
            rule.onNodeWithTag("catalog-release-1").performScrollTo().performClick()
            rule.waitUntil(10_000) { vm.uiState.value.desktopLibrary.selected?.id == "release-1" }
            rule.onNodeWithContentDescription("返回电脑曲库").performScrollTo().performClick()
            rule.onNodeWithTag("artistAlbumsTab").performScrollTo().assertIsSelected()
            val folder = File(context.getExternalFilesDir(null), "collection-artist-qa").apply { mkdirs() }
            File(folder, "desktop-artist-production-bridge.png").outputStream().use {
                check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            assertEquals(queue, host.primaryCoreClient.snapshot.value!!.queue)
        } finally { runBlocking { host.switchToStandaloneLocalCore().getOrThrow() } }
    }
}
