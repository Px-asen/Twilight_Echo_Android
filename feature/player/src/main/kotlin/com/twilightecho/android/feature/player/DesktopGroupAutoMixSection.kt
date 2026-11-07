package com.twilightecho.android.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.DesktopSyncGroup
import com.twilightecho.android.ui.components.SettingsGroup

/** Shared by the zone detail and the legacy computer-audio sheet. Core owns the selected value. */
@Composable
internal fun DesktopGroupAutoMixSection(group: DesktopSyncGroup, enabled: Boolean, busy: Boolean,
    change: (String) -> Unit) {
    SettingsGroup(Modifier.testTag("groupAutoMix"), title = "AutoMix") {
        Column(Modifier.selectableGroup()) {
            val playback = group.playback
            val status = when {
                busy -> "正在保存…"
                group.autoMixMode == "off" -> "已关闭"
                playback?.state != "playing" -> "暂停待播"
                else -> when (playback.autoMixState) {
                    "preparing" -> "正在准备"; "ready" -> "已就绪"; "mixing" -> "正在过渡"; "degraded" -> "普通衔接"; else -> "等待下一曲"
                }
            }
            Text(status + if (playback?.state == "playing" && playback.autoMixStemEnhanced) " · 人声分离增强" else "",
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("groupAutoMixStatus"),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(Triple("off", "关闭", "按原曲顺序衔接"), Triple("natural", "自然", "柔和衔接，保留歌曲节奏"),
                Triple("dj", "DJ", "节拍对齐与混音过渡")).forEach { (mode, label, hint) ->
                val selected = group.autoMixMode == mode
                val canChoose = enabled && !busy && (mode == "off" || group.autoMixAvailable)
                Row(Modifier.fillMaxWidth().selectable(selected, enabled = canChoose, role = Role.RadioButton) {
                    if (!selected) change(mode)
                }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("groupAutoMix-$mode"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected, onClick = null, enabled = canChoose)
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canChoose) 1f else 0.38f))
                        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(if (group.autoMixAvailable) "更改后全组暂停；按播放继续。成员的本地 AutoMix 设置不变。"
                else "更新 Core 与成员引擎后可开启组 AutoMix。", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
