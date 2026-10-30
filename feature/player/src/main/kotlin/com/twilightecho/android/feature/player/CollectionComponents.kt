package com.twilightecho.android.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twilightecho.android.coreclient.CollectionSort
import com.twilightecho.android.coreclient.CollectionSortField
import com.twilightecho.android.ui.components.*
import kotlinx.coroutines.delay

@Composable
internal fun LibraryArtistOverview(name: String, summary: String, profile: LibraryArtistProfile?, onBack: () -> Unit, retry: () -> Unit) {
    val metadata = profile?.metadata
    var now by remember(metadata) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(metadata, now) {
        val expiry = metadata?.artwork.orEmpty().mapNotNull { it.usage?.expiresAt }.filter { it > now }.minOrNull()
        if (expiry != null) { delay(expiry - now); now = System.currentTimeMillis() }
    }
    val external = metadata?.artwork?.firstOrNull { it.usage?.canDisplay(now) != false }
    val preview = when (metadata?.artworkOverride?.mode) {
        "clear" -> null
        "value" -> metadata.manualArtwork?.dataUrl
        else -> metadata?.manualArtwork?.dataUrl ?: external?.dataUrl
    }
    val artwork = rememberTrackArtwork(metadata?.entityId, name, null, null, previewDataUrl = preview,
        previewOnly = true, cachePreview = false)
    val intro = metadata?.introduction
    ImmersiveArtistHero(metadata?.name?.ifBlank { null } ?: name, summary, artwork, onBack, "返回音乐库")
    external?.attribution?.takeIf { preview != null && preview == external.dataUrl }?.let {
        SupportingText(listOf("图片来源", it.provider, it.license, it.attribution).filter { it.isNotBlank() }.joinToString(" · "),
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
    }
    when {
        intro?.value?.isNotBlank() == true -> ArtistBiography(intro.value,
            intro.attribution?.let { listOf(it.provider, it.license, it.attribution).filter { it.isNotBlank() }.joinToString(" · ") })
        profile?.loading == true -> SupportingText("正在载入歌手简介…", Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        profile?.error != null -> Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            SupportingText("歌手简介未载入", Modifier.weight(1f))
            TextButton(onClick = retry) { Text("重试") }
        }
        else -> SupportingText("暂无歌手简介", Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

@Composable
internal fun PlaylistBrowseControls(query: String, onQuery: (String) -> Unit, sort: CollectionSort,
    onSort: (CollectionSort) -> Unit, count: Int, total: Int, busy: Boolean = false) {
    val keyboard = LocalSoftwareKeyboardController.current
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val motionEnabled = rememberMotionEnabled()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("playlistBrowseControls")) {
        TextField(query, onQuery, Modifier.fillMaxWidth().testTag("playlistSearch"), singleLine = true,
            placeholder = { Text("搜索歌单内的歌曲") }, leadingIcon = { Icon(TwilightIcons.Search, null) },
            trailingIcon = {
                AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = TwilightMotion.pillPopEnter(motionEnabled),
                    exit = TwilightMotion.pillPopExit(motionEnabled)
                ) {
                    QuietIconButton(TwilightIcons.Close, "清除歌单搜索", { onQuery("") })
                }
            },
            shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = TextFieldDefaults.colors(focusedContainerColor = colors.surfaceContainerHigh,
                unfocusedContainerColor = colors.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (busy) "正在载入全部歌曲…" else if (query.isBlank()) "$total 首歌曲" else "找到 $count 首 · 共 $total 首",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.testTag("playlistSort")) {
                    Text(sortLabel(sort))
                    Icon(TwilightIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(18.dp))
                }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp), containerColor = colors.surfaceContainerHigh) {
                    CollectionSortField.entries.forEach { field ->
                        DropdownMenuItem(text = { Text(sortFieldLabel(field)) },
                            trailingIcon = { if (sort.field == field) Text("✓", color = colors.primary) },
                            modifier = Modifier.testTag("playlistSort-$field"),
                            onClick = { menu = false; onSort(sort.copy(field = field)) })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    listOf(false, true).forEach { descending ->
                        DropdownMenuItem(text = { Text(if (descending) "倒序" else "正序") },
                            trailingIcon = { if (sort.descending == descending) Text("✓", color = colors.primary) },
                            modifier = Modifier.testTag(if (descending) "playlistDescending" else "playlistAscending"),
                            onClick = { menu = false; onSort(sort.copy(descending = descending)) })
                    }
                }
            }
        }
    }
}

private fun sortFieldLabel(field: CollectionSortField) = when (field) {
    CollectionSortField.ORIGINAL -> "默认顺序"
    CollectionSortField.TITLE -> "歌曲名称"
    CollectionSortField.ARTIST -> "歌手名称"
    CollectionSortField.ALBUM -> "专辑名称"
}

private fun sortLabel(sort: CollectionSort): String = if (sort.field == CollectionSortField.ORIGINAL)
    if (sort.descending) "倒序" else "正序"
    else "${sortFieldLabel(sort.field)} · ${if (sort.descending) "倒序" else "正序"}"

/** The portrait is the page, not a circular thumbnail. The lower scrim meets the page's actual surface. */
@Composable
internal fun ImmersiveArtistHero(name: String, subtitle: String, artwork: TrackArtworkVisual?, onBack: () -> Unit,
    backLabel: String, imageTag: String = "artistPortrait", trailing: @Composable RowScope.() -> Unit = {}) {
    val base = MaterialTheme.colorScheme.background
    val motionEnabled = rememberMotionEnabled()
    val backInteraction = remember { MutableInteractionSource() }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("immersiveArtistHero")) {
        Box(Modifier.fillMaxWidth().height((maxWidth * 1.02f).coerceIn(300.dp, 440.dp))) {
            Crossfade(
                targetState = artwork?.takeIf { it.isRealEmbeddedArt },
                animationSpec = tween(if (motionEnabled) 260 else 0),
                label = "artistHeroPortrait"
            ) { portrait ->
                if (portrait != null) Image(portrait.imageBitmap, name,
                    Modifier.fillMaxSize().testTag(imageTag), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(TwilightIcons.ArtistMic, null, Modifier.size(100.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.25f), 0.42f to Color.Transparent,
                0.7f to base.copy(alpha = 0.45f), 1f to base)))
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    interactionSource = backInteraction,
                    modifier = Modifier.tactilePress(backInteraction, pressedScale = 0.90f)
                        .size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
                ) {
                    Icon(TwilightIcons.ArrowBack, backLabel, tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                trailing()
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
                Text(name, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ArtistBiography(text: String, attribution: String? = null, tag: String = "artistBiography") {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    val motionEnabled = rememberMotionEnabled()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            .then(if (motionEnabled) Modifier.animateContentSize(spring(dampingRatio = 0.86f, stiffness = 380f)) else Modifier)
            .testTag(tag)
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp),
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!attribution.isNullOrBlank()) Text(attribution, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) else Spacer(Modifier.weight(1f))
            if (overflows || expanded) TextButton(onClick = { expanded = !expanded }, Modifier.testTag("artistBiographyToggle")) {
                Text(if (expanded) "收起" else "展开全文")
            }
        }
    }
}

@Composable
internal fun ArtistContentTabs(albums: Boolean, select: (Boolean) -> Unit) {
    val motionEnabled = rememberMotionEnabled()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf(false, true).forEach { tab ->
            val selected = tab == albums
            val interaction = remember { MutableInteractionSource() }
            val ink by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(if (motionEnabled) 180 else 0),
                label = "artistTabInk"
            )
            val barWidth by animateDpAsState(
                if (selected) 24.dp else 0.dp,
                if (motionEnabled) spring(dampingRatio = 0.72f, stiffness = 480f) else snap(),
                label = "artistTabBar"
            )
            val barColor by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                tween(if (motionEnabled) 160 else 0),
                label = "artistTabBarColor"
            )
            Column(
                Modifier.tactilePress(interaction, pressedScale = 0.94f)
                    .selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.Tab,
                        onClick = { select(tab) }
                    )
                    .semantics { stateDescription = if (selected) "已选中" else "未选中" }
                    .testTag(if (tab) "artistAlbumsTab" else "artistSongsTab")
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (tab) "专辑" else "歌曲", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = ink)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(barWidth).height(3.dp).clip(RoundedCornerShape(50))
                    .background(barColor))
            }
        }
    }
}
