package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.ui.components.LocalAmbientArtwork
import com.twilightecho.android.ui.components.TrackArtworkVisual
import com.twilightecho.android.ui.components.rememberTrackArtwork
import com.twilightecho.android.ui.components.rememberByteArtwork
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ArtworkPerformanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun currentCoverIsSharedByTheThemeDockAndPlayerWhileLoadingAndAfterDecode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File.createTempFile("shared-cover-", ".flac", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val root = AtomicReference<TrackArtworkVisual>()
        val dock = AtomicReference<TrackArtworkVisual>()
        val player = AtomicReference<TrackArtworkVisual>()
        try {
            rule.setContent {
                val cover = rememberTrackArtwork("shared", "Song", "Artist", source.absolutePath)
                CompositionLocalProvider(LocalAmbientArtwork provides cover) {
                    val first = rememberTrackArtwork("shared", "Song", "Artist", source.absolutePath)
                    val second = rememberTrackArtwork("shared", "Song", "Artist", source.absolutePath)
                    val thumbnail = rememberTrackArtwork("shared", "Song", "Artist", source.absolutePath, maxSize = 256)
                    SideEffect {
                        assertSame(cover, first)
                        assertSame(cover, second)
                        assertSame("A thumbnail of the current track should reuse the full cover", cover, thumbnail)
                        root.set(cover); dock.set(first); player.set(second)
                    }
                }
            }
            rule.waitUntil(10000) { root.get()?.isRealEmbeddedArt == true }
            assertSame(root.get().imageBitmap, dock.get().imageBitmap)
            assertSame(root.get().imageBitmap, player.get().imageBitmap)
        } finally { source.delete() }
    }

    @Test fun missingCoversReuseOnePlaceholderBitmap() {
        rule.setContent {
            val first = rememberTrackArtwork("missing-one", "One", "Artist", null)
            val second = rememberTrackArtwork("missing-two", "Two", "Artist", null)
            SideEffect {
                assertFalse(first.isRealEmbeddedArt)
                assertNotEquals(first.cacheKey, second.cacheKey)
                assertSame(first.imageBitmap, second.imageBitmap)
            }
        }
        rule.waitForIdle()
    }

    @Test fun scrollingThumbnailsDecodeSmallWithoutPoisoningTheFullSizeCache() {
        val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff6c7f91.toInt()) }
        val data = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }
        bitmap.recycle()
        val thumbnail = AtomicReference<TrackArtworkVisual>()
        val full = AtomicReference<TrackArtworkVisual>()
        rule.setContent {
            val small = rememberTrackArtwork("sized-artwork", "Song", "Artist", null,
                previewDataUrl = data, previewOnly = true, maxSize = 256)
            val large = rememberTrackArtwork("sized-artwork", "Song", "Artist", null,
                previewDataUrl = data, previewOnly = true)
            SideEffect { thumbnail.set(small); full.set(large) }
        }
        rule.waitUntil(10000) { thumbnail.get()?.isRealEmbeddedArt == true && full.get()?.isRealEmbeddedArt == true }
        assertEquals(256, thumbnail.get().imageBitmap.width)
        assertEquals(1024, full.get().imageBitmap.width)
        assertNotSame(thumbnail.get().imageBitmap, full.get().imageBitmap)
    }

    private fun imageBytes(): ByteArray {
        val image = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffb34685.toInt()) }
        return ByteArrayOutputStream().use { output ->
            image.compress(Bitmap.CompressFormat.PNG, 100, output)
            image.recycle()
            output.toByteArray()
        }
    }

    @Test fun byteConsumersShareThePendingLoadDecodeAndBitmapWithoutThumbnailPalettes() {
        val data = imageBytes()
        val key = "shared-bytes-${System.nanoTime()}"
        val calls = AtomicInteger()
        val small = AtomicReference<List<TrackArtworkVisual>>()
        val full = AtomicReference<TrackArtworkVisual>()
        var firstVisible by mutableStateOf(true)
        rule.setContent {
            val views = buildList {
                if (firstVisible) add(rememberByteArtwork(key, 256, extractColors = false) {
                    calls.incrementAndGet(); delay(500); data
                })
                repeat(7) {
                    add(rememberByteArtwork(key, 256, extractColors = false) {
                        calls.incrementAndGet(); delay(500); data
                    })
                }
            }
            val large = rememberByteArtwork(key) { data }
            SideEffect { small.set(views); full.set(large) }
        }
        rule.waitUntil(5000) { calls.get() > 0 }
        // Recycling the original tile must not cancel a request still needed by seven others.
        rule.runOnIdle { firstVisible = false }
        rule.waitUntil(10000) { small.get()?.all { it.isRealEmbeddedArt } == true && full.get()?.isRealEmbeddedArt == true }
        assertEquals(1, calls.get())
        small.get().forEach {
            assertSame(small.get().first().imageBitmap, it.imageBitmap)
            assertEquals(256, it.imageBitmap.width)
            assertNull("Image-only tiles must not calculate Monet", it.seedColor)
        }
        assertEquals(1024, full.get().imageBitmap.width)
        assertNotNull("Full covers retain their theme seed", full.get().seedColor)
    }

    @Test fun leavingAllConsumersCancelsTheLoadAndTheNextVisitCanRetry() {
        val data = imageBytes()
        val key = "cancel-bytes-${System.nanoTime()}"
        val calls = AtomicInteger()
        val cancelled = AtomicInteger()
        val latest = AtomicReference<TrackArtworkVisual>()
        var visible by mutableStateOf(true)
        rule.setContent {
            if (visible) {
                val art = rememberByteArtwork(key, 256, extractColors = false) {
                    if (calls.incrementAndGet() == 1) try { awaitCancellation() } finally { cancelled.incrementAndGet() }
                    else data
                }
                SideEffect { latest.set(art) }
            }
        }
        rule.waitUntil(5000) { calls.get() == 1 }
        rule.runOnIdle { visible = false }
        rule.waitUntil(5000) { cancelled.get() == 1 }
        rule.runOnIdle { visible = true }
        rule.waitUntil(10000) { latest.get()?.isRealEmbeddedArt == true }
        assertEquals(2, calls.get())
    }

    @Test fun failedByteLoadsAreNotCachedAsPermanentMissingArtwork() {
        val data = imageBytes()
        val key = "retry-bytes-${System.nanoTime()}"
        val calls = AtomicInteger()
        val latest = AtomicReference<TrackArtworkVisual>()
        var visible by mutableStateOf(true)
        rule.setContent {
            if (visible) {
                val art = rememberByteArtwork(key, 256, extractColors = false) {
                    if (calls.incrementAndGet() == 1) throw java.io.IOException("temporary CDN failure") else data
                }
                SideEffect { latest.set(art) }
            }
        }
        rule.waitUntil(5000) { calls.get() == 1 }
        rule.runOnIdle { visible = false }
        rule.runOnIdle { visible = true }
        rule.waitUntil(10000) { latest.get()?.isRealEmbeddedArt == true }
        assertEquals(2, calls.get())
    }
}
