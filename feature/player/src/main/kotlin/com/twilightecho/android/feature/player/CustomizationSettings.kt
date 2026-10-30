package com.twilightecho.android.feature.player

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.ui.components.*
import kotlin.math.roundToInt

internal fun LazyListScope.appearanceSettings(state: PlayerUiState, actions: PlayerActions) {
    val settings = state.preferences.appearance
    fun edit(change: (AppearancePreferences) -> AppearancePreferences) =
        actions.editPreferences { it.copy(appearance = change(it.appearance)) }
    item {
        val motionEnabled = rememberMotionEnabled()
        PreferenceGroup("配色") {
            PreferenceChoices(AccentSource.entries, settings.accentSource,
                { if (it == AccentSource.COVER) "封面取色" else "自选颜色" }, { choice -> edit { it.copy(accentSource = choice) } }, "accent")
            AnimatedVisibility(
                visible = settings.accentSource == AccentSource.CUSTOM,
                enter = TwilightMotion.sectionExpand(motionEnabled),
                exit = TwilightMotion.sectionCollapse(motionEnabled)
            ) {
                ColorSetting("强调色", settings.accentArgb, "accentColor") { color -> edit { it.copy(accentArgb = color) } }
            }
        }
    }
    item {
        val motionEnabled = rememberMotionEnabled()
        PreferenceGroup("背景") {
            PreferenceChoices(BackgroundStyle.entries, settings.backgroundStyle,
                { when (it) { BackgroundStyle.COVER -> "封面"; BackgroundStyle.IMAGE -> "图片"; BackgroundStyle.SOLID -> "纯色" } },
                { choice ->
                    if (choice == BackgroundStyle.IMAGE && settings.backgroundImagePath == null) actions.pickBackground()
                    else edit { it.copy(backgroundStyle = choice) }
                }, "background", enabled = !state.isImportingBackground)
            AnimatedContent(
                targetState = settings.backgroundStyle,
                modifier = if (motionEnabled) Modifier.animateContentSize(spring(dampingRatio = 0.86f, stiffness = 400f)) else Modifier,
                transitionSpec = { TwilightMotion.stateFadeTransition(motionEnabled) },
                label = "backgroundStyleControls"
            ) { targetStyle ->
                when (targetStyle) {
                    BackgroundStyle.COVER -> Spacer(Modifier.fillMaxWidth())
                    BackgroundStyle.SOLID -> ColorSetting("背景颜色", settings.backgroundArgb, "backgroundColor") { color ->
                        edit { it.copy(backgroundArgb = color) }
                    }
                    BackgroundStyle.IMAGE -> {
                        val image = rememberBackgroundImage(settings.backgroundImagePath)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            image?.let { Image(it, null, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                            TextButton(actions.pickBackground, enabled = !state.isImportingBackground, modifier = Modifier.padding(start = 8.dp)
                                .testTag("replaceBackground")) { Text("更换图片", style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = state.isImportingBackground,
                enter = TwilightMotion.sectionExpand(motionEnabled),
                exit = TwilightMotion.sectionCollapse(motionEnabled)
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
    }
    item {
        PreferenceGroup("界面") {
            val scale = settings.densityScale
            val systemDensity = LocalDensity.current.density / scale
            PreferenceSlider("界面缩放", scale * 100, 80f..125f, "${(scale * 100).roundToInt()}% · ${(systemDensity * scale * 160).roundToInt()} DPI",
                "densityScale", steps = 8) { value -> edit { it.copy(densityScale = value / 100) } }
            PreferenceSlider("内容不透明度", settings.contentOpacity * 100, 20f..100f, "${(settings.contentOpacity * 100).roundToInt()}%",
                "contentOpacity") { value -> edit { it.copy(contentOpacity = value.roundToInt() / 100f) } }
        }
    }
    item { ResetPreferences("重置外观", "resetAppearance") { edit { AppearancePreferences(backgroundImagePath = it.backgroundImagePath) } } }
}

internal fun LazyListScope.lyricsSettings(state: PlayerUiState, actions: PlayerActions) {
    val settings = state.preferences.lyrics
    fun edit(change: (LyricsPreferences) -> LyricsPreferences) = actions.editPreferences { it.copy(lyrics = change(it.lyrics)) }
    item {
        val motionEnabled = rememberMotionEnabled()
        val line = state.currentLyrics?.lines?.getOrNull(LocalPlayerProgress.current.lyricIndex.value)
        GlassCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()) {
            Column(
                Modifier.padding(22.dp)
                    .then(if (motionEnabled) Modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f)) else Modifier)
                    .testTag("lyricsStylePreview")
            ) {
                Text(line?.text ?: "让一首歌慢慢走远", fontSize = settings.fontSizeSp.sp,
                    lineHeight = (settings.fontSizeSp * 4 / 3).sp, fontWeight = FontWeight(settings.fontWeight))
                AnimatedVisibility(
                    visible = settings.showTranslation,
                    enter = TwilightMotion.sectionExpand(motionEnabled),
                    exit = TwilightMotion.sectionCollapse(motionEnabled)
                ) {
                    Text(line?.translation ?: "在这里，听见每一句", fontSize = settings.translationSizeSp.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                Text("下一句歌词", fontSize = settings.fontSizeSp.sp, fontWeight = FontWeight(settings.fontWeight),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                    modifier = Modifier.padding(top = settings.lineSpacingDp.dp).blur((settings.blurDp * 0.225f).dp))
            }
        }
    }
    item {
        PreferenceGroup("文字") {
            PreferenceSlider("歌词字号", settings.fontSizeSp, 22f..46f, "${settings.fontSizeSp.roundToInt()}", "lyricFontSize", steps = 23) {
                value -> edit { it.copy(fontSizeSp = value.roundToInt().toFloat()) }
            }
            Text("歌词粗细", Modifier.padding(start = 18.dp, top = 12.dp), style = MaterialTheme.typography.titleMedium)
            PreferenceChoices(listOf(400, 500, 700, 800), settings.fontWeight,
                { when (it) { 400 -> "标准"; 500 -> "适中"; 700 -> "加粗"; else -> "特粗" } },
                { value -> edit { it.copy(fontWeight = value) } }, "lyricWeight")
            PreferenceSlider("行间距", settings.lineSpacingDp, 12f..44f, "${settings.lineSpacingDp.roundToInt()}", "lyricSpacing", steps = 31) {
                value -> edit { it.copy(lineSpacingDp = value.roundToInt().toFloat()) }
            }
        }
    }
    item {
        PreferenceGroup("景深") {
            PreferenceSlider("歌词模糊", settings.blurDp, 0f..8f, if (settings.blurDp == 0f) "关闭" else "${settings.blurDp.roundToInt()}",
                "lyricBlur", steps = 7) { value -> edit { it.copy(blurDp = value.roundToInt().toFloat()) } }
            if (Build.VERSION.SDK_INT < 31) Text("Android 12 起支持歌词景深", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 18.dp))
        }
    }
    item {
        val motionEnabled = rememberMotionEnabled()
        PreferenceGroup("译文") {
            SettingsSwitch("显示译文", "", settings.showTranslation) { edit { it.copy(showTranslation = !it.showTranslation) } }
            AnimatedVisibility(
                visible = settings.showTranslation,
                enter = TwilightMotion.sectionExpand(motionEnabled),
                exit = TwilightMotion.sectionCollapse(motionEnabled)
            ) {
                PreferenceSlider("译文字号", settings.translationSizeSp, 14f..26f,
                    "${settings.translationSizeSp.roundToInt()}", "translationFontSize", steps = 11) { value ->
                    edit { it.copy(translationSizeSp = value.roundToInt().toFloat()) }
                }
            }
        }
    }
    item { ResetPreferences("重置歌词样式", "resetLyrics") { edit { LyricsPreferences() } } }
}

internal fun LazyListScope.streamingQualitySettings(state: PlayerUiState, actions: PlayerActions) {
    item {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.selectableGroup()) {
                    NcmPlaybackQuality.entries.forEach { quality ->
                        val selected = quality == state.preferences.ncmQuality
                        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton,
                            onClick = { actions.editPreferences { it.copy(ncmQuality = quality) } })
                            .testTag("quality-${quality.name}").padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(quality.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            RadioButton(selected, onClick = null, modifier = Modifier.size(44.dp))
                        }
                    }
                }
            }
            Text("按账号权益与歌曲资源获取音质，新加载的歌曲使用此设置。", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsGroup {
                SettingsItem(TwilightIcons.SignalWave, "网易云账号", onClick = actions.streaming.account)
            }
        }
    }
}

@Composable
private fun PreferenceGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 12.dp)) {
        SectionHeading(title, modifier = Modifier.padding(bottom = 12.dp))
        GlassCard(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun <T> PreferenceChoices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit,
    tag: String, enabled: Boolean = true) {
    val motionEnabled = rememberMotionEnabled()
    Row(Modifier.fillMaxWidth().padding(8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            val active = option == selected
            val interaction = remember { MutableInteractionSource() }
            val bg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f) else Color.Transparent,
                tween(if (motionEnabled) 180 else 0),
                label = "prefChoiceBg"
            )
            val fg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(if (motionEnabled) 180 else 0),
                label = "prefChoiceFg"
            )
            Box(Modifier.weight(1f).tactilePress(interaction, pressedScale = 0.95f, enabled = enabled)
                .heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                .background(bg)
                .selectable(
                    selected = active,
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    role = Role.RadioButton
                ) { onSelect(option) }.testTag("$tag-$option")
                .padding(horizontal = 2.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(label(option), style = MaterialTheme.typography.bodyLarge, color = fg,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun PreferenceSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String,
    tag: String, steps: Int = 0, onCommit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(if (draft == value) display else draft.roundToInt().toString(),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(draft, { draft = it }, onValueChangeFinished = { onCommit(draft) }, valueRange = range, steps = steps,
            colors = twilightSliderColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
                .semantics { contentDescription = title })
    }
}

@Composable
private fun ResetPreferences(label: String, tag: String, reset: () -> Unit) {
    TextButton(reset, Modifier.padding(horizontal = 20.dp, vertical = 12.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ColorSetting(title: String, argb: Int, tag: String, onSelect: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val motionEnabled = rememberMotionEnabled()
    val swatchColor by animateColorAsState(Color(argb), tween(if (motionEnabled) 180 else 0), label = "swatchColor")
    Row(Modifier.fillMaxWidth().clickable { open = true }.testTag(tag).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Box(Modifier.size(36.dp).clip(CircleShape).background(swatchColor)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape))
    }
    if (open) ColorPickerDialog(title, argb, { open = false }) { onSelect(it); open = false }
}

@Composable
private fun ColorPickerDialog(title: String, initial: Int, dismiss: () -> Unit, apply: (Int) -> Unit) {
    var argb by rememberSaveable { mutableIntStateOf(initial) }
    var hex by rememberSaveable { mutableStateOf(colorHex(initial)) }
    val motionEnabled = rememberMotionEnabled()
    val previewColor by animateColorAsState(Color(argb), tween(if (motionEnabled) 140 else 0), label = "pickerPreviewColor")
    val hsv = remember(argb) { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    fun choose(color: Int) { argb = color; hex = colorHex(color) }
    val presets = listOf("珊瑚红" to 0xFFFA2D48, "琥珀" to 0xFFF0AB4F, "竹绿" to 0xFF74C69D,
        "海蓝" to 0xFF60A5FA, "暖白" to 0xFFF4F0E8, "石墨" to 0xFF1C1C1E)
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, modifier = Modifier.testTag("colorPicker"),
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(previewColor))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    presets.forEach { (name, color) ->
                        val interaction = remember { MutableInteractionSource() }
                        val selected = (argb and 0xFFFFFF) == (color.toInt() and 0xFFFFFF)
                        val borderColor by animateColorAsState(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            tween(if (motionEnabled) 160 else 0),
                            label = "presetBorder"
                        )
                        Box(Modifier.tactilePress(interaction, pressedScale = 0.88f)
                            .size(38.dp).clip(CircleShape).background(Color(color))
                            .border(if (selected) 2.dp else 1.dp, borderColor, CircleShape)
                            .clickable(interactionSource = interaction, indication = LocalIndication.current) { choose(color.toInt()) }
                            .semantics { contentDescription = name })
                    }
                }
                OutlinedTextField(hex, { input ->
                    hex = input.take(7).uppercase()
                    parseColorHex(hex)?.let { argb = it }
                }, label = { Text("色值") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().testTag("colorHex"),
                    colors = twilightFieldColors())
                listOf("色相", "饱和度", "亮度").forEachIndexed { index, label ->
                    Column {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Slider(hsv[index], { value ->
                            val next = hsv.copyOf(); next[index] = value
                            choose(android.graphics.Color.HSVToColor(next))
                        }, valueRange = if (index == 0) 0f..359f else 0f..1f, colors = twilightSliderColors(),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
                    }
                }
            }
        }, confirmButton = { TextButton({ apply(argb) }, enabled = parseColorHex(hex) != null, modifier = Modifier.testTag("applyColor")) {
            Text("应用", style = MaterialTheme.typography.bodyLarge)
        } }, dismissButton = { TextButton(dismiss) { Text("取消", style = MaterialTheme.typography.bodyLarge) } })
}

internal fun colorHex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
internal fun parseColorHex(text: String): Int? = text.removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }
    ?.toLongOrNull(16)?.toInt()?.or(0xFF shl 24)
