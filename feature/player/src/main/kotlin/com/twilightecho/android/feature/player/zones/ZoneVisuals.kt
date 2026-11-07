package com.twilightecho.android.feature.player.zones

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DesktopZonePlayback
import com.twilightecho.android.coreclient.DesktopZoneTrack
import com.twilightecho.android.feature.player.rememberPlayerArtwork
import com.twilightecho.android.ui.components.TrackArtworkVisual
import com.twilightecho.android.ui.components.TwilightIcons
import com.twilightecho.android.ui.components.TwilightMotion
import com.twilightecho.android.ui.components.formatDurationSeconds
import com.twilightecho.android.ui.components.rememberMotionEnabled
import com.twilightecho.android.ui.theme.TwilightTokens
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/*
 * Custom-drawn pieces of the 声区 page. Material 3 1.3 has no expressive wavy progress or shape morph,
 * so both are drawn here. Colours are theme roles and Twilight signal tokens only; nothing is a gradient.
 */

/** Semantic state colour for dots, lines and markers. Text keeps the theme's ink for contrast on any canvas. */
@Composable
internal fun toneColor(tone: ZoneTone): Color = when (tone) {
    ZoneTone.LIVE -> TwilightTokens.SignalLive
    ZoneTone.INFO -> MaterialTheme.colorScheme.tertiary
    ZoneTone.WARNING -> TwilightTokens.SignalWarm
    ZoneTone.DANGER -> MaterialTheme.colorScheme.error
    ZoneTone.NEUTRAL -> TwilightTokens.SignalIdle
}

@Composable
internal fun ToneDot(tone: ZoneTone, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val color by animateColorAsState(toneColor(tone), tween(if (rememberMotionEnabled()) 200 else 0), label = "toneDot")
    Box(modifier.size(size).drawBehind { drawCircle(color) })
}

/**
 * A quiet status pill: a coloured dot and the theme's ink. Danger uses the theme's soft error container;
 * a place that is playing shows the accent 3-bar indicator instead of the dot.
 */
@Composable
internal fun ZoneChip(label: String, tone: ZoneTone, modifier: Modifier = Modifier, playing: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val danger = tone == ZoneTone.DANGER
    Row(modifier.clip(RoundedCornerShape(50))
        .background(if (danger) colors.errorContainer else colors.onSurface.copy(alpha = 0.07f))
        .padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (playing) PlayingBars(Modifier.size(width = 11.dp, height = 12.dp)) else ToneDot(tone, size = 7.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (danger) colors.onErrorContainer else colors.onSurfaceVariant)
    }
}

/** Core's own Chinese message, never paraphrased. */
@Composable
internal fun ZoneErrorNote(message: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.errorContainer)
        .padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(TwilightIcons.Info, null, Modifier.padding(top = 1.dp).size(18.dp), tint = colors.onErrorContainer)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** Three small bars in the accent colour; they only exist while a place is playing. */
@Composable
internal fun PlayingBars(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val motion = rememberMotionEnabled()
    val heights: List<State<Float>> = if (motion) {
        val transition = rememberInfiniteTransition(label = "playingBars")
        listOf(520, 680, 440).mapIndexed { index, duration ->
            transition.animateFloat(if (index == 1) 0.45f else 0.3f, 1f,
                infiniteRepeatable(tween(duration, easing = TwilightMotion.StandardCurve), RepeatMode.Reverse), label = "bar$index")
        }
    } else listOf(0.55f, 1f, 0.75f).map { remember(it) { mutableStateOf(it) } }
    Canvas(modifier.size(width = 13.dp, height = 14.dp).clearAndSetSemantics { }) {
        val bar = 3.dp.toPx()
        val gap = (size.width - bar * 3) / 2
        heights.forEachIndexed { index, height ->
            val h = size.height * height.value.coerceIn(0.2f, 1f)
            drawRoundRect(color, topLeft = Offset(index * (bar + gap), size.height - h), size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2))
        }
    }
}

/** A thin line of progress; reads [position] only while drawing, so ticking never recomposes. */
@Composable
internal fun SlimProgress(position: State<Double>, duration: Double, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val fraction = if (duration > 0) (position.value / duration).toFloat().coerceIn(0f, 1f) else 0f
        val y = size.height / 2
        drawLine(track, Offset(y, y), Offset(size.width - y, y), size.height, StrokeCap.Round)
        if (fraction > 0f) drawLine(color, Offset(y, y), Offset(y + (size.width - 2 * y) * fraction, y), size.height, StrokeCap.Round)
    }
}

/**
 * Position between Core's one-second polls: advanced locally while playing, re-anchored by every report.
 * Frames come from the infinite-animation clock, which UI tests pause.
 */
@Composable
internal fun rememberZonePosition(playback: DesktopZonePlayback?): State<Double> {
    val base = playback?.position?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    val duration = playback?.duration?.takeIf { it.isFinite() && it > 0 } ?: 0.0
    val playing = playback?.state == "playing"
    val position = remember { mutableDoubleStateOf(base) }
    LaunchedEffect(base, duration, playing) {
        position.doubleValue = base
        if (!playing) return@LaunchedEffect
        var start = -1L
        while (true) {
            withInfiniteAnimationFrameMillis { now ->
                if (start < 0) start = now
                val next = base + (now - start) / 1000.0
                position.doubleValue = if (duration > 0) next.coerceAtMost(duration) else next
            }
        }
    }
    return position
}

/** Only covers of the computer's own files can be fetched; anything else keeps the neutral window. */
@Composable
internal fun rememberZoneArtwork(track: DesktopZoneTrack?, size: Int = 512): TrackArtworkVisual? {
    val id = zoneArtworkId(track) ?: return null
    val view = remember(id, track?.title, track?.artist, track?.album) {
        CoreTrackView(id = id, title = track?.title.orEmpty(), artist = track?.artist.orEmpty(), album = track?.album.orEmpty())
    }
    return rememberPlayerArtwork(view, size, extractColors = false).takeIf { it.isRealEmbeddedArt }
}

private enum class WindowContent { COVER, NOTE, OFFLINE, EMPTY }

/**
 * The window of a zone: its cover is the light. Playing shows the cover in full colour; paused dims it
 * (saturation 0.35, opacity 0.7 over 400 ms); offline and empty windows stay an unlit pane with a glyph.
 * The pane is the theme's deepest container, so a window reads as set into its card rather than as part of it.
 */
@Composable
internal fun ZoneWindow(light: ZoneLight, track: DesktopZoneTrack?, message: String?, modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp), artworkSize: Int = 512) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberMotionEnabled()
    val lit = light == ZoneLight.PLAYING || light == ZoneLight.PAUSED
    val artwork = rememberZoneArtwork(track.takeIf { lit }, artworkSize)
    val content = when {
        lit && artwork != null -> WindowContent.COVER
        lit -> WindowContent.NOTE
        light == ZoneLight.EMPTY -> WindowContent.EMPTY
        else -> WindowContent.OFFLINE
    }
    val saturation by animateFloatAsState(if (light == ZoneLight.PLAYING) 1f else 0.35f,
        if (motion) tween(400, easing = TwilightMotion.StandardCurve) else snap(), label = "windowSaturation")
    val opacity by animateFloatAsState(if (light == ZoneLight.PLAYING) 1f else 0.7f,
        if (motion) tween(400, easing = TwilightMotion.StandardCurve) else snap(), label = "windowOpacity")
    BoxWithConstraints(modifier.clip(shape).background(colors.surfaceContainerLowest)
        .semantics { stateDescription = message ?: playbackWindowDescription(light) }, contentAlignment = Alignment.Center) {
        val glyph = min(maxWidth * 0.3f, 44.dp)
        val roomy = maxWidth >= 104.dp
        Crossfade(content, Modifier.fillMaxSize(), tween(if (motion) 240 else 0), label = "windowContent") { shown ->
            when (shown) {
                WindowContent.COVER -> if (artwork != null) {
                    val filter = remember(saturation) { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) }) }
                    Image(artwork.imageBitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        alpha = opacity, colorFilter = filter)
                }
                WindowContent.NOTE -> WindowGlyph(TwilightIcons.MusicNote, null, glyph, false, opacity)
                WindowContent.EMPTY -> WindowGlyph(TwilightIcons.MusicNote, message, glyph, roomy, 1f)
                WindowContent.OFFLINE -> WindowGlyph(TwilightIcons.Unlink, message, glyph, roomy, 1f)
            }
        }
    }
}

private fun playbackWindowDescription(light: ZoneLight) = when (light) {
    ZoneLight.PLAYING -> "播放中"
    ZoneLight.PAUSED -> "已暂停"
    ZoneLight.EMPTY -> "队列为空"
    else -> "输出桥离线"
}

@Composable
private fun WindowGlyph(icon: ImageVector, message: String?, glyph: Dp, showMessage: Boolean, alpha: Float) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().padding(10.dp).graphicsLayer { this.alpha = alpha },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(glyph), tint = ink.copy(alpha = 0.72f))
        if (showMessage && message != null) Text(message, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium,
            color = ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The small round link on each seam between grouped windows. */
@Composable
internal fun LinkBadge(modifier: Modifier = Modifier, ring: Color, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(if (active) colors.primary else colors.onSurfaceVariant,
        tween(if (rememberMotionEnabled()) 200 else 0), label = "linkTint")
    Box(modifier.size(28.dp).clip(CircleShape).background(ring).padding(3.dp).clip(CircleShape)
        .background(colors.surfaceContainerHigh).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Icon(TwilightIcons.Link, null, Modifier.size(15.dp), tint = tint)
    }
}

/**
 * Round play/pause that becomes a squircle while playing or pressed: corner radius 50% → 30% on a spring,
 * drawn behind the glyph so the morph never re-lays out.
 */
@Composable
internal fun MorphPlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 72.dp,
    enabled: Boolean = true, loading: Boolean = false, label: String? = null, tonal: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateFloatAsState(if ((pressed && enabled) || playing) 0.30f else 0.50f,
        if (motion) spring(dampingRatio = 0.55f, stiffness = 420f) else snap(), label = "playMorph")
    val scale by animateFloatAsState(if (pressed && enabled) 0.92f else 1f,
        if (motion) spring(dampingRatio = 0.6f, stiffness = 700f) else snap(), label = "playPress")
    val fill by animateColorAsState(when {
        tonal -> colors.onSurface.copy(alpha = if (pressed && enabled) 0.14f else 0.08f)
        enabled -> colors.primary
        else -> colors.onSurface.copy(alpha = 0.12f)
    }, tween(if (motion) 180 else 0), label = "playFill")
    val ink by animateColorAsState(when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        tonal -> colors.onSurface
        else -> colors.onPrimary
    }, tween(if (motion) 180 else 0), label = "playInk")
    val description = label ?: if (playing) "暂停" else "播放"
    Box(modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }
        .drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(this.size.minDimension * corner)) }
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; if (loading) stateDescription = "加载中" },
        contentAlignment = Alignment.Center) {
        AnimatedContent(if (loading) null else playing, transitionSpec = {
            if (!motion) EnterTransition.None togetherWith ExitTransition.None
            else (scaleIn(spring(dampingRatio = 0.65f, stiffness = 560f), initialScale = 0.7f) + fadeIn(tween(130))) togetherWith
                (scaleOut(tween(110, easing = TwilightMotion.EmphasizedAccelerate), targetScale = 0.7f) + fadeOut(tween(100))) using
                SizeTransform(clip = false)
        }, label = "playGlyph") { shown ->
            when (shown) {
                null -> CircularProgressIndicator(Modifier.size(size * 0.36f), color = ink, strokeWidth = 2.5.dp)
                true -> Icon(TwilightIcons.Pause, null, Modifier.size(size * 0.46f), tint = ink)
                false -> Icon(TwilightIcons.Play, null, Modifier.size(size * 0.46f), tint = ink)
            }
        }
    }
}

/**
 * Seek bar whose played part is a sine wave while playing and flattens to a line when paused (the amplitude
 * morphs over 240 ms). Tap or drag to seek; TalkBack gets a progress range with a set-progress action.
 */
@Composable
internal fun WavySeekBar(position: State<Double>, duration: Double, playing: Boolean, enabled: Boolean,
    onSeek: (Double) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberMotionEnabled()
    val active = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f)
    val inactive = colors.onSurface.copy(alpha = 0.14f)
    val seekable = enabled && duration > 0
    val amplitude by animateFloatAsState(if (playing && duration > 0) 1f else 0f,
        if (motion) tween(240, easing = TwilightMotion.StandardCurve) else snap(), label = "waveAmplitude")
    val phase: State<Float> = if (playing && motion) {
        rememberInfiniteTransition(label = "wave").animateFloat(0f, 1f,
            infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "wavePhase")
    } else remember { mutableStateOf(0f) }
    var drag by remember { mutableStateOf<Float?>(null) }
    // Hold a committed seek on screen until Core's next report has had time to arrive.
    var held by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(held) { if (held != null) { delay(1600); held = null } }
    val currentSeek by rememberUpdatedState(onSeek)
    val thumb by animateFloatAsState(if (drag != null) 1f else 0f,
        if (motion) spring(dampingRatio = 0.7f, stiffness = 600f) else snap(), label = "seekThumb")
    val seconds by remember(duration) { derivedStateOf { position.value.toInt() } }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(seekable, duration) {
                if (!seekable) return@pointerInput
                detectTapGestures { offset ->
                    val fraction = fractionAt(offset.x, size.width.toFloat(), 10.dp.toPx())
                    held = fraction; currentSeek(fraction * duration)
                }
            }
            .pointerInput(seekable, duration) {
                if (!seekable) return@pointerInput
                val inset = 10.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { drag = fractionAt(it.x, size.width.toFloat(), inset) },
                    onDragEnd = { drag?.let { held = it; currentSeek(it * duration) }; drag = null },
                    onDragCancel = { drag = null }
                ) { change, _ ->
                    change.consume(); drag = fractionAt(change.position.x, size.width.toFloat(), inset)
                }
            }
            .semantics {
                contentDescription = "播放进度"
                if (duration > 0) progressBarRangeInfo = ProgressBarRangeInfo(seconds.toFloat().coerceIn(0f, duration.toFloat()),
                    0f..duration.toFloat())
                if (seekable) setProgress { target -> currentSeek(target.toDouble().coerceIn(0.0, duration)); true }
            }) {
            val inset = 10.dp.toPx()
            val width = size.width - inset * 2
            val cy = size.height / 2
            val fraction = drag ?: held ?: if (duration > 0) (position.value / duration).toFloat().coerceIn(0f, 1f) else 0f
            val x = inset + width * fraction
            val stroke = 4.dp.toPx()
            val gap = 5.dp.toPx()
            if (x + gap < size.width - inset) drawLine(inactive, Offset(x + gap, cy), Offset(size.width - inset, cy), stroke, StrokeCap.Round)
            val end = x - gap
            if (end > inset) {
                val amp = 3.2.dp.toPx() * amplitude
                if (amp < 0.25f) drawLine(active, Offset(inset, cy), Offset(end, cy), stroke, StrokeCap.Round)
                else {
                    val wavelength = 26.dp.toPx()
                    val shift = phase.value * 2 * PI.toFloat()
                    val path = Path()
                    var px = inset
                    path.moveTo(px, cy + amp * sin(px / wavelength * 2 * PI.toFloat() - shift))
                    while (px < end) {
                        px = (px + 2f).coerceAtMost(end)
                        path.lineTo(px, cy + amp * sin(px / wavelength * 2 * PI.toFloat() - shift))
                    }
                    drawPath(path, active, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            val thumbHeight = (18.dp + 6.dp * thumb).toPx()
            val thumbWidth = 4.dp.toPx()
            drawRoundRect(active, topLeft = Offset(x - thumbWidth / 2, cy - thumbHeight / 2), size = Size(thumbWidth, thumbHeight),
                cornerRadius = CornerRadius(thumbWidth / 2))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            val shown = drag?.let { (it * duration).toInt() } ?: seconds
            Text(formatDurationSeconds(shown.toDouble()), style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) formatDurationSeconds(duration) else "--:--", style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

private fun fractionAt(x: Float, width: Float, inset: Float): Float =
    ((x - inset) / (width - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)

/**
 * Skew meter: a centred zero line, the ±observation-range band around it and a marker at the measured
 * spread. Unmeasured groups show the axis only.
 */
@Composable
internal fun SkewMeter(reading: SkewReading, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberMotionEnabled()
    val tone = toneColor(when { !reading.measured -> ZoneTone.NEUTRAL; reading.aligned -> ZoneTone.LIVE; else -> ZoneTone.WARNING })
    val marker by animateFloatAsState((reading.skewMs / reading.scaleMs).toFloat().coerceIn(0f, 1f),
        if (motion) tween(240, easing = TwilightMotion.StandardCurve) else snap(), label = "skewMarker")
    val band by animateFloatAsState((reading.rangeMs / reading.scaleMs).toFloat().coerceIn(0f, 1f),
        if (motion) tween(240, easing = TwilightMotion.StandardCurve) else snap(), label = "skewBand")
    val axis = colors.onSurface.copy(alpha = 0.14f)
    val zero = colors.onSurface.copy(alpha = 0.6f)
    val surface = colors.surfaceContainerHigh
    val description = if (reading.measured) "偏差 ${formatMs(reading.skewMs)}，观测范围 ±${formatMs(reading.rangeMs)}，${reading.label}"
        else reading.label
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(30.dp).semantics { contentDescription = description }) {
            val inset = 8.dp.toPx()
            val mid = size.width / 2
            val half = mid - inset
            val cy = size.height / 2
            drawLine(axis, Offset(inset, cy), Offset(size.width - inset, cy), 2.dp.toPx(), StrokeCap.Round)
            val tolerance = (GROUP_SKEW_TOLERANCE_MS / reading.scaleMs).toFloat().coerceIn(0f, 1f) * half
            for (side in listOf(-1f, 1f)) drawLine(axis, Offset(mid + side * tolerance, cy - 5.dp.toPx()),
                Offset(mid + side * tolerance, cy + 5.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
            if (reading.measured && band > 0f) {
                val h = 12.dp.toPx()
                drawRoundRect(tone.copy(alpha = 0.24f), topLeft = Offset(mid - band * half, cy - h / 2),
                    size = Size((band * half * 2).coerceAtLeast(h), h), cornerRadius = CornerRadius(h / 2))
            }
            drawLine(zero, Offset(mid, cy - 9.dp.toPx()), Offset(mid, cy + 9.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            if (reading.measured) {
                val center = Offset(mid + marker * half, cy)
                drawCircle(surface, 8.dp.toPx(), center)
                drawCircle(tone, 6.dp.toPx(), center)
            }
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            val scale = if (reading.scaleMs % 1.0 == 0.0) reading.scaleMs.toInt().toString() else reading.scaleMs.toString()
            Text("−$scale ms", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text("0", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center)
            Text("+$scale ms", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

/** 待命 → 预缓冲 → 调度起播 → 同步播放, with the current step in the phase's colour. */
@Composable
internal fun PhaseStepper(phase: GroupPhase, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberMotionEnabled()
    val index = groupStepIndex(phase)
    val current = toneColor(phase.tone)
    val reached = colors.onSurface.copy(alpha = 0.72f)
    val idle = colors.onSurface.copy(alpha = 0.16f)
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "同步阶段：${phase.label}" }) {
        GroupSteps.forEachIndexed { step, label ->
            val dot by animateColorAsState(when { step == index -> current; step < index -> reached; else -> idle },
                tween(if (motion) 200 else 0), label = "stepDot$step")
            val left by animateColorAsState(if (step <= index) reached else idle, tween(if (motion) 200 else 0), label = "stepIn$step")
            val right by animateColorAsState(if (step < index) reached else idle, tween(if (motion) 200 else 0), label = "stepOut$step")
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                    val cy = size.height / 2
                    val cx = size.width / 2
                    val line = 2.dp.toPx()
                    val radius = (if (step == index) 6.dp else 4.5.dp).toPx()
                    if (step > 0) drawLine(left, Offset(0f, cy), Offset(cx - radius - 3.dp.toPx(), cy), line, StrokeCap.Round)
                    if (step < GroupSteps.lastIndex) drawLine(right, Offset(cx + radius + 3.dp.toPx(), cy), Offset(size.width, cy), line, StrokeCap.Round)
                    drawCircle(dot, radius, Offset(cx, cy))
                }
                Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (step == index) colors.onSurface else colors.onSurfaceVariant)
            }
        }
    }
}

/** An action tile of the detail pages: a tonal disc with the icon and a short Chinese label below. */
@Composable
internal fun ZoneActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, danger: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.9f else 1f,
        if (motion) spring(dampingRatio = 0.6f, stiffness = 700f) else snap(), label = "actionPress")
    val ink = when { !enabled -> colors.onSurface.copy(alpha = 0.38f); danger -> colors.error; else -> colors.onSurface }
    Column(modifier.clip(RoundedCornerShape(16.dp))
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .background(colors.onSurface.copy(alpha = if (pressed) 0.14f else 0.08f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = ink)
        }
        Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
    }
}

/** The accent outline of the selected card; unselected cards stay borderless. */
internal fun Modifier.zoneRing(selected: Boolean, color: Color, shape: Shape): Modifier =
    if (selected) border(1.5.dp, color, shape) else this
