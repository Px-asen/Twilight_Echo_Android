package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable data class DesktopQueueTarget(val kind: String, val id: String, val revision: Long)
data class DesktopQueueDestination(val target: DesktopQueueTarget, val name: String, val queue: DesktopZoneQueue?)
@Serializable data class DesktopSessionQueueItem(val position: Int, val queueEntryId: String, val track: CoreTrackView? = null)
@Serializable data class DesktopSessionQueuePage(val revision: Long, val total: Int, val offset: Int,
    val items: List<DesktopSessionQueueItem> = emptyList())

/** Explicit session operations. The supplied transport owns BOTH the source catalog and destination;
 * this never redirects the main player, nor borrows a different computer's catalog references. */
class DesktopSessionQueueClient(private val transport: CoreTransport, private val epoch: () -> String) {
    private val json = DefaultCoreClient.DefaultJson
    fun scope(): DesktopLibraryScope {
        check(transport.isRemote) { "请先连接电脑" }
        return DesktopLibraryScope(transport.generation, epoch())
    }
    fun isCurrent(scope: DesktopLibraryScope) = transport.isRemote && transport.generation == scope.generation && epoch() == scope.epoch
    private suspend inline fun <reified T> call(scope: DesktopLibraryScope, name: String, payload: JsonObject,
        command: Boolean = false): Result<T> = try {
        check(isCurrent(scope)) { "电脑或 Core 已改变，请重新打开" }
        val id = "session-${UUID.randomUUID()}"
        val response = transport.sendRequest(CoreRequestEnvelope(requestId = id, commandId = id.takeIf { command },
            epoch = scope.epoch, kind = if (command) "command" else "query", name = name, payload = payload))
        currentCoroutineContext().ensureActive()
        check(isCurrent(scope) && response.epoch == scope.epoch) { "电脑或 Core 已改变，请重新打开" }
        if (!response.ok) throw CoreRequestException(response.error ?: CoreErrorView(CoreErrorCode.FAILED, "电脑操作失败"))
        Result.success(json.decodeFromJsonElement<T>(requireNotNull(response.value)))
    } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { Result.failure(error) }

    suspend fun destinations(scope: DesktopLibraryScope): Result<List<DesktopQueueDestination>> = try {
        val zones = call<DesktopZoneSessions>(scope, "zones.list", buildJsonObject {}).getOrThrow()
        val groups = call<DesktopSyncGroups>(scope, "groups.list", buildJsonObject {}).getOrThrow()
        zones.error?.let { error(it) }; groups.error?.let { error(it) }
        Result.success(zones.zones.filter { it.groupId == null }.map {
            DesktopQueueDestination(DesktopQueueTarget("zone", it.id, it.revision), it.name, it.queue)
        } + groups.groups.map { DesktopQueueDestination(DesktopQueueTarget("group", it.id, it.revision), it.name, it.queue) })
    } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { Result.failure(error) }

    suspend fun page(scope: DesktopLibraryScope, destination: DesktopQueueDestination, offset: Int = 0): Result<DesktopSessionQueuePage> {
        val queue = destination.queue ?: return Result.success(DesktopSessionQueuePage(0, 0, 0))
        return call(scope, "zones.queue.page", buildJsonObject {
            put("target", json.encodeToJsonElement(destination.target)); put("queue", key(queue)); put("offset", offset); put("limit", 100)
        })
    }
    suspend fun load(scope: DesktopLibraryScope, destination: DesktopQueueDestination, selection: JsonObject, play: Boolean): Result<Unit> =
        call<JsonObject>(scope, "zones.queue.load", buildJsonObject {
            put("target", json.encodeToJsonElement(destination.target))
            put("queue", destination.queue?.let { queue -> buildJsonObject {
                put("id", queue.id); put("revision", queue.revision); put("occurrenceId", queue.occurrenceId?.let(::JsonPrimitive) ?: JsonNull)
            } } ?: JsonNull)
            put("selection", selection); put("play", play)
        }, true).map { }
    suspend fun edit(scope: DesktopLibraryScope, destination: DesktopQueueDestination, edit: JsonObject): Result<Unit> =
        mutate(scope, destination, "zones.queue.edit") { put("edit", edit) }
    suspend fun select(scope: DesktopLibraryScope, destination: DesktopQueueDestination, item: DesktopSessionQueueItem): Result<Unit> =
        mutate(scope, destination, "zones.queue.select") { put("index", item.position); put("occurrenceId", item.queueEntryId) }
    private suspend fun mutate(scope: DesktopLibraryScope, destination: DesktopQueueDestination, name: String,
        fields: JsonObjectBuilder.() -> Unit): Result<Unit> {
        val queue = destination.queue ?: return Result.failure(IllegalStateException("队列为空，请先选歌"))
        return call<JsonObject>(scope, name, buildJsonObject {
            put("target", json.encodeToJsonElement(destination.target)); put("queue", key(queue)); fields()
        }, true).map { }
    }
    private fun key(queue: DesktopZoneQueue) = buildJsonObject { put("id", queue.id); put("revision", queue.revision) }
    suspend fun catalog(scope: DesktopLibraryScope, search: String, offset: Int = 0, revision: Long? = null): Result<CatalogPage> =
        call(scope, "catalog.query", buildJsonObject {
            put("kind", "recording"); put("scope", "library"); put("search", search); put("offset", offset); put("limit", 100)
            revision?.let { put("revision", it) }
        })
    suspend fun sources(scope: DesktopLibraryScope, entity: String, revision: Long, offset: Int = 0): Result<CatalogSourcePage> =
        call(scope, "catalog.sources", buildJsonObject { put("entityId", entity); put("revision", revision); put("offset", offset); put("limit", 100) })
    suspend fun versions(scope: DesktopLibraryScope): Result<Long> = call<JsonObject>(scope, "core.getSnapshot", buildJsonObject {})
        .mapCatching { requireNotNull(it["versionsRevision"]?.jsonPrimitive?.longOrNull) { "电脑未提供版本状态" } }
}

fun catalogQueueSelection(revision: Long, sourceIds: List<String>, selectedIndex: Int = 0) = buildJsonObject {
    put("kind", "catalog"); put("revision", revision); put("sourceIds", buildJsonArray { sourceIds.forEach { add(it) } }); put("selectedIndex", selectedIndex)
}
