package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable data class DesktopManagedPlugin(val id: String, val name: String, val version: String, val description: String = "",
    val author: String = "", val type: List<String> = emptyList(), val permissions: List<String> = emptyList(),
    val enabled: Boolean = false, val status: String = "installed", val builtIn: Boolean = false,
    val token: String, val issues: List<String> = emptyList())
@Serializable data class DesktopPluginsPage(val canManage: Boolean, val total: Int, val offset: Int = 0,
    val items: List<DesktopManagedPlugin> = emptyList(), val maxUploadBytes: Long = 50L * 1024 * 1024)
@Serializable data class DesktopPluginManifest(val id: String, val name: String, val version: String, val description: String = "",
    val author: String = "", val type: List<String> = emptyList(), val permissions: List<String> = emptyList(),
    val dependencies: Map<String, String> = emptyMap())
@Serializable data class DesktopPluginPreview(val previewId: String, val plugin: DesktopPluginManifest, val previousVersion: String? = null,
    val previouslyEnabled: Boolean = false, val issues: List<String> = emptyList(), val expiresAt: Long)
@Serializable data class DesktopPluginUpload(val uploadId: String, val chunkBytes: Int)
@Serializable data class DesktopPluginChunk(val offset: Long)

/** Extension management follows the selected library computer, independent of audio output. */
class DesktopPluginsClient(private val transport: CoreTransport, private val epoch: () -> String) {
    private val json = DefaultCoreClient.DefaultJson
    fun scope(): DesktopLibraryScope {
        check(transport.isRemote) { "请先选择电脑曲库" }
        return DesktopLibraryScope(transport.generation, epoch())
    }
    fun isCurrent(scope: DesktopLibraryScope) = transport.isRemote && transport.generation == scope.generation && epoch() == scope.epoch
    private suspend inline fun <reified T> call(scope: DesktopLibraryScope, name: String, command: Boolean = false,
        fields: JsonObjectBuilder.() -> Unit = {}): Result<T> = try {
        check(isCurrent(scope)) { "电脑或 Core 已改变，请重新打开扩展管理" }
        val id = "plugins-${UUID.randomUUID()}"
        val response = transport.sendRequest(CoreRequestEnvelope(requestId = id, commandId = id.takeIf { command }, epoch = scope.epoch,
            kind = if (command) "command" else "query", name = "plugins.management.$name", payload = buildJsonObject(fields)))
        currentCoroutineContext().ensureActive()
        check(isCurrent(scope) && response.epoch == scope.epoch) { "电脑或 Core 已改变，请重新打开扩展管理" }
        if (!response.ok) throw CoreRequestException(response.error ?: CoreErrorView(CoreErrorCode.FAILED, "扩展操作失败"))
        val value = requireNotNull(response.value) { "电脑未返回结果" }
        val result = if (command) requireNotNull(value.jsonObject["result"]) { "电脑未返回操作回执" } else value
        Result.success(json.decodeFromJsonElement<T>(result))
    } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { Result.failure(error) }
    suspend fun list(scope: DesktopLibraryScope, offset: Int = 0): Result<DesktopPluginsPage> = call(scope, "list") { put("offset", offset) }
    suspend fun edit(scope: DesktopLibraryScope, plugin: DesktopManagedPlugin, action: String, removeData: Boolean = false): Result<Unit> =
        call<JsonElement>(scope, "edit", true) { put("id", plugin.id); put("token", plugin.token); put("action", action)
            if (action == "uninstall") put("removeData", removeData) }.map { }
    suspend fun begin(scope: DesktopLibraryScope, name: String, size: Long): Result<DesktopPluginUpload> =
        call(scope, "upload.begin", true) { put("fileName", name); put("byteSize", size) }
    suspend fun chunk(scope: DesktopLibraryScope, upload: DesktopPluginUpload, offset: Long, data: String): Result<DesktopPluginChunk> =
        call(scope, "upload.chunk", true) { put("uploadId", upload.uploadId); put("offset", offset); put("data", data) }
    suspend fun cancelUpload(scope: DesktopLibraryScope, upload: DesktopPluginUpload): Result<Unit> =
        call<JsonElement>(scope, "upload.cancel", true) { put("uploadId", upload.uploadId) }.map { }
    suspend fun preview(scope: DesktopLibraryScope, upload: DesktopPluginUpload): Result<DesktopPluginPreview> =
        call(scope, "preview", true) { put("uploadId", upload.uploadId) }
    suspend fun install(scope: DesktopLibraryScope, preview: DesktopPluginPreview): Result<Unit> =
        call<JsonElement>(scope, "install", true) { put("previewId", preview.previewId) }.map { }
    suspend fun cancel(scope: DesktopLibraryScope, preview: DesktopPluginPreview): Result<Unit> =
        call<JsonElement>(scope, "cancel", true) { put("previewId", preview.previewId) }.map { }
}
