package com.twilightecho.android.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.TwilightTokens

internal fun parseProviderAccent(hex: String?, fallback: Color): Color {
    val clean = hex?.trim()?.removePrefix("#") ?: return fallback
    val rgb = when (clean.length) {
        6 -> clean.toLongOrNull(16)?.let { 0xFF000000L or it }
        8 -> clean.toLongOrNull(16)
        else -> null
    } ?: return fallback
    return Color(rgb.toInt())
}

/** Pill bar on the Home tab allowing instant switching between enabled streaming providers. */
@Composable
internal fun HomeProviderSwitcher(
    providers: List<HomeProviderOption>,
    selectedPluginId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val motionEnabled = rememberMotionEnabled()
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp).testTag("home-provider-switcher"),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(providers, key = { it.pluginId }) { option ->
            val selected = option.pluginId == selectedPluginId
            val accent = parseProviderAccent(option.colorHex, colors.primary)
            val interaction = remember { MutableInteractionSource() }
            val bg by animateColorAsState(
                targetValue = if (selected) accent.copy(alpha = 0.18f) else colors.surfaceContainer,
                animationSpec = tween(if (motionEnabled) 180 else 0),
                label = "providerChipBg"
            )
            val fg by animateColorAsState(
                targetValue = if (selected) colors.onSurface else colors.onSurfaceVariant,
                animationSpec = tween(if (motionEnabled) 180 else 0),
                label = "providerChipFg"
            )
            Row(
                Modifier.tactilePress(interaction, pressedScale = 0.95f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .then(
                        if (selected) Modifier.border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(50))
                        else Modifier
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.Tab,
                        onClick = { onSelect(option.pluginId) }
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("home-provider-${option.pluginId}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (selected) accent else accent.copy(alpha = 0.65f))
                )
                Text(
                    option.name,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Populates the Home `LazyColumn` for an ABI v2 streaming provider (QQ 音乐, 酷狗音乐, 汽水音乐, etc.). */
internal fun LazyListScope.providerHomeItems(
    state: ProviderHomeUiState,
    currentTrackId: String?,
    isPlaying: Boolean,
    actions: ProviderHomeActions
) {
    val ready = state.access == StreamingAccess.READY
    val name = state.providerName.ifBlank { "在线音源" }

    item(key = "provider-header-${state.pluginId}") {
        ProviderHomeHeader(state, actions)
    }

    if (state.requiresDisclaimer) {
        item(key = "provider-disclaimer-${state.pluginId}") {
            EmptyState(
                "登录 $name",
                "首次使用${name}前，请在扫码登录前阅读并确认免责声明。确认后即可浏览推荐榜单、精选歌单与个人收藏。",
                TwilightIcons.SignalWave,
                modifier = Modifier.testTag("provider-home-disclaimer"),
                actionLabel = "阅读声明并登录",
                onAction = actions.account
            )
        }
        return
    }

    if (state.access == StreamingAccess.LOADING && state.sections.all { !it.loaded } && state.recommendedPlaylists.isEmpty()) {
        item(key = "provider-loading-${state.pluginId}") {
            LoadingContent("正在连接$name")
        }
        return
    }

    if (state.access == StreamingAccess.ERROR && state.sections.all { !it.loaded }) {
        item(key = "provider-error-${state.pluginId}") {
            EmptyState(
                "${name}暂时无法连接",
                state.accountError ?: "请检查网络后重试。",
                TwilightIcons.SignalWave,
                actionLabel = "重新连接",
                onAction = actions.refresh
            )
        }
        return
    }

    if (!ready && state.homeRequiresLogin) {
        item(key = "provider-locked-${state.pluginId}") {
            EmptyState(
                "登录后查看$name",
                state.subtitle.ifBlank { "登录账号以同步你的歌单、收藏夹与个性化推荐。" },
                TwilightIcons.SignalWave,
                actionLabel = "登录$name",
                onAction = actions.account
            )
        }
        return
    }

    if (state.sections.isNotEmpty()) {
        item(key = "provider-sections-${state.pluginId}") {
            val accent = parseProviderAccent(state.colorHex, MaterialTheme.colorScheme.primary)
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("provider-home-sections"),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.sections, key = { it.id }) { section ->
                    val locked = section.requiresLogin && !ready
                    ProviderRecommendationCard(
                        section = section,
                        providerName = name,
                        accent = accent,
                        locked = locked,
                        onOpen = {
                            if (locked) actions.account() else actions.openSection(section.id)
                        },
                        onPlay = section.tracks.firstOrNull { it.unavailableReason == null }
                            ?.takeIf { !locked }
                            ?.let { first -> { actions.play(section.tracks, first) } }
                    )
                }
            }
        }
    }

    if (!ready) {
        item(key = "provider-login-strip-${state.pluginId}") {
            ProviderLoginBanner(name, state.colorHex, actions.account)
        }
    }

    val previewSection = state.sections.firstOrNull { section ->
        !(section.requiresLogin && !ready) && section.tracks.isNotEmpty()
    }
    if (previewSection != null) {
        item(key = "provider-quick-heading-${state.pluginId}") {
            SectionLink(
                "先听这几首 · ${previewSection.title}",
                { actions.openSection(previewSection.id) },
                Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 4.dp)
            )
        }
        val previewTracks = previewSection.tracks.take(5)
        itemsIndexed(previewTracks, key = { index, track -> "quick-${state.pluginId}-$index-${track.id}" }) { _, track ->
            ProviderQuickTrackRow(
                track = track,
                isCurrent = currentTrackId == track.id,
                isPlaying = isPlaying && currentTrackId == track.id,
                onClick = { actions.play(previewSection.tracks, track) }
            )
        }
    }

    if (ready && state.supportsLibrary) {
        if (state.likedPlaylist != null || state.playlists.isNotEmpty()) {
            item(key = "provider-shortcuts-${state.pluginId}") {
                ProviderLibraryShortcuts(state, actions)
            }
        }
        when {
            state.playlists.isEmpty() && state.libraryLoading -> item(key = "provider-lib-loading-${state.pluginId}") {
                LoadingContent("正在载入歌单")
            }
            state.playlists.isEmpty() && state.libraryError != null -> item(key = "provider-lib-error-${state.pluginId}") {
                EmptyState("歌单未载入", state.libraryError, actionLabel = "重试", onAction = actions.refresh)
            }
            state.playlists.isNotEmpty() -> item(key = "provider-own-playlists-${state.pluginId}") {
                ProviderPlaylistShelf(
                    title = "我的歌单",
                    playlists = state.playlists,
                    tag = "provider-home-playlists",
                    onOpen = actions.openCollection,
                    more = actions.openAllPlaylists
                )
            }
        }
    }

    if (state.recommendedPlaylists.isNotEmpty()) {
        item(key = "provider-recommended-playlists-${state.pluginId}") {
            ProviderPlaylistShelf(
                title = "推荐歌单",
                playlists = state.recommendedPlaylists,
                tag = "provider-home-recommended",
                onOpen = actions.openCollection
            )
        }
    }

    val hasAnyContent = state.sections.isNotEmpty() || state.playlists.isNotEmpty() ||
        state.recommendedPlaylists.isNotEmpty() || state.likedPlaylist != null
    if (!hasAnyContent && !state.libraryLoading && !state.recommendedLoading) {
        item(key = "provider-empty-${state.pluginId}") {
            EmptyState(
                "${name}已就绪",
                "暂未读取到推荐或歌单内容，可点击刷新或打开音源搜索歌曲。",
                TwilightIcons.SignalWave,
                actionLabel = if (state.supportsSearch) "搜索$name" else "刷新",
                onAction = if (state.supportsSearch) actions.openProviderPage else actions.refresh
            )
        }
    }
}

@Composable
private fun ProviderHomeHeader(state: ProviderHomeUiState, actions: ProviderHomeActions) {
    val name = state.providerName.ifBlank { "在线音源" }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 4.dp).testTag("providerHome")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "发现",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                state.subtitle.takeIf { it.isNotBlank() }?.let {
                    SupportingText(it, Modifier.padding(top = 2.dp), maxLines = 1)
                }
            }
            if (state.supportsSearch && !state.requiresDisclaimer) {
                GlassIconButton(
                    TwilightIcons.Search,
                    "搜索$name",
                    actions.openProviderPage,
                    Modifier.padding(end = 6.dp).testTag("provider-home-search")
                )
            }
            ProviderHomeAccountButton(state, actions)
        }
    }
}

@Composable
private fun ProviderHomeAccountButton(state: ProviderHomeUiState, actions: ProviderHomeActions) {
    val colors = MaterialTheme.colorScheme
    val name = state.providerName.ifBlank { "音源" }
    val onClick = if (state.access == StreamingAccess.ERROR) actions.refresh else actions.account
    val enabled = state.access != StreamingAccess.LOADING
    val interaction = remember { MutableInteractionSource() }
    if (state.access == StreamingAccess.READY) {
        Box(
            Modifier.tactilePress(interaction, pressedScale = 0.90f, enabled = enabled)
                .size(44.dp).clip(CircleShape)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
                .testTag("provider-home-account")
                .semantics { contentDescription = "${name}账号" },
            contentAlignment = Alignment.Center
        ) {
            NcmAvatar(state.nickname, state.profile?.avatarUrl, Modifier.fillMaxSize())
        }
    } else {
        Text(
            when (state.access) {
                StreamingAccess.ERROR -> "重试"
                StreamingAccess.LOADING -> "连接中"
                else -> "登录$name"
            },
            Modifier.tactilePress(interaction, pressedScale = 0.94f, enabled = enabled)
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceContainer)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
                .testTag("provider-home-account")
                .padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) colors.primary else colors.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun ProviderRecommendationCard(
    section: ProviderHomeSectionUi,
    providerName: String,
    accent: Color,
    locked: Boolean,
    onOpen: () -> Unit,
    onPlay: (() -> Unit)?
) {
    val covers = section.tracks.take(6)
    val shape = RoundedCornerShape(18.dp)
    val motionEnabled = rememberMotionEnabled()
    val cardInteraction = remember { MutableInteractionSource() }
    val ink by animateColorAsState(
        if (covers.isEmpty()) MaterialTheme.colorScheme.onSurface else Color.White,
        tween(if (motionEnabled) 220 else 0),
        label = "providerRecInk"
    )
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    Box(
        Modifier.tactilePress(cardInteraction, pressedScale = 0.97f)
            .width(272.dp).height(174.dp).clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(accent.copy(alpha = 0.22f), surfaceColor)
                )
            )
            .clickable(interactionSource = cardInteraction, indication = LocalIndication.current, role = Role.Button, onClick = onOpen)
            .testTag("provider-rec-${section.id}")
    ) {
        AnimatedVisibility(
            visible = covers.isNotEmpty(),
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(if (motionEnabled) 260 else 0)),
            exit = fadeOut(tween(if (motionEnabled) 160 else 0))
        ) {
            Box(Modifier.fillMaxSize()) {
                if (covers.isNotEmpty()) {
                    Column(Modifier.matchParentSize()) {
                        repeat(2) { row ->
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                repeat(3) { column ->
                                    TrackArt(covers[(row * 3 + column) % covers.size], Modifier.weight(1f).fillMaxHeight(), ContentScale.Crop)
                                }
                            }
                        }
                    }
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.54f)))
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(providerSectionIcon(section), null, Modifier.size(22.dp), tint = if (covers.isEmpty()) accent else ink)
            if (section.eyebrow.isNotBlank()) {
                Text(
                    section.eyebrow,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = ink.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        AnimatedVisibility(
            visible = onPlay != null,
            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
            enter = TwilightMotion.pillPopEnter(motionEnabled),
            exit = TwilightMotion.pillPopExit(motionEnabled)
        ) {
            val playInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier.tactilePress(playInteraction, pressedScale = 0.88f)
                    .size(40.dp).clip(CircleShape)
                    .background(Color.White)
                    .clickable(interactionSource = playInteraction, indication = LocalIndication.current, role = Role.Button, onClick = { onPlay?.invoke() }),
                contentAlignment = Alignment.Center
            ) {
                Icon(TwilightIcons.Play, "播放${section.title}", Modifier.size(20.dp), tint = Color.Black)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                providerSectionCaption(section, providerName, locked),
                style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun providerSectionCaption(section: ProviderHomeSectionUi, providerName: String, locked: Boolean): String = when {
    locked -> "登录后查看${section.title}"
    section.loading -> "正在准备…"
    section.error != null -> "点击重试"
    section.description.isNotBlank() -> section.description
    section.tracks.isNotEmpty() -> "${section.tracks.size} 首 · $providerName"
    else -> providerName
}

private fun providerSectionIcon(section: ProviderHomeSectionUi): ImageVector {
    val hint = "${section.id} ${section.icon}".lowercase(java.util.Locale.ROOT)
    return when {
        "heart" in hint || "fav" in hint || "like" in hint -> TwilightIcons.HeartFilled
        "fm" in hint || "radio" in hint || "compass" in hint -> TwilightIcons.SignalWave
        "chart" in hint || "soar" in hint || "ris" in hint || "hot" in hint || "fire" in hint -> TwilightIcons.Equalizer
        else -> TwilightIcons.Sparkles
    }
}

@Composable
private fun ProviderLoginBanner(providerName: String, colorHex: String?, onLogin: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = parseProviderAccent(colorHex, colors.primary)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onLogin)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(TwilightIcons.SignalWave, null, Modifier.size(20.dp), tint = accent)
        Text(
            "登录后可同步每日推荐与个人歌单",
            Modifier.weight(1f).padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(onClick = onLogin) {
            Text("登录$providerName")
        }
    }
}

@Composable
private fun ProviderQuickTrackRow(
    track: CoreTrackView,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val available = track.unavailableReason == null
    val alpha = if (available) 1f else 0.45f
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = available, onClick = onClick)
            .then(if (isCurrent) Modifier.background(colors.primary.copy(alpha = 0.10f)) else Modifier)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackArt(track, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) colors.primary else colors.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SupportingText(
                track.unavailableReason ?: listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                Modifier.padding(top = 2.dp),
                maxLines = 1
            )
        }
        if (isCurrent) {
            PlayingBars(isPlaying, colors.primary, Modifier.padding(end = 8.dp))
        } else if (track.duration > 0) {
            Text(
                formatDurationSeconds(track.duration),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProviderLibraryShortcuts(state: ProviderHomeUiState, actions: ProviderHomeActions) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.likedPlaylist?.let { liked ->
            ProviderShortcutCard(
                icon = TwilightIcons.HeartFilled,
                tint = TwilightTokens.FavoriteRose,
                title = liked.title.ifBlank { "我喜欢的音乐" },
                subtitle = liked.count.takeIf { it > 0 }?.let { "$it 首" },
                modifier = Modifier.weight(1f).testTag("provider-home-liked"),
                onClick = actions.openLiked
            )
        }
        if (state.playlists.isNotEmpty()) {
            ProviderShortcutCard(
                icon = TwilightIcons.Queue,
                tint = parseProviderAccent(state.colorHex, colors.primary),
                title = "我的歌单",
                subtitle = "${state.playlists.size} 个歌单",
                modifier = Modifier.weight(1f).testTag("provider-home-all-playlists"),
                onClick = actions.openAllPlaylists
            )
        }
    }
}

@Composable
private fun ProviderShortcutCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.tactilePress(interaction, pressedScale = 0.96f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) SupportingText(subtitle, maxLines = 1)
        }
    }
}

@Composable
private fun ProviderPlaylistShelf(
    title: String,
    playlists: List<StreamingCollection>,
    tag: String,
    onOpen: (StreamingCollection) -> Unit,
    more: (() -> Unit)? = null
) {
    val heading = Modifier.padding(horizontal = 20.dp).padding(top = 24.dp)
    if (more != null) SectionLink(title, more, heading)
    else SectionHeading(title, modifier = heading.padding(vertical = 6.dp))
    LazyRow(
        Modifier.padding(top = 8.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(playlists.distinctBy { it.id }, key = { it.id }) { item ->
            val sub = item.subtitle.ifBlank { item.count.takeIf { it > 0 }?.let { "$it 首" }.orEmpty() }.takeIf { it.isNotBlank() }
            ArtworkTile(
                rememberStreamingArtwork(item, size = 512, extractColors = false),
                item.title,
                sub,
                { onOpen(item) },
                Modifier.width(148.dp).testTag("provider-playlist-${item.id}")
            )
        }
    }
}
