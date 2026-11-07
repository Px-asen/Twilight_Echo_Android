package com.twilightecho.android.app

import android.util.Log
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.adapters.AndroidResourceAccessAdapter
import com.twilightecho.android.adapters.AndroidTrackMetadataProbe
import com.twilightecho.android.coreclient.CoreEqConfigView
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CueRange
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.AudioSnapshot
import com.twilightecho.android.runtimehost.AudioSourceDescriptor
import com.twilightecho.android.runtimehost.ResourceAccessResult
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

    @Test fun literalFileNamesReachResourcesMetadataAndCuePlayback() = runBlocking {
        val file = File(context.cacheDir, "p1-cue #1?100%+%23 中文.wav").apply { writeBytes(toneWave(CUE_ALBUM_PARTS)) }
        val resources = AndroidResourceAccessAdapter(context)
        val adapter = NativeAudioEngineAdapter(resourceAccess = resources, appContext = context)
        try {
            for (source in listOf(file.absolutePath, Uri.fromFile(file).toString())) {
                val stat = resources.stat(source)
                check(stat is ResourceAccessResult.Ok)
                assertEquals(source, stat.value.resourceId)
                assertEquals(file.name, stat.value.displayName)
                assertEquals(file.length(), stat.value.sizeBytes)
                val read = resources.openReadStream(source)
                check(read is ResourceAccessResult.Ok)
                read.value.use { assertArrayEquals(file.readBytes(), it.readBytes()) }
                val lease = resources.acquirePlayableResource(source)
                check(lease is ResourceAccessResult.Ok)
                assertEquals(source, lease.value.resourceId)
                assertEquals(file.absolutePath, lease.value.nativePath)
                assertEquals(source, (resources.resolveNativeUri(source) as ResourceAccessResult.Ok).value)

                val range = CUE_ALBUM_RANGES[1]
                val track = CoreTrackView("cue-literal-path", title = "Part 2", source = source,
                    cueRange = range, duration = range.durationSeconds)
                val detail = AndroidTrackMetadataProbe(context).inspect(track)
                assertEquals(file.name, detail.fileName)
                assertEquals(file.absolutePath, detail.displayPath)
                assertEquals(file.length(), detail.fileSize)
                assertEquals(44100, detail.sampleRate)
                assertEquals(16, detail.bitDepth)
                assertEquals(2, detail.channels)
                assertEquals(range.durationSeconds, detail.durationSeconds!!, 0.0)

                adapter.loadQueue(listOf(track), 0)
                adapter.load(AudioSourceDescriptor(source, source))
                adapter.play(1.0)
                withTimeout(15000) { while (adapter.getSnapshot().positionMs < 1100) delay(20) }
                assertEquals("playing", adapter.getSnapshot().state)
                assertEquals(file.absolutePath, adapter.currentResolvedNativePath)
                assertEquals(range.durationSeconds * 1000, adapter.getSnapshot().durationMs!!.toDouble(), 30.0)
                adapter.pause()
                adapter.seek(2000)
                assertEquals(2000.0, adapter.getSnapshot().positionMs.toDouble(), 2.0)
                adapter.stop()
            }
        } finally {
            adapter.close()
            resources.close()
            file.delete()
        }
    }

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
                CoreTrackView("cue-${index + 1}", title = "Part ${index + 1}", source = file.absolutePath, cueRange = range,
                    duration = range.durationSeconds)
            }
            adapter.setDspConfig(DefaultCoreClient.DefaultJson.encodeToString(CoreEqConfigView.serializer(), CoreEqConfigView()))
            adapter.loadQueue(tracks, 0)
            adapter.load(AudioSourceDescriptor(tracks[0].source!!, tracks[0].source!!))
            adapter.play(0.0)
            await { it.state == "playing" && it.positionMs > 300 }
            assertEquals(0, adapter.getSnapshot().queueIndex)
            assertEquals(8000.0, adapter.getSnapshot().durationMs!!.toDouble(), 30.0)

            val invalid = tracks.toMutableList().apply { this[0] = first().copy(cueRange = CueRange(8.0, 1.0)) }
            assertTrue(runCatching { adapter.loadQueue(invalid, 0) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals("Invalid segment metadata keeps the current decoder running", "playing", adapter.getSnapshot().state)
            assertEquals(0, adapter.getSnapshot().queueIndex)

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

            // A seek past the segment clamps to its exact end and advances, without a 300 ms replay.
            val beforeClamp = samples.size
            adapter.seek(30_000)
            adapter.getSnapshot().let { assertTrue("Seeking past the end lands at the segment boundary: $it", it.queueIndex == 2 || it.positionMs in 8480..8493) }
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

            // A paused seek keeps the exact requested last 125 ms, and EOF
            // advances only after the user resumes.
            adapter.pause()
            val secondDuration = adapter.getSnapshot().durationMs!!
            adapter.seek(secondDuration - 125)
            assertEquals(secondDuration - 125.0, adapter.getSnapshot().positionMs.toDouble(), 2.0)
            adapter.seek(secondDuration)
            assertEquals("paused", adapter.getSnapshot().state)
            assertEquals(1, adapter.getSnapshot().queueIndex)
            assertEquals(secondDuration.toDouble(), adapter.getSnapshot().positionMs.toDouble(), 2.0)
            val beforeEndSeek = samples.size
            adapter.play(null)
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
