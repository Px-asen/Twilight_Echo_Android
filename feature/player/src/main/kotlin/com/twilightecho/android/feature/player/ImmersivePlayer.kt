@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.twilightecho.android.feature.player

import android.content.res.Configuration
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.delay
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.PlayMode
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.LocalTwilightAccents
import com.twilightecho.android.ui.theme.Monet
import com.twilightecho.android.ui.theme.TwilightTokens

/** Cover backgrounds use near-white ink; user images and solid canvases inherit their configured contrast. */
@Composable
private fun PlayerStageTheme(content: @Composable () -> Unit) {
    if (!LocalBackgroundVisuals.current.useCover) {
        content()
        return
    }
    val seed = LocalTwilightAccents.current.seed
    val stage = remember(seed) { Monet.tone(Monet.hueChroma(seed).first, 6.0, 97.0) }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = stage,
        onSurfaceVariant = stage.copy(alpha = 0.72f), onBackground = stage), content = content)
}

@Composable
internal fun ImmersiveNowPlayingScreen(state: PlayerUiState, actions: PlayerActions,
    lyricsViewport: LyricsViewportState, openAudio: () -> Unit) = PlayerStageTheme {
    val track = state.currentTrack ?: return@PlayerStageTheme
    val artwork = rememberPlayerArtwork(track)
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    val motionEnabled = rememberLyricsMotionEnabled()
    val playerActions = actions.copy(seek = {
        lyricsViewport.follow.resume()
        actions.seek(it)
    })
    // Full immersion: only the lyrics remain. A tap on the stage or a few idle seconds while playing enter it; any tap leaves.
    val hasLyrics = lyricsSceneIdentity(state).lyrics?.lines?.isNotEmpty() == true
    var immersiveRequested by rememberSaveable { mutableStateOf(false) }
    val immersive = immersiveRequested && state.isLyricsMode && hasLyrics
    var touches by remember { mutableIntStateOf(0) }
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    LaunchedEffect(state.isLyricsMode, immersive, touches, state.isPlaying, hasLyrics, moreOpen, state.isQueueDrawerOpen) {
        if (!state.isLyricsMode) { immersiveRequested = false; return@LaunchedEffect }
        // Controls never vanish under a screen reader, where they cannot be found by sight again.
        if (immersive || !state.isPlaying || !hasLyrics || moreOpen || state.isQueueDrawerOpen ||
            accessibility?.isTouchExplorationEnabled == true) return@LaunchedEffect
        delay(LyricsMotionSpec.IdleImmersiveMillis)
        immersiveRequested = true
    }
    BackHandler(enabled = immersive) { immersiveRequested = false }
    val stageTap: (Boolean) -> Unit = { onLine -> if (immersive) immersiveRequested = false else if (!onLine) immersiveRequested = true }
    val chromeEnter = fadeIn(tween(if (motionEnabled) 320 else 0)) + expandVertically(tween(if (motionEnabled) 420 else 0))
    val chromeExit = fadeOut(tween(if (motionEnabled) 220 else 0)) + shrinkVertically(tween(if (motionEnabled) 420 else 0))
    // The lyrics page shows the colour field bare; the cover page keeps a light scrim for its text.
    val dim by animateFloatAsState(if (state.isLyricsMode) 0f else 0.26f,
        tween(if (motionEnabled) 420 else 0), label = "stageDim")
    Surface(Modifier.fillMaxSize().testTag("immersivePlayer"), color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); touches++ }
        }) {
            // One continuous living background across cover and lyrics, so switching never cuts the colour.
            if (!LocalBackgroundVisuals.current.useCover) CustomBackgroundBackdrop(Modifier.fillMaxSize())
            else FlowingCoverBackdrop(artwork, Modifier.fillMaxSize()
                .testTag(if (state.isLyricsMode) "lyricsBlurredBackdrop" else "immersiveCoverBackdrop"),
                motionEnabled && state.isPlaying && !lyricsViewport.list.isScrollInProgress, dim)
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                AnimatedVisibility(!immersive, enter = chromeEnter, exit = chromeExit) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            PressableIcon(TwilightIcons.ChevronDown, "收起播放器", { actions.setPlayerOpen(false) }, iconSize = 26.dp)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                CoverLyricsSwitch(state.isLyricsMode, actions.setLyricsMode, motionEnabled)
                            }
                            PressableIcon(TwilightIcons.More, "更多播放操作", { moreOpen = true }, iconSize = 22.dp)
                        }
                    }
                }
                AnimatedContent(
                    targetState = state.isLyricsMode,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        if (!motionEnabled) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else if (targetState) {
                            (fadeIn(tween(240)) +
                                slideInVertically(tween(300, easing = TwilightMotion.EmphasizedDecelerate)) { (it * 0.045f).toInt() } +
                                scaleIn(tween(300, easing = TwilightMotion.EmphasizedDecelerate), initialScale = 0.96f)) togetherWith
                                (fadeOut(tween(170)) +
                                    scaleOut(tween(260, easing = TwilightMotion.EmphasizedAccelerate), targetScale = 0.94f)) using
                                SizeTransform(clip = false)
                        } else {
                            (fadeIn(tween(240)) +
                                scaleIn(tween(300, easing = TwilightMotion.EmphasizedDecelerate), initialScale = 0.94f)) togetherWith
                                (fadeOut(tween(170)) +
                                    slideOutVertically(tween(250, easing = TwilightMotion.EmphasizedAccelerate)) { (it * 0.045f).toInt() } +
                                    scaleOut(tween(250, easing = TwilightMotion.EmphasizedAccelerate), targetScale = 0.96f)) using
                                SizeTransform(clip = false)
                        }
                    },
                    label = "coverLyrics"
                ) { lyrics ->
                    if (lyrics) LyricsPlayerLayout(state, playerActions, lyricsViewport, artwork, motionEnabled,
                        immersive, hasLyrics, stageTap, chromeEnter, chromeExit)
                    else CoverPlayerLayout(state, playerActions, artwork, motionEnabled)
                }
            }
        }
    }
    if (state.isQueueDrawerOpen) QueueSheet(state, actions)
    var picking by remember { mutableStateOf(false) }
    if (picking) StreamingPlaylistPicker(state.streaming, listOf(track), actions.streaming) { picking = false }
    if (moreOpen) ModalBottomSheet(onDismissRequest = { moreOpen = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(artwork, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
                    androidx.compose.ui.layout.ContentScale.Crop)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SupportingText(track.artist.ifBlank { "未知艺术家" }, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (track.ncmSongId != null && state.streaming.access == StreamingAccess.READY)
                SheetAction(TwilightIcons.Plus, "加入歌单") { moreOpen = false; picking = true }
            SheetAction(TwilightIcons.Equalizer, "音频详情与 DSP") { moreOpen = false; openAudio() }
            if (state.operatingMode is CoreOperatingMode.StandaloneLocal) {
                SheetAction(TwilightIcons.Bookmark, "添加当前位置书签", formatDurationSeconds(LocalPlayerProgress.current.positionSeconds.value)) {
                    actions.addBookmark(); moreOpen = false
                }
                SheetAction(TwilightIcons.Library, "导入 LRC 歌词") { moreOpen = false; actions.importLyrics() }
            }
            if (state.currentTrackBookmarks.isNotEmpty()) {
                Text("书签", Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.currentTrackBookmarks.forEach { bookmark ->
                    Row(Modifier.fillMaxWidth().clickable {
                        playerActions.seek(bookmark.positionSec); moreOpen = false
                    }.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDurationSeconds(bookmark.positionSec), Modifier.width(56.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(bookmark.label, Modifier.weight(1f).padding(vertical = 14.dp),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        QuietIconButton(TwilightIcons.Close, "删除书签 ${bookmark.label}", { actions.removeBookmark(bookmark.id) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Two quiet text tabs; the current one is full white and underlined by a short bar. */
@Composable
private fun CoverLyricsSwitch(lyricsMode: Boolean, select: (Boolean) -> Unit, motionEnabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(false, true).forEach { lyrics ->
            val active = lyricsMode == lyrics
            val ink by animateColorAsState(if (active) colors.onSurface else colors.onSurface.copy(alpha = 0.5f),
                tween(if (motionEnabled) 200 else 0), label = "switchInk")
            val bar by animateFloatAsState(if (active) 1f else 0f, tween(if (motionEnabled) 240 else 0), label = "switchBar")
            Column(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Tab) { select(lyrics) }
                .padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(if (lyrics) "歌词" else "封面", style = MaterialTheme.typography.titleMedium, color = ink,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                Box(Modifier.padding(top = 5.dp).width(16.dp * bar).height(3.dp).clip(CircleShape).background(ink))
            }
        }
    }
}

/** Source specifications between the times, with a separate routing line when a desktop is involved. */
@Composable
private fun PlaybackNote(state: PlayerUiState, modifier: Modifier = Modifier) {
    val remote = state.operatingMode as? CoreOperatingMode.RemoteDesktopCore
    val streamed = state.currentTrack?.id?.startsWith(com.twilightecho.android.coreclient.DesktopMediaReference.PREFIX) == true
    val note = when {
        remote != null && !remote.connected -> "电脑未连接"
        remote != null -> "在 ${remote.hostLabel} 上播放"
        streamed -> "来自电脑"
        else -> null
    }
    val warn = remote != null && !remote.connected
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        sourceQualityLabel(state)?.let { quality ->
            Text(quality, Modifier.testTag("playbackQuality"), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (note != null) Text(note, Modifier.testTag("playbackNote"), style = MaterialTheme.typography.labelSmall,
            color = if (warn) TwilightTokens.SignalWarm else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}



@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, size = 36.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) SupportingText(subtitle)
        }
    }
}

@Composable
private fun CoverPlayerLayout(state: PlayerUiState, actions: PlayerActions, artwork: TrackArtworkVisual,
    motionEnabled: Boolean) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().padding(horizontal = 28.dp).testTag("coverPlayer")) {
        val condensed = maxHeight < 640.dp || fontScale > 1.2f
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FullPlayerCover(artwork, state.isPlaying, motionEnabled, Alignment.Center,
                    Modifier.weight(1f).fillMaxHeight().padding(vertical = 16.dp))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center) {
                    CoverPlaybackControls(state, actions, condensed = true)
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            // Measure controls first, then fit the complete artwork into the remaining stage.
            // A short screen makes the image smaller, never crops it or puts text over it.
            // Sits in the upper part of the stage, nearer the tabs, as Apple Music places it.
            FullPlayerCover(artwork, state.isPlaying, motionEnabled, BiasAlignment(0f, -0.5f), Modifier.weight(1f).fillMaxWidth()
                .padding(top = 4.dp, bottom = if (condensed) 16.dp else 28.dp))
            CoverPlaybackControls(state, actions, condensed)
        }
    }
}

/**
 * The whole cover, uncropped and untinted. Like Apple Music it breathes with playback:
 * full size while playing, settling back a step on a soft spring when paused.
 */
@Composable
private fun FullPlayerCover(artwork: TrackArtworkVisual, playing: Boolean, motionEnabled: Boolean, alignment: Alignment,
    modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (playing) 1f else 0.86f,
        if (motionEnabled) spring(dampingRatio = 0.62f, stiffness = 240f) else snap(), label = "coverBreath")
    val lift by animateDpAsState(if (playing) 34.dp else 14.dp,
        if (motionEnabled) spring(dampingRatio = 0.9f, stiffness = 240f) else snap(), label = "coverLift")
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val ratio = artwork.imageBitmap.width.toFloat() / artwork.imageBitmap.height
        val width = minOf(maxWidth, maxHeight * ratio, 440.dp)
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier.width(width).aspectRatio(ratio)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(lift, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .semantics { contentDescription = "当前歌曲完整封面" }
                .testTag("fullCover")
        ) {
            Crossfade(
                targetState = artwork,
                animationSpec = tween(if (motionEnabled) 240 else 0),
                label = "fullCoverArt"
            ) { currentArtwork ->
                ArtworkImage(currentArtwork, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CoverPlaybackControls(state: PlayerUiState, actions: PlayerActions, condensed: Boolean) {
    val track = state.currentTrack ?: return
    val colors = MaterialTheme.colorScheme
    val motionEnabled = rememberLyricsMotionEnabled()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().testTag("coverMetadata"), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = track,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                contentKey = { it.id },
                transitionSpec = {
                    if (!motionEnabled) EnterTransition.None togetherWith ExitTransition.None
                    else (slideInVertically(tween(240, easing = TwilightMotion.EmphasizedDecelerate)) { it / 3 } +
                        fadeIn(tween(200))) togetherWith
                        (slideOutVertically(tween(150, easing = TwilightMotion.EmphasizedAccelerate)) { -it / 3 } +
                            fadeOut(tween(120))) using SizeTransform(clip = false)
                },
                label = "coverTrackMeta"
            ) { currentTrack ->
                Column(Modifier.fillMaxWidth()) {
                    Text(currentTrack.title, color = colors.onSurface,
                        style = (if (condensed) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
                            .copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(trackByline(currentTrack), color = colors.onSurface.copy(alpha = 0.62f),
                        style = if (condensed) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            PlayerFavoriteButton(state, actions)
        }
        Spacer(Modifier.height(if (condensed) 4.dp else 14.dp))
        SeekControl(state, actions)
        Spacer(Modifier.height(if (condensed) 0.dp else 8.dp))
        TransportRow(state, actions, Modifier.testTag("coverTransport"), if (condensed) 64.dp else 76.dp)
        Spacer(Modifier.height(if (condensed) 10.dp else 22.dp))
    }
}

private fun trackByline(track: com.twilightecho.android.coreclient.CoreTrackView): String =
    listOf(track.artist.ifBlank { "未知艺术家" }, track.album).filter { it.isNotBlank() }.joinToString(" · ")

/** Mode, previous, play, next, queue: plain white glyphs on the colour field, as in Apple Music. */
@Composable
private fun TransportRow(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier, playSize: Dp = 76.dp) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        PlayerModeButton(state, actions)
        PressableIcon(TwilightIcons.SkipPrevious, "上一首", actions.previous, size = 60.dp, iconSize = 30.dp)
        PlayPauseButton(state, actions, playSize)
        PressableIcon(TwilightIcons.SkipNext, "下一首", actions.next, size = 60.dp, iconSize = 30.dp)
        PressableIcon(TwilightIcons.Queue, "播放队列", { actions.setQueueOpen(true) }, iconSize = 20.dp,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
    }
}

/**
 * A bare glyph with Apple-style touch feedback: it shrinks under the finger while a faint disc
 * blooms behind it, then springs back. The touch target never drops below 48dp.
 */
@Composable
private fun PressableIcon(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    size: Dp = 48.dp, iconSize: Dp = 24.dp, tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true, lit: Boolean = false) {
    val motionEnabled = rememberLyricsMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.82f else 1f,
        if (motionEnabled) spring(dampingRatio = 0.5f, stiffness = 700f) else snap(),
        label = "press"
    )
    val disc by animateFloatAsState(
        if (pressed) 0.16f else if (lit) 0.14f else 0f,
        tween(if (motionEnabled) 140 else 0),
        label = "pressDisc"
    )
    val animatedTint by animateColorAsState(
        if (enabled) tint else tint.copy(alpha = 0.38f),
        tween(if (motionEnabled) 180 else 0),
        label = "pressTint"
    )
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.size(size).clip(CircleShape)
        .semantics { contentDescription = label }
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .drawBehind { drawCircle(ink.copy(alpha = disc), radius = this.size.minDimension / 2 * (0.72f + 0.28f * scale)) },
        contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                if (!motionEnabled) EnterTransition.None togetherWith ExitTransition.None
                else (scaleIn(spring(dampingRatio = 0.65f, stiffness = 560f), initialScale = 0.72f) + fadeIn(tween(130))) togetherWith
                    (scaleOut(tween(110, easing = TwilightMotion.EmphasizedAccelerate), targetScale = 0.72f) + fadeOut(tween(100))) using
                    SizeTransform(clip = false)
            },
            label = "pressGlyph"
        ) { targetIcon ->
            Icon(targetIcon, null, Modifier.size(iconSize).graphicsLayer { scaleX = scale; scaleY = scale },
                tint = animatedTint)
        }
    }
}

/** The big plain play glyph; no filled disc, the cover's colour field is the button's backdrop. */
@Composable
private fun PlayPauseButton(state: PlayerUiState, actions: PlayerActions, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val motionEnabled = rememberLyricsMotionEnabled()
    val loading = state.requestedPlaying == null && state.playbackState in listOf("loading", "buffering")
    Box(Modifier.size(size).testTag("playerPlayPause"), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = {
                if (!motionEnabled) EnterTransition.None togetherWith ExitTransition.None
                else (scaleIn(spring(dampingRatio = 0.7f, stiffness = 500f), initialScale = 0.75f) + fadeIn(tween(150))) togetherWith
                    (scaleOut(tween(120), targetScale = 0.75f) + fadeOut(tween(110))) using SizeTransform(clip = false)
            },
            label = "playPauseState"
        ) { isLoading ->
            if (isLoading) {
                Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(size * 0.42f), strokeWidth = 3.dp, color = colors.onSurface)
                }
            } else {
                PressableIcon(
                    if (state.playbackButtonPlaying) TwilightIcons.Pause else TwilightIcons.Play,
                    if (state.playbackButtonPlaying) "暂停播放" else "开始播放",
                    actions.togglePlayback,
                    size = size,
                    iconSize = size * 0.53f
                )
            }
        }
    }
}

@Composable
private fun LyricsPlayerLayout(state: PlayerUiState, actions: PlayerActions,
    viewport: LyricsViewportState, artwork: TrackArtworkVisual, motionEnabled: Boolean,
    immersive: Boolean, hasLyrics: Boolean, onStageTap: (onLine: Boolean) -> Unit,
    chromeEnter: EnterTransition, chromeExit: ExitTransition) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    val targetInset = if (immersive) 12.dp else 0.dp
    val inset = animateDpAsState(targetInset, tween(if (motionEnabled) 420 else 0), label = "lyricsInset")
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)
        .then(if (immersive) Modifier.testTag("lyricsImmersive") else Modifier)) {
        val lyrics = lyricsSceneIdentity(state).lyrics
        val condensed = maxHeight < 600.dp || density.fontScale > 1.2f
        val import = actions.importLyrics.takeIf { state.operatingMode is CoreOperatingMode.StandaloneLocal }
        // Reserve the final width once; animate placement without re-wrapping every line on every frame.
        val stage = Modifier.padding(start = targetInset)
            .graphicsLayer { translationX = (inset.value - targetInset).toPx() }
            .then(if (hasLyrics) Modifier.stageTaps(immersive, onStageTap) else Modifier)
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1.2f).fillMaxHeight()) {
                    LyricsStage(lyrics, LocalPlayerProgress.current.lyricIndex.value, viewport, state.isLyricsMode, motionEnabled,
                        actions.seek, import, stage.weight(1f), state.preferences.lyrics)
                }
                if (!immersive) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center) {
                    LyricsTrackHeader(state, actions, artwork, condensed = true)
                    Spacer(Modifier.height(8.dp))
                    SeekControl(state, actions)
                    TransportRow(state, actions, playSize = 64.dp)
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            // Apple Music's lyrics page: who is singing at the top, the lines filling the stage, transport at the foot.
            AnimatedVisibility(!immersive, enter = chromeEnter, exit = chromeExit) {
                LyricsTrackHeader(state, actions, artwork, condensed)
            }
            LyricsStage(lyrics, LocalPlayerProgress.current.lyricIndex.value, viewport, state.isLyricsMode, motionEnabled,
                actions.seek, import, stage.weight(1f).fillMaxWidth(), state.preferences.lyrics)
            AnimatedVisibility(!immersive, enter = chromeEnter, exit = chromeExit) {
                Column {
                    SeekControl(state, actions)
                    TransportRow(state, actions, playSize = if (condensed) 60.dp else 68.dp)
                    Spacer(Modifier.height(if (condensed) 6.dp else 14.dp))
                }
            }
        }
    }
}

/**
 * Taps on the lyrics stage, seen after the lines have had their turn. A tap on empty stage enters
 * full immersion; while immersed, any tap brings the controls back (a tapped line still seeks).
 * Drags and long presses are ignored, so browsing the lyrics never toggles anything.
 */
private fun Modifier.stageTaps(immersive: Boolean, onTap: (onLine: Boolean) -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var moved = false
            var up: PointerInputChange? = null
            while (up == null) {
                val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (change.changedToUpIgnoreConsumed()) up = change
            }
            if (up != null && !moved && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis)
                tap(up.isConsumed)
        }
    }.semantics {
        customActions = listOf(CustomAccessibilityAction(if (immersive) "显示播放控制" else "只看歌词") { tap(false); true })
    }
}

@Composable
private fun LyricsTrackHeader(state: PlayerUiState, actions: PlayerActions, artwork: TrackArtworkVisual,
    condensed: Boolean) {
    val track = state.currentTrack ?: return
    val colors = MaterialTheme.colorScheme
    val motionEnabled = rememberLyricsMotionEnabled()
    val cover = if (condensed) 60.dp else 76.dp
    val coverShape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = if (condensed) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(cover)
                .shadow(14.dp, coverShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(coverShape)
        ) {
            Crossfade(
                targetState = artwork,
                animationSpec = tween(if (motionEnabled) 240 else 0),
                label = "lyricsHeaderCover"
            ) { currentArtwork ->
                ArtworkImage(currentArtwork, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Crop)
            }
        }
        AnimatedContent(
            targetState = track,
            modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp),
            contentKey = { it.id },
            transitionSpec = {
                if (!motionEnabled) EnterTransition.None togetherWith ExitTransition.None
                else (slideInVertically(tween(240, easing = TwilightMotion.EmphasizedDecelerate)) { it / 3 } +
                    fadeIn(tween(200))) togetherWith
                    (slideOutVertically(tween(150, easing = TwilightMotion.EmphasizedAccelerate)) { -it / 3 } +
                        fadeOut(tween(120))) using SizeTransform(clip = false)
            },
            label = "lyricsTrackMeta"
        ) { currentTrack ->
            Column(Modifier.fillMaxWidth()) {
                Text(currentTrack.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(trackByline(currentTrack), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface.copy(alpha = 0.62f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        PlayerFavoriteButton(state, actions)
    }
}

@Composable
private fun PlayerFavoriteButton(state: PlayerUiState, actions: PlayerActions) {
    if (state.operatingMode is CoreOperatingMode.RemoteDesktopCore) return
    val motionEnabled = rememberLyricsMotionEnabled()
    val popScale = remember { Animatable(1f) }
    var previousFavorite by remember(state.currentTrack?.id) { mutableStateOf(state.isCurrentTrackFavorite) }
    LaunchedEffect(state.isCurrentTrackFavorite, motionEnabled) {
        if (state.isCurrentTrackFavorite && !previousFavorite && motionEnabled) {
            popScale.animateTo(1.24f, tween(110, easing = TwilightMotion.EmphasizedDecelerate))
            popScale.animateTo(1f, spring(dampingRatio = 0.52f, stiffness = 520f))
        } else {
            popScale.snapTo(1f)
        }
        previousFavorite = state.isCurrentTrackFavorite
    }
    PressableIcon(
        if (state.isCurrentTrackFavorite) TwilightIcons.HeartFilled else TwilightIcons.HeartOutline,
        if (state.isCurrentTrackFavorite) "取消收藏" else "收藏歌曲",
        actions.toggleFavorite,
        Modifier.graphicsLayer {
            scaleX = popScale.value
            scaleY = popScale.value
        },
        iconSize = 26.dp,
        tint = if (state.isCurrentTrackFavorite) TwilightTokens.FavoriteRose else MaterialTheme.colorScheme.onSurface,
        enabled = !state.isFavoriteBusy
    )
}

@Composable
private fun PlayerModeButton(state: PlayerUiState, actions: PlayerActions) {
    val (icon, label) = when (state.playMode) {
        PlayMode.SEQUENCE -> TwilightIcons.RepeatSequence to "顺序播放"
        PlayMode.LOOP -> TwilightIcons.RepeatAll to "列表循环"
        PlayMode.SINGLE -> TwilightIcons.RepeatOne to "单曲循环"
        PlayMode.SHUFFLE -> TwilightIcons.Shuffle to "随机播放"
        PlayMode.HEART -> TwilightIcons.HeartFilled to "心动模式"
    }
    // Any mode other than plain sequence is "on": full white on a faint disc, like a lit Apple toggle.
    val on = state.playMode != PlayMode.SEQUENCE
    PressableIcon(icon, "播放模式：$label", actions.cyclePlayMode, iconSize = 20.dp, lit = on,
        tint = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
}

/** A hairline that thickens while dragged; white fill and thumb, times and the source quality beneath. */
@Composable
private fun SeekControl(state: PlayerUiState, actions: PlayerActions) {
    val colors = MaterialTheme.colorScheme
    val duration = state.durationSeconds.coerceAtLeast(0.0)
    val external = LocalPlayerProgress.current.positionSeconds.value.coerceIn(0.0, duration).toFloat()
    var dragging by remember(state.currentTrack?.id) { mutableStateOf(false) }
    var draft by remember(state.currentTrack?.id) { mutableFloatStateOf(external) }
    var pendingTarget by remember(state.currentTrack?.id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(external, pendingTarget) {
        val target = pendingTarget ?: return@LaunchedEffect
        if (kotlin.math.abs(external - target) <= 0.5f) {
            pendingTarget = null
        }
    }
    LaunchedEffect(pendingTarget) {
        val target = pendingTarget ?: return@LaunchedEffect
        kotlinx.coroutines.delay(1500L)
        if (pendingTarget == target) pendingTarget = null
    }
    val position = when {
        dragging -> draft
        pendingTarget != null -> pendingTarget!!
        else -> external
    }
    val motionEnabled = rememberLyricsMotionEnabled()
    val thickness by animateDpAsState(
        if (dragging) 8.dp else 4.dp,
        if (motionEnabled) spring(dampingRatio = 0.7f, stiffness = 500f) else snap(),
        label = "seekThickness"
    )
    val thumbSize by animateDpAsState(
        if (dragging) 18.dp else 12.dp,
        if (motionEnabled) spring(dampingRatio = 0.68f, stiffness = 520f) else snap(),
        label = "seekThumb"
    )
    Slider(value = position, onValueChange = { draft = it; pendingTarget = null; dragging = true },
        valueRange = 0f..duration.coerceAtLeast(1.0).toFloat(),
        enabled = duration > 0, onValueChangeFinished = {
            val target = draft
            pendingTarget = target
            dragging = false
            actions.seek(target.toDouble())
        },
        thumb = {
            Box(Modifier.size(thumbSize).shadow(3.dp, CircleShape)
                .clip(CircleShape).background(colors.onSurface))
        },
        track = { slider ->
            val fraction = (slider.value / duration.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(thickness).clip(CircleShape)
                .background(colors.onSurface.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape)
                    .background(colors.onSurface.copy(alpha = if (dragging) 1f else 0.9f)))
            }
        }, modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "播放进度" }.testTag("seek"))
    Row(Modifier.fillMaxWidth().offset(y = (-4).dp), verticalAlignment = Alignment.CenterVertically) {
        // Equal-width time columns keep the note optically centred under the bar.
        Text(formatDurationSeconds(position.toDouble()), Modifier.widthIn(min = 48.dp),
            style = MaterialTheme.typography.labelMedium, color = colors.onSurface.copy(alpha = if (dragging) 1f else 0.62f))
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            PlaybackNote(state)
        }
        Text("-" + formatDurationSeconds((duration - position).coerceAtLeast(0.0)), Modifier.widthIn(min = 48.dp),
            style = MaterialTheme.typography.labelMedium, color = colors.onSurface.copy(alpha = 0.62f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}



@Composable
private fun QueueSheet(state: PlayerUiState, actions: PlayerActions) {
    val list = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(state.queueItems) {
        if (!positioned) {
            val index = state.queueItems.indexOfFirst { it.index == state.currentQueueIndex }
            if (index >= 0) { list.scrollToItem(index); positioned = true }
        }
    }
    ModalBottomSheet(onDismissRequest = { actions.setQueueOpen(false) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer.copy(alpha = 1f)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).testTag("queueSheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("播放队列", style = MaterialTheme.typography.headlineSmall)
                    SupportingText("${state.queueTotal} 首", Modifier.padding(top = 2.dp))
                }
                QuietIconButton(TwilightIcons.Close, "关闭队列", { actions.setQueueOpen(false) },
                    tint = colors.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
            if (state.queueTotal == 0) EmptyState("队列为空", "从音乐库选择一首歌曲开始播放。")
            val openSongMenu = LocalSongMenuOpener.current
            LazyColumn(Modifier.weight(1f).testTag("queueList"), state = list,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                items(state.queueItems, key = { it.occurrenceId }) { entry ->
                    val current = entry.index == state.currentQueueIndex
                    val entryTrack = entry.track
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (current) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .then(
                            if (entryTrack != null) {
                                Modifier.songRowClickable(
                                    track = entryTrack,
                                    onClick = { actions.selectQueueEntry(entry) },
                                    onLongClick = {
                                        openSongMenu(SongMenuRequest(track = entryTrack, queueEntry = entry))
                                    }
                                )
                            } else {
                                Modifier.clickable(enabled = false) {}
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp).testTag("queue-${entry.occurrenceId}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (current) Icon(TwilightIcons.SignalWave, "当前曲目", Modifier.size(24.dp), tint = colors.primary)
                        else Text((entry.index + 1).toString(), Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(entry.track?.title ?: "曲目信息暂不可用", style = MaterialTheme.typography.titleMedium,
                                color = if (current) colors.primary else colors.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            SupportingText(entry.track?.artist.orEmpty(), maxLines = 1)
                        }
                        Text(formatDurationSeconds(entry.track?.duration ?: 0.0), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                    }
                }
                item {
                    when {
                        state.isLoadingQueue -> LoadingContent("正在载入队列")
                        state.queueError != null -> EmptyState("队列载入失败", state.queueError,
                            actionLabel = "重试", onAction = actions.loadMoreQueue)
                        nextQueueOffset(state.queueItems, state.queueTotal) != null ->
                            TextButton(onClick = actions.loadMoreQueue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("加载更多", color = colors.primary)
                            }
                    }
                }
            }
        }
    }
}
