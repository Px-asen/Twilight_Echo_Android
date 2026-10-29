package com.twilightecho.android.coreclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.io.IOException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

sealed interface LibrarySource {
    data object Phone : LibrarySource
    data class Desktop(val serverId: String, val libraryId: String?, val baseUrl: String,
        val hostLabel: String, val connected: Boolean = true) : LibrarySource
}

@Serializable
data class DesktopLibraryInfo(val libraryId: String, val streamFormats: List<String> = emptyList())

/** This is all that enters the phone's queue/session store. Never persist a media ticket or bearer. */
@Serializable
data class DesktopMediaReference(val serverId: String, val libraryId: String, val sourceId: String,
    val sourceVersion: String? = null) {
    fun resourceId(): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(DefaultCoreClient.DefaultJson.encodeToString(this).toByteArray(Charsets.UTF_8))
    companion object {
        const val PREFIX = "desktop-stream:"
        fun parse(value: String): DesktopMediaReference? {
            if (!value.startsWith(PREFIX) || value.length > 2048) return null
            return runCatching { DefaultCoreClient.DefaultJson.decodeFromString<DesktopMediaReference>(
                String(Base64.getUrlDecoder().decode(value.removePrefix(PREFIX)), Charsets.UTF_8)) }.getOrNull()
        }
    }
}

@Serializable
data class DesktopMediaPlan(val libraryId: String, val sourceId: String, val sourceVersion: String,
    val contentPath: String, val contentType: String, val size: Long, val expiresAt: Long,
    val maxRangeBytes: Int)

/** [certificateSha256] is the desktop certificate pinned at pairing; streams go to no other. */
data class AuthorizedDesktopMedia(
    val baseUrl: String,
    val bearer: String,
    val plan: DesktopMediaPlan,
    val certificateSha256: String
)

/** Random-access network bytes go straight to Android's decoder, not through Music Core/JS.
 * A single bounded window supports probes and time seeks without downloading an entire file. */
class DesktopMediaRangeReader(
    private val reference: DesktopMediaReference,
    private val resolve: suspend (DesktopMediaReference) -> AuthorizedDesktopMedia
) : Closeable {
    @Volatile private var closed = false
    private val connection = AtomicReference<Call?>(null)
    private val resolution = AtomicReference<Job?>(null)
    private var access: AuthorizedDesktopMedia? = null
    private var pinnedVersion: String? = reference.sourceVersion
    private var window = ByteArray(0)
    private var windowStart = -1L
    private var acquiredAt = 0L

    private fun plan(renew: Boolean = false): AuthorizedDesktopMedia {
        if (closed) throw IOException("串流已取消")
        val existing = access
        if (!renew && existing != null && System.nanoTime() - acquiredAt < 14L * 60 * 1_000_000_000) return existing
        val job = Job()
        resolution.set(job)
        if (closed) job.cancel()
        val next = try {
            runBlocking(Dispatchers.IO + job) { resolve(reference.copy(sourceVersion = pinnedVersion)) }
        } catch (error: CancellationException) {
            throw IOException("串流已取消", error)
        } finally { resolution.compareAndSet(job, null); job.cancel() }
        if (closed) throw IOException("串流已取消")
        val p = next.plan
        if (p.libraryId != reference.libraryId || p.sourceId != reference.sourceId ||
            p.size <= 0 || p.maxRangeBytes !in 1..262144 ||
            p.contentType !in SUPPORTED_CONTENT_TYPES ||
            !Regex("^/media/[a-zA-Z0-9_-]{24}$").matches(p.contentPath) ||
            (pinnedVersion != null && p.sourceVersion != pinnedVersion))
            throw IOException("电脑音源已变化，请重新选择歌曲")
        require(RemoteNetworkCoreTransport.normalizeBaseUrl(next.baseUrl) == next.baseUrl &&
            Regex("^[0-9a-f]{64}$").matches(next.certificateSha256))
        pinnedVersion = p.sourceVersion
        access = next
        acquiredAt = System.nanoTime()
        return next
    }

    @Synchronized fun size(): Long = plan().plan.size

    @Synchronized fun readAt(position: Long, bytes: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= bytes.size - size)
        if (closed) throw IOException("串流已取消")
        if (size == 0) return 0
        val p = plan().plan
        if (position >= p.size) return -1
        if (windowStart < 0 || position < windowStart || position >= windowStart + window.size) {
            fetchWindow(position, canRenew = true)
        }
        val start = (position - windowStart).toInt()
        val count = minOf(size, window.size - start)
        if (count <= 0) throw IOException("电脑未返回所需的音频数据")
        window.copyInto(bytes, offset, start, start + count)
        return count
    }

    private fun fetchWindow(position: Long, canRenew: Boolean) {
        val a = plan()
        val end = minOf(a.plan.size - 1, position + a.plan.maxRangeBytes - 1)
        val request = pinned(a.certificateSha256).newCall(Request.Builder().url(a.baseUrl + a.plan.contentPath)
            .header("Authorization", "Bearer ${a.bearer}")
            .header("Range", "bytes=$position-$end")
            .header("If-Match", "\"${a.plan.sourceVersion}\"")
            .header("Accept-Encoding", "identity").build())
        connection.set(request)
        try {
            if (closed) throw IOException("串流已取消")
            request.execute().use { response ->
                val status = response.code
                if (status == 404 && canRenew) {
                    response.close(); connection.compareAndSet(request, null)
                    plan(renew = true)
                    fetchWindow(position, canRenew = false)
                    return
                }
                if (status in listOf(409, 412)) throw IOException("电脑上的音频文件已更换，请重新选择歌曲")
                if (status == 401 || status == 403) throw IOException("电脑已撤销授权，请重新连接")
                if (status != 206 || response.header("Content-Range") != "bytes $position-$end/${a.plan.size}" ||
                    response.header("ETag") != "\"${a.plan.sourceVersion}\"")
                    throw IOException("电脑串流读取失败（HTTP $status）")
                val data = ByteArray((end - position + 1).toInt())
                (response.body?.byteStream() ?: throw IOException("电脑未返回音频数据")).use { input ->
                    var count = 0
                    while (count < data.size) {
                        val read = input.read(data, count, data.size - count)
                        if (read < 0) throw IOException("电脑串流中断，请重试")
                        count += read
                    }
                }
                if (closed) throw IOException("串流已取消")
                windowStart = position; window = data
            }
        } finally { connection.compareAndSet(request, null) }
    }

    override fun close() {
        closed = true
        resolution.getAndSet(null)?.cancel()
        connection.getAndSet(null)?.cancel()
    }

    companion object {
        val SUPPORTED_CONTENT_TYPES = setOf(
            "audio/flac",
            "audio/mpeg",
            "audio/mp4",
            "audio/aac",
            "audio/wav",
            "audio/ogg",
            "audio/opus",
            "audio/aiff",
            "audio/x-ape",
            "audio/x-wavpack"
        )
        // Reuse the project's HTTP stack: Call.cancel closes a stalled body immediately.
        private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).build()
        private val pinnedClients = ConcurrentHashMap<String, OkHttpClient>()

        /** One client per desktop certificate, sharing the pool and dispatcher of [http]. */
        private fun pinned(fingerprint: String): OkHttpClient = pinnedClients.getOrPut(fingerprint) {
            val pin = DesktopCertificatePin(fingerprint)
            http.newBuilder().sslSocketFactory(pin.sslSocketFactory, pin.trustManager)
                .hostnameVerifier(pin.hostnameVerifier).build()
        }
    }
}
