package com.twilightecho.android.feature.player

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twilightecho.android.plugins.PluginNotice
import com.twilightecho.android.plugins.ProviderPanel
import com.twilightecho.android.ui.components.*
import com.twilightecho.android.ui.theme.TwilightTokens

/** A plugins-repository provider's account: the sign-in its `ui.authType` asks for, then its settings panels. */
@Composable
internal fun ProviderAccountPanel(state: ProviderPageState, presenter: PluginPresenter) {
    val provider = state.provider
    val name = state.plugin?.name ?: "音源"
    val authType = provider?.ui?.text("authType").orEmpty()
    // Credentials deliberately stay in memory, never rememberSaveable or logs.
    var address by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("providerLogin"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when {
                    state.profile != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            NcmAvatar(state.profile.nickname, state.profile.avatarUrl, Modifier.size(64.dp))
                            Column(Modifier.weight(1f)) {
                                Text(state.profile.nickname, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                SupportingText("已登录$name", maxLines = 1)
                            }
                        }
                        PillButton("退出登录", { presenter.logout() }, enabled = !state.busy, tonal = true)
                    }
                    provider == null -> SupportingText(if (state.busy) "正在读取音源…" else "音源未就绪，请返回后重新打开。")
                    else -> {
                        val qrCapable = "getQrLogin" in provider.methods && "checkQrLogin" in provider.methods
                        val oauth = authType == "oauth"
                        val disclaimer = state.disclaimer?.takeIf { !it.accepted }
                        IconTile(TwilightIcons.SignalWave, size = 52.dp)
                        Text("登录$name", style = MaterialTheme.typography.headlineSmall)
                        provider.ui.text("loginInstructions").takeIf { it.isNotBlank() }?.let { SupportingText(it) }
                        if (disclaimer != null) {
                            ProviderDisclaimerStep(
                                disclaimer = disclaimer,
                                busy = state.busy,
                                primaryLabel = when {
                                    qrCapable && oauth -> "同意并开始授权"
                                    qrCapable -> "同意并获取二维码"
                                    else -> "同意并继续"
                                },
                                showSkipQr = qrCapable,
                                onConfirm = { startQr -> presenter.acceptDisclaimer(startQr = startQr) }
                            )
                        } else if (authType == "settings" && "loginWithServer" in provider.methods) {
                            OutlinedTextField(address, { address = it }, label = { Text("服务器地址") }, placeholder = { Text("http://192.168.1.2:4533") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                shape = RoundedCornerShape(16.dp), colors = twilightFieldColors(), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true,
                                shape = RoundedCornerShape(16.dp), colors = twilightFieldColors(), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                shape = RoundedCornerShape(16.dp), colors = twilightFieldColors(), modifier = Modifier.fillMaxWidth())
                            SupportingText("插件只获准访问这台服务器，局域网和 HTTP 地址也可以。")
                            PillButton("连接", { presenter.serverLogin(address.trim(), username.trim(), password); password = "" }, Modifier.fillMaxWidth(),
                                enabled = !state.busy && address.isNotBlank() && username.isNotBlank())
                        } else if (qrCapable) {
                            rememberQrBitmap(state.qrImage)?.let { LoginQrCode(it, "${name}登录二维码", Modifier.align(Alignment.CenterHorizontally)) }
                            state.authUrl?.let { url -> PillButton("在浏览器中授权", { runCatching { uri.openUri(url) } }, Modifier.fillMaxWidth()) }
                            PillButton(when {
                                state.qrKey == null -> if (oauth) "开始授权" else "获取二维码"
                                else -> if (oauth) "重新授权" else "刷新二维码"
                            }, { presenter.qr() }, Modifier.fillMaxWidth(), enabled = !state.busy, tonal = state.qrKey != null)
                        } else SupportingText("此音源无需在手机上登录。")
                    }
                }
            }
        }
        state.loginMessage?.let { SupportingText(it) }
        state.notice?.let { ProviderNotice(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        provider?.panels
            ?.filter { !state.requiresDisclaimer || it.id != state.disclaimer?.panel?.id }
            ?.forEach { panel -> ProviderPanelCard(panel, state.panel?.takeIf { it.panel.id == panel.id }, state.busy, presenter) }
    }
}

/** Pre-login disclaimer confirmation step presented directly before QR code generation / sign-in. */
@Composable
private fun ProviderDisclaimerStep(
    disclaimer: ProviderDisclaimer,
    busy: Boolean,
    primaryLabel: String,
    showSkipQr: Boolean,
    onConfirm: (startQr: Boolean) -> Unit
) {
    var agreed by remember(disclaimer.panel.id, disclaimer.fieldKey) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("providerDisclaimerGate"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surfaceContainerHigh.copy(alpha = 0.72f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("使用前免责声明", style = MaterialTheme.typography.titleSmall, color = colors.primary)
            Text(disclaimer.notice, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = !busy, role = androidx.compose.ui.semantics.Role.Checkbox) { agreed = !agreed }
                .padding(vertical = 4.dp)
                .testTag("providerDisclaimerAgree"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = agreed, onCheckedChange = null, enabled = !busy)
            Text(
                disclaimer.acceptLabel,
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface
            )
        }
        PillButton(
            primaryLabel,
            { onConfirm(showSkipQr) },
            Modifier.fillMaxWidth().testTag("providerDisclaimerConfirm"),
            enabled = agreed && !busy
        )
        if (showSkipQr) {
            TextButton(
                onClick = { onConfirm(false) },
                enabled = agreed && !busy,
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag("providerDisclaimerOnly")
            ) {
                Text("仅确认声明，暂不扫码")
            }
        }
    }
}

/** A message the plugin raised with `twilight.ui.notify()`. */
@Composable
internal fun ProviderNotice(notice: PluginNotice, modifier: Modifier = Modifier) {
    Text(notice.message, modifier, style = MaterialTheme.typography.bodyMedium, color = when (notice.kind) {
        "error" -> MaterialTheme.colorScheme.error
        "warning" -> TwilightTokens.SignalWarm
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    })
}

/** A `data:image/…;base64,` login QR code, decoded once per image. */
@Composable
internal fun rememberQrBitmap(dataUrl: String?): ImageBitmap? = remember(dataUrl) {
    dataUrl?.substringAfter("base64,")?.let { runCatching { Base64.decode(it, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size).asImageBitmap() } }.getOrNull() }
}

@Composable
internal fun LoginQrCode(image: ImageBitmap, description: String, modifier: Modifier = Modifier) {
    // QR codes need a light quiet zone to scan reliably, so this one tile stays white.
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(14.dp)) {
        Image(image, description, Modifier.size(220.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
    }
}

/** A settings panel the plugin registered: closed it is a row; open it shows the form its command returned. */
@Composable
private fun ProviderPanelCard(panel: ProviderPanel, open: ProviderPanelState?, busy: Boolean, presenter: PluginPresenter) {
    val motionEnabled = rememberMotionEnabled()
    var lastOpen by remember { mutableStateOf(open) }
    if (open != null) lastOpen = open
    GlassCard(Modifier.fillMaxWidth().testTag("providerPanel-${panel.id}"), RoundedCornerShape(28.dp),
        onClick = if (open == null && !busy) ({ presenter.openPanel(panel) }) else null) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(TwilightIcons.Settings, Modifier.padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    Text(panel.title.ifBlank { "插件设置" }, style = MaterialTheme.typography.titleMedium)
                    panel.description?.takeIf { it.isNotBlank() }?.let { SupportingText(it, Modifier.padding(top = 2.dp), maxLines = 3) }
                }
                if (open != null) TextButton(onClick = { presenter.closePanel() }) { Text("收起") }
                else Icon(TwilightIcons.ArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(
                visible = open != null,
                enter = TwilightMotion.sectionExpand(motionEnabled),
                exit = TwilightMotion.sectionCollapse(motionEnabled)
            ) {
                val activeOpen = open ?: lastOpen
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    activeOpen?.form?.let { form -> ProviderFormFields(form, busy, presenter) }
                    activeOpen?.message?.let { SupportingText(it) }
                }
            }
        }
    }
}

@Composable
private fun ProviderFormFields(form: ProviderForm, busy: Boolean, presenter: PluginPresenter) {
    // Drafts live only while this form is shown; a typed secret never outlives it.
    val values = remember(form) { mutableStateMapOf<String, String>().apply { form.fields.forEach { put(it.key, it.value) } } }
    if (form.notice.isNotBlank()) SupportingText(form.notice)
    form.fields.forEachIndexed { index, field ->
        if (field.group.isNotBlank() && field.group != form.fields.getOrNull(index - 1)?.group) Eyebrow(field.group, Modifier.padding(top = 6.dp))
        ProviderFormField(field, values[field.key].orEmpty(), !busy) { values[field.key] = it }
    }
    PillButton(if (form.fields.all { it.readonly }) "刷新" else "保存", { presenter.submitPanel(values.toMap()) }, Modifier.fillMaxWidth(), enabled = !busy)
}

@Composable
private fun ProviderFormField(field: ProviderField, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val label = if (field.required) "${field.label} *" else field.label
    when (field.type) {
        "toggle" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(value == "true", { onChange(it.toString()) }, enabled = enabled && !field.readonly)
        }
        "select" -> Box(Modifier.fillMaxWidth()) {
            var menu by remember { mutableStateOf(false) }
            OutlinedTextField(field.options.firstOrNull { it.second == value }?.first ?: "请选择", {}, readOnly = true, label = { Text(label) },
                trailingIcon = { Icon(TwilightIcons.ChevronDown, null) }, singleLine = true, shape = RoundedCornerShape(16.dp),
                colors = twilightFieldColors(), modifier = Modifier.fillMaxWidth())
            // A read-only text field still takes the tap; this layer opens the choices instead.
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled && !field.readonly) { menu = true })
            DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                field.options.forEach { (text, choice) -> DropdownMenuItem(text = { Text(text) }, onClick = { onChange(choice); menu = false }) }
            }
        }
        else -> OutlinedTextField(value, onChange, label = { Text(label) }, placeholder = if (field.placeholder.isBlank()) null else ({ Text(field.placeholder) }),
            singleLine = true, readOnly = field.readonly, enabled = enabled || field.readonly,
            visualTransformation = if (field.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when (field.type) { "password" -> KeyboardType.Password; "url" -> KeyboardType.Uri; else -> KeyboardType.Text }),
            shape = RoundedCornerShape(16.dp), colors = twilightFieldColors(), modifier = Modifier.fillMaxWidth())
    }
}
