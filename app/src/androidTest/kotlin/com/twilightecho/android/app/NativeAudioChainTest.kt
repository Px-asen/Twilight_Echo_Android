package com.twilightecho.android.app

import android.content.ContentValues
import android.media.MediaDataSource
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.AudioSourceDescriptor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NativeAudioChainTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun nativeQueuePreloadsIndependentContentDescriptorAndSeeksAfterPromotion() = runBlocking {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "Twilight-native-test-${System.nanoTime()}.flac")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/TwilightEchoTests")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        val file = File.createTempFile("native-chain-", ".flac", context.cacheDir)
        val adapter = NativeAudioEngineAdapter(appContext = context)
        try {
            instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
            resolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            adapter.loadQueue(listOf(
                CoreTrackView("native-file", source = file.absolutePath, title = "File", duration = 42.0),
                CoreTrackView("native-content", source = uri.toString(), title = "Content", duration = 42.0)), 0)
            adapter.load(AudioSourceDescriptor(file.absolutePath, file.absolutePath))
            delay(200)
            assertEquals("paused", adapter.getSnapshot().state)
            assertEquals(0L, adapter.getSnapshot().positionMs)
            adapter.play(0.0)
            await { adapter.getSnapshot().positionMs > 300 }
            val output = adapter.getSnapshot()
            assertTrue(output.actualSampleRate!! > 0)
            assertEquals("float32", output.actualSampleFormat)
            assertEquals(44100, output.requestedSampleRate)
            assertFalse(output.outputPerfect)
            adapter.setVolume(0.4)
            adapter.setDucked(true)
            assertEquals(0.4, adapter.getSnapshot().volume, 0.0)
            adapter.setVolume(0.6)
            adapter.setDucked(false)
            assertEquals(0.6, adapter.getSnapshot().volume, 0.0)
            adapter.seek(41500)
            await { adapter.getSnapshot().let { it.queueIndex == 1 && it.positionMs > 300 && it.state == "playing" } }
            assertEquals("oboe", adapter.getSnapshot().outputBackend)
            adapter.pause()
            adapter.pause()
            assertEquals("paused", adapter.getSnapshot().state)
            adapter.seek(12000)
            assertTrue(adapter.getSnapshot().positionMs in 11900..12100)
            adapter.selectQueueIndex(0)
            assertEquals("paused", adapter.getSnapshot().state)
            assertEquals(0L, adapter.getSnapshot().positionMs)
            assertNull(adapter.getSnapshot().actualDeviceId)
            assertNull(adapter.getSnapshot().hardwareSampleRate)
            adapter.play(null)
            await { adapter.getSnapshot().positionMs > 500 }
            assertEquals(0, adapter.getSnapshot().queueIndex)
            adapter.loadQueue(listOf(CoreTrackView("replacement", source = uri.toString(), title = "Replacement", duration = 42.0)), 0)
            delay(200)
            assertEquals("paused", adapter.getSnapshot().state)
            assertEquals(0L, adapter.getSnapshot().positionMs)
            adapter.play(0.0)
            await { adapter.getSnapshot().positionMs > 300 }
            adapter.stop()
            assertEquals("idle", adapter.getSnapshot().state)
            assertNull(adapter.getSnapshot().actualSampleRate)
            assertNull(adapter.getSnapshot().actualDeviceId)
        } finally { adapter.stop(); adapter.close(); resolver.delete(uri, null, null); file.delete() }
    }

    @Test fun stopCancelsNativePreparationBeforeWaitingForControlThread() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val closes = AtomicInteger()
        val adapter = NativeAudioEngineAdapter(appContext = context, desktopStream = {
            object : MediaDataSource() {
                override fun getSize() = 100000L
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    entered.countDown()
                    if (!cancelled.await(5, TimeUnit.SECONDS)) throw IOException("test read timed out")
                    throw IOException("cancelled")
                }
                override fun close() { closes.incrementAndGet(); cancelled.countDown() }
            }
        })
        try {
            adapter.loadQueue(listOf(CoreTrackView("blocked", source = "desktop-stream:blocked", title = "Blocked")), 0)
            val preparing = async(Dispatchers.Default) { runCatching { adapter.play(0.0) } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val before = System.nanoTime()
            withTimeout(2000) { adapter.stop() }
            assertTrue((System.nanoTime() - before) / 1_000_000 < 2000)
            assertTrue(preparing.await().isFailure)
            assertEquals("idle", adapter.getSnapshot().state)
            assertEquals(1, closes.get())
        } finally { cancelled.countDown(); adapter.stop(); adapter.close() }
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(15000) { while (!condition()) delay(50) }
    }
}
