package com.twilightecho.android.ui.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.twilightecho.android.ui.theme.TwilightTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Resolved cover visual for a track, including its [ImageBitmap] and extracted/curated accent
 * colors used by [BlurredCoverBackdrop] and cover cards.
 * Strictly avoids any purple-black colors.
 */
data class TrackArtworkVisual(
    val cacheKey: String,
    val imageBitmap: ImageBitmap,
    val dominantColor: Color,
    val secondaryColor: Color,
    val isRealEmbeddedArt: Boolean
)

private object ArtworkMemoryCache {
    private val cache = LruCache<String, TrackArtworkVisual>(36)

    fun get(key: String): TrackArtworkVisual? = synchronized(cache) { cache.get(key) }

    fun put(key: String, value: TrackArtworkVisual) = synchronized(cache) {
        cache.put(key, value)
    }
}

/**
 * Curated non-purple-black architectural color palettes for generative Bauhaus album covers
 * when a local track does not contain embedded ID3/FLAC cover art.
 */
private data class CuratedCoverPalette(
    val topColorInt: Int,
    val bottomColorInt: Int,
    val orbColorInt: Int,
    val dominantCompose: Color,
    val secondaryCompose: Color
)

private val CURATED_GALLERY_PALETTES = listOf(
    // 0: Nordic Cobalt & Signal Emerald
    CuratedCoverPalette(
        topColorInt = 0xFF1D4ED8.toInt(),
        bottomColorInt = 0xFF0D9488.toInt(),
        orbColorInt = 0xFF38BDF8.toInt(),
        dominantCompose = Color(0xFF2563EB),
        secondaryCompose = Color(0xFF10B981)
    ),
    // 1: Coastal Teal & Warm Amber Sun
    CuratedCoverPalette(
        topColorInt = 0xFF0F766E.toInt(),
        bottomColorInt = 0xFF0284C7.toInt(),
        orbColorInt = 0xFFF59E0B.toInt(),
        dominantCompose = Color(0xFF0D9488),
        secondaryCompose = Color(0xFFF59E0B)
    ),
    // 2: Sunset Coral & Cerulean Sky
    CuratedCoverPalette(
        topColorInt = 0xFFE11D48.toInt(),
        bottomColorInt = 0xFF2563EB.toInt(),
        orbColorInt = 0xFFFBBF24.toInt(),
        dominantCompose = Color(0xFFE11D48),
        secondaryCompose = Color(0xFF2563EB)
    ),
    // 3: Alpine Sage & DeepFjord Blue
    CuratedCoverPalette(
        topColorInt = 0xFF059669.toInt(),
        bottomColorInt = 0xFF1E40AF.toInt(),
        orbColorInt = 0xFF6EE7B7.toInt(),
        dominantCompose = Color(0xFF10B981),
        secondaryCompose = Color(0xFF1D4ED8)
    ),
    // 4: Warm Terracotta & Ocean Azure
    CuratedCoverPalette(
        topColorInt = 0xFFD97706.toInt(),
        bottomColorInt = 0xFF0369A1.toInt(),
        orbColorInt = 0xFFFDA4AF.toInt(),
        dominantCompose = Color(0xFFD97706),
        secondaryCompose = Color(0xFF0284C7)
    )
)

@Composable
fun rememberTrackArtwork(
    trackId: String?,
    title: String?,
    artist: String?,
    sourceUri: String?,
    albumId: String? = null
): TrackArtworkVisual {
    val context = LocalContext.current
    val key = remember(trackId, title, artist, sourceUri, albumId) {
        "${trackId.orEmpty()}|${sourceUri.orEmpty()}|${albumId.orEmpty()}|${title.orEmpty()}|${artist.orEmpty()}"
    }

    val initialVisual = remember(key) {
        ArtworkMemoryCache.get(key) ?: generateArchitecturalCoverVisual(
            cacheKey = key,
            seedText = "${title.orEmpty()}-${artist.orEmpty()}"
        )
    }

    var visual by remember(key) { mutableStateOf(initialVisual) }

    LaunchedEffect(key) {
        val cached = ArtworkMemoryCache.get(key)
        if (cached != null) {
            visual = cached
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            loadEmbeddedOrAlbumBitmap(context, sourceUri, albumId)?.let { bmp ->
                val (dom, sec) = extractSafeGalleryColors(bmp)
                TrackArtworkVisual(
                    cacheKey = key,
                    imageBitmap = bmp.asImageBitmap(),
                    dominantColor = dom,
                    secondaryColor = sec,
                    isRealEmbeddedArt = true
                )
            } ?: initialVisual
        }
        ArtworkMemoryCache.put(key, loaded)
        visual = loaded
    }

    return visual
}

/**
 * Full-screen blurred song cover background layer.
 * Uses the current song's cover art bitmap with large-radius Gaussian blur + adaptive color
 * wash + translucent pearl-glass scrim so the UI never looks plain solid white while keeping
 * typography ultra-crisp and completely free of purple-black hues.
 */
@Composable
fun BlurredCoverBackdrop(
    artwork: TrackArtworkVisual,
    immersiveBoost: Boolean = false,
    modifier: Modifier = Modifier
) {
    val animatedPrimary by animateColorAsState(
        targetValue = artwork.dominantColor,
        animationSpec = tween(durationMillis = 700),
        label = "backdropPrimary"
    )
    val animatedSecondary by animateColorAsState(
        targetValue = artwork.secondaryColor,
        animationSpec = tween(durationMillis = 700),
        label = "backdropSecondary"
    )

    val coverAlpha = if (immersiveBoost) 0.42f else 0.28f
    val scrimTopAlpha = if (immersiveBoost) 0.68f else 0.78f
    val scrimBottomAlpha = if (immersiveBoost) 0.76f else 0.85f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background( Color(0xFFEEF3F9))
    ) {
        // 1. Blurred Cover Image Layer
        Image(
            bitmap = artwork.imageBitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = coverAlpha,
            modifier = Modifier
                .fillMaxSize()
                .blur(radius = 64.dp)
        )

        // 2. Ambient Color Diffusion Mesh from Cover Palette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            animatedPrimary.copy(alpha = if (immersiveBoost) 0.28f else 0.19f),
                            Color.Transparent
                        ),
                        center = Offset(180f, 260f),
                        radius = 1200f
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            animatedSecondary.copy(alpha = if (immersiveBoost) 0.24f else 0.16f),
                            Color.Transparent
                        ),
                        center = Offset(900f, 1750f),
                        radius = 1300f
                    )
                )
        )

        // 3. Frosted Daylight Pearl Scrim for High-Contrast Editorial Readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF4F7FB).copy(alpha = scrimTopAlpha),
                            Color(0xFFEEF3F9).copy(alpha = (scrimTopAlpha + scrimBottomAlpha) / 2f),
                            Color(0xFFF2F6FA).copy(alpha = scrimBottomAlpha)
                        )
                    )
                )
        )
    }
}

/**
 * Crisp rounded cover artwork thumbnail used in the Floating MiniPlayer Capsule, Home Hero Card,
 * Library rows, and Search results.
 */
@Composable
fun TrackCoverThumbnail(
    artwork: TrackArtworkVisual,
    size: Dp = 46.dp,
    cornerRadius: Dp = 13.dp,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = 4.dp,
                shape = shape,
                ambientColor = TwilightTokens.AmbientShadowColor
            )
            .clip(shape)
            .background(TwilightTokens.CardSurface)
            .border(0.8.dp, Color.White.copy(alpha = 0.75f), shape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = artwork.imageBitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (isPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(TwilightTokens.PrimaryCobalt)
                    .border(1.2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TwilightIcons.SignalWave,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp)
                )
            }
        }
    }
}

private fun loadEmbeddedOrAlbumBitmap(
    context: Context,
    sourceUri: String?,
    albumId: String?
): Bitmap? {
    if (!sourceUri.isNullOrBlank()) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (sourceUri.startsWith("content://") || sourceUri.startsWith("file://")) {
                    retriever.setDataSource(context, Uri.parse(sourceUri))
                } else {
                    retriever.setDataSource(sourceUri)
                }
                val bytes = retriever.embeddedPicture
                if (bytes != null && bytes.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { return it }
                }
            } finally {
                runCatching { retriever.release() }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sourceUri.startsWith("content://")) {
            runCatching {
                context.contentResolver.loadThumbnail(Uri.parse(sourceUri), Size(512, 512), null)
            }.getOrNull()?.let { return it }
        }
    }

    val numericAlbumId = albumId?.toLongOrNull()
    if (numericAlbumId != null && numericAlbumId > 0L) {
        runCatching {
            val artUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                numericAlbumId
            )
            context.contentResolver.openInputStream(artUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()?.let { return it }
    }

    return null
}

private fun extractSafeGalleryColors(bitmap: Bitmap): Pair<Color, Color> {
    return runCatching {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val p1 = bitmap.getPixel(w / 3, h / 3)
        val p2 = bitmap.getPixel((w * 2) / 3, (h * 2) / 3)
        sanitizeNonPurpleColor(p1, TwilightTokens.PrimaryCobalt) to
            sanitizeNonPurpleColor(p2, TwilightTokens.SignalEmerald)
    }.getOrDefault(TwilightTokens.PrimaryCobalt to TwilightTokens.SignalEmerald)
}

private fun sanitizeNonPurpleColor(pixel: Int, fallback: Color): Color {
    val r = android.graphics.Color.red(pixel) / 255f
    val g = android.graphics.Color.green(pixel) / 255f
    val b = android.graphics.Color.blue(pixel) / 255f
    // Avoid overly dark or purple-biased tones so the blurred background stays refined
    val isPurpleBiased = (r > 0.35f && b > 0.45f && g < minOf(r, b) * 0.72f)
    val luma = 0.299f * r + 0.587f * g + 0.114f * b
    if (isPurpleBiased || luma < 0.12f) {
        return fallback
    }
    return Color(r, g, b, 1f)
}

private fun generateArchitecturalCoverVisual(
    cacheKey: String,
    seedText: String
): TrackArtworkVisual {
    val hash = abs(seedText.ifBlank { "TwilightEcho" }.hashCode())
    val palette = CURATED_GALLERY_PALETTES[hash % CURATED_GALLERY_PALETTES.size]
    val sizePx = 360
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    // 1. Diagonal Architectural Gradient Base
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            sizePx.toFloat(),
            sizePx.toFloat(),
            palette.topColorInt,
            palette.bottomColorInt,
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), bgPaint)

    // 2. Soft Luminous Orb Highlight
    val orbX = sizePx * (0.28f + (hash % 40) / 100f)
    val orbY = sizePx * (0.25f + ((hash / 7) % 40) / 100f)
    val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            orbX,
            orbY,
            sizePx * 0.62f,
            intArrayOf(palette.orbColorInt, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        alpha = 185
    }
    canvas.drawCircle(orbX, orbY, sizePx * 0.62f, orbPaint)

    // 3. Bauhaus Concentric Acoustic Rings
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.WHITE
        strokeWidth = 2.2f
    }
    val center = sizePx / 2f
    listOf(0.42f, 0.32f, 0.22f).forEachIndexed { idx, frac ->
        ringPaint.alpha = 45 + idx * 25
        canvas.drawCircle(center, center, sizePx * frac, ringPaint)
    }

    // 4. Crisp Center Gallery Disc Label
    val centerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.WHITE
        alpha = 230
    }
    canvas.drawCircle(center, center, sizePx * 0.11f, centerFillPaint)

    val spindlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = palette.topColorInt
    }
    canvas.drawCircle(center, center, sizePx * 0.032f, spindlePaint)

    return TrackArtworkVisual(
        cacheKey = cacheKey,
        imageBitmap = bitmap.asImageBitmap(),
        dominantColor = palette.dominantCompose,
        secondaryColor = palette.secondaryCompose,
        isRealEmbeddedArt = false
    )
}
