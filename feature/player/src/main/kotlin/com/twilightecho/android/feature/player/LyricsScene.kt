package com.twilightecho.android.feature.player

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twilightecho.android.coreclient.CoreLyricLine
import com.twilightecho.android.coreclient.CoreLyricsView
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.LyricsPreferences
import com.twilightecho.android.ui.components.EmptyState
import com.twilightecho.android.ui.components.TwilightIcons
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** App-tuned values, not recovered Apple defaults. m=1, so the spring's natural frequency is √k. */
internal object LyricsMotionSpec {
    const val Stiffness = 110f
    /** Slightly under-damped: lines arrive with a ~2% settle, the soft landing of Apple Music's scroll. */
    const val WaveDamping = 0.8f
    /** Each row below the new line starts this much later, so the list unrolls like a wave. */
    const val StaggerMillis = 42L
    const val MaxStaggerRows = 7
    const val SettleSeconds = 1.1f
    const val FocusMillis = 320
    const val NeighborAlpha = 0.42f
    const val NearAlpha = 0.30f
    const val FarAlpha = 0.22f
    const val InactiveScale = 0.95f
    const val BlurPerLineDp = 0.9f
    const val MaxBlurDp = 4f
    /** Seconds of stillness on the lyrics page, while playing, before only the lyrics remain. */
    const val IdleImmersiveMillis = 5_000L
}

/** Unit step response of the wave spring, from 0 at rest to 1 when the row reaches its new place. */
internal fun lyricSpringStep(seconds: Float, stiffness: Float = LyricsMotionSpec.Stiffness,
    damping: Float = LyricsMotionSpec.WaveDamping): Float {
    if (seconds <= 0f) return 0f
    val omega = sqrt(stiffness)
    val damped = omega * sqrt(1f - damping * damping)
    val decay = exp(-damping * omega * seconds)
    return 1f - decay * (cos(damped * seconds) + damping * omega / damped * sin(damped * seconds))
}

/**
 * Apple Music-style cascade. The list moves to its new offset at once; every row is then drawn
 * back where it was and released on the spring with a per-row delay, so the lines above leave first,
 * the new line follows and the rows beneath trail behind it. Offsets are read only in graphicsLayer.
 */
@Stable
internal class LyricsWave {
    private class Shift(val distance: Float, val pivot: Int) { var startNanos = -1L }
    private val shifts = mutableStateListOf<Shift>()
    private var frameNanos by mutableLongStateOf(0L)

    fun push(distance: Float, pivot: Int) { if (abs(distance) >= 0.5f) shifts += Shift(distance, pivot) }

    fun delayNanos(index: Int, pivot: Int): Long =
        ((index - pivot).coerceIn(-1, LyricsMotionSpec.MaxStaggerRows) + 1) * LyricsMotionSpec.StaggerMillis * 1_000_000L

    /** Visual offset in px still to travel for [index]; zero when every wave has settled. */
    fun offset(index: Int): Float {
        if (shifts.isEmpty()) return 0f
        // Read the frame clock unconditionally: startNanos is not state, so a reader that skipped it on the
        // wave's first (unstarted) frame would never be told to move, and its row would wait at the old place.
        val now = frameNanos
        var sum = 0f
        for (shift in shifts) {
            val elapsed = if (shift.startNanos < 0) 0L else now - shift.startNanos - delayNanos(index, shift.pivot)
            sum += shift.distance * (1f - lyricSpringStep(elapsed / 1e9f))
        }
        return sum
    }

    /** A finite frame loop per burst; suspended without requesting frames while nothing moves. */
    suspend fun run() {
        val lifetime = (LyricsMotionSpec.SettleSeconds * 1e9).toLong() +
            (LyricsMotionSpec.MaxStaggerRows + 1) * LyricsMotionSpec.StaggerMillis * 1_000_000L
        while (true) {
            snapshotFlow { shifts.size }.first { it > 0 }
            while (shifts.isNotEmpty()) withFrameNanos { frame ->
                frameNanos = frame
                shifts.forEach { if (it.startNanos < 0) it.startNanos = frame }
                shifts.removeAll { frame - it.startNanos > lifetime }
            }
        }
    }

    fun clear() = shifts.clear()
}

internal data class LyricsSceneIdentity(
    val source: String,
    val trackId: String?,
    val lyrics: CoreLyricsView?
)

internal fun lyricsSceneIdentity(state: PlayerUiState): LyricsSceneIdentity = LyricsSceneIdentity(
    source = when (val mode = state.operatingMode) {
        CoreOperatingMode.StandaloneLocal -> "local"
        is CoreOperatingMode.RemoteDesktopCore -> mode.baseUrl
    },
    trackId = state.currentTrack?.id,
    lyrics = state.currentLyrics?.takeIf { it.trackId == state.currentTrack?.id }
)

internal data class LyricFocus(
    val alpha: Float,
    val scale: Float,
    val offsetDp: Float,
    val blurDp: Float
)

internal fun lyricFocus(index: Int, activeIndex: Int, synced: Boolean, following: Boolean,
    motionEnabled: Boolean, apiLevel: Int, blurStrengthDp: Float = LyricsMotionSpec.MaxBlurDp): LyricFocus {
    if (!synced) return LyricFocus(0.86f, 1f, 0f, 0f)
    val focused = activeIndex >= 0 && index == activeIndex
    val distance = if (activeIndex < 0) 1 else abs(index - activeIndex)
    return LyricFocus(
        alpha = when { focused -> 1f; distance <= 1 -> LyricsMotionSpec.NeighborAlpha
            distance <= 3 -> LyricsMotionSpec.NearAlpha; else -> LyricsMotionSpec.FarAlpha },
        scale = if (focused || !motionEnabled) 1f else LyricsMotionSpec.InactiveScale,
        offsetDp = 0f,
        // Depth of field: the further a line is from the one being sung, the softer it gets.
        blurDp = if (motionEnabled && following && apiLevel >= 31 && activeIndex >= 0 && !focused)
            minOf(distance * LyricsMotionSpec.BlurPerLineDp, LyricsMotionSpec.MaxBlurDp) *
                (blurStrengthDp / LyricsMotionSpec.MaxBlurDp) else 0f
    )
}

/** One owner for browse/follow transitions. No timers can take scroll ownership from a listener. */
@Stable
internal class LyricsFollowController(initialFollowing: Boolean = true) {
    var following by mutableStateOf(initialFollowing)
        private set
    var alignmentRequest by mutableIntStateOf(0)
        private set
    var lastAlignedIndex: Int = -1
        private set

    fun browse() { following = false }
    fun resume() { following = true; alignmentRequest++ }
    fun aligned(index: Int) { lastAlignedIndex = index }
    fun shouldSnap(target: Int, motionEnabled: Boolean, viewportIndex: Int? = null): Boolean =
        !motionEnabled || lastAlignedIndex < 0 || abs(target - lastAlignedIndex) > 3 ||
            (viewportIndex != null && abs(target - viewportIndex) > 3)

    companion object {
        val StateSaver = Saver<LyricsFollowController, Boolean>(
            save = { it.following }, restore = { LyricsFollowController(it) })
    }
}

@Stable
internal class LyricsViewportState(val list: LazyListState, val follow: LyricsFollowController) {
    val wave = LyricsWave()
}

/** Called above AnimatedVisibility/Crossfade, so closing the player does not discard browsing. */
@Composable
internal fun rememberLyricsViewportState(identity: LyricsSceneIdentity): LyricsViewportState = key(identity) {
    val list = rememberLazyListState()
    val follow = rememberSaveable(saver = LyricsFollowController.StateSaver) { LyricsFollowController() }
    remember(list, follow) { LyricsViewportState(list, follow) }
}

@Composable
internal fun rememberLyricsMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun readMotionEnabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var enabled by remember(resolver) { mutableStateOf(readMotionEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = readMotionEnabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** Engine-free rendering; playback time and active line remain supplied by the existing presenter. */
@Composable
internal fun LyricsStage(lyrics: CoreLyricsView?, activeIndex: Int, viewport: LyricsViewportState,
    visible: Boolean, motionEnabled: Boolean, onSeek: (Double) -> Unit, onImport: (() -> Unit)?,
    modifier: Modifier = Modifier, preferences: LyricsPreferences = LyricsPreferences()) {
    val lines = lyrics?.lines.orEmpty()
    if (lines.isEmpty()) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val viewportHeight = maxHeight
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().heightIn(min = viewportHeight), contentAlignment = Alignment.Center) {
                    EmptyState("暂无歌词", if (onImport != null) "导入这首歌的 LRC 文件，逐行跟随音乐。" else "这首歌暂无可用歌词。",
                        actionLabel = if (onImport != null) "导入歌词" else null, onAction = { onImport?.invoke() })
                }
            }
        }
        return
    }
    val synced = lyrics?.synced == true
    val current = activeIndex.takeIf { synced && it in lines.indices } ?: -1
    val list = viewport.list
    val follow = viewport.follow
    val density = LocalDensity.current
    val input = remember(follow) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) follow.browse()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(list, follow) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow.browse() }
    }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        // LazyList item offsets are relative to its content padding: offset=0 places the row here.
        val anchor = maxHeight / 3
        // Content padding follows viewport resizing by itself. Restarting this effect on each chrome/inset
        // animation frame would enqueue dozens of overlapping waves for a single line.
        LaunchedEffect(visible, current, synced, follow.following, follow.alignmentRequest,
            density.density, density.fontScale, motionEnabled, preferences) {
            if (!visible || !synced || !follow.following) return@LaunchedEffect
            snapshotFlow { list.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
            val target = current.coerceAtLeast(0)
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            // A wrapped row can fill the stage, leaving its next row just outside measurement.
            // Its predecessor still supplies an exact next-row offset; keep that change springy.
            val predecessor = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target - 1 }
            val distance = item?.offset?.toFloat() ?: predecessor?.let {
                it.offset + it.size + with(density) { preferences.lineSpacingDp.dp.toPx() }
            }
            val nearestToAnchor = list.layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset) }?.index
            if (follow.shouldSnap(target, motionEnabled, nearestToAnchor)) {
                viewport.wave.clear()
                list.scrollToItem(target)
            } else if (distance != null) {
                // Layout lands at once; the rows are drawn back and ride the staggered spring home.
                viewport.wave.push(list.scrollBy(distance), target)
            } else {
                list.animateScrollToItem(target)
            }
            follow.aligned(target)
        }
        LaunchedEffect(viewport.wave) { viewport.wave.run() }
        LazyColumn(modifier = Modifier.fillMaxSize().testTag("lyrics")
            .semantics { stateDescription = if (!synced) "非同步歌词" else if (follow.following) "自动跟随" else "手动浏览" }
            .nestedScroll(input)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val fade = Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black,
                    0.78f to Color.Black, 1f to Color.Transparent)
                onDrawWithContent {
                    drawContent()
                    drawRect(fade, blendMode = BlendMode.DstIn)
                }
            }, state = list,
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight * 2 / 3),
            verticalArrangement = Arrangement.spacedBy(preferences.lineSpacingDp.dp)) {
            itemsIndexed(lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
                LyricsLine(line, index, current, synced, follow.following, motionEnabled, viewport.wave, preferences) {
                    onSeek(line.timeMs / 1000.0)
                }
            }
        }
        if (synced && !follow.following) ResumeLyricsPill(follow::resume,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }
}

/** Glass pill over the lyrics: raised neutral fill, hairline edge, accent icon. */
@Composable
private fun ResumeLyricsPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.heightIn(min = 48.dp).clip(CircleShape).background(colors.surfaceContainerHighest.copy(alpha = 0.9f))
        .border(1.dp, colors.onSurface.copy(alpha = 0.10f), CircleShape)
        .clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 20.dp)
        .testTag("resumeLyrics"), verticalAlignment = Alignment.CenterVertically) {
        Icon(TwilightIcons.SignalWave, null, Modifier.size(18.dp), tint = colors.primary)
        Text("回到当前歌词", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
    }
}

@Composable
private fun LyricsLine(line: CoreLyricLine, index: Int, activeIndex: Int, synced: Boolean,
    following: Boolean, motionEnabled: Boolean, wave: LyricsWave, preferences: LyricsPreferences, onClick: () -> Unit) {
    val focus = lyricFocus(index, activeIndex, synced, following, motionEnabled, Build.VERSION.SDK_INT, preferences.blurDp)
    // The new line grows in on a soft, slightly lively spring; the old one settles back.
    val scale = animateFloatAsState(focus.scale,
        if (motionEnabled) spring(dampingRatio = 0.72f, stiffness = 220f) else snap(), label = "lyricScale")
    val opacity = animateFloatAsState(focus.alpha,
        if (motionEnabled) tween(LyricsMotionSpec.FocusMillis) else snap(), label = "lyricAlpha")
    val blur = animateFloatAsState(focus.blurDp,
        if (motionEnabled) tween(LyricsMotionSpec.FocusMillis) else snap(), label = "lyricBlur")
    val density = LocalDensity.current
    val ink = MaterialTheme.colorScheme.onSurface
    // Only the words take the tap, so the open stage beside them is free to toggle immersion.
    Column(Modifier.heightIn(min = 48.dp)
        .graphicsLayer {
            translationY = wave.offset(index)
            alpha = opacity.value
            scaleX = scale.value; scaleY = scale.value
            transformOrigin = TransformOrigin(0f, 0.5f)
        }
        .semantics(mergeDescendants = true) { selected = synced && index == activeIndex }
        .testTag("lyric-line-$index")
        .clip(RoundedCornerShape(14.dp))
        .then(if (synced) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
        .graphicsLayer {
            // Layer-only invalidation: animating depth must not recompose or remeasure the words.
            val radius = blur.value * density.density
            renderEffect = if (Build.VERSION.SDK_INT >= 31 && radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
        }) {
        // White ink on every line; focus comes from opacity, size and depth of field, never a hue change.
        Text(line.text, color = ink, style = TextStyle(fontSize = preferences.fontSizeSp.sp, lineHeight = (preferences.fontSizeSp * 4 / 3).sp,
            fontWeight = FontWeight(preferences.fontWeight), letterSpacing = (-0.6).sp))
        line.translation?.takeIf { preferences.showTranslation && it.isNotBlank() }?.let {
            Text(it, color = ink.copy(alpha = 0.62f), fontSize = preferences.translationSizeSp.sp, lineHeight = (preferences.translationSizeSp * 24 / 17).sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
