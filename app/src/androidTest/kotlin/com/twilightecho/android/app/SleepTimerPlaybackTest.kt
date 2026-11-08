package com.twilightecho.android.app

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class SleepTimerPlaybackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val host get() = AndroidPlaybackHost.getInstance(context)
    private val device get() = UiDevice.getInstance(instrumentation)
    private val client get() = host.phoneLibraryClient
    private val timer get() = SleepTimerClient(host.runtimeHost) { client.snapshot.value!!.epoch }

    private suspend fun await(label: String, test: suspend () -> Boolean) {
        withTimeout(15_000) { while (!test()) delay(50) }
        assertTrue(label, test())
    }
    private suspend fun arm(delayMs: Long, finish: Boolean) {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toString()
        timer.configure(timer.state().revision, SleepTimerState(action = "pause", endsAt = System.currentTimeMillis() + delayMs,
            schedule = SleepTimerSchedule(UUID.randomUUID().toString(), "elapsed", SystemClock.elapsedRealtime() + delayMs, boot),
            finishCurrentTrack = finish))
        client.reconcileState()
    }
    private suspend fun scenario(test: suspend () -> Unit) {
        host.awaitStarted()
        assumeTrue("Timer tests need exact-alarm access", host.sleepTimer.canScheduleExact())
        assumeTrue("Timer tests require idle playback", client.snapshot.value?.playback?.state != "playing")
        assumeTrue("An existing timer belongs to the user", timer.state().timer == null)
        val original = client.snapshot.value!!
        val saved = mutableListOf<CoreTrackView>()
        for (offset in 0 until (original.queue?.total ?: 0) step 50)
            saved += client.queryQueueWindow(offset, 50).getOrNull()!!.items.mapNotNull { it.track }
        val file = File.createTempFile("sleep-test-", ".flac", context.cacheDir)
        try {
            instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
            check(client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = listOf(
                CoreTrackView("sleep-one", title = "Sleep one", source = file.absolutePath, duration = 42.0),
                CoreTrackView("sleep-two", title = "Sleep two", source = file.absolutePath, duration = 42.0)), play = false)).isSuccess)
            check(client.executeCommand(CoreTypedCommand.SetVolume(0.42)).isSuccess)
            test()
        } finally {
            device.executeShellCommand("dumpsys deviceidle unforce")
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            timer.cancel(timer.state().revision)
            client.executeCommand(CoreTypedCommand.Pause)
            client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = saved,
                startIndex = (original.queue?.index ?: 0).coerceAtLeast(0), play = false))
            client.executeCommand(CoreTypedCommand.SetVolume(original.playback?.volume ?: 1.0))
            client.executeCommand(CoreTypedCommand.SetPlayMode(PlayMode.fromWireValue(original.playback?.playMode)))
            file.delete()
        }
    }
    @Test fun lockScreenDeadlinePausesOnceAndKeepsPositionVolumeAndQueue() = runBlocking {
        scenario {
            check(host.requestFocusAndPlay(5.0).isSuccess)
            await("Playing") { client.snapshot.value?.playback?.state == "playing" }
            arm(4000, false)
            device.pressHome(); device.sleep()
            device.executeShellCommand("dumpsys deviceidle force-idle")
            await("Paused under lock screen") { client.snapshot.value?.playback?.state == "paused" && client.snapshot.value?.sleepTimer?.timer == null }
            val snapshot = client.snapshot.value!!
            assertEquals(0.42, snapshot.playback!!.volume, 0.001)
            assertTrue(snapshot.playback!!.position >= 5.0)
            assertEquals(2, snapshot.queue?.total)
            assertEquals("sleep-one", snapshot.track?.id)
        }
    }
    @Test fun finishTrackWinsOverSingleRepeatAndNeverSelectsTheSuccessor() = runBlocking {
        scenario {
            check(client.executeCommand(CoreTypedCommand.SetPlayMode(PlayMode.SINGLE)).isSuccess)
            check(host.requestFocusAndPlay(39.0).isSuccess)
            arm(300, true)
            await("Waiting for this track") { client.snapshot.value?.sleepTimer?.timer?.waitingForTrackEnd == true }
            await("Paused at native EOF") { client.snapshot.value?.playback?.state == "paused" && client.snapshot.value?.sleepTimer?.timer == null }
            assertEquals("sleep-one", client.snapshot.value?.track?.id)
            assertEquals(0.42, client.snapshot.value!!.playback!!.volume, 0.001)
            assertTrue(client.snapshot.value!!.playback!!.position >= 41.0)
        }
    }
    @Test fun expiryDuringFocusInterruptionCancelsAutomaticResume() = runBlocking {
        scenario {
            val manager = context.getSystemService(AudioManager::class.java)
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener({}, android.os.Handler(android.os.Looper.getMainLooper())).build()
            check(host.requestFocusAndPlay(5.0).isSuccess)
            arm(1800, false)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(focus))
            try { await("Timer cleared while interrupted") { client.snapshot.value?.sleepTimer?.timer == null } }
            finally { manager.abandonAudioFocusRequest(focus) }
            delay(800)
            assertEquals("paused", client.snapshot.value?.playback?.state)
        }
    }
    @Test fun selectingADesktopNeverRedirectsThePhoneTimer() = runBlocking {
        scenario {
            val commands = CopyOnWriteArrayList<String>()
            val remote = object : CoreTransport {
                override val isRemote = true
                override val stateEvents = emptyFlow<CoreEventMessage>()
                override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
                    if (envelope.kind == "command") commands += envelope.name
                    return CoreResponseEnvelope(requestId = envelope.requestId, epoch = envelope.epoch, ok = false,
                        error = CoreErrorView(CoreErrorCode.NOT_READY, "Test desktop is disconnected"))
                }
                override suspend fun cancelRequest(envelope: CoreCancelEnvelope) { }
            }
            check(host.requestFocusAndPlay(5.0).isSuccess)
            arm(1800, false)
            host.switchableTransport.switchTo(remote, CoreOperatingMode.RemoteDesktopCore("https://sleep-test.invalid", "Test desktop"))
            try {
                await("Phone pauses while UI controls desktop") { client.snapshot.value?.playback?.state == "paused" && client.snapshot.value?.sleepTimer?.timer == null }
                assertTrue("The timer must send no command to the desktop", commands.isEmpty())
            } finally { host.switchableTransport.switchToLocal() }
        }
    }
}
