package com.twilightecho.android.app

import android.content.Intent
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.PlayerViewModel
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real production HTTP/Range -> shared FFmpeg -> C++ DSP -> AAudio. */
@RunWith(AndroidJUnit4::class)
class DesktopStreamInteropTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val host get() = AndroidPlaybackHost.getInstance(instrumentation.targetContext)
    private val device get() = UiDevice.getInstance(instrumentation)
    private val address get() = InstrumentationRegistry.getArguments().getString("desktopHost")

    @Test fun flacMp3SeekIndependentQueuesLockScreenAndLiveMetadata() {
        assumeTrue("Requires tools/desktop-bridge-fixture.ts", !address.isNullOrBlank())
        rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value != null }
        lateinit var vm: PlayerViewModel
        rule.runOnUiThread { vm = ViewModelProvider(rule.activity, PlayerViewModel.Factory(host))[PlayerViewModel::class.java] }
        try {
            runBlocking {
                host.switchToStandaloneLocalCore().getOrThrow()
                host.primaryCoreClient.executeCommand(CoreTypedCommand.Stop)
                host.connectDesktopLibrary(address!!, "123456").getOrThrow()
            }
            assertTrue(host.operatingMode.value is CoreOperatingMode.StandaloneLocal)
            assertEquals("lib-desktop-interop", (host.librarySource.value as LibrarySource.Desktop).libraryId)
            val desktopQueue = host.libraryCoreClient.snapshot.value?.queue
            val desktopPlayback = host.libraryCoreClient.snapshot.value?.playback?.state
            val page = runBlocking { host.libraryCoreClient.searchTracksPage("", 0, 100).getOrThrow() }
            assertEquals(125, page.total)
            assertEquals(1022519.7329376856, page.items.first().bitrate!!, 0.0001)
            rule.onNodeWithTag("nav-LIBRARY").performClick()
            rule.waitUntil(15000) { rule.onAllNodes(librarySong("来自电脑的音乐")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("targetPhone").assertIsSelected()
            screenshot("stream-library-target-phone")
            rule.onNode(librarySong("来自电脑的音乐")).performClick()
            awaitPhone("playing")
            val phoneTrack = host.primaryCoreClient.snapshot.value!!.track!!
            assertEquals("source-0", DesktopMediaReference.parse(phoneTrack.id)?.sourceId)
            assertSharedNativeOutput()
            assertTrue(runBlocking { host.nativeAudioEngine.getSnapshot().durationMs!! } in 41000..43000)
            awaitCondition { runBlocking { host.nativeAudioEngine.getSnapshot().positionMs } >= 700 }
            assertEquals(desktopQueue, host.libraryCoreClient.snapshot.value?.queue)
            assertEquals(desktopPlayback, host.libraryCoreClient.snapshot.value?.playback?.state)
            assertTrue(runBlocking { host.connectDesktopLibrary(address!!, "000000").isFailure })
            assertEquals(phoneTrack.id, host.primaryCoreClient.snapshot.value?.track?.id)
            awaitPhone("playing")
            rule.waitUntil(10000) { vm.uiState.value.desktopLibrary.artwork[phoneTrack.id]?.startsWith("data:image/") == true }

            liveUi { rule.onNodeWithTag("miniPlayer").performClick() }
            liveUi { rule.onNodeWithTag("playerPlayPause").performClick() }
            awaitPhone("paused")
            rule.onNodeWithTag("seek").performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
            awaitCondition { runBlocking { host.nativeAudioEngine.getSnapshot().positionMs } in 19000..22000 }
            liveUi { rule.onNodeWithTag("playerPlayPause").performClick() }
            awaitPhone("playing")
            screenshot("stream-flac-player")
            liveUi { rule.onNodeWithContentDescription("收起播放器").performClick() }

            // Switching the control target cannot pause phone audio or migrate either queue.
            liveUi { rule.onNodeWithTag("targetDesktop").performClick() }
            rule.waitUntil(15000) { host.operatingMode.value is CoreOperatingMode.RemoteDesktopCore }
            assertEquals(desktopQueue, host.primaryCoreClient.snapshot.value?.queue)
            awaitCondition { host.mediaSessionCoreClient.snapshot.value?.track?.id == phoneTrack.id }
            assertEquals("playing", runBlocking { host.nativeAudioEngine.getSnapshot().state })
            val beforeBackground = runBlocking { host.nativeAudioEngine.getSnapshot().positionMs }
            device.pressHome(); device.sleep()
            Thread.sleep(1500)
            assertEquals("playing", runBlocking { host.nativeAudioEngine.getSnapshot().state })
            assertTrue(runBlocking { host.nativeAudioEngine.getSnapshot().positionMs } > beforeBackground + 700)
            device.executeShellCommand("cmd media_session dispatch pause")
            awaitPhone("paused")
            assertEquals(phoneTrack.id, host.mediaSessionCoreClient.snapshot.value?.track?.id)
            device.executeShellCommand("cmd media_session dispatch play")
            awaitPhone("playing")
            assertEquals(desktopPlayback, host.primaryCoreClient.snapshot.value?.playback?.state)
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            rule.waitUntil(15000) { liveUi { rule.onAllNodesWithTag("targetPhone").fetchSemanticsNodes().isNotEmpty() } }
            liveUi { rule.onNodeWithTag("targetPhone").performClick() }
            rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value?.track?.id == phoneTrack.id }

            assertTrue(runBlocking { host.primaryCoreClient.executeCommand(CoreTypedCommand.Next).isSuccess })
            awaitPhone("playing")
            awaitCondition { DesktopMediaReference.parse(host.primaryCoreClient.snapshot.value?.track?.id.orEmpty())?.sourceId == "source-1" }
            assertTrue(runBlocking { host.nativeAudioEngine.getSnapshot().durationMs!! } in 15000..17000)
            assertTrue(runBlocking { host.primaryCoreClient.executeCommand(CoreTypedCommand.Seek(9.0)).isSuccess })
            awaitCondition { runBlocking { host.nativeAudioEngine.getSnapshot().positionMs } in 8500..12000 }
            screenshot("stream-mp3-playing")
            assertTrue(runBlocking { host.primaryCoreClient.executeCommand(CoreTypedCommand.Seek(15.5)).isSuccess })
            awaitCondition { DesktopMediaReference.parse(host.primaryCoreClient.snapshot.value?.track?.id.orEmpty())?.sourceId == "source-2" }
            awaitPhone("playing")
            assertEquals(desktopQueue, host.libraryCoreClient.snapshot.value?.queue)

            liveUi { rule.onNodeWithText("艺术家", substring = false).performClick() }
            rule.waitUntil(15000) { liveUi { rule.onAllNodesWithTag("catalog-artist-1").fetchSemanticsNodes().isNotEmpty() } }
            liveUi { rule.onNodeWithTag("catalog-artist-1").performClick() }
            rule.waitUntil(15000) { vm.uiState.value.desktopLibrary.metadata?.revision == 9L }
            // The fixture changes both domain revisions after the first detail read.
            rule.waitUntil(15000) { vm.uiState.value.desktopLibrary.metadata?.name == "电脑更正后的艺术家" }
            assertEquals("artist-1", vm.uiState.value.desktopLibrary.selected?.id)
            // Live revisions have arrived while playing. Pause before inspecting/scrolling the
            // detail view so Espresso can settle; the source action must then start phone audio.
            assertTrue(runBlocking { host.primaryCoreClient.executeCommand(CoreTypedCommand.Pause).isSuccess })
            awaitPhone("paused")
            rule.waitUntil(15000) { !vm.uiState.value.isPlaying }
            rule.onNodeWithText("电脑更正后的艺术家", substring = false).performScrollTo().assertIsDisplayed()
            screenshot("stream-metadata-live-refresh")
            // Reproduce the user's detail-page route, not just song-list playback.
            rule.onNodeWithTag("desktopEntity").performScrollToNode(hasContentDescription("在此手机播放 来自电脑的音乐"))
            val sourceButton = rule.onNodeWithContentDescription("在此手机播放 来自电脑的音乐")
            sourceButton.assertIsEnabled().assertIsDisplayed()
            screenshot("stream-artist-source-before-click")
            sourceButton.performClick()
            awaitCondition { check(vm.uiState.value.errorBanner == null) { "Source playback: ${vm.uiState.value.errorBanner}" }
                DesktopMediaReference.parse(host.primaryCoreClient.snapshot.value?.track?.id.orEmpty())?.sourceId == "source-0" &&
                    host.primaryCoreClient.snapshot.value?.queue?.total == 1 }
            awaitPhone("playing")
            assertSharedNativeOutput()
            awaitCondition { runBlocking { host.nativeAudioEngine.getSnapshot().positionMs } >= 700 }
            screenshot("stream-artist-source-playing")
        } catch (error: Throwable) {
            val state = vm.uiState.value
            Log.e("DesktopStreamInterop", "UI=${state.playbackState}/${state.currentTrack?.id}; error=${state.errorBanner}; " +
                "system=${host.mediaSessionCoreClient.snapshot.value?.playback}", error)
            throw error
        } finally {
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            runBlocking {
                host.selectPlaybackTarget(true)
                host.primaryCoreClient.executeCommand(CoreTypedCommand.Stop)
                host.switchToStandaloneLocalCore()
            }
        }
    }

    @Test fun artistSourceStreamsOnPhoneAndReplacesTheExistingQueue() {
        assumeTrue("Requires tools/desktop-bridge-fixture.ts", !address.isNullOrBlank())
        rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value != null }
        lateinit var vm: PlayerViewModel
        rule.runOnUiThread { vm = ViewModelProvider(rule.activity, PlayerViewModel.Factory(host))[PlayerViewModel::class.java] }
        try {
            runBlocking {
                host.switchToStandaloneLocalCore().getOrThrow()
                host.primaryCoreClient.executeCommand(CoreTypedCommand.Stop)
                host.connectDesktopLibrary(address!!, "123456").getOrThrow()
                val tracks = host.libraryCoreClient.searchTracksPage("", 0, 2).getOrThrow().items
                assertTrue(host.primaryCoreClient.executeCommand(CoreTypedCommand.ReplaceQueue(
                    revision = host.primaryCoreClient.snapshot.value?.queue?.revision ?: 0,
                    tracks = host.desktopQueueTracks(tracks), startIndex = 1, play = false)).isSuccess)
            }
            awaitPhone("paused")
            awaitCondition { host.primaryCoreClient.snapshot.value?.queue?.total == 2 }
            assertEquals("source-1", DesktopMediaReference.parse(host.primaryCoreClient.snapshot.value?.track?.id.orEmpty())?.sourceId)
            liveUi { rule.onNodeWithTag("nav-LIBRARY").performClick() }
            liveUi { rule.onNodeWithText("艺术家", substring = false).performClick() }
            rule.waitUntil(15000) { liveUi { rule.onAllNodesWithTag("catalog-artist-1").fetchSemanticsNodes().isNotEmpty() } }
            liveUi { rule.onNodeWithTag("catalog-artist-1").performClick() }
            rule.waitUntil(15000) { vm.uiState.value.desktopLibrary.sources?.items?.any { it.id == "source-0" } == true }
            rule.onNodeWithTag("desktopEntity").performScrollToNode(hasContentDescription("在此手机播放 来自电脑的音乐"))
            val button = rule.onNodeWithContentDescription("在此手机播放 来自电脑的音乐")
            button.assertIsEnabled().assertIsDisplayed()
            screenshot("stream-artist-source-before-click")
            rule.runOnUiThread { vm.onDismissError() }
            button.performClick()
            awaitCondition { check(vm.uiState.value.errorBanner == null) { "Source playback: ${vm.uiState.value.errorBanner}" }
                DesktopMediaReference.parse(host.primaryCoreClient.snapshot.value?.track?.id.orEmpty())?.sourceId == "source-0" &&
                    host.primaryCoreClient.snapshot.value?.queue?.total == 1 }
            awaitPhone("playing")
            assertSharedNativeOutput()
            awaitCondition { runBlocking { host.nativeAudioEngine.getSnapshot().positionMs } >= 700 }
            screenshot("stream-artist-source-playing")
        } catch (error: Throwable) {
            Log.e("DesktopStreamInterop", "Source playback UI=${vm.uiState.value.errorBanner}; phone=${runBlocking { host.nativeAudioEngine.getSnapshot() }}", error)
            screenshot("stream-artist-source-failed")
            throw error
        } finally { runBlocking { host.primaryCoreClient.executeCommand(CoreTypedCommand.Stop); host.switchToStandaloneLocalCore() } }
    }

    @Test fun missingDesktopSourceReportsFailureInsteadOfDemoPlayback() {
        assumeTrue("Requires tools/desktop-bridge-fixture.ts", !address.isNullOrBlank())
        rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value != null }
        try {
            runBlocking {
                host.switchToStandaloneLocalCore().getOrThrow()
                host.connectDesktopLibrary(address!!, "123456").getOrThrow()
                val id = DesktopMediaReference((host.librarySource.value as LibrarySource.Desktop).serverId,
                    "lib-desktop-interop", "source-missing").resourceId()
                assertTrue(host.primaryCoreClient.executeCommand(CoreTypedCommand.ReplaceQueue(
                    revision = host.primaryCoreClient.snapshot.value?.queue?.revision ?: 0,
                    tracks = listOf(CoreTrackView(id, title = "Missing fixture", source = id, duration = 42.0)), play = false)).isSuccess)
                host.requestFocusAndPlay(0.0)
            }
            awaitPhone("error")
            awaitCondition { host.primaryCoreClient.snapshot.value?.error != null }
            assertNotEquals("playing", host.primaryCoreClient.snapshot.value?.playback?.state)
            // Core renders EngineError as "code: message"; preserve the original IO cause.
            assertEquals("曲目或资料已不存在，请刷新后重新选择", host.primaryCoreClient.snapshot.value?.error?.substringAfter(": "))
        } finally { runBlocking { host.primaryCoreClient.executeCommand(CoreTypedCommand.Stop); host.switchToStandaloneLocalCore() } }
    }

    @Test fun phoneLocalFlacKeepsItsOriginalPlaybackPath() {
        rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value != null }
        val file = File.createTempFile("stream-local-regression-", ".flac", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
            runBlocking {
                host.switchToStandaloneLocalCore().getOrThrow()
                assertTrue(host.primaryCoreClient.executeCommand(CoreTypedCommand.ReplaceQueue(
                    revision = host.primaryCoreClient.snapshot.value?.queue?.revision ?: 0,
                    tracks = listOf(CoreTrackView(file.absolutePath, source = file.absolutePath,
                        title = "Local FLAC regression", duration = 42.0, format = "FLAC")), play = false)).isSuccess)
                host.requestFocusAndPlay(0.0)
            }
            awaitPhone("playing")
            awaitCondition { runBlocking { host.nativeAudioEngine.getSnapshot().positionMs } >= 600 }
            assertSharedNativeOutput()
            runBlocking { host.nativeAudioEngine.setDspConfig("""{"enabled":true,"preampDb":-6,"bandsDb":[0,0,0,0,3,0,0,0,0,0]}""") }
            awaitCondition { runBlocking { host.nativeAudioEngine.getSnapshot().let { it.eqActive && it.dspActive } } }
            assertTrue(runBlocking { host.nativeAudioEngine.getSnapshot().durationMs!! } in 41000..43000)
            assertTrue(runBlocking { host.primaryCoreClient.executeCommand(CoreTypedCommand.Pause).isSuccess })
            awaitPhone("paused")
            // The C ABI pause command toggles: the Android pause port must remain idempotent.
            runBlocking { host.nativeAudioEngine.pause() }
            Thread.sleep(200)
            assertEquals("paused", runBlocking { host.nativeAudioEngine.getSnapshot().state })
            assertTrue(runBlocking { host.primaryCoreClient.executeCommand(CoreTypedCommand.Seek(12.0)).isSuccess })
            awaitCondition { runBlocking { host.nativeAudioEngine.getSnapshot().positionMs } in 11000..13000 }
        } finally { runBlocking { host.nativeAudioEngine.setDspConfig("""{"enabled":false,"bandsDb":[]}"""); host.primaryCoreClient.executeCommand(CoreTypedCommand.Stop) }; file.delete() }
    }

    private fun assertSharedNativeOutput() {
        val snapshot = runBlocking { host.nativeAudioEngine.getSnapshot() }
        assertEquals("oboe", snapshot.outputBackend)
        assertTrue("AAudio must negotiate a real output rate: $snapshot", (snapshot.actualSampleRate ?: 0) > 0)
        assertTrue("FFmpeg must report the decoded codec: $snapshot", !snapshot.codec.isNullOrBlank())
    }

    private fun awaitPhone(state: String) = awaitCondition { runBlocking { host.nativeAudioEngine.getSnapshot().state } == state }
    private fun awaitCondition(timeout: Long = 20000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("Timed out; phone=${runBlocking { host.nativeAudioEngine.getSnapshot() }}")
            Thread.sleep(100)
        }
    }
    private fun screenshot(name: String) {
        Thread.sleep(300)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "desktop-bridge-qa").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }
    // Playback updates the UI at 10 Hz. Drive its frames explicitly during the click instead
    // of asking Espresso to wait for a continuously advancing live clock to become idle.
    private fun <T> liveUi(action: () -> T): T {
        val advancing = rule.mainClock.autoAdvance
        rule.mainClock.autoAdvance = false
        try {
            rule.mainClock.advanceTimeBy(400)
            val result = action()
            rule.mainClock.advanceTimeBy(400)
            return result
        } finally { rule.mainClock.autoAdvance = advancing }
    }
    private fun librarySong(title: String) = hasText(title, substring = false) and hasAnyAncestor(hasTestTag("librarySongs"))
}
