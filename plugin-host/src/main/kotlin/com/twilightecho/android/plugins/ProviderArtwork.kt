package com.twilightecho.android.plugins

import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import java.util.concurrent.atomic.AtomicReference

private data class ProviderArtwork(val bytes: ByteArray, val mimeType: String)
private val covers = object : LruCache<String, ProviderArtwork>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ProviderArtwork) = value.bytes.size
}
private val artworkHttp by lazy { PluginHttp() }
private const val MAX_ARTWORK = 4 * 1024 * 1024
@OptIn(ExperimentalCoroutinesApi::class)
private val artworkDownloadDispatcher = Dispatchers.IO.limitedParallelism(4)
private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private class ArtworkRequest(val result: Deferred<ProviderArtwork?>, var users: Int = 0)
private val requests = mutableMapOf<String, ArtworkRequest>() // guarded by covers, like the byte cache

/** Replace an upstream thumbnail parameter rather than appending a second, ineffective one. */
internal fun providerArtworkUrl(url: String, size: Int): String {
    require(size in 64..2048)
    val uri = url.toHttpUrl()
    require(uri.host.endsWith(".music.126.net") && uri.username.isEmpty() && uri.password.isEmpty())
    require(uri.port == if (uri.isHttps) 443 else 80)
    return uri.newBuilder().scheme("https").port(443).fragment(null)
        .removeAllQueryParameters("param").addQueryParameter("param", "${size}y$size").build().toString()
}

/** Original CDN image bytes: no extra JPEG recompression before display or MediaSession delivery. */
suspend fun providerArtworkData(url: String, size: Int = 1024): ByteArray? = loadProviderArtwork(url, size)?.bytes

suspend fun providerArtworkPreview(url: String, size: Int = 1024): String? = withContext(Dispatchers.Default) {
    loadProviderArtwork(url, size)?.let { "data:${it.mimeType};base64," + b64(it.bytes) }
}

private suspend fun loadProviderArtwork(url: String, size: Int): ProviderArtwork? {
    val ncm = runCatching { providerArtworkUrl(url, size) }.getOrNull()
    val target = ncm ?: url
    val request = synchronized(covers) {
        covers.get(target)?.let { return it }
        requests.getOrPut(target) {
            ArtworkRequest(artworkScope.async(start = CoroutineStart.LAZY) { downloadProviderArtwork(target, ncm != null) })
        }.also { it.users++ }
    }
    return try { request.result.await() } finally {
        synchronized(covers) {
            if (--request.users == 0) {
                requests.remove(target)
                request.result.cancel()
            }
        }
    }
}

private suspend fun downloadProviderArtwork(target: String, ncm: Boolean): ProviderArtwork? = coroutineScope {
    val call = AtomicReference<Call>()
    val owner = currentCoroutineContext()
    val onCall: (Call) -> Unit = { active -> call.set(active); if (!owner.isActive) active.cancel() }
    // Await a child so cancellation can close a blocking socket before waiting for the IO worker to finish.
    // Keep cancellation outside the bounded pool: all four workers can be waiting on sockets.
    val worker = async(artworkDownloadDispatcher) { fetchProviderArtwork(target, ncm, onCall) }
    try { worker.await() } finally { if (!isActive) call.get()?.cancel() }
}

private suspend fun fetchProviderArtwork(target: String, ncm: Boolean, onCall: (Call) -> Unit): ProviderArtwork? {
    try {
        currentCoroutineContext().ensureActive()
        val bytes = if (ncm) artworkHttp.request(target, listOf(target.toHttpUrl().host), maxBytes = MAX_ARTWORK, onCall = onCall).let {
            check(it.status == 200); it.bytes
        } else {
            // A v2 provider's cover loads only from hosts an enabled plugin declared, under that plugin's grants.
            val host = ProviderMediaGrants.hostFor(target) ?: return null
            host.openMedia(target, emptyList(), null, 15_000, onCall).use { response ->
                check(response.code == 200); readCapped(response.body!!.byteStream(), MAX_ARTWORK)
            }
        }
        currentCoroutineContext().ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
        require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp"))
        return ProviderArtwork(bytes, bounds.outMimeType).also { synchronized(covers) { covers.put(target, it) } }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { return null }
}
