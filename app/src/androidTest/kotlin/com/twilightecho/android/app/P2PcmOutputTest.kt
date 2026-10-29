package com.twilightecho.android.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.adapters.*
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real QuickJS + JNI/FFmpeg/AAudio; its session and sources never replace the user's session. */
@RunWith(AndroidJUnit4::class)
class P2PcmOutputTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val json = DefaultCoreClient.DefaultJson
    @Volatile private var rates = listOf(96000)

    private inner class Session(directory: File) : AutoCloseable {
        val audio = NativeAudioEngineAdapter(appContext = context, deviceSampleRates = { rates })
        val devices = AndroidAudioDeviceAdapter(context)
        val resources = AndroidResourceAccessAdapter(context)
        val errors = CopyOnWriteArrayList<AudioEvent.EngineError>()
        private val events = audio.subscribe {
            if (it is AudioEvent.SnapshotUpdated) devices.reportPlaybackOutput(it.snapshot)
            if (it is AudioEvent.EngineError) errors += it
        }
        val host = QuickJsRuntimeHost(context, CoreHostPorts(object : ClockPort {
            override fun nowUnixMs() = System.currentTimeMillis()
            override fun nowMonotonicMs() = android.os.SystemClock.elapsedRealtime()
        }, object : SchedulerPort {
            override fun schedule(delayMs: Long, task: () -> Unit) = CancelHandle {}
        }, AndroidSessionStoreAdapter(directory), resources, audio, audioDevice = devices))
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient("p2-pcm"), host, scope)
        suspend fun start() { host.start(); check(client.reconcileState().isSuccess) }
        suspend fun command(command: CoreTypedCommand) { check(client.executeCommand(command).isSuccess) }
        override fun close() {
            runBlocking { host.shutdown() }; scope.cancel(); events.cancel(); devices.close(); audio.close(); resources.close()
        }
    }

    private fun source(directory: File) = File(directory, "source.flac").also { file ->
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @Test fun androidDeviceDeclarationsMatchThePlatformInventory() {
        val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val raw = manager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        assertTrue(raw.isNotEmpty())
        val adapter = AndroidAudioDeviceAdapter(context)
        try {
            raw.forEach { device ->
                assertEquals(device.sampleRates.filter { it in 8000..384000 }.distinct().sorted(),
                    adapter.sampleRatesForOutput(device.id.toString()))
            }
            assertTrue(adapter.sampleRatesForOutput(Int.MAX_VALUE.toString()).isEmpty())
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val media = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
                val route = manager.getAudioDevicesForAttributes(media).firstOrNull()
                val device = raw.firstOrNull { it.type == route?.type && it.address == route?.address }
                assertEquals(device?.sampleRates?.filter { it in 8000..384000 }?.distinct()?.sorted().orEmpty(),
                    adapter.sampleRatesForOutput("auto"))
            }
        } finally { adapter.close() }
    }

    @Test fun familyPolicyRestoresAndReevaluatesLiveCapabilitiesWithoutLosingPause() = runBlocking {
        val directory = File(context.cacheDir, "p2-pcm-${System.nanoTime()}").apply { mkdirs() }
        val file = source(directory)
        try {
            Session(directory).use { first ->
                first.start()
                val config = CoreEqConfigView(bitPerfect = true, dsp = CoreDspConfig(sampleRateFamilySync = true,
                    allowedSampleRates = listOf(48000, 96000)))
                check(first.client.setEqConfig(config).isSuccess)
                first.command(CoreTypedCommand.ReplaceQueue(tracks = listOf(CoreTrackView("p2-source", title = "P2 PCM", source = file.absolutePath, duration = 42.0)), play = false))
                first.command(CoreTypedCommand.Play(6.25))
                await { first.audio.getSnapshot().positionMs > 6500 }
                val playing = first.audio.getSnapshot()
                assertEquals(96000, playing.requestedSampleRate)
                assertTrue(playing.actualSampleRate!! > 0)
                assertFalse(playing.outputPerfect)
                first.command(CoreTypedCommand.Pause)
                first.command(CoreTypedCommand.Seek(6.25))
            }
            rates = listOf(48000)
            Session(directory).use { restored ->
                restored.start()
                var snapshot = restored.client.snapshot.value!!
                assertEquals("paused", snapshot.playback!!.state)
                assertEquals(6.25, snapshot.playback!!.position, 0.025)
                assertTrue(snapshot.eqConfig!!.dsp.sampleRateFamilySync)
                assertEquals(listOf(48000, 96000), snapshot.eqConfig!!.dsp.allowedSampleRates)
                restored.command(CoreTypedCommand.Play(null))
                await { restored.audio.getSnapshot().positionMs > 6500 }
                assertEquals(48000, restored.audio.getSnapshot().requestedSampleRate)
                restored.command(CoreTypedCommand.Pause)
                restored.command(CoreTypedCommand.Seek(6.25))
                rates = listOf(44100)
                try { restored.audio.refreshOutputCapabilities(); fail("disjoint rates must be reported") }
                catch (_: java.io.IOException) { }
                assertTrue(restored.errors.any { it.code == "PCM_SAMPLE_RATE_UNAVAILABLE" })
                assertEquals("paused", restored.audio.getSnapshot().state)
                assertEquals(6250.0, restored.audio.getSnapshot().positionMs.toDouble(), 3.0)
                rates = listOf(96000)
                restored.audio.refreshOutputCapabilities()
                assertEquals("paused", restored.audio.getSnapshot().state)
                assertEquals(96000, restored.audio.getSnapshot().requestedSampleRate)
                assertEquals(6250.0, restored.audio.getSnapshot().positionMs.toDouble(), 3.0)
                restored.client.reconcileState()
                snapshot = restored.client.snapshot.value!!
                assertEquals(restored.audio.getSnapshot().actualSampleRate, snapshot.playback!!.actualSampleRate)
                assertEquals(96000, snapshot.playback!!.requestedSampleRate)
                assertEquals(listOf(48000, 96000), snapshot.eqConfig!!.dsp.allowedSampleRates)
                restored.command(CoreTypedCommand.Play(null))
                await { restored.audio.getSnapshot().positionMs > 6500 }
                restored.command(CoreTypedCommand.Stop)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun fixedTargetAndAllowedSetChangeTogetherAndUnknownCapabilitiesKeepLegacyRequests() = runBlocking {
        val directory = File(context.cacheDir, "p2-fixed-${System.nanoTime()}").apply { mkdirs() }
        val file = source(directory)
        rates = listOf(48000, 96000)
        val audio = NativeAudioEngineAdapter(appContext = context, deviceSampleRates = { rates })
        fun config(rate: Int, allowed: List<Int> = listOf(rate)) = CoreEqConfigView(bitPerfect = true,
            dsp = CoreDspConfig(allowedSampleRates = allowed, outputStage = CoreDspOutputStage(targetSampleRate = rate)))
        try {
            audio.setDspConfig(json.encodeToString(config(48000)))
            audio.load(AudioSourceDescriptor(file.absolutePath, file.absolutePath, startTimeSeconds = 6.25))
            audio.play(null)
            await { audio.getSnapshot().positionMs > 6500 }
            audio.pause(); audio.seek(6250)
            audio.setDspConfig(json.encodeToString(config(96000)))
            assertEquals("paused", audio.getSnapshot().state)
            assertEquals(96000, audio.getSnapshot().requestedSampleRate)
            assertEquals(6250.0, audio.getSnapshot().positionMs.toDouble(), 3.0)
            try { audio.setDspConfig(json.encodeToString(config(48000, listOf(96000)))); fail("invalid fixed set") }
            catch (_: IllegalArgumentException) { }
            assertEquals(96000, audio.getSnapshot().requestedSampleRate)
            rates = emptyList()
            audio.setDspConfig(json.encodeToString(config(88200, emptyList())))
            assertEquals(88200, audio.getSnapshot().requestedSampleRate)
            rates = listOf(48000) // Existing fixed requests are not gated by a new capability declaration.
            audio.refreshOutputCapabilities()
            assertEquals(88200, audio.getSnapshot().requestedSampleRate)
            assertEquals("paused", audio.getSnapshot().state)
        } finally { audio.close(); directory.deleteRecursively() }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(15000) { while (!condition()) delay(50) }
}
