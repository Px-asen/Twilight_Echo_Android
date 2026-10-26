package com.twilightecho.android.coreclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Transport abstraction between `CoreClient` and the underlying `QuickJsRuntimeHost`.
 */
interface CoreTransport {
    val stateEvents: Flow<CoreEventMessage>
    suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope
    suspend fun cancelRequest(envelope: CoreCancelEnvelope)
}

/**
 * Client interface for interacting with the shared Music Core (Section 9.4 & 11.8 + Phase 2).
 * Handles:
 * - Optimistic concurrency (`expectedRevision`)
 * - `epoch` and `sequence` monotonic tracking
 * - Reconnect snapshot + sequence reconciliation so out-of-order/stale events never regress state
 * - Phase 2 local MediaStore sync, song search, favorites, and bookmarks
 */
interface CoreClient {
    val identity: CoreClientIdentity
    val snapshot: StateFlow<CoreSnapshot?>
    val queueWindow: StateFlow<CoreQueueWindowView>

    suspend fun reconcileState(): CoreResult<CoreSnapshot>
    suspend fun executeCommand(command: CoreTypedCommand): CoreResult<JsonObject>
    suspend fun querySnapshot(): CoreResult<CoreSnapshot>
    suspend fun queryQueueWindow(offset: Int = 0, limit: Int = 50): CoreResult<CoreQueueWindowView>

    suspend fun syncLocalTracks(tracks: List<CoreTrackView>): Result<CoreResponseEnvelope>
    suspend fun searchTracks(query: String): Result<CoreLibrarySearchPage>
    suspend fun toggleFavorite(trackId: String): Result<CoreResponseEnvelope>
    suspend fun addBookmark(trackId: String, positionSec: Double, label: String): Result<CoreResponseEnvelope>
    suspend fun removeBookmark(bookmarkId: String): Result<CoreResponseEnvelope>
}

class DefaultCoreClient(
    override val identity: CoreClientIdentity,
    private val transport: CoreTransport,
    private val scope: CoroutineScope,
    private val json: Json = DefaultJson
) : CoreClient {

    private val reconcileMutex = Mutex()
    private val requestCounter = AtomicLong(1L)

    private var currentEpoch: CoreEpoch = "epoch-boot"
    private var lastAppliedSequence: Long = -1L

    private val _snapshot = MutableStateFlow<CoreSnapshot?>(null)
    override val snapshot: StateFlow<CoreSnapshot?> = _snapshot.asStateFlow()

    private val _queueWindow = MutableStateFlow(CoreQueueWindowView())
    override val queueWindow: StateFlow<CoreQueueWindowView> = _queueWindow.asStateFlow()

    init {
        scope.launch {
            transport.stateEvents.collect { event ->
                onStateEventReceived(event)
            }
        }
    }

    /**
     * Reconciles client state on initial connection or after a `STALE_EPOCH` / `OUTCOME_UNKNOWN`
     * by fetching a versioned snapshot and fast-forwarding `currentEpoch` and `lastAppliedSequence`
     * (Section 9.4: "带版本的快照 + 从某个 Sequence 补事件").
     */
    override suspend fun reconcileState(): CoreResult<CoreSnapshot> {
        val result = querySnapshot()
        if (result is CoreResult.Success) {
            applySnapshotIfNewer(result.value, forceEpochReset = true)
            refreshQueueWindowForSnapshot(result.value)
        }
        return result
    }

    override suspend fun executeCommand(command: CoreTypedCommand): CoreResult<JsonObject> {
        val activeSnap = _snapshot.value
        val resolvedExpectedRevision = command.expectedRevision ?: activeSnap?.queue?.revision
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val commandId = "cmd-${UUID.randomUUID()}"

        val payload = encodeCommandPayload(command, resolvedExpectedRevision)
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = commandId,
            epoch = currentEpoch,
            expectedRevision = resolvedExpectedRevision,
            kind = "command",
            name = command.name,
            payload = payload
        )

        val response = transport.sendRequest(envelope)
        if (!response.ok) {
            val failure = response.error ?: CoreErrorView(
                code = CoreErrorCode.FAILED,
                message = "Unknown command failure for ${command.name}"
            )
            // On STALE_EPOCH or OUTCOME_UNKNOWN, reconcile state before surfacing failure (Section 9.4)
            if (failure.code == CoreErrorCode.STALE_EPOCH || failure.code == CoreErrorCode.OUTCOME_UNKNOWN) {
                reconcileState()
            }
            return CoreResult.Failure(failure, response.epoch)
        }

        val valueObj = (response.value as? JsonObject) ?: JsonObject(emptyMap())
        // If response includes an inline snapshot, apply it immediately with sequence monotonicity
        valueObj["snapshot"]?.let { snapshotElem ->
            runCatching {
                json.decodeFromJsonElement<CoreSnapshot>(snapshotElem)
            }.getOrNull()?.let { inlineSnapshot ->
                applySnapshotIfNewer(inlineSnapshot, forceEpochReset = false)
            }
        }
        return CoreResult.Success(valueObj, response.epoch)
    }

    override suspend fun querySnapshot(): CoreResult<CoreSnapshot> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            epoch = currentEpoch,
            kind = "query",
            name = CoreTypedQuery.GetSnapshot.name,
            payload = JsonObject(emptyMap())
        )
        val response = transport.sendRequest(envelope)
        if (!response.ok || response.value == null) {
            return CoreResult.Failure(
                error = response.error ?: CoreErrorView(CoreErrorCode.FAILED, "Failed to query CoreSnapshot"),
                epoch = response.epoch
            )
        }
        return runCatching {
            val snap = json.decodeFromJsonElement<CoreSnapshot>(response.value)
            applySnapshotIfNewer(snap, forceEpochReset = false)
            CoreResult.Success(snap, response.epoch)
        }.getOrElse { err ->
            CoreResult.Failure(
                error = CoreErrorView(CoreErrorCode.INVALID_REQUEST, "Invalid CoreSnapshot JSON: ${err.message}"),
                epoch = response.epoch
            )
        }
    }

    override suspend fun queryQueueWindow(offset: Int, limit: Int): CoreResult<CoreQueueWindowView> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val queueRef = _snapshot.value?.queue
        val payload = buildJsonObject {
            put("id", JsonPrimitive(queueRef?.id ?: "default"))
            put("revision", JsonPrimitive(queueRef?.revision ?: 0L))
            put("offset", JsonPrimitive(offset))
            put("limit", JsonPrimitive(limit))
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            epoch = currentEpoch,
            kind = "query",
            name = "playback.getQueueWindow",
            payload = payload
        )
        val response = transport.sendRequest(envelope)
        if (!response.ok || response.value == null) {
            return CoreResult.Failure(
                error = response.error ?: CoreErrorView(CoreErrorCode.FAILED, "Failed to query queue window"),
                epoch = response.epoch
            )
        }
        return runCatching {
            val window = json.decodeFromJsonElement<CoreQueueWindowView>(response.value)
            _queueWindow.value = window
            CoreResult.Success(window, response.epoch)
        }.getOrElse { err ->
            CoreResult.Failure(
                error = CoreErrorView(CoreErrorCode.INVALID_REQUEST, "Invalid QueueWindow JSON: ${err.message}"),
                epoch = response.epoch
            )
        }
    }

    override suspend fun syncLocalTracks(tracks: List<CoreTrackView>): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put(
                "tracks",
                buildJsonArray {
                    tracks.forEach { add(json.encodeToJsonElement(it)) }
                }
            )
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "library.syncTracks",
            payload = payload
        )
        return sendEnvelopeAndExtractResponse(envelope)
    }

    override suspend fun searchTracks(query: String): Result<CoreLibrarySearchPage> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("query", JsonPrimitive(query))
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            epoch = currentEpoch,
            kind = "query",
            name = "search.songs",
            payload = payload
        )
        val response = transport.sendRequest(envelope)
        if (!response.ok || response.value == null) {
            val msg = response.error?.message ?: "Failed to execute search.songs"
            return Result.failure(IllegalStateException(msg))
        }
        return runCatching {
            json.decodeFromJsonElement<CoreLibrarySearchPage>(response.value)
        }
    }

    override suspend fun toggleFavorite(trackId: String): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("trackId", JsonPrimitive(trackId))
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "playlists.toggleFavorite",
            payload = payload
        )
        return sendEnvelopeAndExtractResponse(envelope)
    }

    override suspend fun addBookmark(
        trackId: String,
        positionSec: Double,
        label: String
    ): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("trackId", JsonPrimitive(trackId))
            put("positionSec", JsonPrimitive(positionSec))
            put("label", JsonPrimitive(label))
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "bookmarks.add",
            payload = payload
        )
        return sendEnvelopeAndExtractResponse(envelope)
    }

    override suspend fun removeBookmark(bookmarkId: String): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("bookmarkId", JsonPrimitive(bookmarkId))
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "bookmarks.remove",
            payload = payload
        )
        return sendEnvelopeAndExtractResponse(envelope)
    }

    private suspend fun sendEnvelopeAndExtractResponse(
        envelope: CoreRequestEnvelope
    ): Result<CoreResponseEnvelope> {
        val response = transport.sendRequest(envelope)
        if (!response.ok) {
            val msg = response.error?.message ?: "Command ${envelope.name} failed"
            return Result.failure(IllegalStateException(msg))
        }
        (response.value as? JsonObject)?.get("snapshot")?.let { snapshotElem ->
            runCatching {
                json.decodeFromJsonElement<CoreSnapshot>(snapshotElem)
            }.getOrNull()?.let { inlineSnapshot ->
                applySnapshotIfNewer(inlineSnapshot, forceEpochReset = false)
            }
        }
        return Result.success(response)
    }

    private suspend fun onStateEventReceived(event: CoreEventMessage) {
        val previousQueueRev = _snapshot.value?.queue?.revision
        val applied = applySnapshotIfNewer(event.snapshot, forceEpochReset = false)
        if (applied && event.snapshot.queue?.revision != previousQueueRev) {
            refreshQueueWindowForSnapshot(event.snapshot)
        }
    }

    private suspend fun applySnapshotIfNewer(
        incoming: CoreSnapshot,
        forceEpochReset: Boolean
    ): Boolean = reconcileMutex.withLock {
        val epochChanged = incoming.epoch != currentEpoch
        if (forceEpochReset || epochChanged || lastAppliedSequence < 0L) {
            currentEpoch = incoming.epoch
            lastAppliedSequence = incoming.sequence
            _snapshot.value = incoming
            return@withLock true
        }
        if (incoming.sequence > lastAppliedSequence) {
            lastAppliedSequence = incoming.sequence
            _snapshot.value = incoming
            return@withLock true
        }
        false
    }

    private suspend fun refreshQueueWindowForSnapshot(snapshot: CoreSnapshot) {
        if (snapshot.queue != null) {
            queryQueueWindow(offset = 0, limit = 50)
        }
    }

    private fun encodeCommandPayload(
        command: CoreTypedCommand,
        resolvedRevision: Long?
    ): JsonObject = when (command) {
        is CoreTypedCommand.Play -> buildJsonObject {
            command.position?.let { put("position", JsonPrimitive(it)) }
        }
        is CoreTypedCommand.Pause -> JsonObject(emptyMap())
        is CoreTypedCommand.Stop -> JsonObject(emptyMap())
        is CoreTypedCommand.Seek -> buildJsonObject {
            put("position", JsonPrimitive(command.position))
        }
        is CoreTypedCommand.Next -> JsonObject(emptyMap())
        is CoreTypedCommand.Previous -> JsonObject(emptyMap())
        is CoreTypedCommand.SetVolume -> buildJsonObject {
            put("volume", JsonPrimitive(command.volume))
        }
        is CoreTypedCommand.SetPlayMode -> buildJsonObject {
            put("mode", JsonPrimitive(command.mode.toWireValue()))
        }
        is CoreTypedCommand.SelectQueueIndex -> buildJsonObject {
            put("id", JsonPrimitive(command.id))
            put("revision", JsonPrimitive(resolvedRevision ?: command.revision))
            put("index", JsonPrimitive(command.index))
            put("occurrenceId", JsonPrimitive(command.occurrenceId))
        }
        is CoreTypedCommand.ReplaceQueue -> buildJsonObject {
            put("id", JsonPrimitive(command.id))
            resolvedRevision?.let { put("expectedRevision", JsonPrimitive(it)) }
            put("startIndex", JsonPrimitive(command.startIndex))
            put("play", JsonPrimitive(command.play))
            put(
                "tracks",
                buildJsonArray {
                    command.tracks.forEach { add(json.encodeToJsonElement(it)) }
                }
            )
        }
        is CoreTypedCommand.SettingsEdit -> buildJsonObject {
            resolvedRevision?.let { put("expectedRevision", JsonPrimitive(it)) }
            put("key", JsonPrimitive(command.key))
            put("valueJson", JsonPrimitive(command.valueJson))
        }
        is CoreTypedCommand.PlatformInterruption -> buildJsonObject {
            put("reason", JsonPrimitive(command.reason))
            put("shouldPause", JsonPrimitive(command.shouldPause))
            put("transientCanResume", JsonPrimitive(command.transientCanResume))
        }
        is CoreTypedCommand.SyncLocalTracks -> buildJsonObject {
            put(
                "tracks",
                buildJsonArray {
                    command.tracks.forEach { add(json.encodeToJsonElement(it)) }
                }
            )
        }
        is CoreTypedCommand.ToggleFavorite -> buildJsonObject {
            put("trackId", JsonPrimitive(command.trackId))
        }
        is CoreTypedCommand.AddBookmark -> buildJsonObject {
            put("trackId", JsonPrimitive(command.trackId))
            put("positionSec", JsonPrimitive(command.positionSec))
            put("label", JsonPrimitive(command.label))
        }
        is CoreTypedCommand.RemoveBookmark -> buildJsonObject {
            put("bookmarkId", JsonPrimitive(command.bookmarkId))
        }
    }

    companion object {
        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
