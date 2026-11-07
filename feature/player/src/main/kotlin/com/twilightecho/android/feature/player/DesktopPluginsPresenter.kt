package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Base64
import java.util.UUID

data class DesktopPluginsState(val open: Boolean = false, val host: String = "", val sessionId: String = "",
    val page: DesktopPluginsPage? = null, val preview: DesktopPluginPreview? = null,
    val loading: Boolean = false, val writing: Boolean = false, val needsRefresh: Boolean = false,
    val progress: Float? = null, val error: String? = null, val notice: String? = null)
data class DesktopPluginsActions(val close: () -> Unit = {}, val refresh: () -> Unit = {}, val more: () -> Unit = {},
    val upload: (String, String, File) -> Unit = { _, _, _ -> }, val install: () -> Unit = {}, val cancelPreview: () -> Unit = {},
    val edit: (DesktopManagedPlugin, String, Boolean) -> Unit = { _, _, _ -> })

internal class DesktopPluginsPresenter(private val scope: CoroutineScope, private val client: DesktopPluginsClient) {
    private val mutable = MutableStateFlow(DesktopPluginsState())
    val state = mutable.asStateFlow()
    private var context: DesktopLibraryScope? = null
    private var read: Job? = null
    val actions = DesktopPluginsActions(::close, ::refresh, ::more, ::upload, ::install, ::cancelPreview, ::edit)
    fun open(host: String) {
        if (mutable.value.writing) return
        close()
        mutable.value = DesktopPluginsState(open = true, host = host, sessionId = UUID.randomUUID().toString())
        context = runCatching { client.scope() }.getOrElse { mutable.value = mutable.value.copy(error = it.message); return }
        refresh()
    }
    fun close() {
        if (mutable.value.writing) return
        cancelPreview(); read?.cancel(); context = null; mutable.value = DesktopPluginsState()
    }
    private fun load(more: Boolean) {
        val captured = context ?: return
        val current = mutable.value
        if (current.writing || current.loading || more && current.needsRefresh) return
        read = scope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try {
                val page = client.list(captured, if (more) current.page?.items?.size ?: 0 else 0).getOrThrow()
                if (mutable.value.sessionId == current.sessionId) mutable.value = mutable.value.copy(
                    page = if (more) page.copy(offset = 0, items = (current.page?.items.orEmpty() + page.items).distinctBy { it.id }) else page,
                    needsRefresh = false)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (mutable.value.sessionId == current.sessionId) mutable.value = mutable.value.copy(error = error.message, needsRefresh = true) }
            finally { if (mutable.value.sessionId == current.sessionId) mutable.value = mutable.value.copy(loading = false) }
        }
    }
    private fun refresh() { cancelPreview(); load(false) }
    private fun more() = load(true)
    private fun write(action: suspend (DesktopLibraryScope) -> Unit): Boolean {
        val captured = context ?: return false
        val current = mutable.value
        if (current.writing || current.loading || current.needsRefresh || current.page?.canManage != true) return false
        mutable.value = current.copy(writing = true, error = null, notice = null)
        scope.launch {
            var confirmed = false
            try {
                action(captured); confirmed = true
                val page = client.list(captured).getOrThrow()
                mutable.value = mutable.value.copy(page = page, notice = if (mutable.value.preview != null) null else "已更新「${current.host}」的扩展")
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.value = mutable.value.copy(needsRefresh = true,
                error = "${if (confirmed) "操作已完成，回读失败" else "操作未获确认"}：${error.message}。请刷新核对。") }
            finally { mutable.value = mutable.value.copy(writing = false, progress = null) }
        }
        return true
    }
    private fun upload(sessionId: String, name: String, file: File) {
        if (sessionId != mutable.value.sessionId) { file.delete(); return }
        cancelPreview()
        val accepted = write { captured ->
            var upload: DesktopPluginUpload? = null
            try {
                val size = file.length()
                require(size in 1..(mutable.value.page?.maxUploadBytes ?: 0)) { "安装包大小无效" }
                val pending = client.begin(captured, name, size).getOrThrow().also { upload = it }
                require(pending.chunkBytes in 1..(768 * 1024)) { "电脑返回了无效分块大小" }
                withContext(Dispatchers.IO) { file.inputStream().use { input ->
                    val buffer = ByteArray(pending.chunkBytes); var offset = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        val receipt = client.chunk(captured, pending, offset, Base64.getEncoder().encodeToString(buffer.copyOf(count))).getOrThrow()
                        offset += count; check(receipt.offset == offset) { "电脑未确认完整分块" }
                        withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(progress = offset.toFloat() / size) }
                        delay(350)
                    }
                } }
                mutable.value = mutable.value.copy(preview = client.preview(captured, pending).getOrThrow())
            } finally {
                if (upload != null && client.isCurrent(captured)) withContext(NonCancellable) { withTimeoutOrNull(5_000) { client.cancelUpload(captured, upload!!) } }
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        }
        if (!accepted) file.delete()
    }
    private fun cancelPreview() {
        if (mutable.value.writing) return
        val captured = context; val preview = mutable.value.preview
        mutable.value = mutable.value.copy(preview = null)
        if (captured != null && preview != null && client.isCurrent(captured)) scope.launch { client.cancel(captured, preview) }
    }
    private fun install() {
        val preview = mutable.value.preview ?: return
        if (preview.issues.isNotEmpty()) return
        write { captured ->
            try { client.install(captured, preview).getOrThrow() }
            finally { mutable.value = mutable.value.copy(preview = null) }
        }
    }
    private fun edit(plugin: DesktopManagedPlugin, action: String, removeData: Boolean) = write {
        client.edit(it, plugin, action, removeData).getOrThrow()
    }
}
