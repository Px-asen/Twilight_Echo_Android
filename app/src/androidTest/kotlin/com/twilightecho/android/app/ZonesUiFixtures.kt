package com.twilightecho.android.app

import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.CoreOperatingMode
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DesktopAudioSnapshot
import com.twilightecho.android.coreclient.DesktopBridgeReceiver
import com.twilightecho.android.coreclient.DesktopDspScene
import com.twilightecho.android.coreclient.DesktopDspState
import com.twilightecho.android.coreclient.DesktopSyncGroup
import com.twilightecho.android.coreclient.DesktopSyncGroups
import com.twilightecho.android.coreclient.DesktopSyncMember
import com.twilightecho.android.coreclient.DesktopZoneConnection
import com.twilightecho.android.coreclient.DesktopZoneOutput
import com.twilightecho.android.coreclient.DesktopZonePlayback
import com.twilightecho.android.coreclient.DesktopZoneQueue
import com.twilightecho.android.coreclient.DesktopZoneSession
import com.twilightecho.android.coreclient.DesktopZoneState
import com.twilightecho.android.coreclient.DesktopZoneTrack
import com.twilightecho.android.coreclient.LibrarySource
import com.twilightecho.android.feature.player.DesktopBrowseState
import com.twilightecho.android.feature.player.MainDestinationTab
import com.twilightecho.android.feature.player.PlayerActions
import com.twilightecho.android.feature.player.PlayerAppContent
import com.twilightecho.android.feature.player.PlayerUiState
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal const val LOCAL = "local"
internal const val STUDY = "zone-11111111-1111-4111-8111-111111111111"
internal const val BEDROOM = "zone-22222222-2222-4222-8222-222222222222"
internal const val KITCHEN = "zone-33333333-3333-4333-8333-333333333333"
internal const val GROUP = "group-44444444-4444-4444-8444-444444444444"

private val golden = DesktopZoneTrack("Golden Hour", "Paper Lantern", "remote-track:golden", "Afterglow", "local", 242.0, "flac", 96000.0, 24.0)
private val quiet = DesktopZoneTrack("A Quiet Return", "North Shore", "remote-track:quiet", "Blue Hours", "local", 198.0, "flac", 44100.0, 16.0)
private val blue = DesktopZoneTrack("Blue Hours", "Moss Garden", "remote-track:blue", "Still Life", "local", 216.0, "flac", 48000.0, 24.0)

private fun playback(state: String, position: Double, track: DesktopZoneTrack, volume: Double, backend: String? = null,
    actualRate: Double? = null, actualBits: Double? = null) = DesktopZonePlayback(state, position, track.duration ?: 0.0, volume,
    track.format, track.sampleRate, track.bitDepth, actualRate, actualBits, backend)

private val e50 = DesktopBridgeReceiver(true, "Topping E50", "wasapi", true, 48000.0, 24.0)
private val livingRoomDac = DesktopBridgeReceiver(true, "FiiO K7", "wasapi", false, 48000.0, 24.0)
private val kitchenReceiver = DesktopBridgeReceiver(false, "板载声卡", "alsa", false, 44100.0, 16.0)

/** Every window state on one wall: playing, paused, bridge offline, empty. */
internal fun windowStateZones(): List<DesktopZoneSession> = listOf(
    DesktopZoneSession(LOCAL, "本机", 5, golden, playback("playing", 74.0, golden, 0.62, "wasapi-exclusive", 96000.0, 24.0),
        DesktopZoneConnection(), queue = DesktopZoneQueue("main", 9, "occ-main")),
    DesktopZoneSession(STUDY, "书房", 3, quiet, playback("paused", 41.0, quiet, 0.5),
        DesktopZoneConnection(true, true, "书房音箱", receiver = e50), queue = DesktopZoneQueue("study", 4, "occ-study")),
    DesktopZoneSession(BEDROOM, "卧室", 2, blue, playback("paused", 12.0, blue, 0.4),
        DesktopZoneConnection(false, true, "卧室小音箱", "连接超时，正在重试"), queue = DesktopZoneQueue("bedroom", 2, "occ-bedroom")),
    DesktopZoneSession(KITCHEN, "厨房", 1, output = DesktopZoneConnection(true, true, "厨房音箱", receiver = kitchenReceiver)))

/** A playing sync group of two windows, a paused local zone and a zone carrying Core's error. */
internal fun groupedZones(): List<DesktopZoneSession> = listOf(
    DesktopZoneSession(LOCAL, "本机", 5, golden, playback("paused", 74.0, golden, 0.62, "wasapi-exclusive", 96000.0, 24.0),
        DesktopZoneConnection(), queue = DesktopZoneQueue("main", 9, "occ-main")),
    DesktopZoneSession(STUDY, "书房", 3, quiet, playback("paused", 41.0, quiet, 0.5),
        DesktopZoneConnection(true, true, "书房音箱", receiver = e50), queue = DesktopZoneQueue("study", 4, "occ-study"), groupId = GROUP),
    DesktopZoneSession(BEDROOM, "客厅", 2,
        output = DesktopZoneConnection(true, true, "客厅音箱", receiver = livingRoomDac), groupId = GROUP),
    DesktopZoneSession(KITCHEN, "厨房", 1, output = DesktopZoneConnection(false, true),
        error = "输出桥配置已失效，请在电脑上重新配对"))

internal fun playingGroup(): DesktopSyncGroup = DesktopSyncGroup(GROUP, "客厅与书房", 4,
    listOf(DesktopSyncMember(STUDY, 12.0), DesktopSyncMember(BEDROOM, 0.0)), playback("playing", 95.0, blue, 0.55, "sync-group"), blue,
    DesktopZoneQueue("shared", 2, "occ-group"), coordination("playing", 0.42, 0.18), autoMixAvailable = true)

private fun coordination(phase: String, skew: Double?, range: Double?): JsonObject = json("""{"phase":"$phase","error":null,"epoch":7,
    "audibleAtCoreMs":1000,"estimatedSkewMs":$skew,"observationRangeMs":$range,"members":[
    {"id":"$STUDY","dspDelayMs":2.5,"downstreamDelayMs":12,"startAtCoreMs":1000},
    {"id":"$BEDROOM","dspDelayMs":0,"downstreamDelayMs":0,"startAtCoreMs":1000}]}""")

private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

private val localPath = json("""{"source":{"codec":"flac","sampleRate":96000,"bitDepth":24,"channels":2},
    "decoded":{"sampleRate":96000,"format":"f32"},
    "dsp":{"applyState":"applied","nodes":[{"type":"equalizer","active":true,"bypassed":false}],"outputStage":{"resampled":false}},
    "transport":{"kind":"local"},
    "output":{"deviceName":"扬声器 (Realtek USB Audio)","backend":"wasapi-exclusive","sampleRate":96000,"bitDepth":24}}""")

private val bridgePath = json("""{"source":{"codec":"flac","sampleRate":44100,"bitDepth":16,"channels":2},
    "decoded":{"sampleRate":44100,"format":"f32"},
    "dsp":{"applyState":"applied","nodes":[],"outputStage":{"resampled":true,"actualSampleRate":48000,"effectiveResampler":"soxr-vhq"}},
    "transport":{"kind":"tls-pcm","connected":true,"sampleRate":48000},
    "receiver":{"sampleRate":48000,"bufferedFrames":9600,"capacityFrames":96000,"underruns":0,"consumedFrames":192000,"exclusive":true,
      "adaptiveSupported":true,"adaptive":{"requestedPpm":1.2,"effectivePpm":1.1,"inputFrames":1,"pendingInputFrames":0,
      "resamplerDelayFrames":32,"sourceConsumedFrames":1,"inputCreditFrames":0},"clock":{"state":"ready"}},
    "output":{"deviceName":"Topping E50","backend":"wasapi","sampleRate":48000,"bitDepth":24}}""")

private val downPath = json("""{"source":{"codec":"flac","sampleRate":48000,"bitDepth":24},"decoded":{"sampleRate":48000,"format":"f32"},
    "dsp":{"nodes":[]},"transport":{"kind":"tls-pcm","connected":false},"output":{}}""")

private val processing = json("""{"dspEnabled":true,"directMode":false,"eqEnabled":true,"eqPreamp":-3,"eqBands":[
    {"frequency":32,"gain":2.5},{"frequency":125,"gain":1},{"frequency":500,"gain":0},
    {"frequency":2000,"gain":-1.5},{"frequency":8000,"gain":2},{"frequency":16000,"gain":3}]}""")

private fun coverDataUrl(file: String): String {
    val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("music/$file").use { it.readBytes() }
    return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/**
 * The computer's Core as the UI sees it. Commands behave like the view model's single lane: busy while in
 * flight, then the new state and a notice; [failNext] answers the next command with Core's message instead.
 */
internal class ZoneWorld(zones: List<DesktopZoneSession>, groups: List<DesktopSyncGroup> = emptyList(), connected: Boolean = true,
    standalone: Boolean = false) {
    val log = mutableListOf<String>()
    var failNext: String? = null
    private val handler = Handler(Looper.getMainLooper())

    val ui: MutableState<PlayerUiState> = mutableStateOf(PlayerUiState(
        operatingMode = if (standalone) CoreOperatingMode.StandaloneLocal
            else CoreOperatingMode.RemoteDesktopCore("http://192.168.1.20:18492", "XOS-STUDIO", connected),
        librarySource = LibrarySource.Desktop("srv", "lib", "http://192.168.1.20:18492", "XOS-STUDIO", connected),
        activeMainTab = MainDestinationTab.ZONES, homeAvailable = true, isLoadingLibrary = false,
        currentTrack = CoreTrackView(id = "remote-track:golden", title = "Golden Hour", artist = "Paper Lantern", album = "Afterglow",
            source = "local", duration = 242.0, format = "FLAC", sampleRate = 96000, bitDepth = 24),
        isPlaying = true, durationSeconds = 242.0, displayedPositionSeconds = 74.0,
        desktopLibrary = DesktopBrowseState(artwork = mapOf("remote-track:golden" to coverDataUrl("01.jpg"),
            "remote-track:quiet" to coverDataUrl("02.jpg"), "remote-track:blue" to coverDataUrl("03.jpg"))),
        desktopZoneSessions = zones, desktopSyncGroups = DesktopSyncGroups(2, groups)))

    init { ui.value = ui.value.copy(desktopAudio = snapshot(LOCAL)) }

    fun zones() = ui.value.desktopZoneSessions
    fun groups() = ui.value.desktopSyncGroups?.groups.orEmpty()

    /** Core's snapshot of one place; the path follows what that place's output actually is. */
    fun snapshot(id: String): DesktopAudioSnapshot {
        val zone = zones().firstOrNull { it.id == id }
        val group = groups().firstOrNull { candidate -> candidate.members.any { it.zoneId == id } }
        val output = zone?.output
        val state = if (id == LOCAL) DesktopZoneState(1, LOCAL, "local", outputs = listOf(DesktopZoneOutput("local", "此电脑", true),
                DesktopZoneOutput("bridge", "输出桥", false)), groupId = group?.id)
            else DesktopZoneState(1, id, "bridge", output?.connected == true, true, groupId = group?.id)
        return DesktopAudioSnapshot(state, DesktopDspState(7, processing,
            listOf(DesktopDspScene("night", "夜间聆听", true), DesktopDspScene("room", "房间校正", true), DesktopDspScene("dsd", "DSD 原生", false)),
            activeSceneId = "night"), when {
                id == LOCAL -> localPath
                output?.connected == true -> bridgePath
                else -> downPath
            }, 3, zone, group)
    }

    private fun followed() = ui.value.desktopAudio?.zone?.zoneId ?: LOCAL

    private fun update(zones: List<DesktopZoneSession> = zones(), groups: List<DesktopSyncGroup> = groups(), notice: String? = ui.value.desktopAudioNotice) {
        ui.value = ui.value.copy(desktopZoneSessions = zones, desktopSyncGroups = DesktopSyncGroups(3, groups), desktopAudioNotice = notice)
        ui.value = ui.value.copy(desktopAudio = snapshot(followed()))
    }

    private fun lane(effect: () -> Unit) {
        ui.value = ui.value.copy(desktopAudioBusy = true, desktopAudioError = null)
        handler.postDelayed({
            val failure = failNext
            failNext = null
            if (failure == null) effect()
            ui.value = ui.value.copy(desktopAudioBusy = false, desktopAudioError = failure)
        }, 150)
    }

    private fun toggled(state: DesktopZonePlayback?, kind: String) =
        state?.copy(state = if (kind == "pause") "paused" else "playing")

    /** Core moves the group's coordination with its transport; the fixture skips the preparing and armed steps. */
    private fun toggled(group: DesktopSyncGroup, kind: String) = group.copy(playback = toggled(group.playback, kind),
        coordination = group.coordination?.let { JsonObject(it + ("phase" to JsonPrimitive(if (kind == "pause") "paused" else "playing"))) })

    val actions = PlayerActions(
        selectTab = { tab -> log += "tab:$tab"; ui.value = ui.value.copy(activeMainTab = tab) },
        setPlayerOpen = { open -> log += "player:$open"; ui.value = ui.value.copy(isImmersivePlayerOpen = open) },
        setQueueOpen = { open -> log += "queue:$open"; ui.value = ui.value.copy(isQueueDrawerOpen = open) },
        desktopZonesActive = { active -> log += "zonesActive:$active" },
        desktopZone = { id -> log += "follow:$id"; ui.value = ui.value.copy(desktopAudio = snapshot(id)) },
        desktopZoneTransportFor = { id, kind ->
            log += "transport:$id:$kind"
            val group = groups().firstOrNull { candidate -> candidate.members.any { it.zoneId == id } }
            if (group != null) update(groups = groups().map { if (it.id == group.id) toggled(it, kind) else it })
            else if (kind == "play" || kind == "pause") update(zones = zones().map { if (it.id == id) it.copy(playback = toggled(it.playback, kind)) else it })
        },
        desktopZoneTransport = { kind ->
            log += "groupTransport:$kind"
            if (kind == "play" || kind == "pause") update(groups = groups().map { toggled(it, kind) })
        },
        desktopZoneValue = { kind, value -> log += "value:$kind:$value" },
        desktopOutput = { id -> log += "output:$id" },
        desktopDsp = { patch -> log += "dsp:$patch" },
        desktopScene = { id -> log += "scene:$id" },
        desktopHandoff = { command ->
            log += "handoff:${command.source.id}->${command.target.id}:replace=${command.replace}:resume=${command.resume}"
            lane { update(notice = "已接力到 ${command.target.name}") }
        },
        desktopCreateGroup = { command ->
            log += "createGroup:${command.name}:${command.members.joinToString(",") { "${it.session.id}@${it.downstreamDelayMs}" }}:source=${command.source.id}"
            lane {
                val id = "group-created"
                val members = command.members.map { it.session.id }
                update(zones = zones().map { if (it.id in members) it.copy(groupId = id) else it },
                    groups = groups() + DesktopSyncGroup(id, command.name, 1, command.members.map { DesktopSyncMember(it.session.id, it.downstreamDelayMs) },
                        command.source.playback?.copy(state = "paused"), command.source.track, command.source.queue, coordination("paused", null, null)),
                    notice = "已建立 ${command.name}")
            }
        },
        desktopGroupAutoMix = { group, mode ->
            log += "groupAutoMix:${group.id}:${group.revision}:$mode"
            lane {
                update(groups = groups().map { if (it.id == group.id) it.copy(revision = it.revision + 1, autoMixMode = mode,
                    playback = it.playback?.copy(state = "paused"), coordination = coordination("paused", null, null)) else it },
                    notice = "组 AutoMix 已保存，全组暂停待播。")
            }
        },
        desktopRemoveGroup = { group ->
            log += "removeGroup:${group.id}"
            lane {
                update(zones = zones().map { if (it.groupId == group.id) it.copy(groupId = null) else it },
                    groups = groups().filter { it.id != group.id }, notice = "已解除 ${group.name}")
            }
        })
}

/** The real app content over a [ZoneWorld]; test tags double as resource ids for UiAutomator. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ZoneWorldContent(world: ZoneWorld) {
    TwilightEchoTheme {
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            PlayerAppContent(world.ui.value, world.actions)
        }
    }
}
