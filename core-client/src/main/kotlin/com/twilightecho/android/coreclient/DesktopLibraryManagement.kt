package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

/** Captured with a library view. A later computer switch or Core restart invalidates its edits. */
data class DesktopLibraryScope(val generation: Long, val epoch: String)

@Serializable data class DesktopRatingValue(val steps: Int? = null, val revision: Long, val updatedAt: Long)
@Serializable data class DesktopRatingDetail(val entityId: String, val kind: String, val scope: String,
    val value: DesktopRatingValue? = null, val token: JsonObject)
@Serializable data class DesktopPreferenceValue(val state: String, val revision: Long, val updatedAt: Long)
@Serializable data class DesktopPreferenceDetail(val entityId: String, val kind: String, val scope: String,
    val value: DesktopPreferenceValue? = null, val token: JsonObject)
@Serializable data class DesktopTag(val id: String, val name: String, val nameRevision: Long,
    val membersRevision: Long, val memberCount: Int, val updatedAt: Long)
@Serializable data class DesktopTagsPage(val token: JsonObject, val offset: Int, val total: Int,
    val items: List<DesktopTag> = emptyList())
@Serializable data class DesktopTagWrite(val token: JsonObject, val tag: DesktopTag)
@Serializable data class DesktopTagDelete(val token: JsonObject, val deletedId: String)
@Serializable data class DesktopEntityPage(val offset: Int, val total: Int, val items: List<CatalogEntity> = emptyList())
@Serializable data class DesktopTagMembers(val token: JsonObject, val tag: DesktopTag, val offset: Int, val total: Int,
    val items: List<CatalogEntity> = emptyList())
@Serializable data class DesktopFocusItem(val id: String, val kind: String, val title: String, val artist: String,
    val sourceCount: Int, val inLibrary: Boolean, val playable: Boolean, val sourceId: String? = null,
    val provider: String? = null, val personalRating: Double? = null, val releaseYear: Int? = null, val lastPlayed: Long? = null)
@Serializable data class DesktopFocusPage(val token: JsonObject, val offset: Int, val total: Int,
    val items: List<DesktopFocusItem> = emptyList())
@Serializable data class DesktopFocusFacet(val value: String, val label: String, val count: Int)
@Serializable data class DesktopFocusFacets(val token: JsonObject, val facet: String, val offset: Int, val total: Int,
    val items: List<DesktopFocusFacet> = emptyList())
@Serializable data class DesktopSavedFocus(val id: String, val name: String, val query: JsonObject,
    val nameRevision: Long, val queryRevision: Long, val updatedAt: Long)
@Serializable data class DesktopSavedFocusPage(val savedRevision: Long, val offset: Int, val total: Int,
    val items: List<DesktopSavedFocus> = emptyList())
@Serializable data class DesktopSavedFocusWrite(val savedRevision: Long, val item: DesktopSavedFocus)
@Serializable data class DesktopSavedFocusDelete(val savedRevision: Long, val deletedId: String)
@Serializable data class DesktopFocusPlayResult(val total: Int, val selectedIndex: Int)
@Serializable data class DesktopFocusPlayStatus(val status: String, val result: DesktopFocusPlayResult? = null, val playback: String? = null)
/** Retain this ID if the reply is lost; inspect its receipt, never resend play automatically. */
data class DesktopFocusPlay(val scope: DesktopLibraryScope, val query: JsonObject, val token: JsonObject,
    val sourceId: String? = null, val requestId: String = "focus-${UUID.randomUUID()}")
@Serializable data class DesktopServerPlaylist(val id: String, val name: String, val count: Int,
    val kind: String? = null, val isDefault: Boolean = false, val imported: Boolean = false,
    val pinnedAt: String? = null, val cover: String? = null)
@Serializable data class DesktopServerPlaylists(val revision: Long, val offset: Int, val total: Int,
    val items: List<DesktopServerPlaylist> = emptyList())
@Serializable data class DesktopServerPlaylistMember(val id: String, val position: Int,
    val track: CoreTrackView? = null, val imported: Boolean = false)
@Serializable data class DesktopServerPlaylistMembers(val revision: Long, val offset: Int, val total: Int,
    val items: List<DesktopServerPlaylistMember> = emptyList())
@Serializable data class DesktopPlaylistMutation(val revision: Long, val id: String? = null)

/** Library routing is independent of playback routing: edits always go back to
 * the computer whose view supplied the scope and CAS tokens, never the phone Core. */
class DesktopLibraryManagementClient(private val transport: CoreTransport, private val epoch: () -> String) {
    private val json = DefaultCoreClient.DefaultJson
    fun scope(): DesktopLibraryScope {
        check(transport.isRemote) { "请先选择已连接的电脑曲库" }
        return DesktopLibraryScope(transport.generation, epoch())
    }
    private fun checkScope(scope: DesktopLibraryScope) {
        check(transport.isRemote && transport.generation == scope.generation && epoch() == scope.epoch) {
            "电脑曲库或 Core 已改变，请重新载入后操作"
        }
    }
    private suspend inline fun <reified T> call(scope: DesktopLibraryScope, name: String, payload: JsonObject,
        command: Boolean = false, requestId: String = "library-${UUID.randomUUID()}"): Result<T> = try {
        checkScope(scope)
        val response = transport.sendRequest(CoreRequestEnvelope(requestId = requestId,
            commandId = requestId.takeIf { command }, epoch = scope.epoch,
            kind = if (command) "command" else "query", name = name, payload = payload))
        currentCoroutineContext().ensureActive()
        checkScope(scope)
        if (!response.ok) throw CoreRequestException(response.error ?: CoreErrorView(CoreErrorCode.FAILED, "电脑曲库操作失败"))
        check(response.epoch == scope.epoch) { "Core 已重启，请重新载入" }
        val value = requireNotNull(response.value) { "电脑未返回曲库结果" }
        val result = if (command) requireNotNull(value.jsonObject["result"]) { "电脑未返回操作回执" } else value
        Result.success(json.decodeFromJsonElement<T>(result))
    } catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) { Result.failure(error) }

    suspend fun rating(scope: DesktopLibraryScope, entityId: String): Result<DesktopRatingDetail> =
        call(scope, "ratings.detail", buildJsonObject { put("entityId", entityId) })
    suspend fun metadata(scope: DesktopLibraryScope, entityId: String): Result<EntityMetadataDetail> =
        call(scope, "metadata.entity.detail", buildJsonObject { put("entityId", entityId); put("language", "zh-Hans") })
    suspend fun rate(scope: DesktopLibraryScope, detail: DesktopRatingDetail, steps: Int?): Result<DesktopRatingDetail> =
        call(scope, "ratings.set", buildJsonObject { put("entityId", detail.entityId); put("token", detail.token); put("steps", steps?.let(::JsonPrimitive) ?: JsonNull) }, true)
    suspend fun preference(scope: DesktopLibraryScope, entityId: String): Result<DesktopPreferenceDetail> =
        call(scope, "preferences.detail", buildJsonObject { put("entityId", entityId) })
    suspend fun prefer(scope: DesktopLibraryScope, detail: DesktopPreferenceDetail, state: String): Result<DesktopPreferenceDetail> =
        call(scope, "preferences.set", buildJsonObject { put("entityId", detail.entityId); put("token", detail.token); put("state", state) }, true)
    suspend fun targets(scope: DesktopLibraryScope, entityId: String, rating: Boolean, offset: Int = 0): Result<DesktopEntityPage> =
        call(scope, if (rating) "ratings.targets" else "preferences.targets", buildJsonObject { put("entityId", entityId); put("offset", offset); put("limit", 100) })
    suspend fun tags(scope: DesktopLibraryScope, entityId: String? = null, offset: Int = 0, token: JsonObject? = null): Result<DesktopTagsPage> =
        call(scope, if (entityId == null) "tags.list" else "tags.entity", buildJsonObject {
            entityId?.let { put("entityId", it) }; put("offset", offset); put("limit", 100); token?.let { put("token", it) }
        })
    suspend fun createTag(scope: DesktopLibraryScope, name: String, tagsRevision: Long): Result<DesktopTagWrite> =
        call(scope, "tags.create", buildJsonObject { put("name", name); put("tagsRevision", tagsRevision) }, true)
    suspend fun renameTag(scope: DesktopLibraryScope, tag: DesktopTag, name: String): Result<DesktopTagWrite> =
        call(scope, "tags.rename", buildJsonObject { put("tagId", tag.id); put("name", name); put("nameRevision", tag.nameRevision) }, true)
    suspend fun deleteTag(scope: DesktopLibraryScope, tag: DesktopTag): Result<DesktopTagDelete> =
        call(scope, "tags.delete", buildJsonObject { put("tagId", tag.id); put("nameRevision", tag.nameRevision); put("membersRevision", tag.membersRevision) }, true)
    suspend fun tagMember(scope: DesktopLibraryScope, tag: DesktopTag, entityId: String, present: Boolean): Result<DesktopTagWrite> =
        call(scope, "tags.member.set", buildJsonObject { put("tagId", tag.id); put("entityId", entityId); put("present", present); put("membersRevision", tag.membersRevision) }, true)
    suspend fun tagMembers(scope: DesktopLibraryScope, tagId: String, offset: Int = 0, token: JsonObject? = null): Result<DesktopTagMembers> =
        call(scope, "tags.members", buildJsonObject { put("tagId", tagId); put("offset", offset); put("limit", 100); token?.let { put("token", it) } })
    suspend fun focus(scope: DesktopLibraryScope, query: JsonObject, offset: Int = 0, token: JsonObject? = null): Result<DesktopFocusPage> =
        call(scope, "focus.query", buildJsonObject { put("query", query); put("offset", offset); put("limit", 100); token?.let { put("token", it) } })
    suspend fun facets(scope: DesktopLibraryScope, query: JsonObject, facet: String, token: JsonObject? = null, offset: Int = 0): Result<DesktopFocusFacets> =
        call(scope, "focus.facets", buildJsonObject { put("query", query); put("facet", facet); put("offset", offset); put("limit", 100); token?.let { put("token", it) } })
    suspend fun savedFocus(scope: DesktopLibraryScope, offset: Int = 0, savedRevision: Long? = null): Result<DesktopSavedFocusPage> =
        call(scope, "focus.saved.list", buildJsonObject { put("offset", offset); put("limit", 100); savedRevision?.let { put("savedRevision", it) } })
    suspend fun saveFocus(scope: DesktopLibraryScope, name: String, query: JsonObject, savedRevision: Long): Result<DesktopSavedFocusWrite> =
        call(scope, "focus.saved.create", buildJsonObject { put("name", name); put("query", query); put("savedRevision", savedRevision) }, true)
    suspend fun renameFocus(scope: DesktopLibraryScope, saved: DesktopSavedFocus, name: String): Result<DesktopSavedFocusWrite> =
        call(scope, "focus.saved.rename", buildJsonObject { put("id", saved.id); put("name", name); put("nameRevision", saved.nameRevision) }, true)
    suspend fun updateFocus(scope: DesktopLibraryScope, saved: DesktopSavedFocus, query: JsonObject): Result<DesktopSavedFocusWrite> =
        call(scope, "focus.saved.update", buildJsonObject { put("id", saved.id); put("query", query); put("queryRevision", saved.queryRevision) }, true)
    suspend fun deleteFocus(scope: DesktopLibraryScope, saved: DesktopSavedFocus): Result<DesktopSavedFocusDelete> =
        call(scope, "focus.saved.delete", buildJsonObject { put("id", saved.id); put("nameRevision", saved.nameRevision); put("queryRevision", saved.queryRevision) }, true)
    suspend fun playFocus(command: DesktopFocusPlay): Result<DesktopFocusPlayResult> = call(command.scope, "focus.play", buildJsonObject {
        put("query", command.query); put("token", command.token); command.sourceId?.let { put("sourceId", it) }
    }, true, command.requestId)
    suspend fun focusPlayStatus(command: DesktopFocusPlay): Result<DesktopFocusPlayStatus> = call(command.scope, "focus.play.status", buildJsonObject {
        put("requestId", command.requestId); put("epoch", command.scope.epoch)
    })
    suspend fun playlists(scope: DesktopLibraryScope, offset: Int = 0, revision: Long? = null): Result<DesktopServerPlaylists> =
        call(scope, "playlists.catalog", buildJsonObject { put("offset", offset); put("limit", 100); revision?.let { put("revision", it) } })
    suspend fun playlistMembers(scope: DesktopLibraryScope, id: String, revision: Long, offset: Int = 0): Result<DesktopServerPlaylistMembers> =
        call(scope, "playlists.members", buildJsonObject { put("id", id); put("revision", revision); put("offset", offset); put("limit", 100) })
    suspend fun editPlaylist(scope: DesktopLibraryScope, revision: Long, edit: JsonObject): Result<DesktopPlaylistMutation> =
        call(scope, "playlists.edit", buildJsonObject { put("revision", revision); put("edit", edit) }, true)
    suspend fun addSources(scope: DesktopLibraryScope, id: String, revision: Long, catalogRevision: Long, sources: List<String>): Result<DesktopPlaylistMutation> =
        call(scope, "playlists.addSources", buildJsonObject { put("id", id); put("revision", revision); put("catalogRevision", catalogRevision)
            put("sourceIds", buildJsonArray { sources.forEach { add(JsonPrimitive(it)) } }) }, true)
    suspend fun introduction(scope: DesktopLibraryScope, detail: EntityMetadataDetail, mode: String, value: String? = null): Result<JsonObject> =
        call(scope, "metadata.entity.override", buildJsonObject { put("entityId", detail.entityId); put("revision", detail.revision); put("language", "zh-Hans"); put("mode", mode); value?.let { put("value", it) } }, true)
    suspend fun artwork(scope: DesktopLibraryScope, detail: EntityMetadataDetail, mode: String, dataUrl: String? = null): Result<JsonObject> =
        call(scope, "metadata.entity.artworkOverride", buildJsonObject { put("entityId", detail.entityId); put("revision", detail.revision); put("language", "zh-Hans"); put("mode", mode); dataUrl?.let { put("dataUrl", it) } }, true)
    suspend fun metadataSource(scope: DesktopLibraryScope, detail: EntityMetadataDetail, provider: String?): Result<JsonObject> =
        call(scope, "metadata.entity.sourcePreference", buildJsonObject { put("entityId", detail.entityId); put("revision", detail.revision); put("language", "zh-Hans"); put("provider", provider?.let(::JsonPrimitive) ?: JsonNull) }, true)
}
