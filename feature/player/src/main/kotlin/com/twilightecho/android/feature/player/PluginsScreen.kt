@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.twilightecho.android.feature.player

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.plugins.PluginEntry
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.TwilightTokens
import kotlinx.coroutines.launch

@Composable
internal fun PluginsScreen(presenter: PluginPresenter, onBack: () -> Unit,
    onPlay: (List<CoreTrackView>, CoreTrackView) -> Unit, modifier: Modifier = Modifier,
    accountOnly: Boolean = false) {
    val repository by presenter.repository.state.collectAsStateWithLifecycle()
    val provider by presenter.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var install by remember { mutableStateOf<PluginEntry?>(null) }
    var remove by remember { mutableStateOf<PluginEntry?>(null) }
    var sourceDialog by remember { mutableStateOf(false) }
    var sourceDraft by rememberSaveable { mutableStateOf(repository.indexUrl) }
    var loginOpen by rememberSaveable { mutableStateOf(accountOnly) }
    // The CDN index is shared with Desktop; this page lists its audio sources, Android-ready ones first.
    val sources = remember(repository.entries) {
        repository.entries.filter { "provider" in it.type || it.installedVersion != null }.sortedBy { it.unsupportedReason != null }
    }
    LaunchedEffect(Unit) { if (!accountOnly) presenter.revalidate() }
    fun back() { if (accountOnly) onBack() else if (loginOpen) loginOpen = false else if (provider.plugin != null) presenter.close() else onBack() }
    BackHandler { back() }
    AmbientBackground(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TwilightIcons.ArrowBack, "返回", ::back)
            Text(if (provider.plugin == null) "插件与音源" else provider.plugin!!.name,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (provider.plugin == null) TextButton(enabled = !repository.busy, onClick = { scope.launch { runCatching { presenter.repository.refresh() } } }) {
                Icon(TwilightIcons.RadarScan, null, Modifier.padding(end = 6.dp).size(16.dp)); Text("刷新仓库")
            }
            else HeaderAccountButton(if (loginOpen) "完成" else provider.nickname ?: "登录", loginOpen || !provider.nickname.isNullOrBlank()) {
                if (accountOnly) onBack() else loginOpen = !loginOpen
            }
        }
        if (repository.busy || provider.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        if (provider.plugin == null) {
            LazyColumn(Modifier.fillMaxSize().testTag("pluginRepository"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    PageHeading("音源")
                    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = { sourceDraft = repository.indexUrl; sourceDialog = true }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(TwilightIcons.Library, Modifier.padding(end = 14.dp), container = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                content = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text("管理仓库来源", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                SupportingText(repository.sourceLabel, maxLines = 2)
                            }
                            Icon(TwilightIcons.ArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    repository.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
                items(sources, key = { it.id }) { plugin ->
                    val installed = repository.installed.find { it.id == plugin.id }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(TwilightIcons.SignalWave, size = 48.dp,
                                    container = if (installed?.enabled == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                                    content = if (installed?.enabled == true) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text(plugin.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TagChip(plugin.version)
                                        TagChip(when {
                                            installed == null -> if (plugin.unsupportedReason != null) "不支持" else "未安装"
                                            installed.enabled -> "已启用"
                                            else -> "已停用"
                                        }, highlighted = installed?.enabled == true)
                                        if (installed != null && plugin.updateAvailable) TagChip("可更新", highlighted = true)
                                    }
                                }
                            }
                            SupportingText(plugin.description, Modifier.padding(top = 14.dp))
                            SupportingText(if (installed == null) plugin.androidAvailability()
                                else if (installed.enabled) "已启用 · ${installed.version}" else "已停用 · ${installed.version}", Modifier.padding(top = 6.dp))
                            installed?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp)) }
                            Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (installed == null || plugin.updateAvailable) PillButton(if (installed == null) "安装" else "更新", { install = plugin },
                                    Modifier.testTag("plugin-install-${plugin.id}"), TwilightIcons.Plus,
                                    enabled = plugin.unsupportedReason == null && !repository.busy, tonal = installed != null)
                                if (installed != null) {
                                    if (installed.enabled) PillButton("打开音源", { presenter.open(plugin) }, Modifier.testTag("plugin-open-${plugin.id}"),
                                        TwilightIcons.ArrowRight, enabled = !repository.busy)
                                    TextButton(enabled = !repository.busy, onClick = { scope.launch { runCatching { presenter.repository.setEnabled(plugin.id, !installed.enabled) } } }) {
                                        Text(if (installed.enabled) "停用" else "启用")
                                    }
                                    TextButton(enabled = !repository.busy, onClick = { remove = plugin }) {
                                        Text("卸载", color = if (repository.busy) Color.Unspecified else MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (loginOpen) ProviderLoginPanel(provider, presenter)
            else {
                OutlinedTextField(provider.query, presenter::query, placeholder = { Text("歌曲、歌手、专辑") }, label = { Text("搜索网易云音乐") },
                    singleLine = true, shape = RoundedCornerShape(50), colors = twilightFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("providerSearch"),
                    leadingIcon = { Icon(TwilightIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { presenter.search() }),
                    trailingIcon = { IconButton(enabled = !provider.busy, onClick = { presenter.search() }) { Icon(TwilightIcons.ArrowRight, "搜索音源") } })
                val sections = listOf("discovery" to "发现歌单", "daily" to "每日推荐", "liked" to "我喜欢", "library" to "我的歌单")
                PillTabs(sections.map { it.first }, provider.pageKind, { kind -> sections.first { it.first == kind }.second },
                    { if (!provider.busy) presenter.section(it) }, Modifier.padding(vertical = 8.dp))
                provider.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                LazyColumn(Modifier.weight(1f).testTag("providerResults"), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        SectionHeading(provider.title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                        if (provider.tracks.isNotEmpty()) SupportingText("点击歌曲，在此手机播放", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
                    }
                    items(provider.playlists, key = { it.id }) { playlist ->
                        ListItem(headlineContent = { Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${playlist.count} 首歌曲") },
                            leadingContent = { IconTile(TwilightIcons.Queue, size = 48.dp) },
                            trailingContent = { Icon(TwilightIcons.ArrowRight, null, Modifier.size(18.dp)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent, supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(18.dp)).clickable(enabled = !provider.busy) { presenter.playlist(playlist) })
                    }
                    itemsIndexed(provider.tracks, key = { i, track -> "$i-${track.id}" }) { _, track ->
                        val available = track.unavailableReason == null
                        ListItem(headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(track.unavailableReason ?: "${track.artist} · ${track.album}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (available) MaterialTheme.colorScheme.onSurfaceVariant else TwilightTokens.SignalWarm) },
                            leadingContent = { TrackArt(track, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) },
                            trailingContent = { Icon(TwilightIcons.Play, "播放 ${track.title}", Modifier.size(20.dp),
                                tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent,
                                headlineColor = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)),
                            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(18.dp))
                                .clickable(enabled = !provider.busy && available) {
                                    if (provider.nickname.isNullOrBlank()) loginOpen = true else onPlay(provider.tracks, track)
                                })
                    }
                    if (provider.total > maxOf(provider.tracks.size, provider.playlists.size) && provider.pageKind in listOf("search", "playlist", "liked", "discovery")) item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val size = if (provider.pageKind in listOf("playlist", "liked")) 100 else 30
                            val count = maxOf(provider.tracks.size, provider.playlists.size)
                            PillButton("上一页", { presenter.nextPage((provider.offset - size).coerceAtLeast(0)) },
                                enabled = provider.offset > 0 && !provider.busy, tonal = true)
                            Text("${provider.offset + 1}–${provider.offset + count} / ${provider.total}", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            PillButton("下一页", { presenter.nextPage(provider.offset + count) },
                                enabled = provider.offset + count < provider.total && !provider.busy, tonal = true)
                        }
                    }
                    if (!provider.busy && provider.tracks.isEmpty() && provider.playlists.isEmpty()) item {
                        EmptyState("从一首歌开始", if (provider.query.isBlank()) "搜索喜欢的音乐，或探索歌单。登录后可读取你的收藏和推荐。" else "没有找到结果。试试其他关键词，或查看上方的错误提示。",
                            TwilightIcons.Search)
                    }
                }
            }
        }
    }
    }
    install?.let { plugin -> AlertDialog(onDismissRequest = { install = null }, title = { Text("安装 ${plugin.name}？") },
        text = { Text("${if (plugin.sourceUrl.startsWith("asset:")) "随应用提供的插件" else "插件包将使用仓库声明的 SHA-256 校验"}\n\n允许访问：\n${plugin.androidRuntime?.networkHosts?.joinToString("\n")}\n\n插件设置和账号凭据独立保存。") },
        confirmButton = { TextButton(onClick = { install = null; scope.launch { runCatching { presenter.repository.install(plugin.id) } } }) { Text("安装并启用") } },
        dismissButton = { TextButton(onClick = { install = null }) { Text("取消") } }) }
    remove?.let { plugin -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("卸载 ${plugin.name}？") }, text = { Text("同时删除插件设置和账号凭据；本机与电脑的音乐队列不受影响。") },
        confirmButton = { TextButton(onClick = { remove = null; scope.launch { runCatching { presenter.repository.uninstall(plugin.id) } } }) { Text("卸载", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } }) }
    if (sourceDialog) AlertDialog(onDismissRequest = { sourceDialog = false }, title = { Text("插件仓库") },
        text = { OutlinedTextField(sourceDraft, { sourceDraft = it }, label = { Text("HTTPS 索引地址") }, shape = RoundedCornerShape(16.dp),
            colors = twilightFieldColors(), supportingText = { Text("沿用桌面端 plugins.json / .tep 格式，桌面专属插件不能安装。") }) },
        confirmButton = { TextButton(onClick = { sourceDialog = false; scope.launch { runCatching { presenter.repository.refresh(sourceDraft) } } }) { Text("保存并刷新") } },
        dismissButton = { TextButton(onClick = { sourceDialog = false }) { Text("取消") } })
}

/** Desktop-only sources share the index; the shared rules still decide what installs. */
private fun PluginEntry.androidAvailability(): String = unsupportedReason?.let { reason ->
    if (androidRuntime == null) "尚未发布 Android 版本 · 桌面端可用" else reason
} ?: "支持 Android · 网络与独立插件设置"

@Composable
private fun HeaderAccountButton(label: String, quiet: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(label, Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50))
        .background(if (quiet) colors.onSurface.copy(alpha = 0.08f) else colors.primary)
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).heightIn(min = 40.dp)
        .padding(horizontal = 18.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge,
        color = if (quiet) colors.onSurface else colors.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ProviderLoginPanel(state: ProviderPageState, presenter: PluginPresenter) {
    var mode by rememberSaveable { mutableStateOf("qr") }
    // Credentials deliberately stay in memory, never rememberSaveable or logs.
    var phone by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("providerLogin"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.profile != null) {
                    NcmProfileCard(state.profile)
                    PillButton("退出登录", { presenter.logout() }, enabled = !state.busy, tonal = true)
                } else {
                    IconTile(TwilightIcons.SignalWave, size = 52.dp)
                    Text("登录网易云音乐", style = MaterialTheme.typography.headlineSmall)
                    SupportingText("同步你的歌单、收藏与每日推荐。")
                    val modes = listOf("qr" to "扫码", "sms" to "验证码", "password" to "手机密码", "email" to "邮箱密码")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modes.forEach { (key, label) -> ChoicePill(label, mode == key, Modifier) { mode = key; secret = "" } }
                    }
                    if (mode == "qr") {
                        val qr = remember(state.qrImage) { state.qrImage?.substringAfter("base64,")?.let { runCatching { Base64.decode(it, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size).asImageBitmap() } }.getOrNull() } }
                        // QR codes need a light quiet zone to scan reliably, so this one tile stays white.
                        qr?.let { Box(Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(14.dp)) {
                            Image(it, "网易云登录二维码", Modifier.size(220.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                        } }
                        PillButton(if (qr == null) "获取二维码" else "刷新二维码", { presenter.qr() }, Modifier.fillMaxWidth(), enabled = !state.busy,
                            tonal = qr != null)
                        if (state.qrKey != null) PillButton("我已扫码，检查登录", { presenter.checkQr() }, Modifier.fillMaxWidth(), enabled = !state.busy)
                    } else {
                        OutlinedTextField(phone, { phone = it }, label = { Text(if (mode == "email") "邮箱" else "手机号") }, singleLine = true,
                            shape = RoundedCornerShape(16.dp), colors = twilightFieldColors(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(secret, { secret = it }, label = { Text(if (mode == "sms") "验证码" else "密码") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(16.dp), colors = twilightFieldColors(),
                            modifier = Modifier.fillMaxWidth())
                        if (mode == "sms") TextButton(enabled = !state.busy && phone.isNotBlank(), onClick = { presenter.sendCaptcha(phone) }) { Text("发送验证码") }
                        PillButton("登录", { presenter.login(phone, secret, mode); secret = "" }, Modifier.fillMaxWidth(),
                            enabled = !state.busy && phone.isNotBlank() && secret.isNotBlank())
                    }
                }
            }
        }
        state.loginMessage?.let { SupportingText(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}
