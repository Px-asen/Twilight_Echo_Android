package com.twilightecho.android.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.LocalTwilightAccents
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import com.twilightecho.android.ui.theme.TwilightTokens
import kotlinx.coroutines.delay

internal data class RemoteArtworkEnvironment(val remote: Boolean = false,
    val previews: Map<String, String?> = emptyMap(), val revision: Long = 0,
    val request: (String) -> Unit = {})
internal val LocalRemoteArtwork = compositionLocalOf { RemoteArtworkEnvironment() }

@Composable
internal fun rememberPlayerArtwork(track: CoreTrackView?, size: Int = 1024, extractColors: Boolean = true): TrackArtworkVisual {
    val environment = LocalRemoteArtwork.current
    // A folder's image is a local file; any other cover is a provider's web image.
    val folderCover = track?.cover?.takeIf { it.startsWith("content://") }
    val remote = track?.id?.let { it.startsWith("remote-track:") || it.startsWith(DesktopMediaReference.PREFIX) } == true
    LaunchedEffect(track?.id, environment.revision) {
        if (remote) track?.id?.let(environment.request)
    }
    if (!remote && folderCover == null && (track?.source == null || track.source?.startsWith("provider://") == true)) {
        track?.cover?.let { return rememberProviderArtwork(it, size, extractColors) }
    }
    return rememberTrackArtwork(track?.id, track?.title, track?.artist, track?.source?.let(CueSource::audio), track?.albumId,
        previewOnly = remote || track?.source?.startsWith("provider://") == true,
        previewDataUrl = if (remote) environment.previews[track?.id] else null, coverUri = folderCover, maxSize = size,
        extractColors = extractColors)
}

/** Which computer the phone is linked to on the left, and where the sound comes out on the right. */
@Composable
internal fun LibraryPlaybackHeader(state: PlayerUiState, actions: PlayerActions) {
    val source = state.librarySource as? LibrarySource.Desktop
    val target = state.operatingMode as? CoreOperatingMode.RemoteDesktopCore
    val host = source?.hostLabel ?: target?.hostLabel ?: "电脑"
    val connected = source?.connected ?: target?.connected ?: false
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape)
            .background(if (connected) TwilightTokens.SignalLive else TwilightTokens.SignalWarm))
        Text(if (connected) host else "$host 未连接", Modifier.weight(1f).padding(start = 8.dp, end = 12.dp),
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .padding(3.dp)) {
            OutputSegment("此手机", TwilightIcons.Phone, target == null, true, Modifier.testTag("targetPhone")) {
                actions.playbackTarget(true)
            }
            if (source != null || target != null) OutputSegment("电脑", TwilightIcons.Computer, target != null,
                source?.connected == true, Modifier.testTag("targetDesktop")) { actions.playbackTarget(false) }
        }
    }
}

@Composable
private fun OutputSegment(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean,
    enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "segmentBg")
    val fg by animateColorAsState(when {
        selected -> MaterialTheme.colorScheme.onPrimary
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }, label = "segmentFg")
    Row(modifier.clip(RoundedCornerShape(50)).background(bg)
        .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
        .heightIn(min = 36.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = fg)
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
internal fun LibraryPageControls(offset: Int, count: Int, total: Int, busy: Boolean,
    page: (Int) -> Unit) {
    if (offset == 0 && count >= total) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { page((offset - 100).coerceAtLeast(0)) }, enabled = offset > 0 && !busy) { Text("上一页") }
        Text("${if (count == 0) 0 else offset + 1}–${offset + count} / $total", Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = { page(offset + count) }, enabled = count > 0 && offset + count < total && !busy) { Text("下一页") }
    }
}

@Composable
internal fun RemoteCatalogScreen(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    val data = state.desktopLibrary
    if (data.selected != null) {
        RemoteEntityScreen(state, actions, modifier)
        return
    }
    Box(modifier) {
    Column(Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        ScrollToTopOn("${state.libraryDevice}:${data.kind}:${data.page?.offset ?: 0}", listState)
        data.error?.let { EmptyState("电脑资料未载入", it, actionLabel = "刷新", onAction = { actions.catalogPage(0) }) }
        val page = data.page
        if (!data.loading && data.error == null && page?.items.isNullOrEmpty())
            EmptyState("还没有整理好的资料", "电脑完成曲库整理后，资料会显示在这里。", actionLabel = "刷新", onAction = { actions.catalogPage(0) })
        LazyColumn(Modifier.weight(1f).testTag("desktopCatalog"), state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp + LocalDockInset.current)) {
            items(page?.items.orEmpty(), key = { it.id }) { entity -> CatalogEntityRow(entity) { actions.openEntity(entity) } }
            page?.let { item { LibraryPageControls(it.offset, it.items.size, it.total, data.loading, actions.catalogPage) } }
        }
    }
    if (data.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@Composable
private fun CatalogEntityRow(entity: CatalogEntity, onClick: () -> Unit) {
    val artist = entity.kind == "artist"
    val art = rememberTrackArtwork(entity.id, entity.title, entity.artist, null, previewOnly = true)
    val shape = if (artist) CircleShape else RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("catalog-${entity.id}")
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtworkImage(art, Modifier.size(60.dp).clip(shape), ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(entity.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!artist && entity.artist.isNotBlank()) SupportingText(entity.artist, Modifier.padding(top = 2.dp), maxLines = 1)
        }
        Icon(TwilightIcons.ArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Facts the hero turns into chips or a subtitle; everything else is listed under the fact table. */
private val HeroFactFields = setOf("name", "introduction", "description", "primaryType", "firstReleaseDate", "country")

@Composable
private fun RemoteEntityScreen(state: PlayerUiState, actions: PlayerActions, modifier: Modifier) {
    val data = state.desktopLibrary
    val entity = data.selected ?: return
    val metadata = data.metadata
    var now by remember(metadata) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(metadata, now) {
        val expiry = (metadata?.artwork.orEmpty().mapNotNull { it.usage?.expiresAt } +
            metadata?.facts.orEmpty().mapNotNull { it.usage?.expiresAt }).filter { it > now }.minOrNull()
        if (expiry != null) { delay((expiry - now).coerceAtLeast(1)); now = System.currentTimeMillis() }
    }
    val external = metadata?.artwork?.firstOrNull { it.usage?.canDisplay(now) != false }
    val preview = when (metadata?.artworkOverride?.mode) {
        "clear" -> null
        "value" -> metadata.manualArtwork?.dataUrl
        else -> metadata?.manualArtwork?.dataUrl ?: external?.dataUrl
    }
    val artwork = rememberTrackArtwork(entity.id, metadata?.name ?: entity.title, entity.artist, null,
        previewDataUrl = preview, previewOnly = true, cachePreview = false)
    val facts = metadata?.facts.orEmpty().filter { it.usage?.canDisplay(now) != false }
    val desktopOutput = state.operatingMode is CoreOperatingMode.RemoteDesktopCore
    val artist = entity.kind == "artist"
    val listState = rememberLazyListState()
    LaunchedEffect(entity.id, data.sources?.offset ?: 0) { listState.scrollToItem(0) }
    // Each artist or album page recolors itself from its own portrait or cover.
    TwilightEchoTheme(seed = artwork.seedColor ?: LocalTwilightAccents.current.seed) {
    AmbientBackground(modifier) {
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().testTag("desktopEntity"), state = listState,
        contentPadding = PaddingValues(bottom = 24.dp + LocalDockInset.current)) {
        item {
            val name = metadata?.name?.ifBlank { null } ?: entity.title
            val attribution = external?.attribution?.takeIf { preview != null && preview == external.dataUrl }
            if (artist) {
                ImmersiveArtistHero(name, listOfNotNull(facts.firstOrNull { it.field == "description" }?.value,
                    facts.firstOrNull { it.field == "country" }?.value?.let(::countryLabel),
                    data.sources?.total?.let { "$it 首歌曲" }).joinToString(" · "), artwork, actions.closeEntity,
                    "返回电脑曲库", "desktopMetadataArtwork")
                attribution?.let { SupportingText(attributionLine(it, "图片"), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) }
            } else EntityHero(entity, name, facts, artwork, actions.closeEntity, artworkAttribution = attribution)
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                data.detailError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { actions.openEntity(entity) }) { Text("重新读取资料") }
                }
                val intro = metadata?.introduction
                if (!artist && intro != null) IntroductionCard("关于这张专辑", intro)
                else if (!artist && metadata != null) GlassCard(Modifier.fillMaxWidth()) {
                    SupportingText("电脑还没有这张专辑的简介，可在电脑端「音乐库资料」中补全。", Modifier.padding(18.dp))
                }
            }
        }
        if (artist) item {
            val intro = metadata?.introduction
            if (intro != null && intro.value.isNotBlank()) ArtistBiography(intro.value,
                intro.attribution?.let { attributionLine(it, "资料") }, "desktopIntroduction")
            else if (metadata != null) SupportingText("电脑还没有这位歌手的简介", Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
        val extra = facts.filter { it.field !in if (artist) setOf("name", "introduction", "description", "country") else HeroFactFields }
        if (extra.isNotEmpty()) item { Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { FactTable(extra) } }
        item {
            if (artist) ArtistContentTabs(data.artistAlbums, actions.artistAlbums)
            else Text("曲目",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 6.dp),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
        }
        if (artist && data.artistAlbums) {
            if (data.albumsLoading && data.albums.isEmpty()) item { LoadingContent("正在载入专辑") }
            data.albumsError?.let { item { EmptyState("专辑未载入", it, actionLabel = "重试", onAction = actions.retryArtistAlbums) } }
            items(data.albums, key = { it.id }) { album -> CatalogEntityRow(album) { actions.openEntity(album) } }
            if (data.albumsLoaded && data.albums.isEmpty()) item { EmptyState("暂无专辑", "电脑曲库中还没有这位歌手的专辑。") }
            return@LazyColumn
        }
        itemsIndexed(data.sources?.items.orEmpty(), key = { _, source -> source.id }) { index, source ->
            val enabled = source.provider == "local" && source.availability != "missing" &&
                (state.librarySource as? LibrarySource.Desktop)?.connected == true
            SourceRow((data.sources?.offset ?: 0) + index + 1, source, enabled, desktopOutput) {
                actions.playSource(source, data.sources!!.revision)
            }
        }
        item { data.sources?.let { LibraryPageControls(it.offset, it.items.size, it.total, data.detailLoading, actions.sourcePage) } }
    }
    if (data.detailLoading || data.albumsLoading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
    }
    }
}

@Composable
private fun EntityHero(entity: CatalogEntity, name: String, facts: List<MetadataFact>, artwork: TrackArtworkVisual,
    onBack: () -> Unit, artworkAttribution: MetadataAttribution?) {
    val colors = MaterialTheme.colorScheme
    val description = facts.firstOrNull { it.field == "description" }?.value
    val details = listOfNotNull(
        facts.firstOrNull { it.field == "primaryType" }?.value?.let(::primaryTypeLabel),
        facts.firstOrNull { it.field == "firstReleaseDate" }?.value?.take(4)?.takeIf { it.length == 4 }
            ?.let { "$it 年" },
        facts.firstOrNull { it.field == "country" }?.value?.let(::countryLabel))
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TwilightIcons.ArrowBack, "返回电脑曲库", onBack)
        }
        val artShape = RoundedCornerShape(18.dp)
        val artModifier = Modifier.fillMaxWidth(0.62f).aspectRatio(1f)
        ArtworkImage(artwork, Modifier.padding(top = 4.dp).then(artModifier)
            .shadow(28.dp, artShape, ambientColor = Color.Black, spotColor = Color.Black).clip(artShape)
            .testTag("desktopMetadataArtwork"), ContentScale.Crop, name)
        Text(name, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 4.dp))
        if (entity.artist.isNotBlank()) Text(entity.artist, style = MaterialTheme.typography.titleMedium,
            color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 2.dp)) }
        if (details.isNotEmpty()) SupportingText(details.joinToString(" · "),
            Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp), maxLines = 1)
        artworkAttribution?.let { Text(attributionLine(it, "图片"), style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, start = 24.dp, end = 24.dp)) }
    }
}

/** The biography, clamped to six lines until the reader expands it. */
@Composable
private fun IntroductionCard(title: String, intro: MetadataIntroduction) {
    var expanded by rememberSaveable(intro.value) { mutableStateOf(false) }
    var overflows by remember(intro.value) { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth().testTag("desktopIntroduction"), RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp).animateContentSize()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(intro.value, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp),
                maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier.padding(top = 8.dp, end = 10.dp))
            Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(intro.attribution?.let { attributionLine(it, "资料") }.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (overflows || expanded) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开全文") }
            }
        }
    }
}

@Composable
private fun FactTable(facts: List<MetadataFact>) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            facts.forEachIndexed { index, fact ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(factLabel(fact.field), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
                    Column(Modifier.weight(1f)) {
                        Text(when (fact.field) {
                            "primaryType" -> primaryTypeLabel(fact.value)
                            "country" -> countryLabel(fact.value)
                            else -> fact.value
                        }, style = MaterialTheme.typography.bodyMedium)
                        fact.attribution?.let { Text(attributionLine(it, ""), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(number: Int, source: CatalogSource, enabled: Boolean, desktopOutput: Boolean, play: () -> Unit) {
    val alpha = if (enabled) 1f else 0.5f
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(number.toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(source.track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            SupportingText(if (source.provider == "local") listOf(source.track.artist, source.track.album)
                .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "电脑文件" } else "请在电脑选择此音源",
                Modifier.padding(top = 2.dp), maxLines = 1)
        }
        if (source.track.duration > 0) Text(formatDurationSeconds(source.track.duration),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = play),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
                Icon(TwilightIcons.Play, "${if (desktopOutput) "在电脑" else "在此手机"}播放 ${source.track.title}",
                    Modifier.size(18.dp), tint = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        }
    }
}

private fun attributionLine(value: MetadataAttribution, prefix: String): String =
    (listOf("${prefix}来源") + listOf(value.provider, value.license, value.attribution).filter { it.isNotBlank() })
        .joinToString(" · ")

private fun factLabel(field: String) = when (field) {
    "releaseDate" -> "发行日期"
    "firstReleaseDate" -> "首次发行"
    "primaryType" -> "类型"
    "country" -> "地区"
    "barcode" -> "条码"
    "description" -> "简述"
    else -> field
}

internal fun primaryTypeLabel(value: String) = when (value.lowercase()) {
    "album" -> "录音室专辑"
    "single" -> "单曲"
    "ep" -> "EP"
    "broadcast" -> "广播"
    "compilation" -> "合辑"
    "live" -> "现场"
    "soundtrack" -> "原声带"
    "person" -> "个人艺术家"
    "group" -> "乐队 / 组合"
    "orchestra" -> "管弦乐团"
    "choir" -> "合唱团"
    "character" -> "虚拟角色"
    "other" -> "其他"
    else -> value
}

internal fun countryLabel(code: String) = when (code.uppercase()) {
    "CN" -> "中国"
    "TW" -> "中国台湾"
    "HK" -> "中国香港"
    "JP" -> "日本"
    "KR" -> "韩国"
    "US" -> "美国"
    "GB" -> "英国"
    "FR" -> "法国"
    "DE" -> "德国"
    "CA" -> "加拿大"
    "SE" -> "瑞典"
    "XW" -> "全球"
    else -> code
}
