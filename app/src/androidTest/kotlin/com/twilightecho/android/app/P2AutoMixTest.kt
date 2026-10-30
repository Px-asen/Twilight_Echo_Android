package com.twilightecho.android.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.AudioSourceDescriptor
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run after pausing user playback: instrumentation restarts the target app. */
@RunWith(AndroidJUnit4::class)
class P2AutoMixTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val json = DefaultCoreClient.DefaultJson
    private suspend fun await(timeout: Long = 20000, condition: suspend () -> Boolean) = withTimeout(timeout) { while (!condition()) delay(25) }
    private fun result(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    @Test fun realModelsPrepareSafFileTransitionAndCancelWithoutBlockingTransport() = transition(null)
    @Test fun realModelsKeepMinimumPhaseAfterSafTransition() = transition("minimum-smooth")

    private fun transition(phase: String?) = runBlocking {
        val directory = File(context.cacheDir, "p2-automix-${System.nanoTime()}").apply { mkdirs() }
        val reports = File(context.getExternalFilesDir(null), if (phase == null) "p2-automix" else "p2-minimum-automix").apply { mkdirs() }
        val next = File(directory, "next.flac").also { file -> instrumentation.context.assets.open("music/02-A-Quiet-Return.flac").use { input -> file.outputStream().use { input.copyTo(it) } } }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "AutoMix-${System.nanoTime()}.flac")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/TwilightEchoTests")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        val audio = NativeAudioEngineAdapter(appContext = context)
        try {
            if (phase != null) audio.setDspConfig(json.encodeToString(CoreEqConfigView(bitPerfect = true,
                dsp = CoreDspConfig(outputStage = CoreDspOutputStage(targetSampleRate = 48000,
                    resamplerQuality = "ultra", resamplerPhase = phase)))))
            resolver.openOutputStream(uri)!!.use { output -> instrumentation.context.assets.open("automix/release.flac").use { it.copyTo(output) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            assertTrue(audio.setAutoMixMode("natural"))
            audio.loadQueue(listOf(CoreTrackView("am-current", title = "Current", source = uri.toString(), duration = 42.0),
                CoreTrackView("am-next", title = "Next", source = next.absolutePath, duration = 42.0)), 0)
            audio.load(AudioSourceDescriptor(uri.toString(), uri.toString()))
            await { audio.getSnapshot().preloadReady }
            resolver.openAssetFileDescriptor(uri, "r")!!.use { asset ->
                val path = "/proc/self/fd/${asset.parcelFileDescriptor.fd}"
                File(reports, "saf-descriptor.txt").writeText("offset=${asset.startOffset}\nlength=${asset.declaredLength}\nstat=${android.system.Os.fstat(asset.fileDescriptor)}\npath=$path\nread=${runCatching { File(path).inputStream().use { it.read() } }}\n")
            }
            val hashes = listOf(uri.toString(), next.absolutePath).mapIndexed { i, source ->
                result(audio.identifyAutoMixSource(source, "identity-$i")).also {
                    File(reports, "identity-$i.json").writeText(it.toString())
                    assertTrue("source[$i]: $it", it["ok"]!!.jsonPrimitive.boolean)
                }["sourceIdentity"]!!.jsonPrimitive.content
            }
            val started = android.os.SystemClock.elapsedRealtime()
            val features = listOf(uri.toString(), next.absolutePath).mapIndexed { i, source ->
                val options = buildJsonObject {
                    put("sourceIdentity", hashes[i]); put("sourceStartSeconds", 0); put("sourceEndSeconds", 42)
                    put("rangeStartSeconds", if (i == 0) 12 else 0)
                }
                result(audio.analyzeAutoMix(source, options.toString(), "analysis-$i")).also {
                    File(reports, "analysis-$i.json").writeText(it.toString())
                    assertTrue(it.toString(), it["ok"]!!.jsonPrimitive.boolean)
                    assertTrue(it["beats"] is JsonArray)
                }
            }
            val analysisMs = android.os.SystemClock.elapsedRealtime() - started
            File(reports, "analysis-timing.txt").writeText("twoWindowAnalysisMs=$analysisMs\n")
            fun request() = buildJsonObject {
                put("ruleVersion", "twilight-rules-5"); put("currentSource", uri.toString()); put("nextSource", next.absolutePath)
                put("currentSourceStartSeconds", 0); put("currentSourceEndSeconds", 42)
                put("nextSourceStartSeconds", 0); put("nextSourceEndSeconds", 42)
                put("currentFeatures", features[0]); put("nextFeatures", features[1])
            }
            val first = result(audio.selectAutoMix(request().toString()))
            File(reports, "selection.json").writeText(first.toString())
            assertTrue(first.toString(), first["ok"]!!.jsonPrimitive.boolean)
            val boundary = first["currentStartSeconds"]!!.jsonPrimitive.double
            audio.seek(((boundary - 3).coerceAtLeast(0.0) * 1000).toLong())
            // Seek revokes the first ticket; selection and preparation bind the new cursor.
            val selection = result(audio.selectAutoMix(request().toString()))
            assertTrue(selection.toString(), selection["ok"]!!.jsonPrimitive.boolean)
            val prepared = JsonObject(request() + ("selectionId" to selection.getValue("selectionId")))
            assertTrue(audio.prepareAutoMix(prepared.toString()))
            await { audio.getSnapshot().autoMixState != "preparing" }
            var ready = audio.getSnapshot()
            File(reports, "ready.txt").writeText(ready.toString())
            assertEquals(ready.toString(), "ready", ready.autoMixState)
            assertTrue(ready.autoMixPlanId > 0)
            assertFalse(audio.submitAutoMix(ready.autoMixPlanId + 1))
            assertTrue(audio.submitAutoMix(ready.autoMixPlanId))
            // POSIX cannot deny other writers. An observed change while Armed
            // must revoke the plan, without changing the paused transport.
            java.io.RandomAccessFile(next, "rw").use { file ->
                file.seek(file.length() - 1); val byte = file.read()
                file.seek(file.length() - 1); file.write(byte xor 1)
                await { audio.getSnapshot().autoMixState == "degraded" }
                assertEquals("source_changed_or_identity_unavailable", audio.getSnapshot().autoMixReason)
                assertEquals("paused", audio.getSnapshot().state)
                File(reports, "source-mutation.txt").writeText(audio.getSnapshot().toString())
                file.seek(file.length() - 1); file.write(byte)
            }
            audio.load(AudioSourceDescriptor(uri.toString(), uri.toString(), startTimeSeconds = (boundary - 3).coerceAtLeast(0.0)))
            await { audio.getSnapshot().preloadReady }
            val replacement = result(audio.selectAutoMix(request().toString()))
            assertTrue(replacement.toString(), replacement["ok"]!!.jsonPrimitive.boolean)
            assertTrue(audio.prepareAutoMix(JsonObject(request() + ("selectionId" to replacement.getValue("selectionId"))).toString()))
            await { audio.getSnapshot().autoMixState != "preparing" }
            ready = audio.getSnapshot()
            assertEquals(ready.toString(), "ready", ready.autoMixState)
            assertTrue(audio.submitAutoMix(ready.autoMixPlanId))
            audio.play(null)
            var mixed = false
            await(25000) {
                val s = audio.getSnapshot()
                mixed = mixed || s.autoMixState == "mixing"
                s.queueIndex == 1 && s.positionMs > 100
            }
            assertTrue("A prepared transition must actually mix, not just advance the queue", mixed)
            if (phase != null) {
                val stage = audio.getDspStatus().outputStage
                File(reports, "phase-after-promotion.json").writeText(stage.toString())
                assertEquals("soxr", stage["effectiveResampler"]?.jsonPrimitive?.content)
                assertEquals(phase, stage["effectiveResamplerPhase"]?.jsonPrimitive?.content)
                assertEquals(false, stage["effectiveResamplerPhaseFallback"]?.jsonPrimitive?.boolean)
                assertEquals(48000, audio.getSnapshot().actualSampleRate)
            }
            audio.pause(); audio.seek(6250)
            val options = buildJsonObject { put("sourceIdentity", hashes[1]); put("sourceStartSeconds", 0); put("sourceEndSeconds", 42); put("rangeStartSeconds", 0) }
            val job = async(Dispatchers.Default) { runCatching { audio.analyzeAutoMix(next.absolutePath, options.toString(), "cancel-live") } }
            delay(100)
            val transportStart = android.os.SystemClock.elapsedRealtime()
            audio.seek(7000)
            val transportMs = android.os.SystemClock.elapsedRealtime() - transportStart
            assertTrue("Analysis must not occupy the transport lane", transportMs < 2000)
            val cancelStart = android.os.SystemClock.elapsedRealtime()
            audio.cancelAutoMixAnalysis("cancel-live")
            val cancelled = withTimeout(5000) { job.await() }
            val cancelMs = android.os.SystemClock.elapsedRealtime() - cancelStart
            assertTrue(cancelled.toString(), cancelled.getOrNull()?.contains("analysis_cancelled") == true || cancelled.exceptionOrNull()?.message == "analysis_cancelled")
            assertEquals("paused", audio.getSnapshot().state)
            assertEquals(7000L, audio.getSnapshot().positionMs)
            audio.setAutoMixMode("off")
            File(reports, "transition.txt").writeText("twoWindowAnalysisMs=$analysisMs\ntransportSeekMs=$transportMs\nanalysisCancelMs=$cancelMs\nready=$ready\nmixed=$mixed\nfinal=${audio.getSnapshot()}\n")
        } finally { audio.close(); resolver.delete(uri, null, null); directory.deleteRecursively() }
    }
}
