package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

enum class DesktopWorkspaceTab(val label: String) { FOCUS("Focus"), TAGS("标签"), PLAYLISTS("Server 歌单") }
data class DesktopLibraryWorkspaceState(
    val open: Boolean = false, val host: String = "电脑", val tab: DesktopWorkspaceTab = DesktopWorkspaceTab.FOCUS,
    val loading: Boolean = false, val writing: Boolean = false, val error: String? = null, val notice: String? = null, val needsRefresh: Boolean = false,
    val query: JsonObject = buildJsonObject { put("version", 1); put("entity", "recording"); put("scope", "library") },
    val focus: DesktopFocusPage? = null, val saved: DesktopSavedFocusPage? = null, val selectedSaved: DesktopSavedFocus? = null,
    val facets: DesktopFocusFacets? = null, val showFocusTags: Boolean = false, val tags: DesktopTagsPage? = null, val tag: DesktopTagMembers? = null,
    val playlists: DesktopServerPlaylists? = null, val playlist: DesktopServerPlaylist? = null,
    val members: DesktopServerPlaylistMembers? = null
)
data class DesktopLibraryWorkspaceActions(
    val close: () -> Unit = {}, val refresh: () -> Unit = {}, val tab: (DesktopWorkspaceTab) -> Unit = {},
    val query: (JsonObject) -> Unit = {}, val focus: () -> Unit = {}, val moreFocus: () -> Unit = {},
    val facet: (String) -> Unit = {}, val moreFacets: () -> Unit = {}, val focusTags: () -> Unit = {},
    val saved: (DesktopSavedFocus) -> Unit = {}, val moreSaved: () -> Unit = {}, val save: (String) -> Unit = {},
    val updateSaved: () -> Unit = {}, val renameSaved: (DesktopSavedFocus, String) -> Unit = { _, _ -> },
    val deleteSaved: (DesktopSavedFocus) -> Unit = {}, val playFocus: (String?) -> Unit = {},
    val moreTags: () -> Unit = {}, val tag: (DesktopTag?) -> Unit = {}, val moreTagMembers: () -> Unit = {},
    val createTag: (String) -> Unit = {}, val renameTag: (DesktopTag, String) -> Unit = { _, _ -> },
    val deleteTag: (DesktopTag) -> Unit = {}, val removeTagMember: (String) -> Unit = {}, val playTag: () -> Unit = {},
    val morePlaylists: () -> Unit = {}, val playlist: (DesktopServerPlaylist?) -> Unit = {}, val moreMembers: () -> Unit = {},
    val playlistEdit: (JsonObject) -> Unit = {}, val addSources: () -> Unit = {}, val playPlaylist: () -> Unit = {}
)

internal class DesktopLibraryWorkspacePresenter(private val scope: CoroutineScope,
    private val client: DesktopLibraryManagementClient, private val queues: DesktopSessionQueueClient,
    private val playSelection: (DesktopLibraryScope, JsonObject) -> Unit,
    private val addSelection: (DesktopServerPlaylist, Long, DesktopLibraryScope) -> Unit) {
    private val mutable = MutableStateFlow(DesktopLibraryWorkspaceState())
    val state = mutable.asStateFlow()
    private var context: DesktopLibraryScope? = null
    private var serial = 0
    private var read: Job? = null
    val actions = DesktopLibraryWorkspaceActions(::close, ::refresh, ::tab, ::query, { focus(false) }, { focus(true) },
        { facet(it, false) }, { mutable.value.facets?.facet?.let { facet(it, true) } },
        { mutable.value = mutable.value.copy(showFocusTags = true); tags(false) }, ::saved, { savedPages(true) }, ::save,
        ::updateSaved, ::renameSaved, ::deleteSaved, ::playFocus, { tags(true) }, ::tag, { tagMembers(true) },
        ::createTag, ::renameTag, ::deleteTag, ::removeTagMember, ::playTag, { playlists(true) }, ::playlist,
        { members(true) }, ::playlistEdit, ::addSources, ::playPlaylist)
    fun open(host: String) {
        if (mutable.value.writing) return
        close()
        context = runCatching { client.scope() }.getOrElse { mutable.value = DesktopLibraryWorkspaceState(open = true, host = host, error = it.message); return }
        mutable.value = DesktopLibraryWorkspaceState(open = true, host = host)
        refresh()
    }
    fun close() {
        if (mutable.value.writing) return
        ++serial; read?.cancel(); read = null; context = null; mutable.value = DesktopLibraryWorkspaceState()
    }
    private fun read(action: suspend (DesktopLibraryScope) -> Unit) {
        if (mutable.value.writing) return
        val captured = context ?: return; val ticket = ++serial
        read?.cancel()
        read = scope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try { action(captured) } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (ticket == serial) mutable.value = mutable.value.copy(error = error.message) }
            finally { if (ticket == serial) mutable.value = mutable.value.copy(loading = false) }
        }
    }
    private fun write(action: suspend (DesktopLibraryScope) -> Unit) {
        if (mutable.value.writing || mutable.value.loading || mutable.value.needsRefresh) return
        val captured = context ?: return; val ticket = ++serial
        read?.cancel()
        mutable.value = mutable.value.copy(writing = true, error = null, notice = null)
        scope.launch {
            var receipt = false
            try {
                action(captured); receipt = true
                mutable.value = mutable.value.copy(notice = "已保存到${mutable.value.host}")
                reload(captured)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (ticket == serial) mutable.value = mutable.value.copy(needsRefresh = true, error = if (receipt)
                "已保存，但读取失败：${error.message}" else "${error.message}；请刷新核对，不会自动重试") }
            finally { if (ticket == serial) mutable.value = mutable.value.copy(writing = false) }
        }
    }
    fun refresh() = read { reload(it); mutable.value = mutable.value.copy(needsRefresh = false) }
    private suspend fun reload(captured: DesktopLibraryScope) {
        when (mutable.value.tab) {
            DesktopWorkspaceTab.FOCUS -> {
                mutable.value = mutable.value.copy(saved = client.savedFocus(captured).getOrThrow())
                val draft = mutable.value.query
                mutable.value = mutable.value.copy(focus = client.focus(captured, draft).getOrThrow(), facets = null)
            }
            DesktopWorkspaceTab.TAGS -> {
                val selected = mutable.value.tag?.tag?.id
                mutable.value = mutable.value.copy(tags = client.tags(captured).getOrThrow())
                if (selected != null) mutable.value = mutable.value.copy(tag = client.tagMembers(captured, selected).getOrThrow())
            }
            DesktopWorkspaceTab.PLAYLISTS -> {
                val page = client.playlists(captured).getOrThrow()
                mutable.value = mutable.value.copy(playlists = page)
                mutable.value.playlist?.let { old ->
                    // The selected row may be beyond page one. Membership reads are bound to
                    // the new global revision; do not silently replace the selected playlist.
                    val members = client.playlistMembers(captured, old.id, page.revision).getOrThrow()
                    mutable.value = mutable.value.copy(playlist = page.items.find { it.id == old.id } ?: old, members = members)
                }
            }
        }
    }
    private fun tab(tab: DesktopWorkspaceTab) {
        if (mutable.value.writing) return
        mutable.value = mutable.value.copy(tab = tab, error = null, notice = null); refresh()
    }
    private fun query(query: JsonObject) {
        if (mutable.value.writing) return
        ++serial; read?.cancel()
        mutable.value = mutable.value.copy(query = query, focus = null, facets = null, showFocusTags = false, loading = false, notice = null)
    }
    private fun focus(append: Boolean) {
        val current = mutable.value
        read { captured ->
            val page = client.focus(captured, current.query, if (append) current.focus?.items?.size ?: 0 else 0,
                current.focus?.token.takeIf { append }).getOrThrow()
            mutable.value = mutable.value.copy(focus = if (append) page.copy(items = current.focus!!.items + page.items) else page)
        }
    }
    private fun facet(facet: String, append: Boolean) {
        val current = mutable.value
        read { captured ->
            val page = client.facets(captured, current.query, facet, current.focus?.token ?: current.facets?.token,
                if (append) current.facets?.items?.size ?: 0 else 0).getOrThrow()
            mutable.value = mutable.value.copy(facets = if (append) page.copy(items = current.facets!!.items + page.items) else page)
        }
    }
    private fun saved(saved: DesktopSavedFocus) { query(saved.query); mutable.value = mutable.value.copy(selectedSaved = saved); focus(false) }
    private fun savedPages(append: Boolean) {
        val old = mutable.value.saved
        read { captured ->
            val page = client.savedFocus(captured, if (append) old?.items?.size ?: 0 else 0, old?.savedRevision.takeIf { append }).getOrThrow()
            mutable.value = mutable.value.copy(saved = if (append) page.copy(items = old!!.items + page.items) else page)
        }
    }
    private fun save(name: String) {
        val current = mutable.value; val revision = current.saved?.savedRevision ?: return
        write { captured -> mutable.value = mutable.value.copy(selectedSaved = client.saveFocus(captured, name, current.query, revision).getOrThrow().item) }
    }
    private fun updateSaved() {
        val current = mutable.value; val saved = current.selectedSaved ?: return
        write { captured -> mutable.value = mutable.value.copy(selectedSaved = client.updateFocus(captured, saved, current.query).getOrThrow().item) }
    }
    private fun renameSaved(saved: DesktopSavedFocus, name: String) = write { captured ->
        val result = client.renameFocus(captured, saved, name).getOrThrow()
        if (mutable.value.selectedSaved?.id == saved.id) mutable.value = mutable.value.copy(selectedSaved = result.item)
    }
    private fun deleteSaved(saved: DesktopSavedFocus) = write { captured ->
        client.deleteFocus(captured, saved).getOrThrow()
        if (mutable.value.selectedSaved?.id == saved.id) mutable.value = mutable.value.copy(selectedSaved = null)
    }
    private fun playFocus(source: String?) {
        val current = mutable.value; val page = current.focus ?: return
        if (current.loading || current.writing) return
        val captured = context ?: return
        playSelection(captured, buildJsonObject { put("kind", "focus"); put("input", buildJsonObject {
            put("query", current.query); put("token", page.token); source?.let { put("sourceId", it) }
        }) })
    }
    private fun tags(append: Boolean) {
        val old = mutable.value.tags
        read { captured ->
            val page = client.tags(captured, offset = if (append) old?.items?.size ?: 0 else 0, token = old?.token.takeIf { append }).getOrThrow()
            mutable.value = mutable.value.copy(tags = if (append) page.copy(items = old!!.items + page.items) else page)
        }
    }
    private fun tag(tag: DesktopTag?) {
        if (mutable.value.writing) return
        if (tag == null) { mutable.value = mutable.value.copy(tag = null); tags(false); return }
        read { captured -> mutable.value = mutable.value.copy(tag = client.tagMembers(captured, tag.id).getOrThrow()) }
    }
    private fun tagMembers(append: Boolean) {
        val old = mutable.value.tag ?: return
        read { captured ->
            val page = client.tagMembers(captured, old.tag.id, if (append) old.items.size else 0, old.token.takeIf { append }).getOrThrow()
            mutable.value = mutable.value.copy(tag = if (append) page.copy(items = old.items + page.items) else page)
        }
    }
    private fun createTag(name: String) {
        val revision = mutable.value.tags?.token?.get("tagsRevision")?.jsonPrimitive?.longOrNull ?: return
        write { client.createTag(it, name, revision).getOrThrow() }
    }
    private fun renameTag(tag: DesktopTag, name: String) = write { client.renameTag(it, tag, name).getOrThrow() }
    private fun deleteTag(tag: DesktopTag) = write {
        client.deleteTag(it, tag).getOrThrow()
        if (mutable.value.tag?.tag?.id == tag.id) mutable.value = mutable.value.copy(tag = null)
    }
    private fun removeTagMember(entity: String) {
        val tag = mutable.value.tag?.tag ?: return
        write { client.tagMember(it, tag, entity, false).getOrThrow() }
    }
    private fun playTag() {
        val tag = mutable.value.tag ?: return
        if (mutable.value.loading || mutable.value.writing) return
        val captured = context ?: return
        playSelection(captured, buildJsonObject { put("kind", "tag"); put("input", buildJsonObject { put("tagId", tag.tag.id); put("token", tag.token) }) })
    }
    private fun playlists(append: Boolean) {
        val old = mutable.value.playlists
        read { captured ->
            val page = client.playlists(captured, if (append) old?.items?.size ?: 0 else 0, old?.revision.takeIf { append }).getOrThrow()
            mutable.value = mutable.value.copy(playlists = if (append) page.copy(items = old!!.items + page.items) else page)
        }
    }
    private fun playlist(playlist: DesktopServerPlaylist?) {
        if (mutable.value.writing) return
        mutable.value = mutable.value.copy(playlist = playlist, members = null)
        if (playlist != null) members(false)
    }
    private fun members(append: Boolean) {
        val current = mutable.value; val selected = current.playlist ?: return; val revision = current.playlists?.revision ?: return
        read { captured ->
            val page = client.playlistMembers(captured, selected.id, revision, if (append) current.members?.items?.size ?: 0 else 0).getOrThrow()
            mutable.value = mutable.value.copy(members = if (append) page.copy(items = current.members!!.items + page.items) else page)
        }
    }
    private fun playlistEdit(edit: JsonObject) {
        val revision = mutable.value.playlists?.revision ?: return
        write { client.editPlaylist(it, revision, edit).getOrThrow()
            if (edit["kind"]?.jsonPrimitive?.content == "delete" && edit["id"]?.jsonPrimitive?.content == mutable.value.playlist?.id)
                mutable.value = mutable.value.copy(playlist = null, members = null)
        }
    }
    private fun addSources() {
        val current = mutable.value; val selected = current.playlist ?: return; val revision = current.playlists?.revision ?: return
        if (!current.loading && !current.writing) context?.let { addSelection(selected, revision, it) }
    }
    private fun playPlaylist() {
        val current = mutable.value; val selected = current.playlist ?: return; val revision = current.playlists?.revision ?: return
        read { captured ->
            val versions = queues.versions(captured).getOrThrow()
            playSelection(captured, buildJsonObject { put("kind", "playlist"); put("id", selected.id); put("revision", revision); put("versionsRevision", versions) })
        }
    }
}
