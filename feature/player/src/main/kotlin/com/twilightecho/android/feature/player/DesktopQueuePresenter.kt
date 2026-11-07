package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

data class DesktopQueueState(
    val open: Boolean = false, val title: String = "选歌与队列", val loading: Boolean = false, val writing: Boolean = false,
    val destinations: List<DesktopQueueDestination> = emptyList(), val destination: DesktopQueueDestination? = null,
    val items: List<DesktopSessionQueueItem> = emptyList(), val total: Int = 0,
    val search: String = "", val catalog: CatalogPage? = null, val sources: CatalogSourcePage? = null,
    val entity: CatalogEntity? = null, val selected: List<String> = emptyList(), val selectionRevision: Long? = null,
    val pending: JsonObject? = null, val preset: JsonObject? = null, val playlist: DesktopServerPlaylist? = null,
    val error: String? = null, val notice: String? = null, val needsRefresh: Boolean = false
)
data class DesktopQueueActions(
    val close: () -> Unit = {}, val refresh: () -> Unit = {}, val destination: (DesktopQueueDestination) -> Unit = {},
    val search: (String) -> Unit = {}, val submit: () -> Unit = {}, val more: () -> Unit = {},
    val entity: (CatalogEntity?) -> Unit = {}, val moreSources: () -> Unit = {}, val toggle: (CatalogSource) -> Unit = {},
    val prepare: () -> Unit = {}, val confirm: (Boolean) -> Unit = {}, val cancel: () -> Unit = {},
    val moreQueue: () -> Unit = {}, val select: (DesktopSessionQueueItem) -> Unit = {},
    val edit: (JsonObject) -> Unit = {}
)

/** One owner for an explicit session sheet. Reads may be cancelled; writes retain
 * their original scope and confirmation until the server responds. */
internal class DesktopQueuePresenter(private val scope: CoroutineScope, private val client: DesktopSessionQueueClient,
    private val management: DesktopLibraryManagementClient? = null, private val playlistChanged: () -> Unit = {}) {
    private val mutable = MutableStateFlow(DesktopQueueState())
    val state = mutable.asStateFlow()
    private var context: DesktopLibraryScope? = null
    private var serial = 0
    private var read: Job? = null
    private var pendingDestination: DesktopQueueDestination? = null
    private var playlistRevision = 0L
    val actions = DesktopQueueActions(::close, ::refresh, ::destination, { mutable.value = mutable.value.copy(search = it) },
        { catalog(false) }, { catalog(true) }, ::entity, { sources(true) }, ::toggle, ::prepare, ::confirm,
        { pendingDestination = null; mutable.value = mutable.value.copy(pending = null) }, { queue(true) }, ::select, ::edit)

    fun open(kind: String? = null, id: String? = null, selection: JsonObject? = null, expected: DesktopLibraryScope? = null) {
        if (mutable.value.writing) return
        close()
        val captured = runCatching { client.scope() }.getOrElse { mutable.value = DesktopQueueState(open = true, error = it.message); return }
        if (expected != null && expected != captured) { mutable.value = DesktopQueueState(open = true, error = "电脑或 Core 已改变，请重新选择音乐"); return }
        context = captured
        mutable.value = DesktopQueueState(open = true)
        runRead {
            val destinations = client.destinations(captured).getOrThrow()
            val target = destinations.firstOrNull { it.target.kind == kind && it.target.id == id }
                ?: if (kind == null) destinations.firstOrNull() else error("声区已改变，请重新打开")
            mutable.value = mutable.value.copy(destinations = destinations, destination = target)
            if (selection != null) mutable.value = mutable.value.copy(preset = selection)
            target?.let { loadQueue(captured, it, false) }
        }
    }
    fun close() {
        if (mutable.value.writing) return
        ++serial; read?.cancel(); read = null; context = null; pendingDestination = null
        mutable.value = DesktopQueueState()
    }
    fun openPlaylist(playlist: DesktopServerPlaylist, revision: Long, expected: DesktopLibraryScope) {
        if (mutable.value.writing) return
        close()
        if (!client.isCurrent(expected)) { mutable.value = DesktopQueueState(open = true, error = "电脑已改变，请重新打开歌单"); return }
        context = expected; playlistRevision = revision
        mutable.value = DesktopQueueState(open = true, title = "添加到「${playlist.name}」", playlist = playlist)
    }
    private fun runRead(block: suspend () -> Unit) {
        if (mutable.value.writing) return
        val ticket = ++serial
        read?.cancel()
        read = scope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try { block() } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (serial == ticket) mutable.value = mutable.value.copy(error = error.message) }
            finally { if (serial == ticket) mutable.value = mutable.value.copy(loading = false) }
        }
    }
    fun refresh() {
        val captured = context ?: return
        if (mutable.value.playlist != null) { catalog(false); return }
        val target = mutable.value.destination?.target
        runRead {
            val destinations = client.destinations(captured).getOrThrow()
            val destination = destinations.firstOrNull { it.target.kind == target?.kind && it.target.id == target.id }
                ?: error("声区已移除，请重新打开")
            pendingDestination = null
            mutable.value = mutable.value.copy(destinations = destinations, destination = destination, pending = null)
            loadQueue(captured, destination, false)
            mutable.value = mutable.value.copy(needsRefresh = false)
        }
    }
    private fun destination(destination: DesktopQueueDestination) {
        if (mutable.value.writing) return
        pendingDestination = null
        mutable.value = mutable.value.copy(destination = destination, items = emptyList(), total = 0, pending = null, notice = null)
        queue(false)
    }
    private suspend fun loadQueue(captured: DesktopLibraryScope, destination: DesktopQueueDestination, append: Boolean) {
        val old = mutable.value.items
        val page = client.page(captured, destination, if (append) old.size else 0).getOrThrow()
        check(page.offset == if (append) old.size else 0) { "队列分页已变化，请刷新" }
        mutable.value = mutable.value.copy(items = if (append) old + page.items else page.items, total = page.total)
    }
    private fun queue(append: Boolean) {
        val captured = context ?: return
        val destination = mutable.value.destination ?: return
        runRead { loadQueue(captured, destination, append) }
    }
    private fun catalog(append: Boolean) {
        val captured = context ?: return
        val current = mutable.value
        if (!append) mutable.value = current.copy(catalog = null, entity = null, sources = null, selected = emptyList(), selectionRevision = null, preset = null)
        runRead {
            val page = client.catalog(captured, current.search, if (append) current.catalog?.items?.size ?: 0 else 0,
                current.catalog?.revision.takeIf { append }).getOrThrow()
            mutable.value = mutable.value.copy(catalog = if (append) page.copy(items = current.catalog!!.items + page.items) else page)
        }
    }
    private fun entity(entity: CatalogEntity?) {
        if (mutable.value.writing) return
        mutable.value = mutable.value.copy(entity = entity, sources = null)
        if (entity != null) sources(false)
    }
    private fun sources(append: Boolean) {
        val captured = context ?: return
        val current = mutable.value
        val entity = current.entity ?: return
        val revision = current.catalog?.revision ?: return
        runRead {
            val page = client.sources(captured, entity.id, revision, if (append) current.sources?.items?.size ?: 0 else 0).getOrThrow()
            check(page.revision == revision) { "音乐库已变化，请重新搜索" }
            mutable.value = mutable.value.copy(sources = if (append) page.copy(items = current.sources!!.items + page.items) else page)
        }
    }
    private fun toggle(source: CatalogSource) {
        if (mutable.value.writing) return
        val current = mutable.value
        val selected = current.selected.toMutableList()
        if (!selected.remove(source.id)) {
            if (selected.size >= 200) { mutable.value = current.copy(error = "一次最多选择 200 个音源"); return }
            selected.add(source.id)
        }
        mutable.value = current.copy(selected = selected, selectionRevision = current.catalog?.revision)
    }
    private fun prepare() {
        val current = mutable.value
        if (current.destination == null && current.playlist == null || current.writing || current.needsRefresh) return
        val selection = current.preset ?: current.selectionRevision?.takeIf { current.selected.isNotEmpty() }
            ?.let { catalogQueueSelection(it, current.selected.toList()) } ?: return
        pendingDestination = current.destination
        mutable.value = current.copy(pending = selection)
    }
    private fun confirm(play: Boolean) {
        val captured = context ?: return
        val selection = mutable.value.pending ?: return
        val playlist = mutable.value.playlist
        if (playlist != null) {
            if (mutable.value.writing || mutable.value.loading || mutable.value.needsRefresh) return
            val revision = playlistRevision
            val ids = selection["sourceIds"]!!.jsonArray.map { it.jsonPrimitive.content }
            val catalogRevision = selection["revision"]!!.jsonPrimitive.long
            val ticket = ++serial
            mutable.value = mutable.value.copy(writing = true, error = null, notice = null)
            scope.launch {
                try {
                    val result = requireNotNull(management).addSources(captured, playlist.id, revision, catalogRevision, ids).getOrThrow()
                    if (ticket == serial) {
                        playlistRevision = result.revision
                        mutable.value = mutable.value.copy(pending = null, selected = emptyList(), notice = "已添加到「${playlist.name}」")
                        playlistChanged()
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { if (ticket == serial) mutable.value = mutable.value.copy(pending = null, needsRefresh = true, error = "${error.message}；请关闭后重新读取歌单核对") }
                finally { if (ticket == serial) mutable.value = mutable.value.copy(writing = false) }
            }
            return
        }
        val destination = pendingDestination ?: return
        write { client.load(captured, destination, selection, play).getOrThrow() }
    }
    private fun select(item: DesktopSessionQueueItem) {
        val captured = context ?: return; val destination = mutable.value.destination ?: return
        write { client.select(captured, destination, item).getOrThrow() }
    }
    private fun edit(edit: JsonObject) {
        val captured = context ?: return; val destination = mutable.value.destination ?: return
        write { client.edit(captured, destination, edit).getOrThrow() }
    }
    private fun write(action: suspend () -> Unit) {
        if (mutable.value.writing || mutable.value.loading || mutable.value.needsRefresh) return
        val captured = context ?: return
        val destination = mutable.value.destination ?: return
        read?.cancel(); val ticket = ++serial
        mutable.value = mutable.value.copy(writing = true, error = null, notice = null)
        scope.launch {
            var receipt = false
            try {
                action(); receipt = true
                val destinations = client.destinations(captured).getOrThrow()
                if (serial != ticket) return@launch
                val next = destinations.firstOrNull { it.target.kind == destination.target.kind && it.target.id == destination.target.id }
                    ?: error("操作已完成，声区已移除")
                pendingDestination = null
                mutable.value = mutable.value.copy(destinations = destinations, destination = next, pending = null, preset = null, selected = emptyList(), notice = "已更新「${next.name}」")
                loadQueue(captured, next, false)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (serial == ticket) {
                    pendingDestination = null
                    mutable.value = mutable.value.copy(pending = null, needsRefresh = true, error = if (receipt) "操作已完成，但读取失败：${error.message}" else "${error.message}；请刷新核对，不会自动重试")
                }
            } finally { if (serial == ticket) mutable.value = mutable.value.copy(writing = false) }
        }
    }
}
