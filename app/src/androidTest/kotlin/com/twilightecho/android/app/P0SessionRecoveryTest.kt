package com.twilightecho.android.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.adapters.AndroidAudioDeviceAdapter
import com.twilightecho.android.adapters.AndroidResourceAccessAdapter
import com.twilightecho.android.adapters.AndroidSessionStoreAdapter
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real QuickJS -> session store -> JNI reconstruction, isolated from the user's saved session. */
@RunWith(AndroidJUnit4::class)
class P0SessionRecoveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private inner class Session(directory: File) : AutoCloseable {
        val audio = NativeAudioEngineAdapter(appContext = context)
        val devices = AndroidAudioDeviceAdapter(context)
        val resources = AndroidResourceAccessAdapter(context)
        private val output = audio.subscribe { if (it is AudioEvent.SnapshotUpdated) devices.reportPlaybackOutput(it.snapshot) }
        private val clock = object : ClockPort {
            override fun nowUnixMs() = System.currentTimeMillis()
            override fun nowMonotonicMs() = android.os.SystemClock.elapsedRealtime()
        }
        val host = QuickJsRuntimeHost(context, CoreHostPorts(clock, object : SchedulerPort {
            override fun schedule(delayMs: Long, task: () -> Unit) = CancelHandle {}
        }, AndroidSessionStoreAdapter(directory), resources, audio, audioDevice = devices))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient("p0-recovery"), host, scope)
        suspend fun start() { host.start(); check(client.reconcileState().isSuccess) }
        suspend fun command(command: CoreTypedCommand) { check(client.executeCommand(command).isSuccess) }
        override fun close() {
            // A restart stops the Core cleanly so its last session save lands first.
            runBlocking { host.shutdown() }; scope.cancel(); output.cancel(); devices.close(); audio.close(); resources.close()
        }
    }

    @Test fun realQuickJsRestoresPositionVolumeAndAudioSettingsPaused() = runBlocking {
        val directory = File(context.cacheDir, "p0-session-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "source.flac")
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            Session(directory).use { first ->
                first.start()
                first.command(CoreTypedCommand.ReplaceQueue(tracks = listOf(
                    CoreTrackView("p0-source", title = "P0 test", source = file.absolutePath, duration = 42.0)), play = false))
                first.command(CoreTypedCommand.SetVolume(0.42))
                first.command(CoreTypedCommand.SetPlayMode(PlayMode.LOOP))
                check(first.client.setEqConfig(CoreEqConfigView(enabled = true, preampDb = -2.0)).isSuccess)
                first.command(CoreTypedCommand.Play(12.75))
                await { first.audio.getSnapshot().positionMs > 13000 }
                first.command(CoreTypedCommand.Pause)
                first.command(CoreTypedCommand.Seek(12750.0 / 1000))
                first.client.reconcileState()
                val p = first.client.snapshot.value!!.playback!!
                assertEquals("float32", p.actualSampleFormat)
                assertEquals(44100, p.requestedSampleRate)
                assertFalse(p.outputPerfect)
                assertEquals(first.devices.getActiveRoute().deviceName, p.actualDeviceName)
                assertTrue(p.eqActive)
            }
            Session(directory).use { restored ->
                restored.start()
                val snapshot = restored.client.snapshot.value!!
                val p = snapshot.playback!!
                assertEquals("p0-source", snapshot.track!!.id)
                assertEquals("paused", p.state)
                assertEquals(12.75, p.position, 0.025)
                assertEquals(0.42, p.volume, 0.0)
                assertEquals("loop", p.playMode)
                assertTrue(snapshot.eqConfig!!.enabled)
                assertEquals(-2.0, snapshot.eqConfig!!.preampDb, 0.0)
                assertNull(p.actualSampleRate)
                assertNull(p.actualDeviceId)
                restored.command(CoreTypedCommand.Play(null))
                await { restored.audio.getSnapshot().positionMs > 13000 }
                restored.command(CoreTypedCommand.Stop)
                restored.client.reconcileState()
                assertNull(restored.client.snapshot.value!!.playback!!.actualSampleRate)
                assertNull(restored.client.snapshot.value!!.playback!!.hardwareSampleRate)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun realQuickJsRestoresCueRangeAndRelativeClockPaused() = runBlocking {
        val directory = File(context.cacheDir, "p1-cue-session-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "source.flac")
        val ranges = listOf(CueRange(0.0, 20.0), CueRange(20.0, 40.0, virtualPregapSeconds = 2.0))
        val tracks = ranges.mapIndexed { index, range -> CoreTrackView("cue-session-$index", title = "Part $index",
            source = file.absolutePath, duration = range.durationSeconds, cueRange = range) }
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            Session(directory).use { first ->
                first.start()
                first.command(CoreTypedCommand.ReplaceQueue(tracks = tracks, startIndex = 1, play = false))
                first.command(CoreTypedCommand.Play(12.75))
                await { first.audio.getSnapshot().positionMs > 13000 }
                assertEquals(22000L, first.audio.getSnapshot().durationMs)
                first.command(CoreTypedCommand.Pause)
                first.command(CoreTypedCommand.Seek(12.75))
                first.client.reconcileState()
                assertEquals(ranges[1], first.client.snapshot.value!!.track!!.cueRange)
            }
            Session(directory).use { restored ->
                restored.start()
                val snapshot = restored.client.snapshot.value!!
                assertEquals("cue-session-1", snapshot.track!!.id)
                assertEquals(file.absolutePath, snapshot.track!!.source)
                assertEquals(ranges[1], snapshot.track!!.cueRange)
                assertEquals("paused", snapshot.playback!!.state)
                assertEquals(12.75, snapshot.playback!!.position, 0.025)
                val window = restored.client.queryQueueWindow(0, 10)
                check(window is CoreResult.Success)
                assertEquals(ranges, window.value.items.map { it.track!!.cueRange })
                restored.command(CoreTypedCommand.Play(null))
                await { restored.audio.getSnapshot().positionMs > 13000 }
                assertEquals(22000L, restored.audio.getSnapshot().durationMs)
                restored.command(CoreTypedCommand.Pause)
                restored.command(CoreTypedCommand.Seek(21.875))
                assertEquals(21875.0, restored.audio.getSnapshot().positionMs.toDouble(), 2.0)
                restored.command(CoreTypedCommand.Seek(22.0))
                assertEquals("paused", restored.audio.getSnapshot().state)
                assertEquals(22000.0, restored.audio.getSnapshot().positionMs.toDouble(), 2.0)
                restored.command(CoreTypedCommand.Play(null))
                try { await { restored.audio.getSnapshot().state == "idle" } }
                catch (error: TimeoutCancellationException) {
                    throw AssertionError("CUE queue did not end: ${restored.audio.getSnapshot()}; core=${restored.client.snapshot.value}", error)
                }
                assertEquals(22000.0, restored.audio.getSnapshot().positionMs.toDouble(), 2.0)
            }
        } finally { directory.deleteRecursively() }
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(15000) { while (!condition()) delay(50) }
    }
}
