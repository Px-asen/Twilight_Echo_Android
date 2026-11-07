package com.twilightecho.android.app

import android.media.MediaDataSource
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.adapters.AndroidAudioDeviceAdapter
import com.twilightecho.android.adapters.AndroidResourceAccessAdapter
import com.twilightecho.android.adapters.AndroidSessionStoreAdapter
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import com.twilightecho.android.runtimehost.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

/** Real FFmpeg -> DSP -> AAudio and QuickJS state, with a controllable network starvation. */
@RunWith(AndroidJUnit4::class)
class StreamingBufferRecoveryTest {
    private class NetworkFixture {
        val opens = AtomicInteger()
        val delivered = AtomicInteger()
        private val lock = Object()
        private var allowance = Int.MAX_VALUE
        // 30 s of stereo PCM16, a quiet 440 Hz tone. No external network or account required.
        private val bytes = ByteBuffer.allocate(44 + 30 * 48000 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(48000); putInt(192000); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
            repeat(30 * 48000) { frame ->
                val sample = (sin(frame * 2 * Math.PI * 440 / 48000) * 500).toInt().toShort()
                putShort(sample); putShort(sample)
            }
        }.array()

        fun allow(count: Int) = synchronized(lock) { allowance = count; lock.notifyAll() }
        fun open(): MediaDataSource {
            opens.incrementAndGet()
            return object : MediaDataSource() {
                private var closed = false
                override fun getSize() = bytes.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(lock) {
                    if (position >= bytes.size) return -1
                    while (!closed && allowance == 0) lock.wait()
                    if (closed) throw IOException("fixture cancelled")
                    val count = minOf(size, bytes.size - position.toInt(), allowance)
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    if (allowance != Int.MAX_VALUE) allowance -= count
                    delivered.addAndGet(count)
                    count
                }
                override fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
            }
        }
    }

    @Test fun starvationWaitsForARealReservoirAndDoesNotReopenOrRewind() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse("Run while phone playback is idle", AndroidPlaybackHost.getInstance(context).nativeAudioEngine.isPlaying)
        val network = NetworkFixture()
        val audio = NativeAudioEngineAdapter(appContext = context, providerStream = { network.open() })
        val resources = AndroidResourceAccessAdapter(context)
        val devices = AndroidAudioDeviceAdapter(context)
        val directory = File(context.cacheDir, "buffer-recovery-${System.nanoTime()}").apply { mkdirs() }
        val clock = object : ClockPort {
            override fun nowUnixMs() = System.currentTimeMillis()
            override fun nowMonotonicMs() = android.os.SystemClock.elapsedRealtime()
        }
        val runtime = QuickJsRuntimeHost(context, CoreHostPorts(clock, object : SchedulerPort {
            override fun schedule(delayMs: Long, task: () -> Unit) = CancelHandle {}
        }, AndroidSessionStoreAdapter(directory), resources, audio, audioDevice = devices))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient("buffer-recovery"), runtime, scope)
        suspend fun await(condition: suspend () -> Boolean) = withTimeout(20000) { while (!condition()) delay(25) }
        suspend fun command(command: CoreTypedCommand) { assertTrue(client.executeCommand(command).isSuccess) }
        try {
            runtime.start()
            assertTrue(client.reconcileState().isSuccess)
            command(CoreTypedCommand.ReplaceQueue(tracks = listOf(CoreTrackView("buffer-fixture", title = "Buffer recovery",
                source = "provider://ncm/buffer-fixture", duration = 30.0)), play = true))
            await { client.snapshot.value?.playback?.let { it.state == "playing" && it.position > 0.2 } == true }
            val opens = network.opens.get()
            val occurrence = client.snapshot.value?.queue?.occurrenceId
            network.allow(0)
            await { client.snapshot.value?.playback?.state == "buffering" }
            val stalled = audio.getSnapshot().positionMs
            delay(350)
            assertEquals(stalled, audio.getSnapshot().positionMs)
            val delivered = network.delivered.get()
            network.allow(48000) // Only 250 ms: not enough to restart another audible fragment.
            await { network.delivered.get() >= delivered + 48000 }
            delay(350)
            assertEquals("buffering", client.snapshot.value?.playback?.state)
            assertEquals(stalled, audio.getSnapshot().positionMs)
            network.allow(192000 * 3)
            await { client.snapshot.value?.playback?.state == "playing" }
            await { audio.getSnapshot().positionMs > stalled + 100 }
            assertEquals(opens, network.opens.get())
            assertEquals(occurrence, client.snapshot.value?.queue?.occurrenceId)
            assertNull(client.snapshot.value?.error)
            network.allow(0)
            await { client.snapshot.value?.playback?.state == "buffering" }
            command(CoreTypedCommand.Pause)
            val paused = audio.getSnapshot().positionMs
            network.allow(Int.MAX_VALUE)
            delay(400)
            assertEquals("paused", client.snapshot.value?.playback?.state)
            assertEquals(paused, audio.getSnapshot().positionMs)
            command(CoreTypedCommand.Play())
            await { audio.getSnapshot().let { it.state == "playing" && it.positionMs > paused + 100 } }
            assertEquals(opens, network.opens.get())
            Log.i("StreamingBufferRecovery", "stalledMs=$stalled pausedMs=$paused decoderOpens=$opens occurrence=$occurrence")
        } finally {
            audio.stop()
            runtime.shutdown()
            scope.cancel(); devices.close(); audio.close(); resources.close()
            directory.deleteRecursively()
        }
    }
}
