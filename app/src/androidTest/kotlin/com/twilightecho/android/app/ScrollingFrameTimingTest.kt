package com.twilightecho.android.app

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.CoreLyricLine
import com.twilightecho.android.coreclient.CoreLyricsView
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.BackgroundStyle
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Real Choreographer/FrameMetrics, not the Compose test clock. No audio session or saved library is changed. */
@RunWith(AndroidJUnit4::class)
class ScrollingFrameTimingTest {
    @Test fun decorativeBackgroundStopsWhenPausedAndHasABoundedCadence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = mutableStateOf(PlayerUiState(currentTrack = CoreTrackView("ambient-qa", title = "背景节奏", duration = 120.0),
            durationSeconds = 120.0, isImmersivePlayerOpen = true, isLoadingLibrary = false, homeAvailable = false))
        val frames = AtomicInteger()
        val handlerThread = HandlerThread("ambient-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, _, _ -> frames.incrementAndGet() }
        val result = JSONObject()
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TwilightEchoTheme { PlayerAppContent(ui.value, PlayerActions()) } }
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
                }
                fun sample(milliseconds: Long): Int {
                    frames.set(0)
                    Thread.sleep(milliseconds)
                    return frames.get()
                }
                Thread.sleep(2000) // artwork preparation, entry and finite cover spring
                result.put("pausedFramesOver1500ms", sample(1500))
                scenario.onActivity { ui.value = ui.value.copy(isPlaying = true) }
                Thread.sleep(1800)
                result.put("playingFramesOver2000ms", sample(2000))
                scenario.onActivity { ui.value = ui.value.copy(isPlaying = false) }
                Thread.sleep(1800)
                result.put("pausedAgainFramesOver1500ms", sample(1500))
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            }
        } finally {
            val output = File(context.getExternalFilesDir(null), "scroll-performance").apply { mkdirs() }
            File(output, "background-cadence.json").writeText(result.toString(2))
            handlerThread.quitSafely()
        }
        assertTrue("Paused background must not keep rendering: $result", result.getInt("pausedFramesOver1500ms") <= 2)
        assertTrue("Slow colour motion must stay within 20 updates/s: $result", result.getInt("playingFramesOver2000ms") in 5..45)
        assertTrue("Pausing again must stop the frame loop: $result", result.getInt("pausedAgainFramesOver1500ms") <= 2)
    }

    @Test fun libraryFlingAndImmersiveLyrics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val profileStages = InstrumentationRegistry.getArguments().getString("profileStages") == "true"
        val source = File.createTempFile("scroll-artwork-", ".flac", context.cacheDir)
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val tracks = List(3000) { index -> CoreTrackView("scroll-$index", title = "暮色里的旋律 $index",
            artist = "Paper Lantern", album = "Evening Echoes", duration = 480.0,
            source = if (index == 0) source.absolutePath else null) }
        val ui = mutableStateOf(PlayerUiState(currentTrack = tracks.first(), durationSeconds = 480.0,
            libraryTracks = tracks, allTracks = tracks, libraryTrackCount = tracks.size,
            isLoadingLibrary = false, activeMainTab = MainDestinationTab.LIBRARY, isPlaying = true,
            currentLyrics = CoreLyricsView(tracks.first().id, lines = List(240) { index ->
                CoreLyricLine(index * 1200L, if (index % 7 == 0) "把漫长的句子交给夜晚，让每一个字都自然地舒展开来" else "让所有喧闹轻轻落下 · $index",
                    "Let the evening stay a little longer")
            })))
        val output = File(context.getExternalFilesDir(null), "scroll-performance").apply { mkdirs() }
        val result = JSONObject()
        val handlerThread = HandlerThread("scroll-frame-metrics").apply { start() }
        val frames = Collections.synchronizedList(mutableListOf<LongArray>())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) frames += longArrayOf(
                metrics.getMetric(FrameMetrics.TOTAL_DURATION), metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                metrics.getMetric(FrameMetrics.DRAW_DURATION), metrics.getMetric(FrameMetrics.GPU_DURATION),
                metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION), metrics.getMetric(FrameMetrics.ANIMATION_DURATION),
                metrics.getMetric(FrameMetrics.SYNC_DURATION), metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION))
        }
        val actions = PlayerActions(setPlayerOpen = { ui.value = ui.value.copy(isImmersivePlayerOpen = it) },
            setLyricsMode = { ui.value = ui.value.copy(isLyricsMode = it) })
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TwilightEchoTheme { PlayerAppContent(ui.value, actions) } }
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
                }
                Thread.sleep(2500)
                fun collect(name: String, block: () -> Unit) {
                    frames.clear()
                    block()
                    Thread.sleep(150)
                    val samples = synchronized(frames) { frames.toList() }
                    assertTrue("No rendered frames for $name", samples.size > 20)
                    fun percentile(column: Int, fraction: Double): Double {
                        val sorted = samples.map { it[column] }.sorted()
                        return sorted[((sorted.size - 1) * fraction).toInt()] / 1e6
                    }
                    result.put(name, JSONObject().put("frames", samples.size).put("widthPx", device.displayWidth)
                        .put("heightPx", device.displayHeight).put("totalP50Ms", percentile(0, 0.5))
                        .put("totalP95Ms", percentile(0, 0.95)).put("layoutP95Ms", percentile(1, 0.95))
                        .put("drawP95Ms", percentile(2, 0.95)).put("gpuP95Ms", percentile(3, 0.95))
                        .put("delayP95Ms", percentile(4, 0.95)).put("animationP95Ms", percentile(5, 0.95))
                        .put("syncP95Ms", percentile(6, 0.95)).put("commandP95Ms", percentile(7, 0.95))
                        .put("swapP95Ms", percentile(8, 0.95))
                        .put("over16ms", samples.count { it[0] > 16_666_667L })
                        .put("samplesNs", JSONArray(samples.map { JSONArray(it.toList()) })))
                    Log.i("ScrollPerformance", "$name: ${result.getJSONObject(name).toString().substringBefore("samplesNs")}")
                }
                if (!profileStages) collect("libraryFling") {
                    repeat(6) {
                        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                            device.displayWidth / 2, device.displayHeight / 3, 15)
                        Thread.sleep(200)
                        instrumentation.runOnMainSync { ui.value = ui.value.copy(sequence = ui.value.sequence + 1) }
                    }
                }
                scenario.onActivity { ui.value = ui.value.copy(isImmersivePlayerOpen = true,
                    isLyricsMode = true, activeLyricIndex = 6, displayedPositionSeconds = 7.2) }
                Thread.sleep(6000) // Includes the real five-second entry into full immersion.
                fun playbackTicks() {
                    val start = SystemClock.elapsedRealtime()
                    repeat(80) {
                        val elapsed = (SystemClock.elapsedRealtime() - start) / 1000.0
                        instrumentation.runOnMainSync { ui.value = ui.value.copy(displayedPositionSeconds = 7.2 + elapsed,
                            activeLyricIndex = 6 + (elapsed / 1.2).toInt(), sequence = it.toLong()) }
                        Thread.sleep(100)
                    }
                }
                if (profileStages) {
                    for ((label, cover, blur) in listOf(Triple("coverWithBlur", true, 4f),
                        Triple("solidWithBlur", false, 4f), Triple("solidNoBlur", false, 0f))) {
                        scenario.onActivity { ui.value = ui.value.copy(preferences = ui.value.preferences.let { prefs ->
                            prefs.copy(appearance = prefs.appearance.copy(backgroundStyle = if (cover) BackgroundStyle.COVER else BackgroundStyle.SOLID),
                                lyrics = prefs.lyrics.copy(blurDp = blur))
                        }) }
                        Thread.sleep(800)
                        collect(label, ::playbackTicks)
                    }
                } else collect("immersiveFollow", ::playbackTicks)
                assertTrue(device.takeScreenshot(File(output, "immersive-follow.png")))
                if (!profileStages) collect("lyricsBrowse") {
                    repeat(4) {
                        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                            device.displayWidth / 2, device.displayHeight / 3, 25)
                        Thread.sleep(300)
                    }
                }
                assertTrue(device.takeScreenshot(File(output, "lyrics-browse.png")))
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            }
            File(output, "frames.json").writeText(result.toString(2))
        } finally {
            source.delete()
            handlerThread.quitSafely()
        }
    }
}
