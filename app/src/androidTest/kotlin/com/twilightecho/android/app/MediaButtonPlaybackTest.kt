package com.twilightecho.android.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.CoreResult
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CoreTypedCommand
import com.twilightecho.android.coreclient.PlayMode
import com.twilightecho.android.mediasession.TwilightMediaSessionService
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Dispatches the same system media key events used by wired headsets and Bluetooth AVRCP. */
@RunWith(AndroidJUnit4::class)
class MediaButtonPlaybackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val host get() = AndroidPlaybackHost.getInstance(context)
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun mediaKeysControlNativePlaybackWithTheScreenOff() = withPlayback {
        device.pressHome()
        device.sleep()

        press(KeyEvent.KEYCODE_HEADSETHOOK)
        awaitState("paused")
        press(KeyEvent.KEYCODE_HEADSETHOOK)
        awaitState("playing")
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        awaitState("paused")
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        awaitState("playing")
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        awaitState("playing")
        press(KeyEvent.KEYCODE_MEDIA_NEXT)
        await("Next must reach Music Core") { host.phoneLibraryClient.snapshot.value?.track?.id == "media-key-2" }
        assertEquals("playing", host.nativeAudioEngine.getSnapshot().state)
        press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        await("Previous must reach Music Core") { host.phoneLibraryClient.snapshot.value?.track?.id == "media-key-1" }
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        awaitState("paused")
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        delay(400)
        assertEquals("paused", host.nativeAudioEngine.getSnapshot().state)
    }

    @Test fun headsetHookResumesAfterTheSessionServiceWasReleased() = resumeReleasedSession(KeyEvent.KEYCODE_HEADSETHOOK)

    @Test fun playKeyResumesAfterTheSessionServiceWasReleased() = resumeReleasedSession(KeyEvent.KEYCODE_MEDIA_PLAY)

    @Test fun playPauseKeyResumesAfterTheSessionServiceWasReleased() = resumeReleasedSession(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    private fun resumeReleasedSession(keyCode: Int) = withPlayback {
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        awaitState("paused")
        val seek = host.phoneLibraryClient.executeCommand(CoreTypedCommand.Seek(12.75))
        check(seek.isSuccess) { "Seek failed: $seek" }
        val position = host.nativeAudioEngine.getSnapshot().positionMs
        device.pressHome()
        await("Activity must release its controller") { rule.activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED }
        assertTrue(context.stopService(Intent(context, TwilightMediaSessionService::class.java)))
        await("Session service must be released before the key event") { platformSession() == null }

        press(keyCode)
        awaitState("playing")
        await("Headset Play must recreate the system session") { platformSession() != null }
        assertEquals("media-key-1", host.phoneLibraryClient.snapshot.value?.track?.id)
        val resumedPosition = host.nativeAudioEngine.getSnapshot().positionMs
        assertTrue("Resume must keep the paused position: $position -> $resumedPosition",
            resumedPosition >= position - 50 && resumedPosition < position + 2500)
    }

    private fun withPlayback(block: suspend () -> Unit) = runBlocking {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        await("Activity must be visible for the initial user Play") { device.currentPackageName == context.packageName }
        val client = host.phoneLibraryClient
        host.awaitStarted()
        val reconciled = client.reconcileState()
        check(reconciled.isSuccess) { "Phone session query failed: $reconciled" }
        val original = client.snapshot.value!!
        assumeTrue("Run while phone playback is idle", original.playback?.state != "playing")
        val oldMode = host.operatingMode.value
        val queue = mutableListOf<CoreTrackView>()
        for (offset in 0 until (original.queue?.total ?: 0) step 50) {
            val page = client.queryQueueWindow(offset, 50)
            check(page is CoreResult.Success) { "Saved queue query failed: $page" }
            queue += page.value.items.mapNotNull { it.track }
        }
        val files = listOf("01-Golden-Hour.flac", "02-A-Quiet-Return.flac", "03-Blue-Hours.flac").map { name ->
            File.createTempFile("media-key-", ".flac", context.cacheDir).also { file ->
                instrumentation.context.assets.open("music/$name").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
        }
        try {
            host.switchToStandaloneLocalCore().getOrThrow()
            val replace = client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = (0..2).map {
                CoreTrackView("media-key-$it", title = "Media key track $it", source = files[it].absolutePath, duration = 42.0)
            }, startIndex = 1, play = false))
            check(replace.isSuccess) { "ReplaceQueue failed: $replace" }
            val mode = client.executeCommand(CoreTypedCommand.SetPlayMode(PlayMode.SEQUENCE))
            check(mode.isSuccess) { "SetPlayMode failed: $mode" }
            await("System session must expose the selected track") {
                platformSession()?.metadata?.description?.mediaId == "media-key-1"
            }
            platformSession()!!.transportControls.play()
            awaitState("playing")
            block()
        } finally {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            client.executeCommand(CoreTypedCommand.Stop)
            client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = queue,
                startIndex = original.queue?.index?.coerceAtLeast(0) ?: 0, play = false))
            original.playback?.let {
                client.executeCommand(CoreTypedCommand.SetVolume(it.volume))
                client.executeCommand(CoreTypedCommand.SetPlayMode(PlayMode.fromWireValue(it.playMode)))
                client.executeCommand(CoreTypedCommand.Seek(it.position))
            }
            if (oldMode is CoreOperatingMode.RemoteDesktopCore) host.selectPlaybackTarget(false)
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            files.forEach { it.delete() }
        }
    }

    private fun press(keyCode: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now + 30, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun platformSession(): MediaController? {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
        return try {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
                .firstOrNull { it.packageName == context.packageName }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private suspend fun awaitState(state: String) = await("Native playback must become $state") {
        host.nativeAudioEngine.getSnapshot().state == state && host.phoneLibraryClient.snapshot.value?.playback?.state == state
    }

    private suspend fun await(message: String, condition: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError("$message; native=${host.nativeAudioEngine.getSnapshot()}; core=${host.phoneLibraryClient.snapshot.value?.playback}")
            }
            delay(50)
        }
    }
}
