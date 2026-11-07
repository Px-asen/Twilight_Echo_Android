package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable data class DesktopDspAsset(val id: String, val kind: String, val name: String, val fileName: String,
    val byteSize: Long, val referenceCount: Int = 0)
@Serializable data class DesktopDspAssets(val total: Int = 0, val offset: Int = 0, val items: List<DesktopDspAsset> = emptyList())
@Serializable data class DesktopVst3Plugin(val id: String, val name: String, val vendor: String = "", val version: String = "",
    val status: String, val parameterCount: Int = 0)
@Serializable data class DesktopVst3Plugins(val total: Int = 0, val offset: Int = 0, val items: List<DesktopVst3Plugin> = emptyList())
@Serializable data class DesktopDspNode(val id: String, val type: String, val enabled: Boolean, val assetId: String? = null, val catalogId: String? = null)
@Serializable data class DesktopManagedScene(val id: String, val name: String, val active: Boolean = false, val pinned: Boolean = false,
    val nodes: List<DesktopDspNode> = emptyList())
@Serializable data class DesktopVst3Helpers(val platformSupported: Boolean = false, val scannerPresent: Boolean = false, val hostPresent: Boolean = false)
@Serializable data class DesktopDspLibrary(val revision: Long, val assets: DesktopDspAssets = DesktopDspAssets(),
    val plugins: DesktopVst3Plugins = DesktopVst3Plugins(), val scenes: List<DesktopManagedScene> = emptyList(),
    val vst3Enabled: Boolean = false, val scanning: Boolean = false, val scanFailed: Boolean = false, val searchRootCount: Int = 0,
    val helpers: DesktopVst3Helpers = DesktopVst3Helpers(), val maxUploadBytes: Long = 64L * 1024 * 1024)
@Serializable data class DesktopDspUpload(val uploadId: String, val chunkBytes: Int)
@Serializable data class DesktopDspChunk(val offset: Long)
@Serializable data class DesktopProfileScene(val name: String, val nodeCount: Int)
@Serializable data class DesktopProfilePreview(val previewId: String, val revision: Long, val name: String,
    val scenes: List<DesktopProfileScene>, val assetCount: Int, val assetBytes: Long, val issues: List<String> = emptyList())
@Serializable data class DesktopProfileDownload(val downloadId: String, val fileName: String, val byteSize: Long, val chunkBytes: Int)
@Serializable data class DesktopProfileChunk(val offset: Long, val nextOffset: Long, val data: String)
@Serializable data class DesktopVst3Parameter(val id: Long, val title: String, val unit: String = "", val defaultNormalizedValue: Double,
    val stepCount: Int = 0, val flags: Int = 0, val value: Double? = null, val overridden: Boolean = false) {
    val editable: Boolean get() = flags and (2 or 16) == 0
}
@Serializable data class DesktopParameterPage(val revision: Long, val sceneId: String, val nodeId: String, val name: String,
    val total: Int, val offset: Int, val items: List<DesktopVst3Parameter> = emptyList())

/** All operations retain the playback computer, Core epoch and explicitly named Zone. */
class DesktopDspLibraryClient(private val transport: CoreTransport, private val epoch: () -> String) {
    private val json = DefaultCoreClient.DefaultJson
    fun scope(): DesktopLibraryScope {
        check(transport.isRemote) { "请先选择电脑作为播放目标" }
        return DesktopLibraryScope(transport.generation, epoch())
    }
    fun isCurrent(scope: DesktopLibraryScope) = transport.isRemote && transport.generation == scope.generation && epoch() == scope.epoch
    private suspend inline fun <reified T> call(scope: DesktopLibraryScope, zoneId: String, name: String,
        command: Boolean = false, fields: JsonObjectBuilder.() -> Unit = {}): Result<T> = try {
        check(isCurrent(scope)) { "电脑或 Core 已改变，请重新打开资料管理" }
        val id = "dsp-library-${UUID.randomUUID()}"
        val response = transport.sendRequest(CoreRequestEnvelope(requestId = id, commandId = id.takeIf { command }, epoch = scope.epoch,
            kind = if (command) "command" else "query", name = "zones.dsp.$name", payload = buildJsonObject { put("zoneId", zoneId); fields() }))
        currentCoroutineContext().ensureActive()
        check(isCurrent(scope) && response.epoch == scope.epoch) { "电脑或 Core 已改变，请重新打开资料管理" }
        if (!response.ok) throw CoreRequestException(response.error ?: CoreErrorView(CoreErrorCode.FAILED, "DSP 操作失败"))
        val value = requireNotNull(response.value) { "电脑未返回结果" }
        val result = if (command) requireNotNull(value.jsonObject["result"]) { "电脑未返回操作回执" } else value
        Result.success(json.decodeFromJsonElement<T>(result))
    } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { Result.failure(error) }
    suspend fun library(scope: DesktopLibraryScope, zone: String, assetOffset: Int = 0, pluginOffset: Int = 0, revision: Long? = null): Result<DesktopDspLibrary> =
        call(scope, zone, "library") { put("assetOffset", assetOffset); put("pluginOffset", pluginOffset); revision?.let { put("revision", it) } }
    suspend fun edit(scope: DesktopLibraryScope, zone: String, revision: Long, edit: JsonObject): Result<Unit> =
        call<JsonElement>(scope, zone, "library.edit", true) { put("revision", revision); put("edit", edit) }.map { }
    suspend fun begin(scope: DesktopLibraryScope, zone: String, kind: String, fileName: String, byteSize: Long): Result<DesktopDspUpload> =
        call(scope, zone, "upload.begin", true) { put("kind", kind); put("fileName", fileName); put("byteSize", byteSize) }
    suspend fun chunk(scope: DesktopLibraryScope, zone: String, upload: DesktopDspUpload, offset: Long, data: String): Result<DesktopDspChunk> =
        call(scope, zone, "upload.chunk", true) { put("uploadId", upload.uploadId); put("offset", offset); put("data", data) }
    suspend fun commit(scope: DesktopLibraryScope, zone: String, upload: DesktopDspUpload, revision: Long): Result<DesktopDspAsset> =
        call(scope, zone, "upload.commit", true) { put("uploadId", upload.uploadId); put("revision", revision) }
    suspend fun cancel(scope: DesktopLibraryScope, zone: String, upload: DesktopDspUpload): Result<Unit> =
        call<JsonElement>(scope, zone, "upload.cancel", true) { put("uploadId", upload.uploadId) }.map { }
    suspend fun preview(scope: DesktopLibraryScope, zone: String, upload: DesktopDspUpload, revision: Long): Result<DesktopProfilePreview> =
        call(scope, zone, "profile.preview", true) { put("uploadId", upload.uploadId); put("revision", revision) }
    suspend fun importProfile(scope: DesktopLibraryScope, zone: String, upload: DesktopDspUpload, preview: DesktopProfilePreview): Result<Unit> =
        call<JsonElement>(scope, zone, "profile.import", true) { put("uploadId", upload.uploadId); put("revision", preview.revision); put("previewId", preview.previewId) }.map { }
    suspend fun exportProfile(scope: DesktopLibraryScope, zone: String, revision: Long, name: String): Result<DesktopProfileDownload> =
        call(scope, zone, "profile.export", true) { put("revision", revision); put("name", name) }
    suspend fun profileChunk(scope: DesktopLibraryScope, zone: String, download: DesktopProfileDownload, offset: Long): Result<DesktopProfileChunk> =
        call(scope, zone, "profile.chunk") { put("downloadId", download.downloadId); put("offset", offset) }
    suspend fun releaseProfile(scope: DesktopLibraryScope, zone: String, download: DesktopProfileDownload): Result<Unit> =
        call<JsonElement>(scope, zone, "profile.release", true) { put("downloadId", download.downloadId) }.map { }
    suspend fun parameters(scope: DesktopLibraryScope, zone: String, revision: Long, sceneId: String, nodeId: String, offset: Int = 0): Result<DesktopParameterPage> =
        call(scope, zone, "parameters") { put("revision", revision); put("sceneId", sceneId); put("nodeId", nodeId); put("offset", offset) }
}
