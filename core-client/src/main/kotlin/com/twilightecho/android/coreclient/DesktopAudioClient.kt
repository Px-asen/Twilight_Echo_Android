package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable
data class DesktopZoneOutput(val id: String, val name: String, val available: Boolean = false)
@Serializable
data class DesktopZoneState(val revision: Long, val zoneId: String = "local",
    val selectedOutputId: String = "local", val connected: Boolean = false,
    val selected: Boolean = false, val outputs: List<DesktopZoneOutput> = emptyList(), val error: String? = null,
    val groupId: String? = null)
@Serializable
data class DesktopDspScene(val id: String, val name: String, val enabled: Boolean = false)
@Serializable
data class DesktopDspState(val revision: Long, val processing: JsonObject = JsonObject(emptyMap()),
    val scenes: List<DesktopDspScene> = emptyList(), val activeSceneId: String? = null, val pinnedSceneId: String? = null)
data class DesktopAudioSnapshot(val zone: DesktopZoneState, val dsp: DesktopDspState, val path: JsonObject, val targetGeneration: Long,
    val session: DesktopZoneSession? = null, val group: DesktopSyncGroup? = null)
/** [id] is the computer's session reference (`remote-track:`); only `local` provider tracks have artwork behind it. */
@Serializable
data class DesktopZoneTrack(val title: String = "", val artist: String = "", val id: String? = null,
    val album: String = "", val source: String? = null, val duration: Double? = null,
    val format: String? = null, val sampleRate: Double? = null, val bitDepth: Double? = null)
@Serializable
data class DesktopZonePlayback(val state: String = "stopped", val position: Double = 0.0, val duration: Double = 0.0, val volume: Double = 0.5,
    val codec: String? = null, val sourceSampleRate: Double? = null, val sourceBitDepth: Double? = null,
    val actualSampleRate: Double? = null, val actualBitDepth: Double? = null, val outputBackend: String? = null,
    val autoMixState: String = "off", val autoMixStemEnhanced: Boolean = false)
/** The receiver's own report of what reaches its DAC; facts only, never inferred on the phone. */
@Serializable
data class DesktopBridgeReceiver(val adaptiveSupported: Boolean = false, val deviceName: String = "", val backend: String = "",
    val exclusive: Boolean = false, val actualSampleRate: Double = 0.0, val actualBitDepth: Double = 0.0, val failed: Boolean = false)
@Serializable
data class DesktopZoneConnection(val connected: Boolean = false, val selected: Boolean = false, val peerName: String? = null,
    val error: String? = null, val receiver: DesktopBridgeReceiver? = null)
@Serializable
data class DesktopZoneQueue(val id: String, val revision: Long, val occurrenceId: String? = null)
@Serializable
data class DesktopZoneSession(val id: String, val name: String, val revision: Long, val track: DesktopZoneTrack? = null,
    val playback: DesktopZonePlayback? = null, val output: DesktopZoneConnection? = null, val error: String? = null,
    val queue: DesktopZoneQueue? = null, val groupId: String? = null)
@Serializable
data class DesktopZoneSessions(val revision: Long, val zones: List<DesktopZoneSession> = emptyList(), val error: String? = null)
data class DesktopSessionHandoff(val source: DesktopZoneSession, val target: DesktopZoneSession,
    val targetGeneration: Long, val replace: Boolean = false, val resume: Boolean = false)

/** Targets the playback transport, not the library browser or phone DSP. */
class DesktopAudioClient(private val transport: CoreTransport, private val epoch: () -> String) {
    private val json = DefaultCoreClient.DefaultJson
    private suspend fun request(kind: String, name: String, payload: JsonObject): JsonObject {
        check(transport.isRemote) { "请先选择电脑作为播放控制目标" }
        val generation = transport.generation
        val response = transport.sendRequest(CoreRequestEnvelope(requestId = "audio-${UUID.randomUUID()}",
            epoch = epoch(), kind = kind, name = name, payload = payload))
        check(transport.isRemote && transport.generation == generation) { "播放目标已改变，请刷新输出状态" }
        if (!response.ok) throw CoreRequestException(response.error ?: CoreErrorView(CoreErrorCode.FAILED, "电脑音频操作失败"))
        return response.value?.jsonObject ?: JsonObject(emptyMap())
    }
    private suspend fun <T> result(action: suspend () -> T): Result<T> = try { Result.success(action()) }
        catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { Result.failure(error) }

    suspend fun sessions(): Result<DesktopZoneSessions> = result {
        json.decodeFromJsonElement<DesktopZoneSessions>(request("query", "zones.list", JsonObject(emptyMap())))
    }
    suspend fun groups(): Result<DesktopSyncGroups> = result {
        json.decodeFromJsonElement<DesktopSyncGroups>(request("query", "groups.list", JsonObject(emptyMap())))
    }
    private suspend fun group(id: String?): DesktopSyncGroup? = id?.let {
        groups().getOrThrow().groups.singleOrNull { group -> group.id == id } ?: error("同步组已改变，请刷新")
    }
    suspend fun snapshot(zoneId: String = "local"): Result<DesktopAudioSnapshot> = result {
        val generation = transport.generation
        if (zoneId != "local") {
            val session = sessions().getOrThrow().zones.singleOrNull { it.id == zoneId }
                ?: error("Zone 已移除，请重新选择")
            val payload = buildJsonObject { put("zoneId", zoneId) }
            val dsp = json.decodeFromJsonElement<DesktopDspState>(request("query", "zones.dsp", payload))
            val group = group(session.groupId)
            val path = if (group == null) request("query", "zones.signalPath", payload)
                else request("query", "groups.signalPath", buildJsonObject { put("groupId", group.id); put("zoneId", zoneId) })
            check(transport.generation == generation) { "播放目标已改变" }
            return@result DesktopAudioSnapshot(DesktopZoneState(session.revision, zoneId, "bridge",
                connected = session.output?.connected == true, selected = true, error = session.error, groupId = session.groupId), dsp, path, generation, session, group)
        }
        val zone = json.decodeFromJsonElement<DesktopZoneState>(request("query", "zones.state", JsonObject(emptyMap())))
        val dsp = json.decodeFromJsonElement<DesktopDspState>(request("query", "audio.dsp", JsonObject(emptyMap())))
        val group = group(zone.groupId)
        val path = if (group == null) request("query", "audio.signalPath", JsonObject(emptyMap()))
            else request("query", "groups.signalPath", buildJsonObject { put("groupId", group.id); put("zoneId", "local") })
        check(transport.generation == generation) { "播放目标已改变" }
        DesktopAudioSnapshot(zone, dsp, path, generation, group = group)
    }
    suspend fun selectOutput(revision: Long, outputId: String, targetGeneration: Long): Result<Unit> = result {
        check(transport.generation == targetGeneration) { "播放目标已改变，请刷新" }
        request("command", "zones.select", buildJsonObject { put("revision", revision); put("outputId", outputId) }); Unit
    }
    suspend fun processing(revision: Long, settings: JsonObject, targetGeneration: Long, zoneId: String = "local"): Result<Unit> = edit(revision, targetGeneration, buildJsonObject {
        put("kind", "processing"); put("settings", settings)
    }, zoneId)
    suspend fun scene(revision: Long, id: String?, targetGeneration: Long, zoneId: String = "local"): Result<Unit> = edit(revision, targetGeneration, buildJsonObject {
        put("kind", "apply"); put("id", id?.let(::JsonPrimitive) ?: JsonNull)
        put("confirmDsdPcmFallback", true)
    }, zoneId)
    suspend fun control(session: DesktopAudioSnapshot, kind: String, value: Double? = null): Result<Unit> = result {
        check(transport.generation == session.targetGeneration) { "播放目标已改变，请刷新" }
        check(kind in setOf("play", "pause", "stop", "next", "previous", "volume", "seek")) { "未知播放操作" }
        if (kind == "volume") require(value != null && value.isFinite() && value in 0.0..1.0) { "音量范围为 0–100%" }
        if (kind == "seek") require(value != null && value.isFinite() && value >= 0.0) { "播放位置无效" }
        request("command", if (session.group == null) "zones.transport" else "groups.transport", buildJsonObject {
            if (session.group == null) put("zoneId", session.zone.zoneId) else put("groupId", session.group.id)
            put("revision", session.group?.revision ?: session.zone.revision)
            put("action", buildJsonObject { put("kind", kind); if (kind == "volume") put("volume", requireNotNull(value)); if (kind == "seek") put("position", requireNotNull(value)) })
        }); Unit
    }
    suspend fun interrupt(session: DesktopAudioSnapshot, kind: String): Result<Unit> = result {
        require(kind == "pause" || kind == "stop")
        check(transport.generation == session.targetGeneration) { "播放目标已改变，请刷新" }
        // Silence bypasses only the transport revision, never epoch/target/Zone.
        // It must not wait for a slow list, DSP or output-status request.
        request("command", if (session.group == null) "zones.transport" else "groups.transport", buildJsonObject {
            if (session.group == null) put("zoneId", session.zone.zoneId) else put("groupId", session.group.id)
            put("revision", JsonNull)
            put("action", buildJsonObject { put("kind", kind) })
        }); Unit
    }
    suspend fun handoff(command: DesktopSessionHandoff): Result<Unit> = result {
        check(transport.generation == command.targetGeneration) { "播放目标已改变，请刷新" }
        require(command.source.id != command.target.id) { "请选择不同的目标 Zone" }
        require(command.source.groupId == null && command.target.groupId == null) { "同步组成员请先解除编组" }
        require(command.source.queue?.occurrenceId != null) { "源会话没有可接力的队列" }
        require(command.replace || command.target.queue == null) { "请先确认替换目标队列" }
        fun endpoint(session: DesktopZoneSession) = buildJsonObject {
            put("zoneId", session.id); put("revision", session.revision)
            put("queue", session.queue?.let { json.encodeToJsonElement(it) } ?: JsonNull)
        }
        request("command", "zones.handoff", buildJsonObject {
            put("source", endpoint(command.source)); put("target", endpoint(command.target))
            put("replace", command.replace); put("resume", command.resume)
        }); Unit
    }
    suspend fun createGroup(command: DesktopSyncGroupCreate): Result<Unit> = result {
        check(transport.generation == command.targetGeneration) { "播放目标已改变，请刷新" }
        require(command.name.isNotBlank() && command.name.length <= 80)
        require(command.members.size in 2..4 && command.members.map { it.session.id }.distinct().size == command.members.size)
        require(command.members.any { it.session.id == command.source.id } && command.source.queue?.occurrenceId != null)
        require(command.members.all { it.session.groupId == null && it.downstreamDelayMs.isFinite() && it.downstreamDelayMs in 0.0..250.0 })
        request("command", "groups.create", buildJsonObject {
            put("revision", command.revision); put("name", command.name.trim())
            put("source", buildJsonObject { put("zoneId", command.source.id); put("queue", json.encodeToJsonElement(command.source.queue)) })
            put("members", buildJsonArray { command.members.forEach { member -> add(buildJsonObject {
                put("zoneId", member.session.id); put("revision", member.session.revision); put("downstreamDelayMs", member.downstreamDelayMs)
            }) } })
        }); Unit
    }
    suspend fun removeGroup(group: DesktopSyncGroup, targetGeneration: Long): Result<Unit> = result {
        check(transport.generation == targetGeneration) { "播放目标已改变，请刷新" }
        request("command", "groups.remove", buildJsonObject { put("groupId", group.id); put("revision", group.revision) }); Unit
    }
    suspend fun groupAutoMix(group: DesktopSyncGroup, mode: String, targetGeneration: Long): Result<Unit> = result {
        check(transport.generation == targetGeneration) { "播放目标已改变，请刷新" }
        require(mode in setOf("off", "natural", "dj")) { "无效的组 AutoMix 模式" }
        check(mode == "off" || group.autoMixAvailable) { "请更新 Core 与成员引擎以使用组 AutoMix" }
        request("command", "groups.settings.edit", buildJsonObject {
            put("groupId", group.id); put("revision", group.revision); put("autoMixMode", mode)
        }); Unit
    }
    private suspend fun edit(revision: Long, targetGeneration: Long, edit: JsonObject, zoneId: String): Result<Unit> = result {
        check(transport.generation == targetGeneration) { "播放目标已改变，请刷新" }
        request("command", if (zoneId == "local") "audio.dsp.edit" else "zones.dsp.edit", buildJsonObject {
            put("revision", revision); put("edit", edit); if (zoneId != "local") put("zoneId", zoneId)
        }); Unit
    }
}
