package com.twilightecho.android.app

import android.media.MediaDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twilightecho.android.nativeintegration.NativeAudioInputProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class NativeAudioInputFailureTest {
    @Test fun createSizeAndReadErrorsKeepTheirOriginalCausePerSource() {
        val opening = IOException("电脑授权已失效，请重新连接")
        val sizing = IOException("电脑文件版本已改变")
        val reading = IOException("电脑音频读取超时")
        val provider = NativeAudioInputProvider { source ->
            if (source == "open") throw opening
            object : MediaDataSource() {
                override fun getSize(): Long { if (source == "size") throw sizing; return 42 }
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int { throw reading }
                override fun close() = Unit
            }
        }
        try {
            assertSame(opening, runCatching { provider.open("open") }.exceptionOrNull())
            val size = provider.open("size")!!
            assertSame(sizing, runCatching { size.size() }.exceptionOrNull())
            val read = provider.open("read")!!
            assertSame(reading, runCatching { read.readAt(0, ByteArray(4), 0, 4) }.exceptionOrNull())
            size.close(); read.close() // FFmpeg releases a failed decoder before emitting its error.
            assertSame(opening, provider.failure("open"))
            assertSame(sizing, provider.failure("size"))
            assertSame(reading, provider.failure("read"))
            assertNull(provider.failure("a-different-preload"))
        } finally { provider.cancelAll() }
        assertNull(provider.failure("open"))
        assertNull(provider.failure("size"))
        assertNull(provider.failure("read"))
    }

    @Test fun reopeningClearsOldErrorsAndAnOldDecoderCannotOverwriteTheNewAttempt() {
        val stale = IOException("旧解码器读取失败")
        val provider = NativeAudioInputProvider {
            object : MediaDataSource() {
                override fun getSize() = 42L
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int { throw stale }
                override fun close() = Unit
            }
        }
        try {
            val old = provider.open("same")!!
            runCatching { old.readAt(0, ByteArray(4), 0, 4) }
            assertSame(stale, provider.failure("same"))
            val fresh = provider.open("same")!!
            assertNull(provider.failure("same"))
            runCatching { old.readAt(0, ByteArray(4), 0, 4) }
            old.close()
            assertNull(provider.failure("same"))
            assertEquals(42L, fresh.size())
        } finally { provider.cancelAll() }
    }

    @Test fun cancellationDoesNotRetainALateOpenFailure() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>()
        val failure = IOException("被取消的请求结束")
        val provider = NativeAudioInputProvider { started.countDown(); check(release.await(5, TimeUnit.SECONDS)); throw failure }
        val worker = thread { thrown.set(runCatching { provider.open("cancelled") }.exceptionOrNull()) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            provider.cancelAll()
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertSame(failure, thrown.get())
            assertNull(provider.failure("cancelled"))
        } finally { release.countDown(); worker.join(5000); provider.cancelAll() }
    }
}
