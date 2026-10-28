package com.twilightecho.android.plugins

import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

private data class ProviderArtwork(val bytes: ByteArray, val mimeType: String)
private val covers = object : LruCache<String, ProviderArtwork>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ProviderArtwork) = value.bytes.size
}
private val artworkHttp by lazy { PluginHttp() }

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

suspend fun providerArtworkPreview(url: String, size: Int = 1024): String? =
    loadProviderArtwork(url, size)?.let { "data:${it.mimeType};base64," + b64(it.bytes) }

private suspend fun loadProviderArtwork(url: String, size: Int): ProviderArtwork? = withContext(Dispatchers.IO) {
    try {
        val target = providerArtworkUrl(url, size)
        synchronized(covers) { covers.get(target) }?.let { return@withContext it }
        val response = artworkHttp.request(target, listOf(target.toHttpUrl().host), maxBytes = 4 * 1024 * 1024)
        check(response.status == 200)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(response.bytes, 0, response.bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
        require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp"))
        ProviderArtwork(response.bytes, bounds.outMimeType).also { synchronized(covers) { covers.put(target, it) } }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }
}
