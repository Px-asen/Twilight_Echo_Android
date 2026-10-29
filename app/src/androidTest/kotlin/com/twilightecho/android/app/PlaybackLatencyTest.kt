package com.twilightecho.android.app

import android.media.MediaDataSource
import android.os.SystemClock
import android.util.Log
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
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/** Real decoder/QuickJS controls. Timings exclude test polling and UI idling. */
@RunWith(AndroidJUnit4::class)
class PlaybackLatencyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val profilePorts get() = InstrumentationRegistry.getArguments().getString("profilePorts") == "true"

    @Test fun resumeKeepsDecoderAndPosition() = runBlocking<Unit> {
        val directory = fixtureDirectory()
        val opens = AtomicInteger()
        val audio = NativeAudioEngineAdapter(appContext = context, desktopStream = {
            opens.incrementAndGet()
            val file = RandomAccessFile(File(directory, "source.flac"), "r")
            object : MediaDataSource() {
                override fun getSize() = file.length()
                @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    file.seek(position)
                    return file.read(buffer, offset, size)
                }
                override fun close() = file.close()
            }
        })
        try {
            audio.loadQueue(listOf(CoreTrackView("latency", title = "Latency", source = "desktop-stream:latency", duration = 42.0)), 0)
            measure("native-first-play") { audio.play(0.0) }
            await { audio.getSnapshot().positionMs > 500 }
            val preparedOpens = opens.get()
            repeat(3) {
                measure("native-pause-$it") { audio.pause() }
                val position = audio.getSnapshot().positionMs
                measure("native-resume-$it") { audio.play(null) }
                val resumed = audio.getSnapshot()
                assertEquals("playing", resumed.state)
                assertTrue("Resume keeps the clock: $resumed", resumed.positionMs >= position - 30)
            }
            Log.i(TAG, "decoder-opens prepared=$preparedOpens after-resume=${opens.get()}")
            assertEquals("Resume must not reopen a stream or resolve its URL again", preparedOpens, opens.get())
            audio.pause()
            audio.play(12.0)
            assertTrue("Play(position) still seeks", audio.getSnapshot().positionMs in 11950..12250)
            audio.stop()
            audio.play(0.0)
            assertTrue("Play after stop prepares a new decoder", opens.get() > preparedOpens)
        } finally {
            audio.stop(); audio.close(); directory.deleteRecursively()
        }
    }

    @Test fun measureCoreControls() = runBlocking {
        val directory = fixtureDirectory()
        val audio = NativeAudioEngineAdapter(appContext = context)
        val resources = AndroidResourceAccessAdapter(context)
        val devices = AndroidAudioDeviceAdapter(context)
        val snapshots = AtomicInteger()
        val queueQueries = AtomicInteger()
        val clock = object : ClockPort {
            override fun nowUnixMs() = System.currentTimeMillis()
            override fun nowMonotonicMs() = SystemClock.elapsedRealtime()
        }
        val measuredAudio = object : AudioEnginePort by audio {
            override suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int, queueGeneration: Long) =
                measure("port-load-queue") { audio.loadQueue(tracks, startIndex, queueGeneration) }
            override suspend fun play(startTimeSeconds: Double?) = measure("port-play") { audio.play(startTimeSeconds) }
            override suspend fun pause() = measure("port-pause") { audio.pause() }
            override suspend fun getSnapshot() = measure("port-snapshot") { audio.getSnapshot() }
            override fun subscribe(listener: (AudioEvent) -> Unit) = audio.subscribe { event ->
                if (event is AudioEvent.SnapshotUpdated) snapshots.incrementAndGet()
                listener(event)
            }
        }
        val store = AndroidSessionStoreAdapter(directory)
        val measuredStore = object : SessionStorePort by store {
            override suspend fun save(payloadJson: String, expectedRevision: String?) =
                measure("port-session-save") { store.save(payloadJson, expectedRevision) }
        }
        val host = QuickJsRuntimeHost(context, CoreHostPorts(clock, object : SchedulerPort {
            override fun schedule(delayMs: Long, task: () -> Unit) = CancelHandle {}
        }, measuredStore, resources, measuredAudio, audioDevice = devices), onRequest = {
            if (it.name == "playback.getQueueWindow") queueQueries.incrementAndGet()
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient("latency"), host, scope)
        suspend fun command(label: String, command: CoreTypedCommand) {
            val initialSnapshots = snapshots.get()
            val initialQueries = queueQueries.get()
            measure(label) { assertTrue("$label failed", client.executeCommand(command).isSuccess) }
            Log.i(TAG, "$label snapshot-events=${snapshots.get() - initialSnapshots} queue-queries=${queueQueries.get() - initialQueries}")
        }
        try {
            host.start()
            assertTrue(client.reconcileState().isSuccess)
            val tracks = listOf("source.flac", "second.flac").mapIndexed { index, name ->
                CoreTrackView("latency-$index", title = name, source = File(directory, name).absolutePath, duration = 42.0)
            }
            command("core-queue", CoreTypedCommand.ReplaceQueue(tracks = tracks, play = false))
            command("core-first-play", CoreTypedCommand.Play())
            await { audio.getSnapshot().positionMs > 300 }
            repeat(3) { index ->
                command("core-pause-$index", CoreTypedCommand.Pause)
                command("core-resume-$index", CoreTypedCommand.Play())
                command("core-next-$index", CoreTypedCommand.Next)
                assertEquals("latency-1", client.snapshot.value?.track?.id)
                command("core-previous-$index", CoreTypedCommand.Previous)
                assertEquals("latency-0", client.snapshot.value?.track?.id)
            }
        } finally {
            host.shutdown(); scope.cancel(); audio.stop(); audio.close(); devices.close(); resources.close()
            directory.deleteRecursively()
        }
    }

    private fun fixtureDirectory(): File = File(context.cacheDir, "latency-${System.nanoTime()}").apply {
        mkdirs()
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input ->
            File(this, "source.flac").outputStream().use { input.copyTo(it) }
        }
        File(this, "source.flac").copyTo(File(this, "second.flac"))
    }

    private suspend fun <T> measure(label: String, action: suspend () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        val result = action()
        if (!label.startsWith("port-") || profilePorts) {
            Log.i(TAG, "$label ${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0} ms")
        }
        return result
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(15000) { while (!condition()) delay(30) }
    }

    companion object { private const val TAG = "PlaybackLatency" }
}
