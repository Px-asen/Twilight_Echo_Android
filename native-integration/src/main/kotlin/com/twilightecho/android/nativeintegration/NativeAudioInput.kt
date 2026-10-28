package com.twilightecho.android.nativeintegration

import android.media.MediaDataSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Decode-worker JNI input. Each native decoder gets its own reader, never audio-thread IO. */
class NativeAudioInput internal constructor(
    private val source: MediaDataSource,
    private val removed: (NativeAudioInput) -> Unit,
    private val failed: (Exception) -> Unit
) {
    private val closed = AtomicBoolean(false)
    fun size(): Long = read { source.size }
    fun readAt(position: Long, bytes: ByteArray, offset: Int, count: Int): Int = read {
        source.readAt(position, bytes, offset, count)
    }
    private inline fun <T> read(action: () -> T): T = try {
        checkOpen()
        action()
    } catch (error: Exception) {
        failed(error)
        throw error
    }
    fun close() {
        if (closed.compareAndSet(false, true)) { try { source.close() } finally { removed(this) } }
    }
    private fun checkOpen() { if (closed.get()) throw IOException("音频读取已取消") }
}

class NativeAudioInputProvider(private val create: (String) -> MediaDataSource?) {
    private class Attempt { @Volatile var error: Exception? = null }
    private val inputs = ConcurrentHashMap.newKeySet<NativeAudioInput>()
    private val attempts = ConcurrentHashMap<String, Attempt>()
    private val generation = AtomicLong()
    @Volatile private var accepting = true
    fun open(source: String): NativeAudioInput? {
        val origin = generation.get()
        if (!accepting) throw IOException("音频读取已取消")
        val attempt = Attempt()
        attempts[source] = attempt
        if (!accepting || origin != generation.get()) {
            attempts.remove(source, attempt)
            throw IOException("音频读取已取消")
        }
        val data = try { create(source) } catch (error: Exception) {
            attempt.error = error
            throw error
        } ?: run { attempts.remove(source, attempt); return null }
        val input = NativeAudioInput(data, {
            inputs.remove(it)
            if (attempt.error == null) attempts.remove(source, attempt)
        }, { attempt.error = it })
        inputs.add(input)
        if (!accepting || origin != generation.get()) { input.close(); throw IOException("音频读取已取消") }
        return input
    }
    // JNI must clear a Java IO exception before returning a decoder error. Retain its actual
    // cause per source so authorization/network failures do not become "FFmpeg open failed".
    fun failure(source: String?): Exception? = source?.let { attempts[it]?.error }
    fun resume() { accepting = true }
    fun cancelAll() {
        accepting = false
        generation.incrementAndGet()
        attempts.clear()
        inputs.toList().forEach { it.close() }
    }
}
