package com.twilightecho.android.coreclient

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A request can be cancelled before or after the transport has installed its connection. */
class RangeReadRequest {
    private val cancelled = AtomicBoolean(false)
    private val cancellation = AtomicReference<(() -> Unit)?>(null)

    fun checkActive() { if (cancelled.get()) throw IOException("音频读取已取消") }
    fun onCancel(action: () -> Unit) {
        cancellation.set(action)
        if (cancelled.get()) cancellation.getAndSet(null)?.invoke()
    }
    fun clearCancellation() { cancellation.set(null) }
    internal fun cancel() {
        cancelled.set(true)
        cancellation.getAndSet(null)?.invoke()
    }
}

/**
 * Bounded encoded-byte lookahead. Probes fetch one window; sequential decoding enables
 * background reads, so crossing a Range boundary does not have to wait for another RTT.
 * A seek cancels obsolete IO, and neither stale bytes nor errors can enter its new window.
 */
class BufferedRangeReader(
    private val length: Long,
    private val rangeBytes: Int = 256 * 1024,
    private val windowCount: Int = 8,
    private val readRange: (Long, Int, RangeReadRequest) -> ByteArray
) : Closeable {
    private data class Window(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
        fun contains(position: Long) = position >= start && position < end
    }

    private val lock = Object()
    private val consumer = Any()
    private val windows = ArrayDeque<Window>()
    private var closed = false
    private var generation = 0L
    private var nextPosition = -1L
    private var demandPosition = -1L
    private var sequentialBytes = 0L
    private var prefetch = false
    private var failure: IOException? = null
    private var active: RangeReadRequest? = null
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TwilightAudio-ReadAhead").apply { isDaemon = true }
    }

    init {
        require(length > 0 && rangeBytes > 0 && windowCount >= 2)
        worker.execute(::pump)
    }

    fun readAt(position: Long, bytes: ByteArray, offset: Int, size: Int): Int = synchronized(consumer) {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= bytes.size - size)
        synchronized(lock) {
            checkOpen()
            if (size == 0) return 0
            if (position >= length) return -1
            // A forward seek can land just beyond a full lookahead cache. Free old
            // windows before waiting, otherwise the producer has no slot for demand.
            while (windows.size > 2 && windows.elementAt(2).start <= position) windows.removeFirst()
            val cached = windows.any { it.contains(position) }
            // Only the immediately pending range belongs to this read. A random-access probe
            // or seek must not queue behind a speculative download at the old position.
            val pending = nextPosition >= 0 && position >= nextPosition &&
                position - nextPosition < rangeBytes
            if (!cached && !pending) {
                generation++
                active?.cancel()
                windows.clear()
                nextPosition = position
                sequentialBytes = 0
                prefetch = false
                failure = null
            }
            demandPosition = position
            lock.notifyAll()
            var window = windows.firstOrNull { it.contains(position) }
            while (window == null) {
                checkOpen()
                failure?.let { throw it }
                try { lock.wait() }
                catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("音频读取已取消", error)
                }
                window = windows.firstOrNull { it.contains(position) }
            }
            checkOpen()
            val start = (position - window.start).toInt()
            val count = minOf(size, window.bytes.size - start)
            window.bytes.copyInto(bytes, offset, start, start + count)
            sequentialBytes += count
            if (sequentialBytes >= minOf(32 * 1024, rangeBytes).toLong()) prefetch = true
            // Retain one previous window for small decoder backtracks, but free consumed
            // windows so the downloader can continue without unbounded song caching.
            while (windows.size > 2 && windows.elementAt(2).start <= position + count) windows.removeFirst()
            lock.notifyAll()
            count
        }
    }

    private fun pump() {
        while (true) {
            val request: RangeReadRequest
            val position: Long
            val count: Int
            val origin: Long
            synchronized(lock) {
                while (!closed && !shouldRead()) {
                    try { lock.wait() } catch (_: InterruptedException) { return }
                }
                if (closed) return
                request = RangeReadRequest()
                active = request
                origin = generation
                position = nextPosition
                count = minOf(rangeBytes.toLong(), length - position).toInt()
            }
            var data: ByteArray? = null
            var error: IOException? = null
            try {
                data = readRange(position, count, request)
                request.checkActive()
                if (data.size != count) throw IOException("音频串流被提前截断")
            } catch (cause: Exception) {
                error = cause as? IOException ?: IOException(cause.message ?: "音频串流读取失败", cause)
            } finally { request.clearCancellation() }
            synchronized(lock) {
                if (active === request) active = null
                if (!closed && origin == generation) {
                    if (error != null) failure = error
                    else {
                        windows.addLast(Window(position, checkNotNull(data)))
                        nextPosition = position + count
                    }
                    lock.notifyAll()
                }
            }
        }
    }

    private fun shouldRead(): Boolean = failure == null && nextPosition in 0 until length &&
        windows.size < windowCount && (prefetch ||
        (demandPosition >= nextPosition && demandPosition - nextPosition < rangeBytes))

    private fun checkOpen() { if (closed) throw IOException("音频读取已取消") }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            active?.cancel()
            windows.clear()
            lock.notifyAll()
        }
        worker.shutdownNow()
    }
}
