package com.twilightecho.android.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException
import java.io.File

@RunWith(AndroidJUnit4::class)
class P0SystemPlaybackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun screenOffSystemControlsAndTransientFocusKeepTheSessionAndUserVolume() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = AndroidPlaybackHost.getInstance(context)
        val device = UiDevice.getInstance(instrumentation)
        val client = host.phoneLibraryClient
        await("Phone Core must publish its initial session") { client.snapshot.value != null }
        val original = client.snapshot.value!!
        assumeTrue("Run when phone playback is idle", original.playback?.state != "playing")
        val savedQueue = mutableListOf<CoreTrackView>()
        val total = original.queue?.total ?: 0
        for (offset in 0 until total step 50) {
            val window = client.queryQueueWindow(offset, 50)
            check(window is CoreResult.Success)
            savedQueue += window.value.items.mapNotNull { it.track }
        }
        val originalMode = host.operatingMode.value
        val file = File.createTempFile("p0-system-", ".flac", context.cacheDir)
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val transient = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ }, android.os.Handler(android.os.Looper.getMainLooper())).build()
        val failures = mutableListOf<Throwable>()
        suspend fun restore(action: suspend () -> Unit) {
            try { action() } catch (failure: Throwable) { failures += failure }
        }
        suspend fun restoreCommand(command: CoreTypedCommand) {
            val result = client.executeCommand(command)
            check(result.isSuccess) { "Cleanup ${command.name} failed: ${result.errorOrNull()}" }
        }
        try {
            instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
            host.switchToStandaloneLocalCore().getOrThrow()
            check(client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = listOf(
                CoreTrackView("p0-system", title = "System playback test", source = file.absolutePath, duration = 42.0)), play = false)).isSuccess)
            check(client.executeCommand(CoreTypedCommand.SetVolume(0.42)).isSuccess)
            check(host.requestFocusAndPlay(0.0).isSuccess)
            await("Native playback clock must advance") { host.nativeAudioEngine.getSnapshot().positionMs > 500 }
            val before = host.nativeAudioEngine.getSnapshot().positionMs
            device.pressHome(); device.sleep()
            delay(1500)
            assertEquals("playing", host.nativeAudioEngine.getSnapshot().state)
            assertTrue(host.nativeAudioEngine.getSnapshot().positionMs > before + 700)
            device.executeShellCommand("cmd media_session dispatch pause")
            await("System Pause must reach the app") { host.nativeAudioEngine.getSnapshot().state == "paused" }
            device.executeShellCommand("cmd media_session dispatch play")
            await("System Play must reach the app") { host.nativeAudioEngine.getSnapshot().state == "playing" }
            assertEquals("p0-system", host.mediaSessionCoreClient.snapshot.value?.track?.id)
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            delay(500)

            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(transient))
            await("Transient focus loss must pause playback") { host.nativeAudioEngine.getSnapshot().state == "paused" }
            manager.abandonAudioFocusRequest(transient)
            await("Abandoning transient focus must resume playback") { host.nativeAudioEngine.getSnapshot().state == "playing" }
            assertEquals(0.42, host.nativeAudioEngine.getSnapshot().volume, 0.0)

            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(transient))
            await("Second transient focus loss must pause playback") { host.nativeAudioEngine.getSnapshot().state == "paused" }
            check(client.executeCommand(CoreTypedCommand.Pause).isSuccess)
            manager.abandonAudioFocusRequest(transient)
            delay(800)
            assertEquals("paused", host.nativeAudioEngine.getSnapshot().state)
            assertEquals(0.42, host.nativeAudioEngine.getSnapshot().volume, 0.0)
        } catch (failure: Throwable) {
            failures += failure
        } finally {
            restore { device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard") }
            restore { manager.abandonAudioFocusRequest(transient) }
            restore {
                if (savedQueue.isEmpty()) clearTestPlaybackQueue(client)
                else {
                    restoreCommand(CoreTypedCommand.Stop)
                    restoreCommand(CoreTypedCommand.ReplaceQueue(tracks = savedQueue,
                        startIndex = original.queue?.index?.coerceAtLeast(0) ?: 0, play = false))
                }
            }
            original.playback?.let {
                restore { restoreCommand(CoreTypedCommand.SetVolume(it.volume)) }
                restore { restoreCommand(CoreTypedCommand.SetPlayMode(PlayMode.fromWireValue(it.playMode))) }
                if (savedQueue.isNotEmpty() && original.track != null) {
                    restore { restoreCommand(CoreTypedCommand.Seek(it.position)) }
                    if (it.state in setOf("idle", "stopped")) restore { restoreCommand(CoreTypedCommand.Stop) }
                }
            }
            restore { if (originalMode is CoreOperatingMode.RemoteDesktopCore) host.selectPlaybackTarget(false).getOrThrow() }
            restore { check(file.delete()) { "Cannot delete system playback fixture: $file" } }
        }
        MultipleFailureException.assertEmpty(failures)
    }

    private suspend fun await(message: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(15000) { while (!condition()) delay(50) }
        } catch (failure: TimeoutCancellationException) {
            val host = AndroidPlaybackHost.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
            throw AssertionError("$message; native=${host.nativeAudioEngine.getSnapshot()}; " +
                "core=${host.phoneLibraryClient.snapshot.value?.playback}", failure)
        }
    }
}
