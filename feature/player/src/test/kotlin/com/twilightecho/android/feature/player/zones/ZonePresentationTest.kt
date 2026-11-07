package com.twilightecho.android.feature.player.zones

import com.twilightecho.android.coreclient.DesktopBridgeReceiver
import com.twilightecho.android.coreclient.DesktopSyncGroup
import com.twilightecho.android.coreclient.DesktopSyncMember
import com.twilightecho.android.coreclient.DesktopZoneConnection
import com.twilightecho.android.coreclient.DesktopZonePlayback
import com.twilightecho.android.coreclient.DesktopZoneQueue
import com.twilightecho.android.coreclient.DesktopZoneSession
import com.twilightecho.android.coreclient.DesktopZoneTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZonePresentationTest {
    private val track = DesktopZoneTrack("Clair de lune", "Debussy", "remote-track:abc", "Suite bergamasque", "local",
        format = "flac", sampleRate = 96000.0, bitDepth = 24.0)

    private fun receiver(adaptive: Boolean = true) = DesktopBridgeReceiver(adaptiveSupported = adaptive, deviceName = "Topping E50",
        backend = "wasapi", exclusive = true, actualSampleRate = 48000.0, actualBitDepth = 24.0)

    private fun local(state: String? = "paused", queue: Boolean = true, output: DesktopZoneConnection? = null,
        error: String? = null, groupId: String? = null) = DesktopZoneSession("local", "本机", 4,
        track = track.takeIf { queue }, playback = state?.let { DesktopZonePlayback(it, 30.0, 300.0, 0.5) }, output = output,
        error = error, queue = DesktopZoneQueue("main", 2, "occ-local").takeIf { queue }, groupId = groupId)

    private fun bridge(id: String, state: String? = "paused", queue: Boolean = true, connected: Boolean = true,
        peer: String? = "书房音箱", adaptive: Boolean = true, outputError: String? = null, groupId: String? = null,
        occurrence: String? = "occ-$id") = DesktopZoneSession(id, "声区 $id", 7,
        track = track.takeIf { queue }, playback = state?.let { DesktopZonePlayback(it, 30.0, 300.0, 0.5) },
        output = DesktopZoneConnection(connected, selected = true, peerName = peer, error = outputError,
            receiver = receiver(adaptive).takeIf { connected }),
        queue = DesktopZoneQueue("q-$id", 1, occurrence).takeIf { queue }, groupId = groupId)

    private fun group(id: String, vararg members: Pair<String, Double>, coordination: String? = null, state: String = "paused") =
        DesktopSyncGroup(id, "客厅与书房", 12, members.map { DesktopSyncMember(it.first, it.second) },
            playback = DesktopZonePlayback(state, 10.0, 200.0, 0.6), track = track, queue = DesktopZoneQueue("shared", 3, "occ-g"),
            coordination = coordination?.let { Json.parseToJsonElement(it).jsonObject })

    // -- Window light and status ------------------------------------------------------------------

    @Test
    fun windowLightFollowsOutputMusicAndPlayback() {
        assertEquals(ZoneLight.PLAYING, zoneLight(local("playing")))
        assertEquals(ZoneLight.PAUSED, zoneLight(local("paused")))
        assertEquals(ZoneLight.PAUSED, zoneLight(local("stopped")))
        assertEquals(ZoneLight.EMPTY, zoneLight(local(queue = false)))
        assertEquals(ZoneLight.OFFLINE, zoneLight(bridge("a", "playing", connected = false)))
        assertEquals(ZoneLight.UNPAIRED, zoneLight(bridge("a", connected = false, peer = null)))
        // The local zone falls dark only when its selected bridge is down.
        val localBridgeDown = local("playing", output = DesktopZoneConnection(connected = false, selected = true, peerName = "客厅"))
        assertEquals(ZoneLight.OFFLINE, zoneLight(localBridgeDown))
        assertEquals(ZoneLight.PLAYING, zoneLight(local("playing", output = DesktopZoneConnection(connected = false, selected = false))))
    }

    @Test
    fun memberWindowsShareTheGroupMusicButKeepTheirOwnOutput() {
        val g = group("group-1", "a" to 0.0, "b" to 0.0, state = "playing")
        assertEquals(ZoneLight.PLAYING, memberLight(bridge("a", state = "paused", queue = false), g))
        assertEquals(ZoneLight.OFFLINE, memberLight(bridge("b", connected = false), g))
        assertEquals(ZoneLight.EMPTY, memberLight(bridge("a"), g.copy(queue = null)))
    }

    @Test
    fun outputStateAndLabelUseTheSpecWording() {
        assertEquals(ZoneOutputState.CONNECTED, zoneOutputState(local()))
        assertEquals("本机输出", zoneOutputLabel(local()))
        val localBridge = local(output = DesktopZoneConnection(connected = true, selected = true, peerName = "客厅"))
        assertEquals("输出桥 · 客厅", zoneOutputLabel(localBridge))
        assertEquals(ZoneOutputState.CONNECTING,
            zoneOutputState(local(output = DesktopZoneConnection(connected = false, selected = true, peerName = "客厅"))))
        assertEquals(ZoneOutputState.OFFLINE, zoneOutputState(bridge("a", connected = false, outputError = "连接被拒绝")))
        // Core's error on the zone itself means the connection is not simply in flight.
        assertEquals(ZoneOutputState.OFFLINE,
            zoneOutputState(bridge("a", connected = false, peer = null).copy(error = "输出桥配置已失效，请在电脑上重新配对")))
        assertEquals("输出桥 · 书房音箱", zoneOutputLabel(bridge("a")))
        assertEquals("尚未连接输出桥", zoneOutputLabel(bridge("a", connected = false, peer = null)))
        assertEquals(ZoneOutputState.UNSELECTED, zoneOutputState(bridge("a").copy(output = null)))
        assertEquals(listOf("已连接", "连接中", "离线", "未选择"), ZoneOutputState.entries.map { it.label })
    }

    @Test
    fun statusChipPrefersErrorsThenOutputThenPlayback() {
        fun status(zone: DesktopZoneSession) = zoneStatus(zone, zoneLight(zone), zone.playback)
        assertEquals(ZoneBadge("需要处理", ZoneTone.DANGER), status(local("playing", error = "解码失败")))
        assertEquals(ZoneBadge("播放中", ZoneTone.LIVE), status(local("playing")))
        assertEquals(ZoneBadge("已暂停", ZoneTone.NEUTRAL), status(local("paused")))
        assertEquals(ZoneBadge("已停止", ZoneTone.NEUTRAL), status(local("stopped")))
        assertEquals(ZoneBadge("队列为空", ZoneTone.NEUTRAL), status(local(queue = false)))
        assertEquals(ZoneBadge("输出桥离线", ZoneTone.DANGER), status(bridge("a", connected = false, outputError = "超时")))
        assertEquals(ZoneBadge("连接中", ZoneTone.WARNING), status(bridge("a", connected = false)))
        assertEquals(ZoneBadge("未连接输出桥", ZoneTone.NEUTRAL), status(bridge("a", connected = false, peer = null)))
        assertEquals("加载中", playbackStateLabel("loading"))
        assertEquals("已停止", playbackStateLabel(null))
    }

    @Test
    fun windowMessagesNameTheDarkStates() {
        assertNull(zoneWindowMessage(local("playing"), ZoneLight.PLAYING))
        assertEquals("队列为空", zoneWindowMessage(local(queue = false), ZoneLight.EMPTY))
        assertEquals("输出桥离线", zoneWindowMessage(bridge("a", connected = false, outputError = "x"), ZoneLight.OFFLINE))
        assertEquals("正在连接输出桥", zoneWindowMessage(bridge("a", connected = false), ZoneLight.OFFLINE))
        assertEquals("未连接输出桥", zoneWindowMessage(bridge("a", connected = false, peer = null), ZoneLight.UNPAIRED))
    }

    // -- Formats and artwork ------------------------------------------------------------------------

    @Test
    fun formatLineReportsOnlyFactsCoreSent() {
        assertEquals("44.1 kHz", formatRate(44100.0))
        assertEquals("96 kHz", formatRate(96000.0))
        assertEquals("", formatRate(0.0))
        assertEquals("96 kHz / 24 bit", formatAudio(96000.0, 24.0))
        assertEquals("192 kHz", formatAudio(192000.0, null))
        val playback = DesktopZonePlayback("playing", codec = "flac", sourceSampleRate = 96000.0, sourceBitDepth = 24.0,
            actualSampleRate = 96000.0, actualBitDepth = 32.0, outputBackend = "wasapi-shared")
        // A connected bridge reports what reaches its DAC; otherwise this computer's output is shown.
        assertEquals("FLAC · 96 kHz / 24 bit → WASAPI 独占 · 48 kHz / 24 bit", zoneFormatLine(playback, track, bridge("a")))
        assertEquals("FLAC · 96 kHz / 24 bit → WASAPI 共享 · 96 kHz / 32 bit", zoneFormatLine(playback, track, local()))
        assertEquals("FLAC · 96 kHz / 24 bit", zoneFormatLine(null, track, null))
        assertEquals("", zoneFormatLine(null, null, null))
        assertEquals("输出桥 PCM", backendLabel("bridge-pcm"))
        assertEquals("WASAPI 独占", backendLabel("wasapi", exclusive = true))
    }

    @Test
    fun onlyLocalProviderCoversAreFetched() {
        assertEquals("remote-track:abc", zoneArtworkId(track))
        assertEquals("remote-track:abc", zoneArtworkId(track.copy(source = null)))
        assertNull(zoneArtworkId(track.copy(source = "ncm")))
        assertNull(zoneArtworkId(track.copy(id = "ncm:42")))
        assertNull(zoneArtworkId(null))
        assertEquals("Debussy · Suite bergamasque", trackSubtitle(track))
        assertEquals("", trackSubtitle(DesktopZoneTrack()))
        assertEquals("46%", volumeLabel(0.456))
        assertEquals("100%", volumeLabel(1.4))
    }

    // -- The wall ------------------------------------------------------------------------------------

    @Test
    fun aGroupTakesThePlaceOfItsFirstListedMember() {
        val zones = listOf(local("playing"), bridge("a", groupId = "group-1"), bridge("b"), bridge("c", groupId = "group-1"))
        val g = group("group-1", "c" to 0.0, "a" to 5.0, "gone" to 0.0)
        val entries = zoneEntries(zones, listOf(g))
        assertEquals(listOf("zone:local", "group:group-1", "zone:b"), entries.map { it.key })
        val grouped = entries[1] as ZoneEntry.Group
        // Members keep the group's order; one the zone list no longer has is null.
        assertEquals(listOf("c", "a", null), grouped.members.map { it?.id })
        // A group none of whose members is listed still appears, after the listed zones.
        val orphan = group("group-2", "x" to 0.0, "y" to 0.0)
        assertEquals("group:group-2", zoneEntries(zones, listOf(g, orphan)).last().key)
    }

    @Test
    fun captionCountsLitWindows() {
        val g = group("group-1", "a" to 0.0, "b" to 0.0, state = "playing")
        val zones = listOf(local("playing"), bridge("a", groupId = "group-1"), bridge("b", groupId = "group-1", connected = false),
            bridge("c", "paused"))
        assertEquals(2, playingCount(zones, listOf(g)))
        assertEquals("4 个声区 · 2 处在播", zonesCaption(zones, listOf(g)))
        assertEquals("1 个声区 · 0 处在播", zonesCaption(listOf(local()), emptyList()))
    }

    // -- Handoff -------------------------------------------------------------------------------------

    @Test
    fun handoffSourceNeedsAQueueAndNoGroup() {
        assertNull(handoffSourceProblem(local()))
        assertEquals("此声区没有可接力的队列", handoffSourceProblem(local(queue = false)))
        assertEquals("此声区没有可接力的队列", handoffSourceProblem(bridge("a", occurrence = null)))
        assertEquals("同步组成员请先解除编组", handoffSourceProblem(bridge("a", groupId = "group-1")))
    }

    @Test
    fun handoffTargetsExcludeTheSourceAndRefuseGroupMembers() {
        val zones = listOf(local(), bridge("a"), bridge("b", groupId = "group-1"))
        val targets = handoffTargets(zones[0], zones)
        assertEquals(listOf("a", "b"), targets.map { it.zone.id })
        assertTrue(targets[0].enabled)
        assertFalse(targets[1].enabled)
        assertEquals("同步组成员，不能接收接力", targets[1].reason)
    }

    @Test
    fun replacingAQueueMustBeConfirmed() {
        val source = local("playing")
        val busyTarget = bridge("a")
        assertTrue(handoffNeedsReplace(busyTarget))
        assertEquals("请先确认替换目标队列", handoffProblem(source, busyTarget, replace = false))
        assertNull(buildHandoff(source, busyTarget, 3, replace = false, resume = true))
        val confirmed = buildHandoff(source, busyTarget, 3, replace = true, resume = true)
        assertNotNull(confirmed)
        assertTrue(confirmed!!.replace)
        assertTrue(confirmed.resume)
        assertEquals(3L, confirmed.targetGeneration)
        // An empty target needs no confirmation, and replace is never sent for it.
        val empty = buildHandoff(source, bridge("b", queue = false), 3, replace = true, resume = false)
        assertFalse(empty!!.replace)
        assertEquals("请选择目标声区", handoffProblem(source, null, replace = false))
        assertEquals("请选择不同的目标声区", handoffProblem(source, source, replace = true))
    }

    @Test
    fun resumeIsDroppedWhenTheTargetBridgeIsDown() {
        val down = bridge("a", queue = false, connected = false)
        assertTrue(handoffResumeBlocked(down))
        assertFalse(handoffResumeBlocked(bridge("b")))
        assertFalse(buildHandoff(local(), down, 1, replace = false, resume = true)!!.resume)
        assertEquals("目标输出桥未连接，接力后保持暂停", HANDOFF_RESUME_BLOCKED_HINT)
    }

    // -- Creating a group ----------------------------------------------------------------------------

    @Test
    fun draftSelectionStopsAtFourAndForgetsADeselectedSource() {
        var draft = GroupDraft("同步组 1")
        listOf("local", "a", "b", "c", "d").forEach { draft = draft.toggle(it) }
        assertEquals(listOf("local", "a", "b", "c"), draft.selected)
        draft = draft.copy(sourceId = "a").toggle("a")
        assertEquals(listOf("local", "b", "c"), draft.selected)
        assertNull(draft.sourceId)
        draft = draft.copy(sourceId = "b").toggle("c")
        assertEquals("b", draft.sourceId)
        assertEquals(0.0, draft.delayOf("b"), 0.0)
    }

    @Test
    fun draftProblemsMirrorTheClientPreconditions() {
        val zones = listOf(local(), bridge("a"), bridge("b", queue = false), bridge("c", groupId = "group-1"))
        val ok = GroupDraft("客厅与书房", listOf("local", "a"), "a", mapOf("a" to 12.0))
        assertNull(groupDraftProblem(ok, zones))
        assertEquals("至少选择 2 个声区", groupDraftProblem(ok.copy(selected = listOf("a")), zones))
        assertEquals("所选声区已变化，请重新选择", groupDraftProblem(ok.copy(selected = listOf("local", "c")), zones))
        assertEquals("请选择采用哪份队列", groupDraftProblem(ok.copy(sourceId = null), zones))
        assertEquals("请选择采用哪份队列", groupDraftProblem(ok.copy(selected = listOf("local", "b"), sourceId = "b"), zones))
        assertEquals("下游延迟须在 0–250 ms", groupDraftProblem(ok.copy(delays = mapOf("a" to 251.0)), zones))
        assertEquals("下游延迟须在 0–250 ms", groupDraftProblem(ok.copy(delays = mapOf("a" to -1.0)), zones))
        assertEquals("请填写组名", groupDraftProblem(ok.copy(name = "  "), zones))
        assertEquals("组名最多 80 个字", groupDraftProblem(ok.copy(name = "长".repeat(81)), zones))
    }

    @Test
    fun createCommandKeepsCoreZoneOrderAndDelays() {
        val zones = listOf(local(), bridge("a"), bridge("b"))
        val draft = GroupDraft("  客厅与书房 ", listOf("b", "local"), "b", mapOf("b" to 40.0))
        val command = buildGroupCreate(draft, zones, revision = 9, generation = 4)!!
        assertEquals("客厅与书房", command.name)
        assertEquals("b", command.source.id)
        assertEquals(listOf("local", "b"), command.members.map { it.session.id })
        assertEquals(listOf(0.0, 40.0), command.members.map { it.downstreamDelayMs })
        assertEquals(9L, command.revision)
        assertEquals(4L, command.targetGeneration)
        assertNull(buildGroupCreate(draft.copy(selected = listOf("b")), zones, 9, 4))
    }

    @Test
    fun delaysAreWholeMillisecondsWithinRange() {
        assertEquals(0.0, clampDelay(-5.0), 0.0)
        assertEquals(250.0, clampDelay(300.0), 0.0)
        assertEquals(13.0, clampDelay(12.6), 0.0)
        assertEquals(0.0, clampDelay(Double.NaN), 0.0)
        assertEquals("12 ms", formatDelayMs(12.4))
    }

    @Test
    fun onlySelectedZonesWithAFencedQueueCanSupplyTheGroupQueue() {
        val zones = listOf(local(), bridge("a", occurrence = null), bridge("b"), bridge("c", queue = false))
        val draft = GroupDraft("g", listOf("local", "a", "c"))
        assertEquals(listOf("local"), groupSourceOptions(zones, draft).map { it.id })
        assertEquals(listOf("local", "a", "b", "c"), groupCandidates(zones).map { it.id })
    }

    @Test
    fun readinessNeedsAConnectedAdaptiveBridge() {
        assertEquals(ZoneBadge("声区已移除", ZoneTone.DANGER), memberReadiness(null))
        assertEquals(ZoneBadge("需要输出桥", ZoneTone.WARNING), memberReadiness(local()))
        assertEquals(ZoneBadge("输出桥离线", ZoneTone.DANGER), memberReadiness(bridge("a", connected = false)))
        assertEquals(ZoneBadge("不支持时钟自适应", ZoneTone.WARNING), memberReadiness(bridge("a", adaptive = false)))
        assertEquals(ZoneBadge("可同步", ZoneTone.LIVE), memberReadiness(bridge("a")))
        val zones = listOf(bridge("a"), bridge("b"), bridge("c", adaptive = false))
        assertTrue(groupReady(group("g", "a" to 0.0, "b" to 0.0), zones))
        assertFalse(groupReady(group("g", "a" to 0.0, "c" to 0.0), zones))
        assertFalse(groupReady(group("g", "a" to 0.0, "missing" to 0.0), zones))
    }

    // -- Coordination --------------------------------------------------------------------------------

    @Test
    fun phaseLabelsMatchTheSpec() {
        assertEquals(
            mapOf("paused" to "已暂停", "preparing" to "共同预缓冲", "armed" to "已调度起播", "playing" to "同步播放中",
                "rebuffering" to "共同重缓冲", "completed" to "队列已结束", "failed" to "需要恢复"),
            GroupPhase.entries.associate { it.key to it.label })
        assertEquals(GroupPhase.PREPARING, groupPhase(group("g", "a" to 0.0, coordination = """{"phase":"preparing"}""")))
        assertEquals(GroupPhase.PLAYING, groupPhase(group("g", "a" to 0.0, state = "playing")))
        assertEquals(GroupPhase.PAUSED, groupPhase(group("g", "a" to 0.0, coordination = """{"phase":"unknown"}""")))
        assertEquals(listOf("待命", "预缓冲", "调度起播", "同步播放"), GroupSteps)
        assertEquals(1, groupStepIndex(GroupPhase.REBUFFERING))
        assertEquals(0, groupStepIndex(GroupPhase.COMPLETED))
        assertEquals(0, groupStepIndex(GroupPhase.FAILED))
        assertEquals(2, groupStepIndex(GroupPhase.ARMED))
        assertEquals(3, groupStepIndex(GroupPhase.PLAYING))
    }

    @Test
    fun skewIsUnmeasuredUntilCoreReportsIt() {
        val waiting = groupSkew(group("g", "a" to 0.0, coordination = """{"phase":"paused","estimatedSkewMs":null}"""))
        assertFalse(waiting.measured)
        assertEquals("播放后测量", waiting.label)
        assertEquals("正在测量", groupSkew(group("g", "a" to 0.0, state = "playing")).label)
        val aligned = groupSkew(group("g", "a" to 0.0, coordination = """{"estimatedSkewMs":0.4,"observationRangeMs":0.2}"""))
        assertTrue(aligned.measured && aligned.aligned)
        assertEquals("对齐良好", aligned.label)
        assertEquals(2.0, aligned.scaleMs, 0.0)
        // Drifting only once the lower bound (skew - range) passes 1 ms.
        assertTrue(groupSkew(group("g", "a" to 0.0, coordination = """{"estimatedSkewMs":1.5,"observationRangeMs":0.6}""")).aligned)
        val drifting = groupSkew(group("g", "a" to 0.0, coordination = """{"estimatedSkewMs":3.0,"observationRangeMs":0.5}"""))
        assertFalse(drifting.aligned)
        assertEquals("超出 1 ms 容差", drifting.label)
        assertEquals(4.5, drifting.scaleMs, 0.0)
        assertEquals("0.40 ms", formatMs(0.4))
    }

    @Test
    fun coordinationMembersAndErrorsAreRead() {
        val coordination = groupCoordination(group("g", "a" to 0.0, coordination =
            """{"phase":"failed","error":"成员时钟丢失","members":[{"id":"a","dspDelayMs":2.5,"downstreamDelayMs":12},{"dspDelayMs":1}]}"""))!!
        assertEquals("成员时钟丢失", coordination.error)
        assertEquals(listOf(CoordinationMember("a", 2.5, 12.0)), coordination.members)
        assertNull(groupCoordination(group("g", "a" to 0.0)))
    }

    @Test
    fun groupCaptionExplainsWhyAGroupCannotStart() {
        val zones = listOf(bridge("a"), bridge("b"))
        val g = group("g", "a" to 0.0, "b" to 0.0, coordination = """{"estimatedSkewMs":0.4,"observationRangeMs":0.1}""")
        assertEquals("2 个声区 · 偏差 0.40 ms", groupCaption(g, groupMembersOf(g, zones), ready = true))
        assertEquals("2 个声区 · 有成员未就绪", groupCaption(g, groupMembersOf(g, zones), ready = false))
        val missing = group("g", "a" to 0.0, "gone" to 0.0)
        assertEquals("2 个声区 · 有声区已移除", groupCaption(missing, groupMembersOf(missing, zones), ready = false))
        assertEquals("2 个声区 · 播放后测量", groupCaption(group("g", "a" to 0.0, "b" to 0.0), zones, ready = true))
    }

    // -- Signal path ---------------------------------------------------------------------------------

    private fun path(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun localPathIsOneStageEndingAtTheDevice() {
        val stages = signalPathStages(path("""{"source":{"sampleRate":44100,"bitDepth":16,"codec":"flac","channels":2},
            "decoded":{"sampleRate":44100,"format":"f32"},"dsp":{"nodes":[],"outputStage":{"resampled":false}},
            "transport":{"kind":"local"},"output":{"deviceName":"扬声器","sampleRate":44100,"bitDepth":24,"backend":"wasapi"}}"""))
        assertEquals(listOf("core"), stages.map { it.id })
        assertEquals(listOf("source", "decode", "dsp", "src", "device"), stages.single().nodes.map { it.id })
        assertEquals("FLAC · 44.1 kHz / 16 bit", stages.single().nodes.first().value)
        assertEquals("32 位浮点 · 44.1 kHz", stages.single().nodes[1].value)
        assertEquals("原样输出" to ZoneTone.LIVE, signalPathVerdict(stages))
    }

    @Test
    fun activeProcessingAndResamplingChangeTheVerdict() {
        val processed = signalPathStages(path("""{"source":{"sampleRate":44100},"dsp":{"nodes":[{"type":"equalizer","active":true},
            {"type":"x","active":true,"bypassed":true}]},"transport":{"kind":"local"},"output":{}}"""))
        val dsp = processed.single().nodes.first { it.id == "dsp" }
        assertEquals("均衡器", dsp.value)
        assertEquals(PathTone.ENHANCED, dsp.tone)
        assertEquals("已处理" to ZoneTone.WARNING, signalPathVerdict(processed))
        val resampled = signalPathStages(path("""{"source":{"sampleRate":44100},"dsp":{"outputStage":{"resampled":true,
            "actualSampleRate":48000,"effectiveResampler":"soxr"}},"transport":{"kind":"local"},"output":{}}"""))
        assertEquals("转换至 48 kHz", resampled.single().nodes.first { it.id == "src" }.value)
        assertEquals("已转换" to ZoneTone.INFO, signalPathVerdict(resampled))
        val idle = signalPathStages(path("""{"source":{},"transport":{"kind":"local"},"output":{}}"""))
        assertEquals("待机" to ZoneTone.NEUTRAL, signalPathVerdict(idle))
    }

    @Test
    fun bridgePathAddsNetworkAndReceiverStages() {
        val stages = signalPathStages(path("""{"source":{"sampleRate":96000,"bitDepth":24},"decoded":{"sampleRate":96000},
            "transport":{"kind":"tls-pcm","connected":true,"sampleRate":96000},
            "receiver":{"sampleRate":96000,"bufferedFrames":9600,"underruns":0,"consumedFrames":192000,"exclusive":true},
            "output":{"sampleRate":96000,"bitDepth":24,"backend":"wasapi"}}"""), peerName = "书房")
        assertEquals(listOf("core", "network", "bridge"), stages.map { it.id })
        assertEquals("书房 · 输出桥", stages.last().label)
        assertEquals("Float32 PCM · 96 kHz", stages[1].nodes.single().value)
        assertEquals("100 ms", stages.last().nodes.first { it.id == "buffer" }.value)
        assertEquals("WASAPI 独占 · 96 kHz / 24 bit", stages.last().nodes.last().value)
        val down = signalPathStages(path("""{"source":{"sampleRate":96000},"transport":{"kind":"tls-pcm","connected":false},"output":{}}"""))
        assertEquals(PathTone.FAULT, down[1].nodes.single().tone)
        assertEquals("链路中断" to ZoneTone.DANGER, signalPathVerdict(down))
    }
}
