package com.twilightecho.android.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.media3.common.util.UnstableApi
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import com.twilightecho.android.mediasession.CoreSimpleBasePlayer
import com.twilightecho.android.feature.player.PlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the production desktop server/bridge from tools/desktop-bridge-fixture.ts over adb reverse. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DesktopBridgeInteropTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val host get() = AndroidPlaybackHost.getInstance(instrumentation.targetContext)

    @Test fun desktopLibraryMetadataSelectionControlsAndReturnToPhone() {
        val address = InstrumentationRegistry.getArguments().getString("desktopHost")
        assumeTrue("Pass -e desktopHost with the interop fixture address", !address.isNullOrBlank())
        rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value != null }
        val localEpoch = host.primaryCoreClient.snapshot.value!!.epoch
        try {
            rule.onNodeWithTag("nav-SETTINGS").performClick()
            rule.onNodeWithText("连接电脑", substring = false).performClick()
            rule.onNodeWithTag("manualDesktopConnection").performScrollTo().performClick()
            rule.onNodeWithTag("remoteHost").performScrollTo().performTextClearance()
            rule.onNodeWithTag("remoteHost").performTextInput(address!!)
            rule.onNodeWithTag("remotePin").performScrollTo().performTextClearance()
            rule.onNodeWithTag("remotePin").performTextInput("123456")
            rule.runOnUiThread { WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime()) }
            rule.onNodeWithText("连接电脑 Core").performScrollTo().performClick()
            rule.waitUntil(15000) { (host.librarySource.value as? LibrarySource.Desktop)?.connected == true &&
                host.libraryCoreClient.snapshot.value?.epoch?.startsWith("desktop-interop") == true }
            rule.onNodeWithTag("nav-LIBRARY").performClick()
            rule.onNodeWithTag("targetDesktop").performClick()
            rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value?.epoch?.startsWith("desktop-interop") == true }
            rule.waitUntil(15000) { rule.onAllNodes(librarySong("来自电脑的音乐")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(librarySong("来自电脑的音乐")).assertIsDisplayed()
            rule.onNodeWithTag("librarySongs").performScrollToIndex(90)
            rule.onNodeWithText("下一页", substring = false).performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithText("电脑歌曲 101", substring = false).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("电脑歌曲 101", substring = false).assertIsDisplayed()
            lateinit var viewModel: PlayerViewModel
            rule.runOnUiThread { viewModel = ViewModelProvider(rule.activity, PlayerViewModel.Factory(host))[PlayerViewModel::class.java] }
            rule.waitUntil(15000) {
                val page = viewModel.uiState.value
                page.desktopLibrary.artwork[page.libraryTracks.firstOrNull()?.id]?.startsWith("data:image/") == true
            }
            screenshot("desktop-songs-page-2")
            rule.onNodeWithText("上一页", substring = false).performClick()
            rule.waitUntil(15000) { rule.onAllNodes(librarySong("来自电脑的音乐")).fetchSemanticsNodes().isNotEmpty() }
            val previousQueueRevision = host.primaryCoreClient.snapshot.value?.queue?.revision ?: 0
            rule.onNode(librarySong("来自电脑的音乐")).performClick()
            rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value?.let {
                val queue = it.queue
                it.playback?.state == "playing" && queue != null && queue.total == 100 && queue.revision > previousQueueRevision
            } == true }
            assertTrue(host.primaryCoreClient.snapshot.value!!.track!!.id.startsWith("remote-track:"))
            assertEquals(100, host.primaryCoreClient.snapshot.value?.queue?.total)
            rule.onNodeWithContentDescription("暂停", substring = false).performClick()
            rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value?.playback?.state == "paused" }
            // The system media timeline is a bounded window, not a second independent queue.
            rule.waitUntil(15000) { host.mediaSessionCoreClient.queueWindow.value.items.size == 50 }
            lateinit var mediaPlayer: CoreSimpleBasePlayer
            rule.runOnUiThread { mediaPlayer = CoreSimpleBasePlayer(host); mediaPlayer.seekTo(10, 0) }
            try {
                rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value?.queue?.index == 10 }
                assertTrue(host.primaryCoreClient.snapshot.value!!.queue!!.occurrenceId!!.startsWith("desktop-entry:"))
            } finally { rule.runOnUiThread { mediaPlayer.release() } }
            rule.onNodeWithText("艺术家", substring = false).performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithTag("catalog-artist-1").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("catalog-artist-1").performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithText("整理后的共享艺术家", substring = false).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("这段简介来自电脑已整理的音乐资料。", substring = false).performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("desktopMetadataArtwork").performScrollTo().assertIsDisplayed()
            screenshot("desktop-artist-metadata")
            rule.runOnUiThread { viewModel.onDismissError() }
            rule.onNodeWithTag("desktopEntity").performScrollToNode(hasContentDescription("在电脑播放 来自电脑的音乐"))
            val sourceButton = rule.onNodeWithContentDescription("在电脑播放 来自电脑的音乐")
            sourceButton.assertIsEnabled().assertIsDisplayed()
            screenshot("desktop-source-selection")
            sourceButton.performClick()
            rule.waitUntil(15000) { viewModel.uiState.value.errorBanner != null ||
                (host.primaryCoreClient.snapshot.value?.playback?.state == "playing" &&
                    host.primaryCoreClient.snapshot.value?.queue?.total == 1) }
            assertNull("Catalog playback failed: ${viewModel.uiState.value.errorBanner}", viewModel.uiState.value.errorBanner)
            assertEquals(1, host.primaryCoreClient.snapshot.value?.queue?.total)
            assertEquals("desktop-interop", host.primaryCoreClient.snapshot.value?.epoch)
            runBlocking { host.switchToStandaloneLocalCore().getOrThrow() }
            rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value?.epoch == localEpoch }
            assertTrue(host.operatingMode.value is CoreOperatingMode.StandaloneLocal)
        } finally { runBlocking { host.switchToStandaloneLocalCore() } }
    }

    @Test fun revokedPairingStaysRemoteAndKeepsPhoneSilent() {
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("desktopHost")
        assumeTrue("Requires the revoking desktop fixture", args.getString("desktopRevocation") == "true" && !address.isNullOrBlank())
        rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value != null }
        try {
            runBlocking {
                host.connectToDesktopCore(address!!, "123456").getOrThrow()
                assertTrue(host.primaryCoreClient.executeCommand(CoreTypedCommand.PlayCatalogSource("source-0", 5)).isSuccess)
            }
            rule.waitUntil(20000) { host.remoteConnection.value.phase == RemoteConnectionPhase.PAIRING_REQUIRED }
            assertTrue(host.operatingMode.value is CoreOperatingMode.RemoteDesktopCore)
            assertFalse((host.operatingMode.value as CoreOperatingMode.RemoteDesktopCore).connected)
            assertNotEquals("playing", runBlocking { host.nativeAudioEngine.getSnapshot().state })
            rule.onNodeWithText("配对已失效 · 请在设置中重新连接电脑", substring = false).assertIsDisplayed()
            screenshot("desktop-pairing-revoked")
        } finally { runBlocking { host.switchToStandaloneLocalCore() } }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        UiDevice.getInstance(instrumentation).waitForIdle()
        Thread.sleep(350) // Semantics settle before Android's next physical draw/screenshot.
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "desktop-bridge-qa").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, "$name.png")))
    }

    private fun librarySong(title: String) = hasText(title, substring = false) and hasAnyAncestor(hasTestTag("librarySongs"))
}
