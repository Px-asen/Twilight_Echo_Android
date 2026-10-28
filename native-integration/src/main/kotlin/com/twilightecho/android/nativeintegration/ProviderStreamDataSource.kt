package com.twilightecho.android.nativeintegration

import android.media.MediaDataSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Independent, cancellable Range reader for public provider CDN bytes. FFmpeg still owns decoding. */
class ProviderStreamDataSource(
    private val resolveUrl: (Boolean) -> String,
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }
) : MediaDataSource() {
    private val closed = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection?>()
    private val resolving = AtomicReference<FutureTask<String>?>()
    private var url: String? = null
    private var length = -1L
    private var version: String? = null
    private var cacheStart = -1L
    private var cache = ByteArray(0)
    override fun getSize(): Long { if (length < 0) range(0, 1); return length }
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        checkOpen(); require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= getSize()) return -1
        if (position < cacheStart || position >= cacheStart + cache.size) range(position, minOf(256 * 1024L, length - position).toInt())
        val start = (position - cacheStart).toInt(); val count = minOf(size, cache.size - start)
        check(count > 0); cache.copyInto(buffer, offset, start, start + count); return count
    }
    private fun range(position: Long, count: Int) {
        checkOpen()
        var target = url ?: resolve(false).also { url = it }
        for (attempt in 0..4) {
            val uri = URI(target)
            require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
                (uri.host?.endsWith(".music.126.net") == true || uri.host?.endsWith(".music.163.com") == true || uri.host == "music.163.com")) { "网易云返回了未授权的音频地址" }
            val conn = openConnection(uri)
            conn.instanceFollowRedirects = false; conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            conn.setRequestProperty("Range", "bytes=$position-${position + count - 1}")
            conn.setRequestProperty("Accept-Encoding", "identity")
            connection.set(conn)
            try {
                checkOpen()
                val status = conn.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    target = uri.resolve(conn.getHeaderField("Location") ?: error("音频重定向缺少地址")).toString(); continue
                }
                if (status == 403 && attempt == 0) {
                    val renewed = resolve(true)
                    if (URI(renewed).path != URI(url!!).path) throw IOException("音频版本已变化，请重新播放此曲")
                    url = renewed; target = renewed; continue
                }
                if (status != 206) throw IOException("网易云音频 Range 请求失败 (HTTP $status)")
                val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(conn.getHeaderField("Content-Range") ?: "") ?: throw IOException("音频未返回有效 Range")
                val (first, last, total) = match.destructured
                require(first.toLong() == position && last.toLong() == position + count - 1 && total.toLong() > last.toLong()) { "音频 Range 不匹配" }
                if (length >= 0 && total.toLong() != length) throw IOException("音频文件已变化，请重新播放")
                val tag = conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified")
                if (version != null && tag != version) throw IOException("音频文件版本已变化")
                length = total.toLong(); version = tag
                val bytes = ByteArray(count); var read = 0
                conn.inputStream.use { stream -> while (read < count) { checkOpen(); val n = stream.read(bytes, read, count - read); if (n <= 0) throw IOException("音频串流被提前截断"); read += n } }
                cacheStart = position; cache = bytes; checkOpen(); return
            } finally { connection.compareAndSet(conn, null); conn.disconnect() }
        }
        throw IOException("音频重定向或续期次数过多")
    }
    private fun resolve(force: Boolean): String {
        val task = FutureTask { resolveUrl(force) }
        resolving.set(task)
        resolverExecutor.execute(task)
        try {
            while (true) {
                checkOpen()
                try { return task.get(50, TimeUnit.MILLISECONDS).also { checkOpen() } }
                catch (_: TimeoutException) { /* close() also cancels a blocked Provider IPC */ }
                catch (error: java.util.concurrent.ExecutionException) { throw IOException(error.cause?.message ?: "音源地址解析失败", error.cause) }
            }
        } finally { resolving.compareAndSet(task, null); if (!task.isDone) task.cancel(true) }
    }
    override fun close() { closed.set(true); resolving.getAndSet(null)?.cancel(true); connection.getAndSet(null)?.disconnect() }
    private fun checkOpen() { if (closed.get()) throw IOException("音频读取已取消") }
    companion object {
        private val resolverExecutor = Executors.newFixedThreadPool(2) { task -> Thread(task, "TwilightProvider-Resolve").apply { isDaemon = true } }
    }
}
