package com.twilightecho.android.plugins

import android.media.MediaDataSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** A resolved stream: the CDN URL and the request headers the plugin's loopback proxy would have sent. */
data class ProviderPlayback(val url: String, val headers: List<Pair<String, String>>)

/** `getPlaybackUrl` as the SDK returns it, `{url, headers: [[name, value]]}`; null when the song has no stream. */
internal fun providerPlayback(value: JsonElement?): ProviderPlayback? {
    val result = value as? JsonObject ?: return null
    val url = (result["url"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: return null
    val headers = (result["headers"] as? JsonArray).orEmpty().mapNotNull { pair ->
        val items = (pair as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content }
        items?.getOrNull(0)?.let { name -> items.getOrNull(1)?.let { name to it } }
    }
    return ProviderPlayback(url, headers)
}

private const val WINDOW = 256 * 1024
private const val MAX_SPOOL = 1024L * 1024 * 1024
private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")
private val EXPIRED = setOf(401, 403, 410)
private val resolver = Executors.newFixedThreadPool(2) { task -> Thread(task, "TwilightProvider-Resolve").apply { isDaemon = true } }

/**
 * Random access to a v2 provider's audio. A server that honours Range is read in 256 KiB windows and must keep its
 * length and strong ETag; one that ignores Range is copied once into a private spool file while playback reads it.
 * An expired signed URL (401/403/410) is resolved again, once per read.
 */
internal class ProviderMediaReader(
    private val resolve: (force: Boolean) -> ProviderPlayback,
    private val open: (playback: ProviderPlayback, range: String, onCall: (Call) -> Unit) -> Response,
    private val spoolDirectory: File
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val call = AtomicReference<Call?>()
    private val resolving = AtomicReference<FutureTask<ProviderPlayback>?>()
    private var playback: ProviderPlayback? = null
    private var length = -1L
    private var etag: String? = null
    private var cacheStart = 0L
    private var cache = ByteArray(0)
    @Volatile private var spool: Spool? = null

    /** -1 while a spooled stream without Content-Length is still downloading. */
    @Synchronized fun size(): Long { started(); return spool?.size() ?: length }

    @Synchronized fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        checkOpen(); require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        started()
        spool?.let { return it.readAt(position, buffer, offset, size) }
        if (size == 0) return 0
        if (position >= length) return -1
        if (position < cacheStart || position >= cacheStart + cache.size) fetch(position)
        val start = (position - cacheStart).toInt(); val count = minOf(size, cache.size - start)
        cache.copyInto(buffer, offset, start, start + count); return count
    }

    private fun started() { if (length < 0 && spool == null) fetch(0) }

    private fun fetch(position: Long) {
        var target = playback ?: resolveNow(false).also { playback = it }
        var renewed = false
        while (true) {
            checkOpen()
            val end = (if (length >= 0) minOf(length, position + WINDOW) else position + WINDOW) - 1
            val response = open(target, "bytes=$position-$end") { call.set(it) }
            var kept = false
            try {
                checkOpen()
                when {
                    response.code in EXPIRED && !renewed -> { renewed = true; target = resolveNow(true).also { playback = it } }
                    response.code == 206 -> { window(response, position); return }
                    response.code == 200 && position == 0L && length < 0 -> { spool = Spool(response); kept = true; return }
                    response.code == 200 -> throw IOException("音源服务器不再支持断点续传，请重新播放")
                    else -> throw IOException("音源返回 HTTP ${response.code}")
                }
            } finally { call.set(null); if (!kept) response.close() }
        }
    }

    private fun window(response: Response, position: Long) {
        val match = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("音频未返回有效 Range")
        val (first, last, total) = match.destructured.toList().map(String::toLong)
        if (first != position || last < first || last >= total) throw IOException("音频 Range 不匹配")
        if (length >= 0 && total != length) throw IOException("音频文件已变化，请重新播放")
        // Weak validators and Last-Modified differ between CDN edges; a strong ETag names the bytes.
        val tag = response.header("ETag")?.takeUnless { it.startsWith("W/") }
        if (etag != null && tag != null && tag != etag) throw IOException("音频文件版本已变化，请重新播放")
        val bytes = ByteArray(minOf(last - first + 1, WINDOW.toLong()).toInt()); var read = 0
        response.body!!.byteStream().use { stream ->
            while (read < bytes.size) {
                checkOpen(); val n = stream.read(bytes, read, bytes.size - read)
                if (n <= 0) throw IOException("音频串流被提前截断")
                read += n
            }
        }
        length = total; etag = etag ?: tag; cacheStart = position; cache = bytes
    }

    /** Provider IPC can take tens of seconds; [close] must not wait for it. */
    private fun resolveNow(force: Boolean): ProviderPlayback {
        val task = FutureTask { resolve(force) }
        resolving.set(task); resolver.execute(task)
        try {
            while (true) {
                checkOpen()
                try { return task.get(50, TimeUnit.MILLISECONDS).also { checkOpen() } }
                catch (_: TimeoutException) { /* poll [closed] */ }
                catch (_: CancellationException) { throw IOException("音频读取已取消") } // only [close] cancels it
                catch (error: ExecutionException) { throw IOException(error.cause?.message ?: "音源地址解析失败", error.cause) }
            }
        } finally { resolving.compareAndSet(task, null); if (!task.isDone) task.cancel(true) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resolving.getAndSet(null)?.cancel(true); call.getAndSet(null)?.cancel(); spool?.close()
    }
    private fun checkOpen() { if (closed.get()) throw IOException("音频读取已取消") }

    /** A body downloaded in the background; reads wait for the bytes they need. */
    private inner class Spool(private val response: Response) {
        private val file = File.createTempFile("stream-", ".spool", spoolDirectory.apply { mkdirs() })
        private val data = RandomAccessFile(file, "rw")
        private val declared = response.body!!.contentLength()
        private val lock = ReentrantLock()
        private val grown = lock.newCondition()
        private var written = 0L
        private var done = false
        private var released = false
        private var failure: IOException? = null

        init {
            if (declared > MAX_SPOOL) { response.close(); release(); throw IOException("音频文件过大") }
            thread(name = "TwilightProvider-Spool", isDaemon = true) {
                var error: IOException? = null
                try {
                    response.body!!.byteStream().use { input ->
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(chunk); if (n < 0) break
                            lock.withLock {
                                if (released) throw IOException("音频读取已取消")
                                if (written + n > MAX_SPOOL) throw IOException("音频文件过大")
                                data.seek(written); data.write(chunk, 0, n); written += n; grown.signalAll()
                            }
                        }
                    }
                    if (declared >= 0 && written != declared) error = IOException("音频串流被提前截断")
                } catch (e: IOException) { error = e }
                finally {
                    response.close()
                    lock.withLock { failure = error; done = true; grown.signalAll(); if (released) release() }
                }
            }
        }

        fun size(): Long = if (declared >= 0) declared else lock.withLock { if (done && failure == null) written else -1 }

        fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = lock.withLock {
            if (size == 0) return 0
            while (written <= position && !done) { checkOpen(); grown.await(100, TimeUnit.MILLISECONDS) }
            checkOpen()
            if (position >= written) { failure?.let { throw it }; return -1 }
            val count = minOf(size.toLong(), written - position).toInt()
            data.seek(position); data.readFully(buffer, offset, count); count
        }

        fun close() {
            response.close()
            lock.withLock { released = true; grown.signalAll(); if (done) release() }
        }
        private fun release() { runCatching { data.close() }; file.delete() }
    }
}

/** The player's view of a [ProviderMediaReader]. */
class ProviderMediaDataSource internal constructor(private val reader: ProviderMediaReader) : MediaDataSource() {
    override fun getSize(): Long = reader.size()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = reader.readAt(position, buffer, offset, size)
    override fun close() = reader.close()
}

/** The enabled v2 plugins' grants, so artwork they list is fetched under the declaring plugin's own rules. */
internal object ProviderMediaGrants {
    private val hosts = ConcurrentHashMap<String, PluginHostApi>()
    fun register(pluginId: String, host: PluginHostApi) { hosts[pluginId] = host }
    fun unregister(pluginId: String) { hosts.remove(pluginId) }
    fun hostFor(url: String): PluginHostApi? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        return hosts.values.firstOrNull { host -> runCatching { host.checkGrant(host.upgrade(parsed)) }.isSuccess }
    }
}
