package com.twilightecho.android.ui.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Trace
import android.util.LruCache
import android.util.Size
import android.util.Base64
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Immutable
data class TrackArtworkVisual(
    val cacheKey: String,
    val imageBitmap: ImageBitmap,
    val dominantColor: Color,
    val secondaryColor: Color,
    val isRealEmbeddedArt: Boolean,
    /** Monet seed for theming; null when the image has no usable color. */
    val seedColor: Color? = null
)

private object ArtworkMemoryCache {
    private val lock = Any()
    private val cache = object : LruCache<String, TrackArtworkVisual>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: TrackArtworkVisual) =
            if (value.isRealEmbeddedArt) value.imageBitmap.width * value.imageBitmap.height * 4 else 256
    }
    private class Pending(val result: Deferred<TrackArtworkVisual>, var users: Int = 0)
    private val pending = mutableMapOf<String, Pending>()
    private val scope = CoroutineScope(SupervisorJob() + ArtworkDecodeDispatcher)
    fun get(key: String): TrackArtworkVisual? = synchronized(lock) { cache.get(key) }

    /** A request belongs to all visible consumers, not whichever tile happened to start it first. */
    suspend fun load(key: String, cacheMissing: Boolean, decode: suspend () -> TrackArtworkVisual): TrackArtworkVisual {
        val request = synchronized(lock) {
            cache.get(key)?.let { return it }
            pending.getOrPut(key) {
                Pending(scope.async(start = CoroutineStart.LAZY) {
                    val visual = decode()
                    ensureActive()
                    if (visual.isRealEmbeddedArt || cacheMissing) synchronized(lock) { cache.put(key, visual) }
                    visual
                })
            }.also { it.users++ }
        }
        return try { request.result.await() } finally {
            synchronized(lock) {
                if (--request.users == 0) {
                    pending.remove(key)
                    // Recycled tiles leave no queued decoder/palette work behind.
                    request.result.cancel()
                }
            }
        }
    }
}

// A fling can compose many covers at once. Keep decoder/palette work from saturating CPU and allocating in parallel.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val ArtworkDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)

/** [coverUri] is an image file beside the audio, used when the file holds no picture of its own. */
@Composable
fun rememberTrackArtwork(trackId: String?, title: String?, artist: String?, sourceUri: String?,
    albumId: String? = null, previewDataUrl: String? = null, previewOnly: Boolean = false,
    cachePreview: Boolean = true, coverUri: String? = null, maxSize: Int = 1024,
    extractColors: Boolean = true): TrackArtworkVisual {
    val context = LocalContext.current.applicationContext
    val sourceKey = "${trackId.orEmpty()}|${sourceUri.orEmpty()}|${albumId.orEmpty()}|$previewOnly|${previewDataUrl?.hashCode()}|${coverUri.orEmpty()}"
    return rememberArtworkVisual(sourceKey, maxSize, extractColors, cachePreview, cacheMissing = true) {
        if (previewOnly) decodeRemotePreview(previewDataUrl, maxSize)
        else loadEmbeddedOrAlbumBitmap(context, sourceUri, albumId, coverUri, maxSize)
    }
}

/** Native image bytes stay off the composition/main thread; identity does not change when a response arrives. */
@Composable
fun rememberByteArtwork(imageKey: String, maxSize: Int = 1024, extractColors: Boolean = true,
    load: suspend () -> ByteArray?): TrackArtworkVisual =
    rememberArtworkVisual("bytes|$imageKey", maxSize, extractColors, cachePreview = true, cacheMissing = false) {
        val bytes = try { load() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        bytes?.let { decodeCover(it, maxSize) }
    }

@Composable
private fun rememberArtworkVisual(sourceKey: String, maxSize: Int, extractColors: Boolean,
    cachePreview: Boolean, cacheMissing: Boolean, load: suspend () -> Bitmap?): TrackArtworkVisual {
    require(maxSize in 64..2048)
    val key = sourceKey + (if (maxSize == 1024) "" else "|size=$maxSize") + (if (extractColors) "" else "|imageOnly")
    // The app's palette, backdrop, dock and full-screen cover share the current artwork.
    // Reuse the parent's in-flight result as well as its cached image, instead of opening
    // and decoding the same file separately at every call site during a track change.
    LocalAmbientArtwork.current?.takeIf { it.cacheKey == key || maxSize < 1024 && it.cacheKey == sourceKey }?.let { return it }
    val placeholder = remember(key) {
        (if (cachePreview) ArtworkMemoryCache.get(key) else null) ?: placeholderCover(key)
    }
    var visual by remember(key) { mutableStateOf(placeholder) }
    LaunchedEffect(key) {
        val cached = if (cachePreview) ArtworkMemoryCache.get(key) else null
        if (cached != null) {
            visual = cached
        } else {
            val decode: suspend () -> TrackArtworkVisual = {
                val bitmap = load()
                if (bitmap != null) {
                    try {
                        currentCoroutineContext().ensureActive()
                        val colors = if (extractColors) artworkColors(bitmap) else placeholder.dominantColor to placeholder.secondaryColor
                        val seed = if (extractColors) artworkTrace("Artwork:palette") {
                            com.twilightecho.android.ui.theme.Monet.seedFromBitmap(bitmap)
                        } else null
                        currentCoroutineContext().ensureActive()
                        // Start the texture upload on the worker, before the image's first scrolling frame.
                        artworkTrace("Artwork:prepare") { bitmap.prepareToDraw() }
                        TrackArtworkVisual(key, bitmap.asImageBitmap(), colors.first, colors.second, true, seed)
                    } catch (error: CancellationException) {
                        // This bitmap has not been published or cached; do not wait for GC after a fast fling.
                        bitmap.recycle()
                        throw error
                    }
                } else placeholder
            }
            // Missing embedded art is cached; transient network failures can be retried on the next visit.
            visual = if (cachePreview) ArtworkMemoryCache.load(key, cacheMissing, decode)
                else withContext(ArtworkDecodeDispatcher) { decode() }
        }
    }
    return visual
}

private object BlurredArtworkCache {
    private val cache = object : LruCache<String, ImageBitmap>(512 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    fun get(key: String): ImageBitmap? = synchronized(cache) { cache.get(key) }
    fun put(key: String, image: ImageBitmap) = synchronized(cache) { cache.put(key, image) }
}

/** A static artwork blur, prepared off the UI thread and shared by API 26+ devices. */
@Composable
fun BlurredCoverBackdrop(artwork: TrackArtworkVisual, immersiveBoost: Boolean = true,
    modifier: Modifier = Modifier, motionEnabled: Boolean = true) {
    BlurredArtworkBackdrop(artwork, motionEnabled, modifier.testTag("lyricsBlurredBackdrop"),
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = if (immersiveBoost) 0.52f else 0.64f),
            0.45f to Color.Black.copy(alpha = 0.56f),
            0.72f to Color.Black.copy(alpha = 0.76f),
            1f to Color.Black.copy(alpha = 0.96f)))
}

/** The background extends the cover's colors; the separate foreground image shows every edge. */
@Composable
fun ImmersiveCoverBackdrop(artwork: TrackArtworkVisual, motionEnabled: Boolean = true,
    modifier: Modifier = Modifier) {
    BlurredArtworkBackdrop(artwork, motionEnabled, modifier.testTag("immersiveCoverBackdrop"),
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.56f),
            0.45f to Color.Black.copy(alpha = 0.56f),
            0.72f to Color.Black.copy(alpha = 0.76f),
            1f to Color.Black.copy(alpha = 0.96f)))
}

/**
 * Apple Music-style living background: four pieces of the blurred cover, each stretched far past
 * the screen and turning on its own slow orbit, so the cover's colours drift into each other.
 * Each orbiting piece fades out radially before its square edge, so no edge ever sweeps across the
 * screen as a band. Animation values are read only while drawing; with [motionEnabled] off it holds still.
 * [dim] lays an even dark veil for text over the cover (0 = none). Independently, very bright covers are
 * toned down inside the colour matrix, so white type stays readable without a grey film.
 */
@Composable
fun FlowingCoverBackdrop(artwork: TrackArtworkVisual, modifier: Modifier = Modifier,
    motionEnabled: Boolean = true, dim: Float = 0.30f) {
    val blurred = rememberBlurredArtwork(artwork, light = true)
    val pieces by produceState<FlowingArtwork?>(null, blurred) {
        value = blurred?.let { withContext(Dispatchers.Default) { prepareFlowingArtwork(it) } }
    }
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motionEnabled) {
        if (!motionEnabled) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            // A 48-second colour drift needs at most 20 updates/s, not a full-screen render every vsync.
            // Delay before requesting a frame so a quiet screen can actually stop between updates.
            delay(50)
            withFrameNanos { now ->
                phase.floatValue = (phase.floatValue + (now - previous) / 48_000_000_000f) % 1f
                previous = now
            }
        }
    }
    val lum = artwork.dominantColor.luminance()
    val filter = remember(lum) {
        // Cap the average linear luminance near 0.22; the scale is applied to gamma-encoded channels.
        val scale = Math.pow((0.22 / lum.coerceAtLeast(0.01f)).coerceAtMost(1.0), 1 / 2.2).toFloat()
        ColorFilter.colorMatrix(ColorMatrix().apply {
            setToSaturation(1.45f)
            timesAssign(ColorMatrix().apply { setToScale(scale, scale, scale, 1f) })
        })
    }
    val strength = (dim / 0.26f).coerceIn(0f, 1f)
    val lift = (dim + lum * 0.34f * strength).coerceIn(0f, 0.78f)
    BoxWithConstraints(modifier.background(artwork.dominantColor)) {
        // This is already a heavily blurred colour field. Rasterize at a bounded resolution, then
        // upscale once, rather than shading four oversized cover textures at display resolution.
        val rasterScale = (maxOf(constraints.maxWidth, constraints.maxHeight) / 384f).coerceAtLeast(1f)
        val rasterSize = Modifier.requiredSize(maxWidth / rasterScale, maxHeight / rasterScale).graphicsLayer {
            scaleX = rasterScale; scaleY = rasterScale
            transformOrigin = TransformOrigin(0f, 0f)
            compositingStrategy = CompositingStrategy.Offscreen
        }
        Crossfade(pieces, rasterSize, tween(if (motionEnabled) 600 else 0), label = "flowArtwork") { images ->
            if (images != null) Canvas(Modifier.fillMaxSize()) {
                val t = phase.floatValue * 2f * PI.toFloat()
                val w = size.width; val h = size.height
                val span = maxOf(w, h)
                fun piece(image: ImageBitmap, cx: Float, cy: Float, extent: Float, turns: Float, alpha: Float) {
                    val side = span * extent
                    withTransform({ rotate(t * turns * 57.29578f, Offset(cx, cy)) }) {
                        drawImage(image, IntOffset.Zero, IntSize(image.width, image.height),
                            IntOffset((cx - side / 2).toInt(), (cy - side / 2).toInt()),
                            IntSize(side.toInt(), side.toInt()), alpha = alpha, colorFilter = filter)
                    }
                }
                // Whole cover underneath (large enough to cover every corner), then three quadrants orbiting at unrelated speeds.
                piece(images.base, w * 0.5f, h * 0.5f, 1.9f, 1f, 1f)
                piece(images.topLeft, w * (0.28f + 0.10f * cos(t)), h * (0.26f + 0.06f * sin(t)), 1.25f, -2f, 0.86f)
                piece(images.bottomRight, w * (0.74f + 0.10f * sin(t)), h * (0.70f + 0.07f * cos(t)), 1.35f, 1f, 0.78f)
                piece(images.topRight, w * (0.78f + 0.08f * cos(t)), h * (0.30f + 0.08f * sin(t)), 1.1f, -1f, 0.62f)
            }
        }
        // One even veil: darkening towards the bottom would turn the cover into a colour-to-black gradient.
        if (lift > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (lift + 0.12f * strength).coerceAtMost(0.9f))))
    }
}

@Immutable
private data class FlowingArtwork(val base: ImageBitmap, val topLeft: ImageBitmap,
    val bottomRight: ImageBitmap, val topRight: ImageBitmap)

/** Bake the rotationally symmetric mask once at thumbnail size, not into three screen-sized layers per frame. */
private fun prepareFlowingArtwork(image: ImageBitmap): FlowingArtwork {
    val source = image.asAndroidBitmap()
    val halfWidth = (source.width / 2).coerceAtLeast(1)
    val halfHeight = (source.height / 2).coerceAtLeast(1)
    val side = 160
    val bounds = android.graphics.Rect(0, 0, side, side)
    val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(side / 2f, side / 2f, side / 2f,
            intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    fun feather(left: Int, top: Int): ImageBitmap {
        val tile = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(tile).apply {
            drawBitmap(source, android.graphics.Rect(left, top, left + halfWidth, top + halfHeight), bounds, paint)
            drawRect(0f, 0f, side.toFloat(), side.toFloat(), mask)
        }
        return tile.asImageBitmap()
    }
    return FlowingArtwork(image, feather(0, 0), feather(halfWidth, halfHeight), feather(halfWidth, 0))
}

/** [light] keeps more of the cover's shapes, for the moving player backdrop; the default is a deep, still blur. */
@Composable
internal fun rememberBlurredArtwork(artwork: TrackArtworkVisual, light: Boolean = false): ImageBitmap? {
    val key = if (artwork.isRealEmbeddedArt) "${artwork.cacheKey}:true:$light" else "placeholder:$light"
    val blurred by produceState(BlurredArtworkCache.get(key), key, artwork.imageBitmap) {
        value = BlurredArtworkCache.get(key) ?: withContext(Dispatchers.Default) {
            val source = artwork.imageBitmap.asAndroidBitmap()
            (if (light) blurArtwork(source, sample = 160, radius = 4, passes = 2) else blurArtwork(source)).asImageBitmap()
        }.also { BlurredArtworkCache.put(key, it) }
    }
    return blurred
}

@Composable
private fun BlurredArtworkBackdrop(artwork: TrackArtworkVisual, motionEnabled: Boolean,
    modifier: Modifier, scrim: Brush) {
    val blurred = rememberBlurredArtwork(artwork)
    Box(modifier.background(artwork.dominantColor)) {
        Crossfade(blurred, Modifier.fillMaxSize(), tween(if (motionEnabled) 220 else 0), label = "blurredArtwork") { image ->
            image?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

/** Repeated separable box passes approximate a Gaussian. The small bitmap is scaled by Image,
 * avoiding a screen-size filter on every playback frame and RenderEffect's API 31 restriction. */
private fun blurArtwork(source: Bitmap, sample: Int = 128, radius: Int = 6, passes: Int = 3): Bitmap {
    val scale = sample.toFloat() / maxOf(source.width, source.height)
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    val sampled = Bitmap.createScaledBitmap(source, width, height, true)
    var pixels = IntArray(width * height)
    sampled.getPixels(pixels, 0, width, 0, 0, width, height)
    if (sampled !== source) sampled.recycle()
    val diameter = radius * 2 + 1
    fun pass(input: IntArray, horizontal: Boolean): IntArray {
        val output = IntArray(input.size)
        val length = if (horizontal) width else height
        val rows = if (horizontal) height else width
        fun index(row: Int, column: Int) = if (horizontal) row * width + column else column * width + row
        for (row in 0 until rows) {
            var red = 0; var green = 0; var blue = 0
            fun accumulate(column: Int, sign: Int) {
                val pixel = input[index(row, column.coerceIn(0, length - 1))]
                red += ((pixel shr 16) and 255) * sign
                green += ((pixel shr 8) and 255) * sign
                blue += (pixel and 255) * sign
            }
            for (column in -radius..radius) accumulate(column, 1)
            for (column in 0 until length) {
                output[index(row, column)] = android.graphics.Color.rgb(red / diameter, green / diameter, blue / diameter)
                accumulate(column - radius, -1)
                accumulate(column + radius + 1, 1)
            }
        }
        return output
    }
    repeat(passes) { pixels = pass(pass(pixels, true), false) }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

@Composable
fun TrackCoverThumbnail(artwork: TrackArtworkVisual, size: Dp = 48.dp, cornerRadius: Dp = 8.dp,
    isPlaying: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(cornerRadius))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        ArtworkImage(artwork, Modifier.fillMaxSize())
    }
}

private inline fun <T> artworkTrace(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try { block() } finally { Trace.endSection() }
}

private fun decodeCover(bytes: ByteArray, maxSize: Int): Bitmap? = artworkTrace("Artwork:decode") {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
}

private fun decodeRemotePreview(value: String?, maxSize: Int): Bitmap? {
    if (value == null || value.length > 6 * 1024 * 1024 ||
        !Regex("^data:image/(?:png|jpe?g|webp);base64,", RegexOption.IGNORE_CASE).containsMatchIn(value)) return null
    return runCatching { decodeCover(Base64.decode(value.substringAfter(','), Base64.DEFAULT), maxSize) }.getOrNull()
}

private fun loadEmbeddedOrAlbumBitmap(context: Context, sourceUri: String?, albumId: String?, coverUri: String?, maxSize: Int): Bitmap? {
    if (!sourceUri.isNullOrBlank() && !sourceUri.startsWith("http", ignoreCase = true)) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (sourceUri.startsWith("content://") || sourceUri.startsWith("file://")) {
                    retriever.setDataSource(context, Uri.parse(sourceUri))
                } else {
                    retriever.setDataSource(sourceUri)
                }
                retriever.embeddedPicture?.let { decodeCover(it, maxSize) }?.let { return it }
            } finally {
                retriever.release()
            }
        }
        if (Build.VERSION.SDK_INT >= 29 && sourceUri.startsWith("content://")) {
            runCatching {
                context.contentResolver.loadThumbnail(Uri.parse(sourceUri), Size(minOf(maxSize, 640), minOf(maxSize, 640)), null)
            }.getOrNull()?.let { return it }
        }
    }
    if (coverUri != null && coverUri.startsWith("content://")) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(coverUri))?.use { input ->
                // A folder image can be a poster-sized scan; it is decoded only up to 8 MB.
                val bytes = input.readBytes()
                if (bytes.size <= 8 * 1024 * 1024) decodeCover(bytes, maxSize) else null
            }
        }.getOrNull()?.let { return it }
    }
    albumId?.toLongOrNull()?.takeIf { it > 0 }?.let { id ->
        runCatching {
            val uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), id)
            context.contentResolver.openInputStream(uri)?.use { decodeCover(it.readBytes(), maxSize) }
        }.getOrNull()?.let { return it }
    }
    return null
}

/** Average each half of a small sample. Hue is preserved, including purple and dark artwork. */
private fun artworkColors(bitmap: Bitmap): Pair<Color, Color> {
    fun sample(top: Boolean): Color {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in 0 until 12) for (y in 0 until 6) {
            val px = bitmap.getPixel(x * (bitmap.width - 1) / 11,
                (y + if (top) 0 else 6) * (bitmap.height - 1) / 11)
            r += android.graphics.Color.red(px); g += android.graphics.Color.green(px)
            b += android.graphics.Color.blue(px); n++
        }
        return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
    return sample(true) to sample(false)
}

/** Placeholder art: a flat Apple-grey tile with a quiet note, so a song without a cover adds no colour of its own. */
private val PlaceholderImage: ImageBitmap by lazy {
    val tile = com.twilightecho.android.ui.theme.AppleGrey.Elevated
    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(tile.toArgb())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF636366.toInt() }
    // A beamed pair of eighth notes, centred.
    canvas.drawOval(166f, 300f, 238f, 352f, paint)
    canvas.drawOval(286f, 276f, 358f, 328f, paint)
    canvas.drawRect(222f, 168f, 238f, 326f, paint)
    canvas.drawRect(342f, 144f, 358f, 302f, paint)
    canvas.drawPath(android.graphics.Path().apply {
        moveTo(222f, 168f); lineTo(358f, 144f); lineTo(358f, 180f); lineTo(222f, 204f); close()
    }, paint)
    bitmap.asImageBitmap()
}

private fun placeholderCover(key: String): TrackArtworkVisual {
    val tile = com.twilightecho.android.ui.theme.AppleGrey.Elevated
    return TrackArtworkVisual(key, PlaceholderImage, tile, tile, false, seedColor = null)
}
