package com.twilightecho.android.feature.player.zones

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.DesktopSyncGroup
import com.twilightecho.android.coreclient.DesktopZoneSession
import com.twilightecho.android.feature.player.LoadingContent
import com.twilightecho.android.feature.player.LocalDockInset
import com.twilightecho.android.feature.player.MainDestinationTab
import com.twilightecho.android.feature.player.PlayerActions
import com.twilightecho.android.feature.player.PlayerUiState
import com.twilightecho.android.ui.components.EmptyState
import com.twilightecho.android.ui.components.PageHeading
import com.twilightecho.android.ui.components.PillButton
import com.twilightecho.android.ui.components.SupportingText
import com.twilightecho.android.ui.components.TwilightIcons
import com.twilightecho.android.ui.components.TwilightMotion
import com.twilightecho.android.ui.components.rememberMotionEnabled
import kotlinx.coroutines.delay

/*
 * The 声区 tab: a wall of windows, one per place the computer plays to, and the detail pages behind them.
 * [detail] is the route's saved page: null for the wall, "zone:<id>" or "group:<id>" for a detail page.
 */

@Composable
internal fun ZonesTabScreen(state: PlayerUiState, actions: PlayerActions, detail: String?, openDetail: (String?) -> Unit) {
    // Zones are polled only while this tab is on screen; the view model counts overlapping pages.
    LifecycleStartEffect(Unit) {
        actions.desktopZonesActive(true)
        onStopOrDispose { actions.desktopZonesActive(false) }
    }
    when {
        detail == null -> ZonesWall(state, actions, openDetail)
        detail.startsWith("group:") -> GroupDetailScreen(state, actions, detail.removePrefix("group:")) { openDetail(null) }
        else -> ZoneDetailScreen(state, actions, detail.removePrefix("zone:"), { openDetail(null) }, openDetail)
    }
}

/** Consecutive single zones share a row on wide screens; a group always spans the row. */
private sealed interface WallRow {
    val key: String
    data class Singles(val zones: List<DesktopZoneSession>) : WallRow {
        override val key get() = zones.joinToString("+") { "zone:${it.id}" }
    }
    data class Grouped(val group: DesktopSyncGroup, val members: List<DesktopZoneSession?>) : WallRow {
        override val key get() = "group:${group.id}"
    }
}

private fun wallRows(entries: List<ZoneEntry>, perRow: Int): List<WallRow> {
    val rows = mutableListOf<WallRow>()
    val pending = mutableListOf<DesktopZoneSession>()
    fun flush() {
        pending.chunked(perRow).forEach { rows += WallRow.Singles(it) }
        pending.clear()
    }
    entries.forEach { entry ->
        when (entry) {
            is ZoneEntry.Single -> pending += entry.zone
            is ZoneEntry.Group -> { flush(); rows += WallRow.Grouped(entry.group, entry.members) }
        }
    }
    flush()
    return rows
}

@Composable
private fun ZonesWall(state: PlayerUiState, actions: PlayerActions, openDetail: (String?) -> Unit) {
    val desktop = state.operatingMode as? CoreOperatingMode.RemoteDesktopCore
    val zones = state.desktopZoneSessions
    val zonesError = state.desktopZonesError
    val groupsError = state.desktopGroupsError
    val groups = state.desktopSyncGroups?.groups.orEmpty()
    val motion = rememberMotionEnabled()
    var creating by rememberSaveable { mutableStateOf(false) }
    // The card the user came back from keeps the selected treatment: tonal surface and accent outline.
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = { key -> lastOpened = key; openDetail(key) }
    val canGroup = groupCandidates(zones).size >= MIN_GROUP_MEMBERS && state.desktopSyncGroups != null &&
        groupsError == null && state.desktopAudio != null && !state.desktopAudioBusy
    val connected = desktop != null && desktop.connected
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rows = wallRows(zoneEntries(zones, groups), if (maxWidth >= 600.dp) 2 else 1)
        val wide = maxWidth >= 600.dp
        LazyColumn(Modifier.fillMaxSize().testTag("zonesScreen"),
            contentPadding = PaddingValues(bottom = 24.dp + LocalDockInset.current)) {
            item(key = "heading") {
                val caption = when {
                    !connected -> desktop?.hostLabel
                    zones.isEmpty() -> null
                    else -> zonesCaption(zones, groups)
                }
                PageHeading("声区", caption, Modifier.padding(horizontal = 20.dp).padding(top = 12.dp)) {
                    if (connected && zones.isNotEmpty()) PillButton("建立同步组", { creating = true },
                        Modifier.testTag("zonesCreateGroup"), icon = TwilightIcons.Link, enabled = canGroup, tonal = true)
                }
            }
            when {
                !connected -> item(key = "offline") {
                    EmptyState("电脑未连接", "${desktop?.hostLabel ?: "电脑"} 重新连接后，这里会显示它的声区。", TwilightIcons.Computer)
                }
                zones.isEmpty() && zonesError != null -> item(key = "unavailable") {
                    EmptyState("声区不可用", zonesError, TwilightIcons.Speaker)
                }
                zones.isEmpty() -> item(key = "loading") { LoadingContent("正在读取声区") }
                else -> {
                    item(key = "feedback") { ZoneFeedback(state, Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp)) }
                    zonesError?.let { error ->
                        item(key = "zonesError") { ZoneErrorNote(error, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                    }
                    items(rows, key = { it.key }) { row ->
                        val placement = if (motion) Modifier.animateItem() else Modifier
                        when (row) {
                            is WallRow.Singles -> Row(placement.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.zones.forEach { zone ->
                                    ZoneCard(zone, selected = lastOpened == "zone:${zone.id}", onOpen = { open("zone:${zone.id}") },
                                        onTransport = { kind -> actions.desktopZoneTransportFor(zone.id, kind) },
                                        onPickMusic = { actions.openDesktopQueue("zone", zone.id) }, modifier = Modifier.weight(1f))
                                }
                                if (wide && row.zones.size == 1) Spacer(Modifier.weight(1f))
                            }
                            is WallRow.Grouped -> GroupCard(row.group, row.members, zones, selected = lastOpened == "group:${row.group.id}",
                                onOpen = { open("group:${row.group.id}") },
                                onTransport = { kind -> groupTransportZone(state, row.group)?.let { actions.desktopZoneTransportFor(it, kind) } },
                                modifier = placement.padding(horizontal = 16.dp, vertical = 6.dp))
                        }
                    }
                    if (zones.size == 1) item(key = "hint") {
                        SupportingText("在电脑上新建声区并连接输出桥后，可以在这里接力或编组。",
                            Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                    }
                    groupsError?.let { error ->
                        item(key = "groupsError") {
                            SupportingText("同步组暂不可用：$error", Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                        }
                    }
                }
            }
        }
    }
    if (creating) GroupCreateSheet(state, actions) { creating = false }
}

/** A group's transport goes through one member's snapshot: the followed one if it is a member, else the first listed. */
private fun groupTransportZone(state: PlayerUiState, group: DesktopSyncGroup): String? {
    val members = group.members.map { it.zoneId }.filter { id -> state.desktopZoneSessions.any { it.id == id } }
    return state.desktopAudio?.zone?.zoneId?.takeIf { it in members } ?: members.firstOrNull()
}

/**
 * Core's answer to the last command: a success note that fades after four seconds, or its Chinese error,
 * kept until the next command clears it.
 */
@Composable
internal fun ZoneFeedback(state: PlayerUiState, modifier: Modifier = Modifier) {
    val motion = rememberMotionEnabled()
    val colors = MaterialTheme.colorScheme
    val notice = state.desktopAudioNotice
    var noticeShown by remember { mutableStateOf(false) }
    var lastNotice by remember { mutableStateOf("") }
    LaunchedEffect(notice) {
        if (notice == null) { noticeShown = false; return@LaunchedEffect }
        lastNotice = notice
        noticeShown = true
        delay(4000)
        noticeShown = false
    }
    val error = state.desktopAudioError
    var lastError by remember { mutableStateOf("") }
    if (error != null) lastError = error
    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(noticeShown, enter = TwilightMotion.sectionExpand(motion), exit = TwilightMotion.sectionCollapse(motion)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp))
                .background(colors.onSurface.copy(alpha = 0.07f)).padding(horizontal = 12.dp, vertical = 10.dp).testTag("zoneNotice"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToneDot(ZoneTone.LIVE)
                Text(lastNotice, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            }
        }
        AnimatedVisibility(error != null, enter = TwilightMotion.sectionExpand(motion), exit = TwilightMotion.sectionCollapse(motion)) {
            ZoneErrorNote(lastError, Modifier.padding(top = 12.dp).testTag("zoneError"))
        }
    }
}
