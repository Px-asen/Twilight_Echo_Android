package com.twilightecho.android.feature.player.zones

import com.twilightecho.android.coreclient.DesktopSessionHandoff
import com.twilightecho.android.coreclient.DesktopSyncGroup
import com.twilightecho.android.coreclient.DesktopSyncGroupCreate
import com.twilightecho.android.coreclient.DesktopSyncSelection
import com.twilightecho.android.coreclient.DesktopZonePlayback
import com.twilightecho.android.coreclient.DesktopZoneSession
import com.twilightecho.android.coreclient.DesktopZoneTrack
import com.twilightecho.android.coreclient.desktopAdaptiveObservation
import com.twilightecho.android.coreclient.desktopClockObservation
import com.twilightecho.android.feature.player.dspModules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Pure 声区 presentation rules. Core owns every decision; these functions only read what it reported
 * and mirror the command preconditions DesktopAudioClient enforces, so the UI never offers a command
 * the client would refuse.
 */

const val LOCAL_ZONE_ID = "local"
const val MIN_GROUP_MEMBERS = 2
const val MAX_GROUP_MEMBERS = 4
const val MAX_DOWNSTREAM_DELAY_MS = 250.0
const val MAX_GROUP_NAME_LENGTH = 80
/** Core flags drift once the lower bound of the measured spread exceeds 1 ms. */
const val GROUP_SKEW_TOLERANCE_MS = 1.0

/** How a zone's window is lit: the cover is the light, its state the switch. */
enum class ZoneLight { PLAYING, PAUSED, OFFLINE, UNPAIRED, EMPTY }

/** Semantic state colour; the UI maps it to theme roles and Twilight signal tokens. */
enum class ZoneTone { LIVE, INFO, WARNING, DANGER, NEUTRAL }

enum class ZoneOutputState(val label: String, val tone: ZoneTone) {
    CONNECTED("已连接", ZoneTone.LIVE),
    CONNECTING("连接中", ZoneTone.WARNING),
    OFFLINE("离线", ZoneTone.DANGER),
    UNSELECTED("未选择", ZoneTone.NEUTRAL)
}

val DesktopZoneSession.isBridgeZone: Boolean get() = id != LOCAL_ZONE_ID

/** A bridge zone never falls back to local speakers, so "not connected" is a real, dark state. */
fun zoneOutputDown(zone: DesktopZoneSession): Boolean {
    val output = zone.output
    return if (zone.isBridgeZone) output?.connected != true else output != null && output.selected && !output.connected
}

/**
 * Connection of the zone's output. The local zone without a selected bridge plays on this computer's
 * own device. A selected bridge with neither a connection nor an error is a connection still in flight;
 * once Core reports an error for the output or the zone, it is not called "connecting".
 */
fun zoneOutputState(zone: DesktopZoneSession): ZoneOutputState {
    val output = zone.output
    if (!zone.isBridgeZone && output?.selected != true) return ZoneOutputState.CONNECTED
    if (output == null) return if (zone.error != null) ZoneOutputState.OFFLINE else ZoneOutputState.UNSELECTED
    return when {
        output.connected -> ZoneOutputState.CONNECTED
        !output.selected -> ZoneOutputState.UNSELECTED
        output.error == null && zone.error == null -> ZoneOutputState.CONNECTING
        else -> ZoneOutputState.OFFLINE
    }
}

fun zoneLight(zone: DesktopZoneSession): ZoneLight = light(zone, zone.queue != null && zone.track != null, zone.playback)

/** Members share the group's music; only their own output can switch a window off. */
fun memberLight(zone: DesktopZoneSession, group: DesktopSyncGroup): ZoneLight =
    light(zone, group.queue != null && group.track != null, group.playback)

private fun light(zone: DesktopZoneSession, hasMusic: Boolean, playback: DesktopZonePlayback?): ZoneLight = when {
    zoneOutputDown(zone) -> if (zone.output?.peerName != null) ZoneLight.OFFLINE else ZoneLight.UNPAIRED
    !hasMusic -> ZoneLight.EMPTY
    playback?.state == "playing" -> ZoneLight.PLAYING
    else -> ZoneLight.PAUSED
}

fun playbackStateLabel(state: String?): String = when (state) {
    "playing" -> "播放中"
    "paused" -> "已暂停"
    "loading", "buffering" -> "加载中"
    else -> "已停止"
}

/** "本机输出" for this computer's device, "输出桥 · {peerName}" once a receiver is known. */
fun zoneOutputLabel(zone: DesktopZoneSession): String {
    val output = zone.output
    if (!zone.isBridgeZone) return output?.takeIf { it.selected }?.let { "输出桥 · ${it.peerName ?: "未命名设备"}" } ?: "本机输出"
    return output?.peerName?.let { "输出桥 · $it" } ?: "尚未连接输出桥"
}

/** What a dark window says instead of a cover. */
fun zoneWindowMessage(zone: DesktopZoneSession, light: ZoneLight): String? = when (light) {
    ZoneLight.OFFLINE -> if (zoneOutputState(zone) == ZoneOutputState.CONNECTING) "正在连接输出桥" else "输出桥离线"
    ZoneLight.UNPAIRED -> if (zone.isBridgeZone) "未连接输出桥" else "输出桥离线"
    ZoneLight.EMPTY -> "队列为空"
    else -> null
}

fun formatRate(rate: Double?): String {
    if (rate == null || !rate.isFinite() || rate <= 0) return ""
    val khz = rate / 1000.0
    val text = if (khz == khz.toLong().toDouble()) khz.toLong().toString()
        else String.format(Locale.ROOT, "%.1f", khz).trimEnd('0').trimEnd('.')
    return "$text kHz"
}

fun formatAudio(rate: Double?, bits: Double?): String {
    val value = formatRate(rate)
    if (value.isEmpty()) return ""
    return if (bits != null && bits.isFinite() && bits > 0) "$value / ${bits.roundToInt()} bit" else value
}

private val BackendLabels = mapOf(
    "wasapi" to "WASAPI 共享", "wasapi-shared" to "WASAPI 共享", "wasapi-exclusive" to "WASAPI 独占",
    "asio" to "ASIO", "coreaudio" to "CoreAudio", "coreaudio-exclusive" to "CoreAudio 独占",
    "alsa" to "ALSA", "sync-group" to "同步组", "bridge-pcm" to "输出桥 PCM"
)

fun backendLabel(backend: String?, exclusive: Boolean = false): String {
    if (backend.isNullOrBlank()) return ""
    if (backend == "wasapi" && exclusive) return BackendLabels.getValue("wasapi-exclusive")
    return BackendLabels[backend] ?: backend
}

/** "FLAC · 96 kHz / 24 bit → WASAPI 独占 · 48 kHz / 24 bit" — only facts Core reported. */
fun zoneFormatLine(playback: DesktopZonePlayback?, track: DesktopZoneTrack?, zone: DesktopZoneSession?): String {
    val codec = (playback?.codec?.takeIf { it.isNotBlank() } ?: track?.format).orEmpty().trim().uppercase(Locale.ROOT)
    val source = listOf(codec, formatAudio(playback?.sourceSampleRate?.takeIf { it > 0 } ?: track?.sampleRate,
        playback?.sourceBitDepth?.takeIf { it > 0 } ?: track?.bitDepth)).filter { it.isNotEmpty() }.joinToString(" · ")
    val output = zone?.output
    val receiver = output?.takeIf { it.selected && it.connected }?.receiver
    val sink = (if (receiver != null) listOf(backendLabel(receiver.backend, receiver.exclusive),
            formatAudio(receiver.actualSampleRate, receiver.actualBitDepth))
        else listOf(backendLabel(playback?.outputBackend), formatAudio(playback?.actualSampleRate, playback?.actualBitDepth)))
        .filter { it.isNotEmpty() }.joinToString(" · ")
    return if (source.isNotEmpty() && sink.isNotEmpty()) "$source → $sink" else source.ifEmpty { sink }
}

/** Covers exist only behind `remote-track:` references of the computer's own (local provider) files. */
fun zoneArtworkId(track: DesktopZoneTrack?): String? = track?.id
    ?.takeIf { it.startsWith("remote-track:") && track?.source.let { source -> source == null || source == "local" } }

fun trackSubtitle(track: DesktopZoneTrack?): String =
    listOfNotNull(track?.artist?.takeIf { it.isNotBlank() }, track?.album?.takeIf { it.isNotBlank() }).joinToString(" · ")

fun volumeLabel(volume: Double): String = "${(volume.coerceIn(0.0, 1.0) * 100).roundToInt()}%"

// ---------------------------------------------------------------------------------------------
// The wall of windows

sealed interface ZoneEntry {
    val key: String
    data class Single(val zone: DesktopZoneSession) : ZoneEntry { override val key get() = "zone:${zone.id}" }
    /** [members] follows the group's order; a member the zone list no longer has is null. */
    data class Group(val group: DesktopSyncGroup, val members: List<DesktopZoneSession?>) : ZoneEntry {
        override val key get() = "group:${group.id}"
    }
}

/** Zones in Core's order; each sync group takes the place of its first listed member. */
fun zoneEntries(zones: List<DesktopZoneSession>, groups: List<DesktopSyncGroup>): List<ZoneEntry> {
    val byId = zones.associateBy { it.id }
    val grouped = groups.flatMap { group -> group.members.map { it.zoneId } }.toSet()
    val placed = mutableSetOf<String>()
    val entries = mutableListOf<ZoneEntry>()
    fun place(group: DesktopSyncGroup) {
        if (placed.add(group.id)) entries += ZoneEntry.Group(group, group.members.map { byId[it.zoneId] })
    }
    zones.forEach { zone ->
        val group = groups.firstOrNull { candidate -> candidate.members.any { it.zoneId == zone.id } }
        when {
            group != null -> place(group)
            zone.id !in grouped -> entries += ZoneEntry.Single(zone)
        }
    }
    groups.forEach(::place)
    return entries
}

/** A place counts as playing when its window is lit; a member with its output down is silent. */
fun playingCount(zones: List<DesktopZoneSession>, groups: List<DesktopSyncGroup>): Int = zones.count { zone ->
    val group = groups.firstOrNull { candidate -> candidate.members.any { it.zoneId == zone.id } }
    (if (group != null) memberLight(zone, group) else zoneLight(zone)) == ZoneLight.PLAYING
}

fun zonesCaption(zones: List<DesktopZoneSession>, groups: List<DesktopSyncGroup>): String =
    "${zones.size} 个声区 · ${playingCount(zones, groups)} 处在播"

/** The session the transport of a sync group acts on, as the existing sheet renders it. */
fun DesktopSyncGroup.asSession(): DesktopZoneSession = DesktopZoneSession(id, name, revision, track, playback, queue = queue)

// ---------------------------------------------------------------------------------------------
// Handoff (接力)

/** Why [source] cannot hand its queue over, or null when it can. */
fun handoffSourceProblem(source: DesktopZoneSession): String? = when {
    source.groupId != null -> "同步组成员请先解除编组"
    source.queue?.occurrenceId == null -> "此声区没有可接力的队列"
    else -> null
}

data class HandoffTarget(val zone: DesktopZoneSession, val reason: String?) {
    val enabled: Boolean get() = reason == null
}

fun handoffTargets(source: DesktopZoneSession, zones: List<DesktopZoneSession>): List<HandoffTarget> =
    zones.filter { it.id != source.id }.map { HandoffTarget(it, if (it.groupId != null) "同步组成员，不能接收接力" else null) }

/** A target that has a queue is replaced only after the user says so. */
fun handoffNeedsReplace(target: DesktopZoneSession): Boolean = target.queue != null

/** Resuming on an output that is down could only fail; the handoff then stays paused. */
fun handoffResumeBlocked(target: DesktopZoneSession): Boolean = zoneOutputDown(target)

const val HANDOFF_RESUME_BLOCKED_HINT = "目标输出桥未连接，接力后保持暂停"

/** Mirrors DesktopAudioClient.handoff's preconditions; null when the command may be sent. */
fun handoffProblem(source: DesktopZoneSession, target: DesktopZoneSession?, replace: Boolean): String? = when {
    target == null -> "请选择目标声区"
    target.id == source.id -> "请选择不同的目标声区"
    source.groupId != null || target.groupId != null -> "同步组成员请先解除编组"
    source.queue?.occurrenceId == null -> "此声区没有可接力的队列"
    handoffNeedsReplace(target) && !replace -> "请先确认替换目标队列"
    else -> null
}

fun buildHandoff(source: DesktopZoneSession, target: DesktopZoneSession, generation: Long,
    replace: Boolean, resume: Boolean): DesktopSessionHandoff? {
    if (handoffProblem(source, target, replace) != null) return null
    return DesktopSessionHandoff(source, target, generation, replace = replace && handoffNeedsReplace(target),
        resume = resume && !handoffResumeBlocked(target))
}

// ---------------------------------------------------------------------------------------------
// Creating a sync group

data class GroupDraft(val name: String = "", val selected: List<String> = emptyList(),
    val sourceId: String? = null, val delays: Map<String, Double> = emptyMap()) {
    fun delayOf(zoneId: String): Double = delays[zoneId] ?: 0.0

    /** Selecting past [MAX_GROUP_MEMBERS] is ignored; deselecting the queue's owner clears the choice. */
    fun toggle(zoneId: String): GroupDraft = if (zoneId in selected)
        copy(selected = selected - zoneId, sourceId = sourceId.takeIf { it != zoneId })
    else if (selected.size >= MAX_GROUP_MEMBERS) this
    else copy(selected = selected + zoneId)
}

fun groupCandidates(zones: List<DesktopZoneSession>): List<DesktopZoneSession> = zones.filter { it.groupId == null }

/** Only a selected member whose queue Core can fence may supply the group's queue. */
fun groupSourceOptions(zones: List<DesktopZoneSession>, draft: GroupDraft): List<DesktopZoneSession> =
    groupCandidates(zones).filter { it.id in draft.selected && it.queue?.occurrenceId != null }

fun clampDelay(ms: Double): Double = if (ms.isFinite()) ms.coerceIn(0.0, MAX_DOWNSTREAM_DELAY_MS).roundToInt().toDouble() else 0.0

/** Mirrors DesktopAudioClient.createGroup; the first reason the draft cannot be sent, or null. */
fun groupDraftProblem(draft: GroupDraft, zones: List<DesktopZoneSession>): String? {
    val candidates = groupCandidates(zones).associateBy { it.id }
    val source = draft.sourceId?.let(candidates::get)
    return when {
        draft.selected.size < MIN_GROUP_MEMBERS -> "至少选择 2 个声区"
        draft.selected.size > MAX_GROUP_MEMBERS -> "最多选择 4 个声区"
        draft.selected.distinct().size != draft.selected.size -> "声区重复，请重新选择"
        draft.selected.any { it !in candidates } -> "所选声区已变化，请重新选择"
        source == null || source.id !in draft.selected || source.queue?.occurrenceId == null -> "请选择采用哪份队列"
        draft.selected.any { draft.delayOf(it).let { ms -> !ms.isFinite() || ms !in 0.0..MAX_DOWNSTREAM_DELAY_MS } } ->
            "下游延迟须在 0–250 ms"
        draft.name.isBlank() -> "请填写组名"
        draft.name.trim().length > MAX_GROUP_NAME_LENGTH -> "组名最多 80 个字"
        else -> null
    }
}

fun buildGroupCreate(draft: GroupDraft, zones: List<DesktopZoneSession>, revision: Long, generation: Long): DesktopSyncGroupCreate? {
    if (groupDraftProblem(draft, zones) != null) return null
    val byId = groupCandidates(zones).associateBy { it.id }
    return DesktopSyncGroupCreate(revision, draft.name.trim(), byId.getValue(draft.sourceId!!),
        // Members keep Core's zone order, as the computer lists them.
        groupCandidates(zones).filter { it.id in draft.selected }.map { DesktopSyncSelection(it, draft.delayOf(it.id)) }, generation)
}

/** Synchronised start needs a connected bridge that adapts to its own DAC clock. */
fun memberReady(zone: DesktopZoneSession?): Boolean =
    zone?.output?.let { it.connected && it.receiver?.adaptiveSupported == true } == true

data class ZoneBadge(val label: String, val tone: ZoneTone)

/** Whether a member can join synchronised playback, and why not. */
fun memberReadiness(zone: DesktopZoneSession?): ZoneBadge {
    val output = zone?.output
    return when {
        zone == null -> ZoneBadge("声区已移除", ZoneTone.DANGER)
        output?.connected != true -> if (output?.peerName != null || zone.isBridgeZone) ZoneBadge("输出桥离线", ZoneTone.DANGER)
            else ZoneBadge("需要输出桥", ZoneTone.WARNING)
        output?.receiver?.adaptiveSupported != true -> ZoneBadge("不支持时钟自适应", ZoneTone.WARNING)
        else -> ZoneBadge("可同步", ZoneTone.LIVE)
    }
}

/** The short state beside a window's name; an error's own message is shown separately. */
fun zoneStatus(zone: DesktopZoneSession, light: ZoneLight, playback: DesktopZonePlayback?): ZoneBadge = when {
    zone.error != null -> ZoneBadge("需要处理", ZoneTone.DANGER)
    light == ZoneLight.OFFLINE -> ZoneBadge(if (zoneOutputState(zone) == ZoneOutputState.CONNECTING) "连接中" else "输出桥离线",
        if (zoneOutputState(zone) == ZoneOutputState.CONNECTING) ZoneTone.WARNING else ZoneTone.DANGER)
    light == ZoneLight.UNPAIRED -> ZoneBadge("未连接输出桥", ZoneTone.NEUTRAL)
    light == ZoneLight.EMPTY -> ZoneBadge("队列为空", ZoneTone.NEUTRAL)
    light == ZoneLight.PLAYING -> ZoneBadge("播放中", ZoneTone.LIVE)
    else -> ZoneBadge(playbackStateLabel(playback?.state), ZoneTone.NEUTRAL)
}

fun groupReady(group: DesktopSyncGroup, zones: List<DesktopZoneSession>): Boolean =
    group.members.all { member -> memberReady(zones.firstOrNull { it.id == member.zoneId }) }

/** The group's members in its own order; a member the zone list no longer has is null. */
fun groupMembersOf(group: DesktopSyncGroup, zones: List<DesktopZoneSession>): List<DesktopZoneSession?> =
    group.members.map { member -> zones.firstOrNull { it.id == member.zoneId } }

/** "3 个声区 · 偏差 0.42 ms", or why the group cannot start yet. */
fun groupCaption(group: DesktopSyncGroup, members: List<DesktopZoneSession?>, ready: Boolean): String {
    val count = "${group.members.size} 个声区"
    val skew = groupSkew(group)
    return when {
        members.any { it == null } -> "$count · 有声区已移除"
        !ready -> "$count · 有成员未就绪"
        skew.measured -> "$count · 偏差 ${formatMs(skew.skewMs)}"
        else -> "$count · ${skew.label}"
    }
}

// ---------------------------------------------------------------------------------------------
// Group coordination

enum class GroupPhase(val key: String, val label: String, val tone: ZoneTone) {
    PAUSED("paused", "已暂停", ZoneTone.NEUTRAL),
    PREPARING("preparing", "共同预缓冲", ZoneTone.INFO),
    ARMED("armed", "已调度起播", ZoneTone.INFO),
    PLAYING("playing", "同步播放中", ZoneTone.LIVE),
    REBUFFERING("rebuffering", "共同重缓冲", ZoneTone.WARNING),
    COMPLETED("completed", "队列已结束", ZoneTone.NEUTRAL),
    FAILED("failed", "需要恢复", ZoneTone.DANGER)
}

/** The four steps a synchronised start walks through. */
val GroupSteps = listOf("待命", "预缓冲", "调度起播", "同步播放")

data class CoordinationMember(val id: String, val dspDelayMs: Double?, val downstreamDelayMs: Double?)
data class GroupCoordination(val phase: String?, val error: String?, val estimatedSkewMs: Double?,
    val observationRangeMs: Double?, val members: List<CoordinationMember>)

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())

fun groupCoordination(group: DesktopSyncGroup): GroupCoordination? = group.coordination?.let { json ->
    GroupCoordination(json.text("phase"), json.text("error"), json.number("estimatedSkewMs"), json.number("observationRangeMs"),
        (json["members"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { member ->
            member.text("id")?.let { CoordinationMember(it, member.number("dspDelayMs"), member.number("downstreamDelayMs")) }
        })
}

fun groupPhase(group: DesktopSyncGroup): GroupPhase {
    val key = groupCoordination(group)?.phase
    return GroupPhase.entries.firstOrNull { it.key == key }
        ?: if (group.playback?.state == "playing") GroupPhase.PLAYING else GroupPhase.PAUSED
}

/** Index into [GroupSteps]; rebuffering re-enters preparation, completion and failure rest at the start. */
fun groupStepIndex(phase: GroupPhase): Int = when (phase) {
    GroupPhase.PAUSED, GroupPhase.COMPLETED, GroupPhase.FAILED -> 0
    GroupPhase.PREPARING, GroupPhase.REBUFFERING -> 1
    GroupPhase.ARMED -> 2
    GroupPhase.PLAYING -> 3
}

data class SkewReading(val measured: Boolean, val skewMs: Double, val rangeMs: Double,
    val aligned: Boolean, val label: String, val scaleMs: Double)

/**
 * Core reports the spread between members ([estimatedSkewMs], never negative) and twice the worst clock
 * uncertainty ([observationRangeMs]). It calls the group drifting once `skew - range > 1 ms`.
 */
fun groupSkew(group: DesktopSyncGroup): SkewReading {
    val coordination = groupCoordination(group)
    val skew = coordination?.estimatedSkewMs
    if (skew == null) return SkewReading(false, 0.0, 0.0, true,
        if (groupPhase(group) == GroupPhase.PLAYING) "正在测量" else "播放后测量", 2.0)
    val range = (coordination?.observationRangeMs ?: 0.0).coerceAtLeast(0.0)
    val aligned = skew - range <= GROUP_SKEW_TOLERANCE_MS
    val scale = max(2.0, ceil((skew + range) * 1.25 * 2) / 2)
    return SkewReading(true, skew, range, aligned, if (aligned) "对齐良好" else "超出 1 ms 容差", scale)
}

fun formatMs(value: Double, digits: Int = 2): String = String.format(Locale.ROOT, "%.${digits}f ms", value)

fun formatDelayMs(value: Double): String = "${value.roundToInt()} ms"

// ---------------------------------------------------------------------------------------------
// Signal path (信号链)

enum class PathTone(val label: String, val tone: ZoneTone) {
    EXACT("原样", ZoneTone.LIVE),
    ADAPTED("已转换", ZoneTone.INFO),
    ENHANCED("已处理", ZoneTone.WARNING),
    FAULT("异常", ZoneTone.DANGER),
    IDLE("待机", ZoneTone.NEUTRAL)
}

data class PathNode(val id: String, val label: String, val value: String, val tone: PathTone, val detail: List<String>)
data class PathStage(val id: String, val label: String, val nodes: List<PathNode>)

fun dspNodeLabel(type: String): String = when (type) {
    "equalizer" -> "均衡器"
    else -> dspModules.firstOrNull { it.type == type }?.title ?: type
}

private fun decodedLabel(format: String): String = when {
    format.isBlank() -> ""
    Regex("^f(?:loat)?32", RegexOption.IGNORE_CASE).containsMatchIn(format) -> "32 位浮点"
    Regex("^f(?:loat)?64", RegexOption.IGNORE_CASE).containsMatchIn(format) -> "64 位浮点"
    else -> Regex("(\\d{2})").find(format)?.groupValues?.get(1)?.let { "$it 位整数" } ?: format
}

/** Core's reported path as Core → network → receiver stages; a stage only appears if the path has it. */
fun signalPathStages(path: JsonObject, peerName: String? = null): List<PathStage> {
    val source = path.obj("source")
    val decoded = path.obj("decoded")
    val dsp = path.obj("dsp")
    val transport = path.obj("transport")
    val output = path.obj("output")
    val receiver = path["receiver"] as? JsonObject
    val sourceRate = source.number("sampleRate") ?: 0.0
    val playing = sourceRate > 0
    val remote = transport.text("kind") == "tls-pcm"
    val codec = source.text("codec").orEmpty().trim().uppercase(Locale.ROOT)
    val sourceNode = PathNode("source", "音源",
        if (playing) listOf(codec, formatAudio(sourceRate, source.number("bitDepth"))).filter { it.isNotEmpty() }.joinToString(" · ") else "尚未播放",
        if (playing) PathTone.EXACT else PathTone.IDLE,
        if (playing) listOf("${(source.number("channels") ?: 2.0).roundToInt().takeIf { it > 0 } ?: 2} 声道",
            if (codec.isNotEmpty()) "$codec 文件" else "音频文件") else listOf("开始播放后显示实际格式"))
    val decodedRate = decoded.number("sampleRate") ?: 0.0
    val decodedFormat = decoded.text("format").orEmpty()
    val decodedChanged = playing && decodedRate > 0 && decodedRate != sourceRate
    val decodeNode = PathNode("decode", "解码",
        if (playing) listOf(decodedLabel(decodedFormat), formatRate(decodedRate)).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "已解码" } else "待机",
        when { !playing -> PathTone.IDLE; decodedChanged -> PathTone.ADAPTED; else -> PathTone.EXACT },
        if (playing) listOf(if (decodedChanged) "解码时改变了采样率" else "采样率与音源一致",
            if (decodedFormat.isNotEmpty()) "样本格式 $decodedFormat" else "样本格式未报告") else emptyList())
    val active = (dsp["nodes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .filter { it.flag("active") && !it.flag("bypassed") }
    val applyState = dsp.text("applyState")
    val dspNode = PathNode("dsp", "DSP",
        when {
            applyState == "failed" -> "应用失败"
            applyState == "pending" -> "正在应用"
            active.size == 1 -> dspNodeLabel(active.single().text("type").orEmpty())
            active.isNotEmpty() -> "${active.size} 个处理节点"
            else -> "未处理"
        },
        when { applyState == "failed" -> PathTone.FAULT; !playing -> PathTone.IDLE; active.isNotEmpty() -> PathTone.ENHANCED; else -> PathTone.EXACT },
        if (active.isNotEmpty()) active.map { dspNodeLabel(it.text("type").orEmpty()) } else listOf("没有正在运行的处理节点，声音不被改变"))
    val stage = dsp["outputStage"] as? JsonObject
    val resampled = stage?.flag("resampled") == true
    val srcNode = PathNode("src", "采样率转换",
        if (resampled) "转换至 ${formatRate(stage?.number("actualSampleRate"))}" else "保持原采样率",
        when { !playing -> PathTone.IDLE; resampled -> PathTone.ADAPTED; else -> PathTone.EXACT },
        if (resampled) listOf(stage?.text("effectiveResampler")?.takeIf { it.isNotBlank() }?.let { "重采样器 $it" } ?: "由 Core 重采样")
        else listOf("Core 未重采样"))
    val exclusive = receiver?.flag("exclusive") == true
    val outputRate = output.number("sampleRate")
    val deviceName = output.text("deviceName")?.takeIf { it.isNotBlank() }
    val backend = backendLabel(output.text("backend"), exclusive)
    val transportConnected = transport["connected"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: !remote
    val device = PathNode("device", "输出设备",
        listOf(deviceName ?: backend.ifEmpty { if (remote) "输出桥设备" else "系统默认设备" }, formatAudio(outputRate, output.number("bitDepth")))
            .filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "未报告" },
        when {
            remote && !transportConnected -> PathTone.FAULT
            !playing -> PathTone.IDLE
            outputRate != null && outputRate > 0 && outputRate != sourceRate -> PathTone.ADAPTED
            else -> PathTone.EXACT
        },
        listOf(backend.ifEmpty { "驱动未报告" }, "驱动回报的格式；DAC 内部处理未知"))
    val core = PathStage("core", if (remote) "此电脑 · Core" else "此电脑", listOf(sourceNode, decodeNode, dspNode, srcNode))
    if (!remote) return listOf(core.copy(nodes = core.nodes + device))
    val network = PathStage("network", "网络传输", listOf(PathNode("transport", "加密传输",
        if (transportConnected) "Float32 PCM · ${formatRate(transport.number("sampleRate"))}" else "未连接",
        if (!transportConnected) PathTone.FAULT else if (playing) PathTone.EXACT else PathTone.IDLE,
        if (transportConnected) listOf("TLS 加密的无损 PCM", "输出桥不重复应用 Core 的 DSP") else listOf("输出桥未连接，不会切回本机输出"))))
    val bridgeNodes = mutableListOf<PathNode>()
    val adaptive = receiver?.get("adaptive") as? JsonObject
    if (receiver != null && adaptive != null) {
        val ppm = adaptive.number("effectivePpm") ?: 0.0
        bridgeNodes += PathNode("clock", "时钟适配", "修正 ${if (ppm >= 0) "+" else ""}${String.format(Locale.ROOT, "%.1f", ppm)} ppm",
            PathTone.ADAPTED, desktopAdaptiveObservation(path))
    }
    if (receiver != null) {
        val rate = receiver.number("sampleRate") ?: 0.0
        val ms = if (rate > 0) ((receiver.number("bufferedFrames") ?: 0.0) * 1000 / rate).roundToInt() else 0
        val underruns = (receiver.number("underruns") ?: 0.0).toLong()
        bridgeNodes += PathNode("buffer", "缓冲", "$ms ms${if (underruns > 0) " · 欠载 $underruns" else ""}",
            when { receiver.flag("failed") -> PathTone.FAULT; underruns > 0 -> PathTone.ENHANCED; playing -> PathTone.EXACT; else -> PathTone.IDLE },
            listOf("已出队 ${(receiver.number("consumedFrames") ?: 0.0).toLong()} 帧") + desktopClockObservation(path))
    }
    bridgeNodes += device
    return listOf(core, network, PathStage("bridge", "${peerName?.takeIf { it.isNotBlank() } ?: "输出桥"} · 输出桥", bridgeNodes))
}

fun signalPathVerdict(stages: List<PathStage>): Pair<String, ZoneTone> {
    val nodes = stages.flatMap { it.nodes }
    return when {
        nodes.any { it.tone == PathTone.FAULT } -> "链路中断" to ZoneTone.DANGER
        nodes.all { it.tone == PathTone.IDLE } -> "待机" to ZoneTone.NEUTRAL
        nodes.any { it.tone == PathTone.ENHANCED } -> "已处理" to ZoneTone.WARNING
        nodes.any { it.tone == PathTone.ADAPTED } -> "已转换" to ZoneTone.INFO
        else -> "原样输出" to ZoneTone.LIVE
    }
}

// ---------------------------------------------------------------------------------------------
// Processing (DSP)

fun JsonObject.processingFlag(key: String): Boolean = flag(key)
fun JsonObject.processingNumber(key: String): Double = number(key) ?: 0.0
fun JsonObject.processingBands(): List<JsonObject> = (this["eqBands"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
