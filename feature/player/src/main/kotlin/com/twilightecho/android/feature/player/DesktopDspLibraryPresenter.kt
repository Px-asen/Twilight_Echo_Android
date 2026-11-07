package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64
import java.util.UUID

data class DesktopDspLibraryState(val open: Boolean = false, val zoneId: String = "", val subject: String = "",
    val sessionId: String = "",
    val library: DesktopDspLibrary? = null, val loading: Boolean = false, val writing: Boolean = false,
    val preview: DesktopProfilePreview? = null, val parameters: DesktopParameterPage? = null,
    val exportedFile: File? = null, val exportName: String = "",
    val progress: Float? = null, val needsRefresh: Boolean = false, val error: String? = null, val notice: String? = null)
data class DesktopDspLibraryActions(val close: () -> Unit = {}, val refresh: () -> Unit = {},
    val moreAssets: () -> Unit = {}, val morePlugins: () -> Unit = {}, val edit: (JsonObject) -> Unit = {},
    val upload: (String, String, String, File) -> Unit = { _, _, _, _ -> },
    val importProfile: () -> Unit = {}, val cancelPreview: () -> Unit = {}, val exportProfile: (File) -> Unit = {}, val releaseExport: () -> Unit = {},
    val parameters: (String, String) -> Unit = { _, _ -> }, val moreParameters: () -> Unit = {}, val closeParameters: () -> Unit = {},
    val parameter: (Long, Double?) -> Unit = { _, _ -> })

internal class DesktopDspLibraryPresenter(private val scope: CoroutineScope, private val client: DesktopDspLibraryClient) {
    private val mutable = MutableStateFlow(DesktopDspLibraryState())
    val state = mutable.asStateFlow()
    private var context: DesktopLibraryScope? = null
    private var read: Job? = null
    private var profileUpload: DesktopDspUpload? = null
    val actions = DesktopDspLibraryActions(::close, ::refresh, { more(true) }, { more(false) }, ::edit, ::upload,
        ::importProfile, ::cancelPreview, ::exportProfile, ::releaseExport, ::parameters, ::moreParameters,
        { if (!mutable.value.writing) mutable.value = mutable.value.copy(parameters = null) }, ::parameter)
    fun open(zoneId: String, subject: String) {
        if (mutable.value.writing) return
        close()
        mutable.value = DesktopDspLibraryState(open = true, zoneId = zoneId, subject = subject, sessionId = UUID.randomUUID().toString())
        context = runCatching { client.scope() }.getOrElse { mutable.value = mutable.value.copy(error = it.message); return }
        refresh()
    }
    fun close() {
        if (mutable.value.writing) return
        cancelPreview(); releaseExport()
        read?.cancel(); context = null; mutable.value = DesktopDspLibraryState()
    }
    fun refresh() = read { captured, zone ->
        val library = client.library(captured, zone).getOrThrow()
        cancelPreview()
        mutable.value = mutable.value.copy(library = library, needsRefresh = false, parameters = null)
    }
    private fun more(assets: Boolean) {
        val previous = mutable.value.library ?: return
        if (mutable.value.needsRefresh) return
        read { captured, zone ->
            val next = client.library(captured, zone, if (assets) previous.assets.items.size else 0,
                if (assets) 0 else previous.plugins.items.size, previous.revision).getOrThrow()
            mutable.value = mutable.value.copy(library = next.copy(
                assets = if (assets) next.assets.copy(offset = 0, items = previous.assets.items + next.assets.items) else previous.assets,
                plugins = if (!assets) next.plugins.copy(offset = 0, items = previous.plugins.items + next.plugins.items) else previous.plugins))
        }
    }
    private fun read(action: suspend (DesktopLibraryScope, String) -> Unit) {
        val captured = context ?: return
        if (mutable.value.writing || mutable.value.loading) return
        val zone = mutable.value.zoneId
        val ticket = mutable.value.sessionId
        read?.cancel()
        read = scope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try { action(captured, zone) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (mutable.value.sessionId == ticket) mutable.value = mutable.value.copy(error = error.message, needsRefresh = true) }
            finally { if (mutable.value.sessionId == ticket) mutable.value = mutable.value.copy(loading = false) }
        }
    }
    private fun edit(edit: JsonObject) = write { captured, zone, revision -> client.edit(captured, zone, revision, edit).getOrThrow() }
    private fun write(action: suspend (DesktopLibraryScope, String, Long) -> Unit): Boolean {
        val current = mutable.value
        val captured = context ?: return false
        val library = current.library ?: return false
        if (current.writing || current.loading || current.needsRefresh) return false
        mutable.value = current.copy(writing = true, error = null, notice = null)
        scope.launch {
            var confirmed = false
            try {
                action(captured, current.zoneId, library.revision); confirmed = true
                val next = client.library(captured, current.zoneId).getOrThrow()
                mutable.value = mutable.value.copy(library = next, notice = if (next.scanning) "扫描已开始，稍后刷新查看结果" else "已更新「${current.subject}」")
                current.parameters?.let { previous ->
                    val page = client.parameters(captured, current.zoneId, next.revision, previous.sceneId, previous.nodeId).getOrThrow()
                    mutable.value = mutable.value.copy(parameters = page)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.value = mutable.value.copy(needsRefresh = true,
                error = "${if (confirmed) "操作已完成，回读失败" else "操作未获确认"}：${error.message}。请刷新核对后再操作。") }
            finally { mutable.value = mutable.value.copy(writing = false, progress = null) }
        }
        return true
    }
    /** The UI hands ownership of a bounded cache file to this operation; no content URI survives it. */
    private fun upload(sessionId: String, kind: String, name: String, file: File) {
        if (sessionId != mutable.value.sessionId) { file.delete(); return }
        cancelPreview()
        val accepted = write { captured, zone, revision ->
            var upload: DesktopDspUpload? = null
            var committed = false
            try {
                val size = file.length()
                require(size in 1..(mutable.value.library?.maxUploadBytes ?: 0)) { "文件超出上传大小限制" }
                val pending = client.begin(captured, zone, kind, name, size).getOrThrow().also { upload = it }
                require(pending.chunkBytes in 1..(768 * 1024)) { "电脑返回了无效分块大小" }
                withContext(Dispatchers.IO) {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(pending.chunkBytes)
                        var offset = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            val next = client.chunk(captured, zone, pending, offset,
                                Base64.getEncoder().encodeToString(buffer.copyOf(count))).getOrThrow()
                            offset += count
                            check(next.offset == offset) { "电脑未确认完整分块" }
                            withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(progress = offset.toFloat() / size) }
                            // Leave room for the existing playback/state polling budget.
                            delay(350)
                        }
                    }
                }
                if (kind == "profile") {
                    val preview = client.preview(captured, zone, pending, revision).getOrThrow()
                    profileUpload = pending
                    mutable.value = mutable.value.copy(preview = preview)
                } else client.commit(captured, zone, pending, revision).getOrThrow()
                committed = true
            } finally {
                if (!committed && upload != null && client.isCurrent(captured)) withContext(NonCancellable) {
                    withTimeoutOrNull(5_000) { client.cancel(captured, zone, upload!!) }
                }
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        }
        if (!accepted) file.delete()
    }
    private fun cancelPreview() {
        if (mutable.value.writing) return
        val captured = context; val pending = profileUpload; val zone = mutable.value.zoneId
        profileUpload = null; mutable.value = mutable.value.copy(preview = null)
        if (captured != null && pending != null && client.isCurrent(captured)) scope.launch { client.cancel(captured, zone, pending) }
    }
    private fun importProfile() {
        val pending = profileUpload ?: return
        val preview = mutable.value.preview ?: return
        if (preview.issues.isNotEmpty()) return
        write { captured, zone, _ ->
            try { client.importProfile(captured, zone, pending, preview).getOrThrow() }
            finally {
                profileUpload = null; mutable.value = mutable.value.copy(preview = null)
                if (client.isCurrent(captured)) withContext(NonCancellable) { withTimeoutOrNull(5_000) { client.cancel(captured, zone, pending) } }
            }
        }
    }
    private fun releaseExport() {
        mutable.value.exportedFile?.delete()
        mutable.value = mutable.value.copy(exportedFile = null, exportName = "")
    }
    private fun exportProfile(directory: File) {
        if (mutable.value.exportedFile != null) return
        write { captured, zone, revision ->
            var download: DesktopProfileDownload? = null
            var file: File? = null
            try {
                val result = client.exportProfile(captured, zone, revision, "${mutable.value.subject} DSP").getOrThrow().also { download = it }
                require(result.byteSize in 1..(64L * 1024 * 1024)) { "配置包大小无效" }
                val temporary = withContext(Dispatchers.IO) { File.createTempFile("server-profile-", ".tedsp", directory) }.also { file = it }
                withContext(Dispatchers.IO) { temporary.outputStream().use { output ->
                    var offset = 0L
                    while (offset < result.byteSize) {
                        val chunk = client.profileChunk(captured, zone, result, offset).getOrThrow()
                        val bytes = Base64.getDecoder().decode(chunk.data)
                        check(chunk.offset == offset && bytes.isNotEmpty() && chunk.nextOffset == offset + bytes.size && chunk.nextOffset <= result.byteSize) { "配置包分块不完整" }
                        output.write(bytes); offset = chunk.nextOffset
                        withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(progress = offset.toFloat() / result.byteSize) }
                        delay(350)
                    }
                } }
                mutable.value = mutable.value.copy(exportedFile = temporary, exportName = result.fileName); file = null
            } finally {
                file?.delete()
                if (download != null && client.isCurrent(captured)) withContext(NonCancellable) { withTimeoutOrNull(5_000) { client.releaseProfile(captured, zone, download!!) } }
            }
        }
    }
    private fun parameters(scene: String, node: String) {
        val revision = mutable.value.library?.revision ?: return
        read { captured, zone -> mutable.value = mutable.value.copy(parameters = client.parameters(captured, zone, revision, scene, node).getOrThrow()) }
    }
    private fun moreParameters() {
        val previous = mutable.value.parameters ?: return
        read { captured, zone ->
            val page = client.parameters(captured, zone, previous.revision, previous.sceneId, previous.nodeId, previous.items.size).getOrThrow()
            mutable.value = mutable.value.copy(parameters = page.copy(offset = 0, items = previous.items + page.items))
        }
    }
    private fun parameter(id: Long, value: Double?) {
        val page = mutable.value.parameters ?: return
        if (page.items.none { it.id == id && it.editable }) return
        edit(buildJsonObject { put("kind", "parameter"); put("sceneId", page.sceneId); put("nodeId", page.nodeId); put("parameterId", id); put("value", value?.let(::JsonPrimitive) ?: JsonNull) })
    }
}
