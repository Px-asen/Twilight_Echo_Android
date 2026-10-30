@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.twilightecho.android.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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

private data class PluginSubpage(val pluginId: String?, val loginOpen: Boolean) {
    val depth: Int get() = when {
        pluginId == null -> 0
        loginOpen -> 2
        else -> 1
    }
}

@Composable
internal fun PluginsScreen(presenter: PluginPresenter, onBack: () -> Unit,
    onPlay: (List<CoreTrackView>, CoreTrackView) -> Unit, modifier: Modifier = Modifier,
    accountOnly: Boolean = false, backEnabled: Boolean = true) {
    val repository by presenter.repository.state.collectAsStateWithLifecycle()
    val provider by presenter.state.collectAsStateWithLifecycle()
    val motionEnabled = rememberMotionEnabled()
    val scope = rememberCoroutineScope()
    var install by remember { mutableStateOf<PluginEntry?>(null) }
    var remove by remember { mutableStateOf<PluginEntry?>(null) }
    var sourceDialog by remember { mutableStateOf(false) }
    var sourceDraft by rememberSaveable { mutableStateOf(repository.indexUrl) }
    var loginOpen by rememberSaveable { mutableStateOf(accountOnly) }
    // The CDN index is shared with Desktop and lists every plugin; only audio sources with an Android build install here.
    val sources = remember(repository.entries) {
        repository.entries.sortedWith(compareBy({ it.installedVersion == null }, { it.unsupportedReason != null }))
    }
    LaunchedEffect(Unit) { if (!accountOnly) presenter.revalidate() }
    fun back() { if (accountOnly) onBack() else if (loginOpen) loginOpen = false else if (provider.plugin != null) presenter.close() else onBack() }
    BackHandler(enabled = backEnabled) { back() }
    var lastProviderState by remember { mutableStateOf(provider) }
    if (provider.plugin != null) lastProviderState = provider
    val subpage = PluginSubpage(provider.plugin?.id, provider.plugin != null && loginOpen)
    AmbientBackground(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TwilightIcons.ArrowBack, "返回", ::back)
            AnimatedContent(
                targetState = if (provider.plugin == null) "插件与音源" else provider.plugin!!.name,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                transitionSpec = {
                    if (!motionEnabled) EnterTransition.None togetherWith ExitTransition.None
                    else (fadeIn(tween(180)) togetherWith fadeOut(tween(120))) using SizeTransform(clip = false)
                },
                label = "pluginHeaderTitle"
            ) { title ->
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (provider.plugin == null) TextButton(enabled = !repository.busy, onClick = { scope.launch { runCatching { presenter.repository.refresh() } } }) {
                Icon(TwilightIcons.RadarScan, null, Modifier.padding(end = 6.dp).size(16.dp)); Text("刷新仓库")
            }
            else HeaderAccountButton(if (loginOpen) "完成" else provider.nickname ?: "登录", loginOpen || !provider.nickname.isNullOrBlank()) {
                if (accountOnly) onBack() else loginOpen = !loginOpen
            }
        }
        AnimatedVisibility(
            visible = repository.busy || provider.busy,
            enter = TwilightMotion.sectionExpand(motionEnabled),
            exit = TwilightMotion.sectionCollapse(motionEnabled)
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        }
        AnimatedContent(
            targetState = subpage,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                TwilightMotion.hierarchicalTransition(
                    forward = targetState.depth >= initialState.depth,
                    motionEnabled = motionEnabled,
                    targetZIndex = targetState.depth.toFloat()
                )
            },
            label = "pluginSubpage"
        ) { page ->
        val pageProvider = if (provider.plugin != null) provider else lastProviderState
        if (page.pluginId == null) {
            LazyColumn(Modifier.fillMaxSize().testTag("pluginRepository"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    PageHeading("插件", "手机上可安装音源插件，其他插件请在桌面端使用")
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
                                IconTile(if ("provider" in plugin.type) TwilightIcons.SignalWave else TwilightIcons.Sparkles, size = 48.dp,
                                    container = if (installed?.enabled == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                                    content = if (installed?.enabled == true) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text(plugin.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TagChip(plugin.version)
                                        TagChip(when {
                                            installed == null -> plugin.unsupportedReason?.let { if (it == MOBILE_UNSUPPORTED) it else "不支持" } ?: "未安装"
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
                                if ((installed == null && plugin.unsupportedReason == null) || (installed != null && plugin.updateAvailable))
                                    PillButton(if (installed == null) "安装" else "更新", { install = plugin },
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
            if (page.loginOpen) { if (pageProvider.generic) ProviderAccountPanel(pageProvider, presenter) else ProviderLoginPanel(pageProvider, presenter) }
            else Column(Modifier.fillMaxSize()) {
                // Plugins-repository providers show search only when they declare it, as on Desktop.
                if (!pageProvider.generic || pageProvider.provider?.searchable() == true)
                OutlinedTextField(pageProvider.query, presenter::query, placeholder = { Text("歌曲、歌手、专辑") },
                    label = { Text(if (pageProvider.generic) "搜索${pageProvider.plugin?.name.orEmpty()}" else "搜索网易云音乐") },
                    singleLine = true, shape = RoundedCornerShape(50), colors = twilightFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("providerSearch"),
                    leadingIcon = { Icon(TwilightIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { presenter.search() }),
                    trailingIcon = { IconButton(enabled = !pageProvider.busy, onClick = { presenter.search() }) { Icon(TwilightIcons.ArrowRight, "搜索音源") } })
                val sections = if (pageProvider.generic) pageProvider.tabs
                    else listOf("discovery" to "发现歌单", "daily" to "每日推荐", "liked" to "我喜欢", "library" to "我的歌单")
                if (sections.isNotEmpty()) PillTabs(sections.map { it.first }, pageProvider.pageKind, { kind -> sections.first { it.first == kind }.second },
                    { if (!pageProvider.busy) presenter.section(it) }, Modifier.padding(vertical = 8.dp))
                pageProvider.notice?.let { ProviderNotice(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
                pageProvider.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                LazyColumn(Modifier.weight(1f).testTag("providerResults"), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        SectionHeading(pageProvider.title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                        if (pageProvider.tracks.isNotEmpty()) SupportingText("点击歌曲，在此手机播放", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
                    }
                    items(pageProvider.playlists, key = { it.id }) { playlist ->
                        ListItem(headlineContent = { Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${playlist.count} 首歌曲") },
                            leadingContent = { IconTile(TwilightIcons.Queue, size = 48.dp) },
                            trailingContent = { Icon(TwilightIcons.ArrowRight, null, Modifier.size(18.dp)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent, supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(18.dp)).clickable(enabled = !pageProvider.busy) { presenter.playlist(playlist) })
                    }
                    itemsIndexed(pageProvider.tracks, key = { i, track -> "$i-${track.id}" }) { _, track ->
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
                                .clickable(enabled = !pageProvider.busy && available) {
                                    // NCM asks for an account first; other sources report their own sign-in needs when played.
                                    if (!pageProvider.generic && pageProvider.nickname.isNullOrBlank()) loginOpen = true else onPlay(pageProvider.tracks, track)
                                })
                    }
                    if (!pageProvider.generic && pageProvider.total > maxOf(pageProvider.tracks.size, pageProvider.playlists.size) && pageProvider.pageKind in listOf("search", "playlist", "liked", "discovery")) item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val size = if (pageProvider.pageKind in listOf("playlist", "liked")) 100 else 30
                            val count = maxOf(pageProvider.tracks.size, pageProvider.playlists.size)
                            PillButton("上一页", { presenter.nextPage((pageProvider.offset - size).coerceAtLeast(0)) },
                                enabled = pageProvider.offset > 0 && !pageProvider.busy, tonal = true)
                            Text("${pageProvider.offset + 1}–${pageProvider.offset + count} / ${pageProvider.total}", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            PillButton("下一页", { presenter.nextPage(pageProvider.offset + count) },
                                enabled = pageProvider.offset + count < pageProvider.total && !pageProvider.busy, tonal = true)
                        }
                    }
                    // A provider's own paging: where the next page starts, and the first page's size to step back.
                    if (pageProvider.generic && pageProvider.pageKind in listOf("search", "playlist") && (pageProvider.offset > 0 || pageProvider.nextOffset != null)) item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PillButton("上一页", { presenter.nextPage((pageProvider.offset - pageProvider.pageSize.coerceAtLeast(1)).coerceAtLeast(0)) },
                                enabled = pageProvider.offset > 0 && !pageProvider.busy, tonal = true)
                            Text("${pageProvider.offset + 1}–${pageProvider.offset + pageProvider.tracks.size}" +
                                if (pageProvider.pageKind == "playlist" && pageProvider.total >= pageProvider.offset + pageProvider.tracks.size) " / ${pageProvider.total}" else "",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            PillButton("下一页", { pageProvider.nextOffset?.let { presenter.nextPage(it) } },
                                enabled = pageProvider.nextOffset != null && !pageProvider.busy, tonal = true)
                        }
                    }
                    if (!pageProvider.busy && pageProvider.tracks.isEmpty() && pageProvider.playlists.isEmpty()) item {
                        EmptyState("从一首歌开始", when {
                            pageProvider.query.isNotBlank() -> "没有找到结果。试试其他关键词，或查看上方的错误提示。"
                            !pageProvider.generic -> "搜索喜欢的音乐，或探索歌单。登录后可读取你的收藏和推荐。"
                            pageProvider.profile == null -> "登录后可读取你的歌单和推荐；点右上角的“登录”。"
                            else -> "从上方选择推荐或歌单。"
                        }, TwilightIcons.Search)
                    }
                }
            }
        }
        }
    }
    }
    install?.let { plugin -> AlertDialog(onDismissRequest = { install = null }, title = { Text("安装 ${plugin.name}？") },
        text = { Text(plugin.installSummary(), Modifier.verticalScroll(rememberScrollState())) },
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

/** The shared index's reason for a plugin that runs only on Desktop: anything but an audio source with an Android build. */
private const val MOBILE_UNSUPPORTED = "移动端不适配"

/** Desktop-only plugins share the index; the shared rules still decide what installs. */
private fun PluginEntry.androidAvailability(): String = when (val reason = unsupportedReason) {
    null -> "支持 Android · 网络与独立插件设置"
    MOBILE_UNSUPPORTED -> "$MOBILE_UNSUPPORTED · 请在桌面端使用"
    else -> reason
}

/** What installing grants: the hosts the runtime declares, and the server the user types in for `userServer` sources. */
private fun PluginEntry.installSummary(): String = buildString {
    val runtime = androidRuntime
    append(if (sourceUrl.startsWith("asset:")) "随应用提供的插件" else "插件包将使用仓库声明的 SHA-256 校验")
    append("\n\n允许访问：\n").append(runtime?.networkHosts.orEmpty().joinToString("\n"))
    runtime?.mediaHosts?.takeIf { it.isNotEmpty() }?.let { append("\n\n图片与音频：\n").append(it.joinToString("\n")) }
    runtime?.cleartextHosts?.takeIf { it.isNotEmpty() }?.let { append("\n\n允许 HTTP 明文：\n").append(it.joinToString("\n")) }
    if (runtime?.userServer == true) append("\n\n登录时填写的服务器（可为局域网或 HTTP 地址）")
    append("\n\n插件设置和账号凭据独立保存。")
}

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
    val motionEnabled = rememberMotionEnabled()
    // Credentials deliberately stay in memory, never rememberSaveable or logs.
    var phone by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("providerLogin"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(28.dp)) {
            Column(
                Modifier
                    .padding(20.dp)
                    .then(if (motionEnabled) Modifier.animateContentSize(spring(stiffness = 400f)) else Modifier),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
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
                    AnimatedContent(
                        targetState = mode,
                        transitionSpec = {
                            val fromIdx = modes.indexOfFirst { it.first == initialState }
                            val toIdx = modes.indexOfFirst { it.first == targetState }
                            TwilightMotion.directionalTabTransition(toIdx.compareTo(fromIdx), motionEnabled, driftFraction = 0.08f)
                        },
                        label = "loginAuthMode"
                    ) { activeMode ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (activeMode == "qr") {
                                val qr = rememberQrBitmap(state.qrImage)
                                AnimatedVisibility(
                                    visible = qr != null,
                                    enter = TwilightMotion.pillPopEnter(motionEnabled) + TwilightMotion.sectionExpand(motionEnabled),
                                    exit = TwilightMotion.sectionCollapse(motionEnabled),
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    qr?.let { LoginQrCode(it, "网易云登录二维码") }
                                }
                                PillButton(if (qr == null) "获取二维码" else "刷新二维码", { presenter.qr() }, Modifier.fillMaxWidth(), enabled = !state.busy,
                                    tonal = qr != null)
                                AnimatedVisibility(
                                    visible = state.qrKey != null,
                                    enter = TwilightMotion.sectionExpand(motionEnabled),
                                    exit = TwilightMotion.sectionCollapse(motionEnabled)
                                ) {
                                    PillButton("我已扫码，检查登录", { presenter.checkQr() }, Modifier.fillMaxWidth(), enabled = !state.busy)
                                }
                            } else {
                                OutlinedTextField(phone, { phone = it }, label = { Text(if (activeMode == "email") "邮箱" else "手机号") }, singleLine = true,
                                    shape = RoundedCornerShape(16.dp), colors = twilightFieldColors(), modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(secret, { secret = it }, label = { Text(if (activeMode == "sms") "验证码" else "密码") }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(16.dp), colors = twilightFieldColors(),
                                    modifier = Modifier.fillMaxWidth())
                                if (activeMode == "sms") TextButton(enabled = !state.busy && phone.isNotBlank(), onClick = { presenter.sendCaptcha(phone) }) { Text("发送验证码") }
                                PillButton("登录", { presenter.login(phone, secret, activeMode); secret = "" }, Modifier.fillMaxWidth(),
                                    enabled = !state.busy && phone.isNotBlank() && secret.isNotBlank())
                            }
                        }
                    }
                }
            }
        }
        state.loginMessage?.let { SupportingText(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}
