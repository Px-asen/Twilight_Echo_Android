package com.twilightecho.android.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.CoreEqConfigView
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CueSource
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.AudioSnapshot
import com.twilightecho.android.runtimehost.AudioSourceDescriptor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** CUE songs cut from one file, through the shared native engine: each keeps to its own part and runs into the next. */
@RunWith(AndroidJUnit4::class)
class P1CueEngineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Sample(val state: String, val index: Int, val positionMs: Long, val durationMs: Long?, val gapless: Boolean)

    @Test fun cueSongsKeepToTheirPartsAndRunOnWithoutAGap() = runBlocking<Unit> {
        val file = File(context.cacheDir, "p1-cue-engine.wav").apply { writeBytes(toneWave(CUE_ALBUM_PARTS)) }
        val adapter = NativeAudioEngineAdapter(appContext = context)
        val samples = CopyOnWriteArrayList<Sample>()
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                adapter.getSnapshot().let { samples += Sample(it.state, it.queueIndex, it.positionMs, it.durationMs, it.gaplessActive) }
                delay(20)
            }
        }
        suspend fun await(condition: (AudioSnapshot) -> Boolean) = try {
            withTimeout(15000) { while (!condition(adapter.getSnapshot())) delay(20) }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Engine never reached the expected state: ${adapter.getSnapshot()}", error)
        }
        fun since(mark: Int) = samples.toList().drop(mark)
        try {
            val tracks = CUE_ALBUM_RANGES.mapIndexed { index, range ->
                CoreTrackView("cue-${index + 1}", title = "Part ${index + 1}", source = CueSource.encode(file.absolutePath, range),
                    duration = range.durationSeconds)
            }
            adapter.setDspConfig(DefaultCoreClient.DefaultJson.encodeToString(CoreEqConfigView.serializer(), CoreEqConfigView()))
            adapter.loadQueue(tracks, 0)
            adapter.load(AudioSourceDescriptor(tracks[0].source!!, tracks[0].source!!))
            adapter.play(0.0)
            await { it.state == "playing" && it.positionMs > 300 }
            assertEquals(0, adapter.getSnapshot().queueIndex)
            assertEquals(8000.0, adapter.getSnapshot().durationMs!!.toDouble(), 30.0)

            // Near the end of the first song the second is ready, and playback runs into it without stopping.
            adapter.seek(6500)
            await { it.queueIndex == 0 && it.positionMs >= 6500 }
            val beforeFirstBoundary = samples.size
            await { it.queueIndex == 1 && it.positionMs > 300 }
            val first = since(beforeFirstBoundary)
            Log.i("P1CueEngineTest", "First boundary: ${first.joinToString { "${it.index}/${it.state}/${it.positionMs}/${it.gapless}" }}")
            assertTrue("The second song is preloaded for a seamless start", first.any { it.index == 0 && it.gapless })
            assertEquals(listOf("playing"), first.map { it.state }.distinct())
            assertTrue("The first song stops at its own end", first.filter { it.index == 0 }.all { it.positionMs <= 8060 })
            assertTrue("The second song starts at its beginning", first.first { it.index == 1 }.positionMs < 1500)
            assertEquals(CUE_ALBUM_RANGES[1].durationSeconds * 1000, adapter.getSnapshot().durationMs!!.toDouble(), 30.0)

            // A seek past a song's end plays the song's last moment, not the rest of the file, and the next song starts.
            val beforeClamp = samples.size
            adapter.seek(30_000)
            adapter.getSnapshot().let { assertTrue("Seeking past the end lands at the song's last moment: $it", it.queueIndex == 2 || it.positionMs in 8150..8493) }
            await { it.queueIndex == 2 && it.positionMs > 300 }
            val clamped = since(beforeClamp)
            assertTrue(clamped.filter { it.index == 1 }.all { it.positionMs <= 8560 })
            assertTrue("The third song starts at its beginning", clamped.first { it.index == 2 }.positionMs < 1500)
            assertEquals(CUE_ALBUM_RANGES[2].durationSeconds * 1000, adapter.getSnapshot().durationMs!!.toDouble(), 30.0)

            // Positions are the song's own, not the file's.
            adapter.seek(2000)
            assertEquals(2, adapter.getSnapshot().queueIndex)
            assertTrue(adapter.getSnapshot().positionMs in 1900..2600)
            adapter.selectQueueIndex(1)
            adapter.play(4.0)
            await { it.queueIndex == 1 && it.state == "playing" && it.positionMs >= 4000 }
            assertTrue(adapter.getSnapshot().positionMs < 5500)

            // A seek to the very end moves on the same way.
            val beforeEndSeek = samples.size
            adapter.seek(adapter.getSnapshot().durationMs!!)
            await { it.queueIndex == 2 && it.positionMs > 300 }
            assertTrue(since(beforeEndSeek).first { it.index == 2 }.positionMs < 1500)

            // The last song ends with the file.
            adapter.selectQueueIndex(2)
            adapter.play(7.0)
            val beforeEnd = samples.size
            await { it.queueIndex == 2 && it.state != "playing" && it.state != "loading" }
            assertTrue(since(beforeEnd).filter { it.index == 2 }.all { it.positionMs <= CUE_ALBUM_RANGES[2].durationSeconds * 1000 + 60 })
            Log.i("P1CueEngineTest", "Queue end: ${adapter.getSnapshot()}")
        } finally {
            sampler.cancelAndJoin()
            adapter.stop()
            adapter.close()
            file.delete()
        }
    }
}
