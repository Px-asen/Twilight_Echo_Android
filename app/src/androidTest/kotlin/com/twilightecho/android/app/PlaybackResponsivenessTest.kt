package com.twilightecho.android.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.PlayerViewModel
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlaybackResponsivenessTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun tapFeedbackIsImmediateAndRapidTogglesSettleOnTheLastIntent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = AndroidPlaybackHost.getInstance(context)
        val client = host.primaryCoreClient
        await { client.snapshot.value != null }
        val originalMode = host.operatingMode.value
        assumeTrue("Run while phone playback is idle", host.phoneLibraryClient.snapshot.value?.playback?.state != "playing")
        host.selectPlaybackTarget(phone = true).getOrThrow()
        val original = client.snapshot.value!!
        val savedQueue = mutableListOf<CoreTrackView>()
        for (offset in 0 until (original.queue?.total ?: 0) step 50) {
            savedQueue += (client.queryQueueWindow(offset, 50) as CoreResult.Success).value.items.mapNotNull { it.track }
        }
        val source = File.createTempFile("responsive-", ".flac", context.cacheDir)
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val nextSource = File.createTempFile("responsive-next-", ".flac", context.cacheDir)
        source.copyTo(nextSource, overwrite = true)
        lateinit var viewModel: PlayerViewModel
        rule.runOnUiThread { viewModel = ViewModelProvider(rule.activity)[PlayerViewModel::class.java] }
        try {
            assertTrue(client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = listOf(
                CoreTrackView("responsive", title = "Golden Hour", artist = "Paper Lantern", source = source.absolutePath, duration = 42.0),
                CoreTrackView("responsive-next", title = "Next Song", artist = "Paper Lantern", source = nextSource.absolutePath, duration = 42.0)), play = false)).isSuccess)
            await { viewModel.uiState.value.currentTrack?.id == "responsive" }
            assertNull("The replacement fixture must start without a Core error", client.snapshot.value?.error)
            rule.runOnUiThread { viewModel.onDismissError() }
            rule.runOnUiThread {
                viewModel.onTogglePlayPause()
                assertEquals(true, viewModel.uiState.value.requestedPlaying)
                assertTrue(viewModel.uiState.value.playbackButtonPlaying)
            }
            await { viewModel.uiState.value.let { it.isPlaying && it.requestedPlaying == null } }
            rule.runOnUiThread {
                viewModel.onTogglePlayPause()
                assertEquals(false, viewModel.uiState.value.requestedPlaying)
                viewModel.onTogglePlayPause()
                assertEquals(true, viewModel.uiState.value.requestedPlaying)
            }
            await { viewModel.uiState.value.let { it.isPlaying && it.requestedPlaying == null } }
            delay(350)
            assertEquals("playing", host.nativeAudioEngine.getSnapshot().state)
            assertNull(viewModel.uiState.value.errorBanner)
            // Also tap while the native 200ms pause fade is actually in flight.
            rule.runOnUiThread { viewModel.onTogglePlayPause() }
            delay(40)
            rule.runOnUiThread { viewModel.onTogglePlayPause() }
            await { viewModel.uiState.value.let { it.isPlaying && it.requestedPlaying == null } }
            assertEquals("playing", host.nativeAudioEngine.getSnapshot().state)
            assertNull(viewModel.uiState.value.errorBanner)

            val interpolatedWhileBrowsing = AtomicInteger()
            val observer = launch(Dispatchers.Main.immediate) {
                viewModel.uiState.collect { state ->
                    if (!state.isImmersivePlayerOpen && state.displayedPositionSeconds != state.anchorPositionSeconds)
                        interpolatedWhileBrowsing.incrementAndGet()
                }
            }
            delay(600)
            observer.cancelAndJoin()
            assertEquals("The library/dock has no progress display and must not redraw at 10 Hz", 0, interpolatedWhileBrowsing.get())
            rule.runOnUiThread { viewModel.onSetImmersivePlayerOpen(true) }
            await { viewModel.uiState.value.displayedPositionSeconds > viewModel.uiState.value.anchorPositionSeconds }
            rule.onNodeWithContentDescription("下一首").performClick()
            await { viewModel.uiState.value.let { it.currentTrack?.id == "responsive-next" && it.isPlaying } }
            await { host.nativeAudioEngine.currentResolvedNativePath == nextSource.absolutePath &&
                host.nativeAudioEngine.getSnapshot().let { it.state == "playing" && it.positionMs > 100 } }
            // Core's SQL cursor is global; native keeps a bounded current + next window.
            assertEquals(1, client.snapshot.value?.queue?.index)
            assertNull(viewModel.uiState.value.errorBanner)
            rule.onNodeWithContentDescription("上一首").performClick()
            await { viewModel.uiState.value.let { it.currentTrack?.id == "responsive" && it.isPlaying } }
            await { host.nativeAudioEngine.currentResolvedNativePath == source.absolutePath &&
                host.nativeAudioEngine.getSnapshot().let { it.state == "playing" && it.positionMs > 100 } }
            assertEquals(0, client.snapshot.value?.queue?.index)
            assertNull(viewModel.uiState.value.errorBanner)
            val directory = File(context.getExternalFilesDir(null), "performance-ui").apply { mkdirs() }
            rule.waitForIdle()
            assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "player.png")))
        } finally {
            client.executeCommand(CoreTypedCommand.Stop)
            client.executeCommand(CoreTypedCommand.ReplaceQueue(id = original.queue?.id ?: "default", tracks = savedQueue,
                startIndex = (original.queue?.index ?: 0).coerceAtLeast(0), play = false))
            if (savedQueue.isNotEmpty()) {
                client.executeCommand(CoreTypedCommand.Seek(original.playback?.position ?: 0.0))
            }
            if (originalMode is CoreOperatingMode.RemoteDesktopCore) host.selectPlaybackTarget(phone = false)
            source.delete()
            nextSource.delete()
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(15000) { while (!condition()) delay(25) }
    }
}
