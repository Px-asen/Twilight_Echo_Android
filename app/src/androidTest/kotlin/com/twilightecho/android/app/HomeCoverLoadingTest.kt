package com.twilightecho.android.app

import android.graphics.*
import android.os.*
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Cold, delayed covers on the real homepage, with real input and Choreographer (no test clock). */
@RunWith(AndroidJUnit4::class)
class HomeCoverLoadingTest {
    @Test fun coldCoversArriveWhileHomeIsScrolling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val width = device.displayWidth
        val height = device.displayHeight
        val session = SystemClock.elapsedRealtimeNanos()
        val output = File(context.getExternalFilesDir(null), "home-cover-performance").apply { mkdirs() }
        // Original cover assets, varied per cover. Encode before measuring; transport returns native-size images.
        val originals = listOf("01.jpg", "03.jpg").map { name ->
            instrumentation.context.assets.open("music/$name").use { BitmapFactory.decodeStream(it)!! }
        }
        val images = (0 until 24).flatMap { id -> listOf(256, 512).map { size ->
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                val matrix = ColorMatrix().apply { setRotate(id % 3, id * 19f) }
                drawBitmap(originals[id % 2], null, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(matrix)
                })
                drawText("${id + 1}", size * .08f, size * .9f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = size * .2f; typeface = Typeface.DEFAULT_BOLD
                })
            }
            val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it); it.toByteArray() }
            bitmap.recycle()
            (id to size) to bytes
        } }.toMap()
        originals.forEach(Bitmap::recycle)
        val loads = ConcurrentHashMap<String, AtomicInteger>()
        val completed = AtomicInteger()
        val mainLoads = AtomicInteger()
        val loader: suspend (String, Int) -> ByteArray? = { url, size ->
            if (Looper.myLooper() == Looper.getMainLooper()) mainLoads.incrementAndGet()
            val id = url.substringAfterLast('/').toInt()
            loads.getOrPut("$id:$size") { AtomicInteger() }.incrementAndGet()
            delay(450L + id % 6 * 125L)
            images.getValue(id to size).also { completed.incrementAndGet() }
        }
        fun cover(id: Int) = "fixture://home-$session/$id"
        val tracks = List(6) { id -> CoreTrackView("home-$session-track-$id", title = "夜航 $id", artist = "Paper Lantern",
            source = "provider://ncm/$id", cover = cover(id)) }
        val playlists = List(36) { id -> StreamingCollection("$session-$id", "playlist", "晚风与回声 ${id + 1}",
            cover = cover(id % 24), count = 30 + id, owned = id < 18) }
        val state = PlayerUiState(isLoadingLibrary = false, activeMainTab = MainDestinationTab.HOME,
            streaming = StreamingUiState(access = StreamingAccess.READY, nickname = "夜航",
                sections = StreamingRecommendation.entries.associateWith { StreamingSection(tracks, loaded = true) },
                playlists = playlists, libraryLoaded = true, recommendedPlaylists = playlists.reversed()))
        val frames = Collections.synchronizedList(mutableListOf<LongArray>())
        val stalls = Collections.synchronizedList(mutableListOf<Long>())
        val metricsThread = HandlerThread("home-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) frames += longArrayOf(
                metrics.getMetric(FrameMetrics.TOTAL_DURATION), metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                metrics.getMetric(FrameMetrics.DRAW_DURATION), metrics.getMetric(FrameMetrics.SYNC_DURATION),
                metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION), metrics.getMetric(FrameMetrics.GPU_DURATION))
        }
        val main = Handler(Looper.getMainLooper())
        var due = 0L
        val heartbeat = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                stalls += (now - due).coerceAtLeast(0)
                due = now + 16
                main.postAtTime(this, due)
            }
        }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper))
                    due = SystemClock.uptimeMillis() + 16
                    main.postAtTime(heartbeat, due)
                    activity.setContent {
                        CompositionLocalProvider(LocalProviderArtworkData provides loader) {
                            TwilightEchoTheme { PlayerAppContent(state, PlayerActions()) }
                        }
                    }
                }
                Thread.sleep(150)
                // Scroll immediately, before the first 450ms delivery; revisit covers and traverse both axes.
                repeat(3) {
                    device.swipe(width * 5 / 6, height / 4, width * 5 / 36, height / 4, 25)
                    device.swipe(width / 2, height * 3 / 4, width / 2, height * 17 / 48, 30)
                    device.swipe(width * 5 / 6, height * 5 / 12, width * 5 / 36, height * 5 / 12, 25)
                    device.swipe(width / 2, height * 17 / 48, width / 2, height * 3 / 4, 30)
                }
                scenario.onActivity { activity ->
                    activity.window.removeOnFrameMetricsAvailableListener(listener)
                    main.removeCallbacks(heartbeat)
                }
                assertTrue("Fixture must actually deliver images during scrolling", completed.get() >= 8)
                assertTrue(frames.size > 20)
                device.takeScreenshot(File(output, "home-loaded.png"))
            }
            val samples = synchronized(frames) { frames.toList() }
            val delays = synchronized(stalls) { stalls.sorted() }
            fun p(column: Int, fraction: Double) = samples.map { it[column] }.sorted().let {
                it[((it.size - 1) * fraction).toInt()] / 1e6
            }
            val result = JSONObject().put("frames", samples.size).put("totalP50Ms", p(0, .5)).put("totalP95Ms", p(0, .95))
                .put("layoutP95Ms", p(1, .95)).put("drawP95Ms", p(2, .95)).put("syncP95Ms", p(3, .95))
                .put("commandP95Ms", p(4, .95)).put("gpuP95Ms", p(5, .95))
                .put("mainDelayP95Ms", delays[(delays.size - 1) * 95 / 100]).put("mainDelayMaxMs", delays.last())
                .put("mainDelaysOver50ms", delays.count { it > 50 }).put("loads", loads.values.sumOf { it.get() })
                .put("uniqueLoads", loads.size).put("completedLoads", completed.get()).put("loadsStartedOnMain", mainLoads.get())
                .put("samplesNs", JSONArray(samples.map { JSONArray(it.toList()) }))
            File(output, "frames.json").writeText(result.toString(2))
            android.util.Log.i("HomeCoverPerformance", result.toString().substringBefore("samplesNs"))
        } finally {
            main.removeCallbacks(heartbeat)
            metricsThread.quitSafely()
        }
    }
}
