package com.twilightecho.android.app

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.adapters.AndroidResourceAccessAdapter
import com.twilightecho.android.adapters.AndroidSessionStoreAdapter
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class P2AutoMixCoreTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val now get() = android.os.SystemClock.elapsedRealtime()
    private suspend fun await(timeout: Long = 20000, condition: suspend () -> Boolean) = withTimeout(timeout) { while (!condition()) delay(50) }
    private inner class Session(private val directory: File) : AutoCloseable {
        private val isolatedContext = object : ContextWrapper(context) {
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val audio = NativeAudioEngineAdapter(appContext = isolatedContext)
        val analyses = CopyOnWriteArrayList<String>()
        val cancellations = CopyOnWriteArrayList<String>()
        val results = CopyOnWriteArrayList<String>()
        val submissions = CopyOnWriteArrayList<Long>()
        val active = AtomicReference<String?>()
        // Trace the real port without substituting models, native status or ACKs.
        private val traced = object : AudioEnginePort by audio {
            override suspend fun analyzeAutoMix(source: String, optionsJson: String, requestId: String): String {
                analyses += requestId; active.set(requestId)
                return try { audio.analyzeAutoMix(source, optionsJson, requestId).also { results += it } }
                finally { active.compareAndSet(requestId, null) }
            }
            override fun cancelAutoMixAnalysis(requestId: String) { cancellations += requestId; audio.cancelAutoMixAnalysis(requestId) }
            override suspend fun submitAutoMix(planId: Long) = audio.submitAutoMix(planId).also { if (it) submissions += planId }
        }
        private val resources = AndroidResourceAccessAdapter(context)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = QuickJsRuntimeHost(context, CoreHostPorts(object : ClockPort {
            override fun nowUnixMs() = System.currentTimeMillis()
            override fun nowMonotonicMs() = now
        }, object : SchedulerPort {
            override fun schedule(delayMs: Long, task: () -> Unit) = CancelHandle {}
        }, AndroidSessionStoreAdapter(File(directory, "session")), resources, traced))
        val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient("p2-automix-core"), host, scope)
        suspend fun start() { host.start(); check(client.reconcileState().isSuccess) }
        suspend fun command(command: CoreTypedCommand) {
            val result = client.executeCommand(command)
            assertTrue(result.toString(), result.isSuccess)
        }
        suspend fun mode(mode: String) = command(CoreTypedCommand.SettingsEdit(key = "autoMixMode", valueJson = JsonPrimitive(mode).toString()))
        suspend fun playback(): CorePlaybackView { check(client.reconcileState().isSuccess); return client.snapshot.value!!.playback!! }
        override fun close() { runBlocking { host.shutdown() }; scope.cancel(); audio.close(); resources.close() }
    }

    @Test fun realQuickJsCancelsOnSeparateLanePreparesAndRestoresOnlyCachedFacts() = runBlocking {
        val directory = File(context.cacheDir, "p2-automix-core-${System.nanoTime()}").apply { mkdirs() }
        val reports = File(context.getExternalFilesDir(null), "p2-automix").apply { mkdirs() }
        fun fixture(asset: String, name: String) = File(directory, name).also { file ->
            instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val current = fixture("automix/release-long.flac", "current.flac")
        val next = fixture("music/02-A-Quiet-Return.flac", "next.flac")
        try {
            Session(directory).use { s ->
                s.start(); assertEquals("off", s.playback().autoMixMode)
                s.command(CoreTypedCommand.ReplaceQueue(tracks = listOf(
                    CoreTrackView("core-am-current", title = "Controlled release", source = current.absolutePath, duration = 84.0),
                    CoreTrackView("core-am-next", title = "Incoming", source = next.absolutePath, duration = 42.0)), play = false))
                s.mode("natural"); s.command(CoreTypedCommand.Play(0.0))
                await { s.active.get() != null }
                val id = s.active.get()!!; val pauseStart = now
                s.command(CoreTypedCommand.Pause)
                val pauseMs = now - pauseStart
                assertTrue("Pause must overtake analysis: ${pauseMs}ms", pauseMs < 2000)
                s.command(CoreTypedCommand.Seek(7.0))
                await(5000) { s.active.get() == null }
                assertTrue(s.cancellations.toString(), id in s.cancellations)
                assertTrue(s.results.toString(), s.results.any { it.contains("analysis_cancelled") })
                val paused = s.playback(); assertEquals("paused", paused.state); assertEquals(7.0, paused.position, .025)
                s.command(CoreTypedCommand.Play(null))
                // Native Ready precedes the coordinator's exact-ID submit ACK.
                await(90000) { s.playback().autoMixState == "ready" && s.submissions.isNotEmpty() }
                val ready = s.playback()
                assertEquals("content-boundary", ready.autoMixStrategy)
                assertTrue(ready.autoMixPlanId > 0)
                assertEquals(listOf(ready.autoMixPlanId), s.submissions.toList())
                assertEquals("ready", s.audio.getSnapshot().autoMixState)
                assertFalse(ready.crossfadeMixActive); assertFalse(ready.outputPerfect)
                s.command(CoreTypedCommand.Pause); s.command(CoreTypedCommand.Seek(7.0))
                File(reports, "quickjs-prepared.txt").writeText("pauseDuringAnalysisMs=$pauseMs\nanalyses=${s.analyses.size}\nready=$ready\n")
            }
            Session(directory).use { s ->
                s.start()
                val restored = s.playback()
                assertEquals("natural", restored.autoMixMode)
                assertEquals("paused", restored.state); assertEquals(7.0, restored.position, .025)
                assertEquals(0L, restored.autoMixPlanId)
                s.command(CoreTypedCommand.Play(null))
                await { s.playback().autoMixState == "ready" && s.submissions.isNotEmpty() }
                assertTrue("Persisted windows should avoid fresh model work", s.analyses.isEmpty())
                assertEquals(1, s.submissions.size)
                s.command(CoreTypedCommand.Pause); s.command(CoreTypedCommand.Seek(7.0))
                val before = s.client.snapshot.value!!.eqConfig!!
                val strict = before.copy(bitPerfect = true, dsp = before.dsp.copy(usbPcmPassthrough = true))
                assertTrue(s.client.setEqConfig(strict).isFailure)
                assertTrue(s.client.reconcileState().isSuccess)
                assertEquals(before, s.client.snapshot.value!!.eqConfig)
                assertEquals("natural", s.playback().autoMixMode)
                s.mode("off"); s.client.setEqConfig(strict).getOrThrow()
                val rejected = s.client.executeCommand(CoreTypedCommand.SettingsEdit(key = "autoMixMode", valueJson = "\"natural\""))
                assertFalse(rejected.toString(), rejected.isSuccess)
                assertEquals("off", s.playback().autoMixMode)
                assertEquals("paused", s.playback().state)
                s.client.setEqConfig(before).getOrThrow()
                File(reports, "quickjs-restored.txt").writeText("restored=$restored\ncacheAnalyses=${s.analyses.size}\nsubmissions=${s.submissions}\nfinal=${s.playback()}\n")
            }
        } finally { directory.deleteRecursively() }
    }
}
