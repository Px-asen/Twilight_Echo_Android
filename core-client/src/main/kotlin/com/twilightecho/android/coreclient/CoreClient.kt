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
    val generation: Long get() = 0L
    val isRemote: Boolean get() = false
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

    /** Replaces the library membership, or with [merge] adds/updates rows and keeps the rest. */
    suspend fun syncLocalTracks(tracks: List<CoreTrackView>, merge: Boolean = false): Result<CoreResponseEnvelope>
    suspend fun searchTracks(query: String): Result<CoreLibrarySearchPage>
    suspend fun searchTracksPage(query: String, offset: Int, limit: Int): Result<CoreLibrarySearchPage> = searchTracks(query)
    suspend fun toggleFavorite(trackId: String): Result<CoreResponseEnvelope>
    suspend fun addBookmark(trackId: String, positionSec: Double, label: String): Result<CoreResponseEnvelope>
    suspend fun removeBookmark(bookmarkId: String): Result<CoreResponseEnvelope>

    // Phase 3: Custom Playlists, Album/Artist Collections, and EQ/DSP Configuration
    suspend fun queryCollections(): Result<CoreCollectionsView>
    suspend fun listPlaylists(): Result<List<CorePlaylistView>>
    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): Result<CoreResponseEnvelope>
    suspend fun deletePlaylist(playlistId: String): Result<CoreResponseEnvelope>
    suspend fun renamePlaylist(playlistId: String, name: String): Result<CoreResponseEnvelope>
    suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>): Result<CoreResponseEnvelope>
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String): Result<CoreResponseEnvelope>
    suspend fun setEqConfig(config: CoreEqConfigView): Result<CoreResponseEnvelope>
    suspend fun getEqConfig(): Result<CoreEqConfigView>
    suspend fun fetchOnlineLyrics(trackId: String? = null, customUrl: String? = null): Result<CoreLyricsView>
    suspend fun importLrcLyrics(trackId: String, lrcText: String): Result<CoreResponseEnvelope>
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
    private var sourceGeneration = transport.generation

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
            refreshQueueWindowForSnapshot(_snapshot.value ?: result.value)
        }
        return result
    }

    override suspend fun executeCommand(command: CoreTypedCommand): CoreResult<JsonObject> {
        resetChangedSource()
        val origin = transport.generation
        val activeSnap = _snapshot.value
        val resolvedExpectedRevision = command.expectedRevision ?: activeSnap?.queue?.revision
            ?: if (transport.isRemote) 0L else null
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
            if (failure.code in listOf(CoreErrorCode.STALE_EPOCH, CoreErrorCode.STALE_REVISION, CoreErrorCode.OUTCOME_UNKNOWN)) {
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
                val before = _snapshot.value
                val applied = applySnapshotIfNewer(inlineSnapshot, forceEpochReset = false, expectedGeneration = origin)
                if (applied && (before?.epoch != inlineSnapshot.epoch || before.queue != inlineSnapshot.queue))
                    refreshQueueWindowForSnapshot(inlineSnapshot)
            }
        }
        return CoreResult.Success(valueObj, response.epoch)
    }

    override suspend fun querySnapshot(): CoreResult<CoreSnapshot> {
        resetChangedSource()
        val origin = transport.generation
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
            applySnapshotIfNewer(snap, forceEpochReset = true, expectedGeneration = origin)
            CoreResult.Success(snap, response.epoch)
        }.getOrElse { err ->
            CoreResult.Failure(
                error = CoreErrorView(CoreErrorCode.INVALID_REQUEST, "Invalid CoreSnapshot JSON: ${err.message}"),
                epoch = response.epoch
            )
        }
    }

    override suspend fun queryQueueWindow(offset: Int, limit: Int): CoreResult<CoreQueueWindowView> {
        resetChangedSource()
        val origin = transport.generation
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
            if (origin != transport.generation) return@runCatching CoreResult.Failure(
                CoreErrorView(CoreErrorCode.CANCELLED, "播放来源已切换，请重新选择"), response.epoch)
            val current = _snapshot.value
            if (current?.epoch != response.epoch || current.queue?.id != window.queueId || current.queue.revision != window.revision) {
                if (current?.queue != null) return@runCatching CoreResult.Failure(
                    CoreErrorView(CoreErrorCode.STALE_REVISION, "队列已变化，请重新选择"), response.epoch)
            }
            _queueWindow.value = window
            CoreResult.Success(window, response.epoch)
        }.getOrElse { err ->
            CoreResult.Failure(
                error = CoreErrorView(CoreErrorCode.INVALID_REQUEST, "Invalid QueueWindow JSON: ${err.message}"),
                epoch = response.epoch
            )
        }
    }

    override suspend fun syncLocalTracks(tracks: List<CoreTrackView>, merge: Boolean): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put(
                "tracks",
                buildJsonArray {
                    tracks.forEach { add(json.encodeToJsonElement(it)) }
                }
            )
            if (merge) put("merge", JsonPrimitive(true))
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
        return searchTracksInternal(query, null, null)
    }

    override suspend fun searchTracksPage(query: String, offset: Int, limit: Int): Result<CoreLibrarySearchPage> =
        searchTracksInternal(query, offset, limit)

    private suspend fun searchTracksInternal(query: String, offset: Int?, limit: Int?): Result<CoreLibrarySearchPage> {
        resetChangedSource()
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("query", JsonPrimitive(query))
            offset?.let { put("offset", JsonPrimitive(it)) }
            limit?.let { put("limit", JsonPrimitive(it)) }
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
            return Result.failure(CoreRequestException(response.error ?: CoreErrorView(CoreErrorCode.FAILED, msg)))
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

    override suspend fun queryCollections(): Result<CoreCollectionsView> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            epoch = currentEpoch,
            kind = "query",
            name = "library.collections",
            payload = JsonObject(emptyMap())
        )
        val response = transport.sendRequest(envelope)
        if (!response.ok || response.value == null) {
            val msg = response.error?.message ?: "Failed to execute library.collections"
            return Result.failure(IllegalStateException(msg))
        }
        return runCatching {
            json.decodeFromJsonElement<CoreCollectionsView>(response.value)
        }
    }

    override suspend fun listPlaylists(): Result<List<CorePlaylistView>> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            epoch = currentEpoch,
            kind = "query",
            name = "playlists.list",
            payload = JsonObject(emptyMap())
        )
        val response = transport.sendRequest(envelope)
        if (!response.ok || response.value == null) {
            val msg = response.error?.message ?: "Failed to execute playlists.list"
            return Result.failure(IllegalStateException(msg))
        }
        return runCatching {
            json.decodeFromJsonElement<List<CorePlaylistView>>(response.value)
        }
    }

    override suspend fun createPlaylist(
        name: String,
        trackIds: List<String>
    ): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("name", JsonPrimitive(name))
            put(
                "trackIds",
                buildJsonArray {
                    trackIds.forEach { add(JsonPrimitive(it)) }
                }
            )
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "playlists.create",
            payload = payload
        )
        return sendEnvelopeAndExtractResponse(envelope)
    }

    override suspend fun deletePlaylist(playlistId: String): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("playlistId", JsonPrimitive(playlistId))
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "playlists.delete",
            payload = payload
        )
        return sendEnvelopeAndExtractResponse(envelope)
    }

    override suspend fun renamePlaylist(
        playlistId: String,
        name: String
    ): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("playlistId", JsonPrimitive(playlistId))
            put("name", JsonPrimitive(name))
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "playlists.rename",
            payload = payload
        )
        return sendEnvelopeAndExtractResponse(envelope)
    }

    override suspend fun addTracksToPlaylist(
        playlistId: String,
        trackIds: List<String>
    ): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("playlistId", JsonPrimitive(playlistId))
            put(
                "trackIds",
                buildJsonArray {
                    trackIds.forEach { add(JsonPrimitive(it)) }
                }
            )
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "playlists.addTracks",
            payload = payload
        )
        return sendEnvelopeAndExtractResponse(envelope)
    }

    override suspend fun removeTrackFromPlaylist(
        playlistId: String,
        trackId: String
    ): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("playlistId", JsonPrimitive(playlistId))
            put("trackId", JsonPrimitive(trackId))
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "playlists.removeTrack",
            payload = payload
        )
        return sendEnvelopeAndExtractResponse(envelope)
    }

    override suspend fun setEqConfig(config: CoreEqConfigView): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("enabled", JsonPrimitive(config.enabled))
            put("preset", JsonPrimitive(config.preset))
            put("preampDb", JsonPrimitive(config.preampDb))
            put(
                "bandsDb",
                buildJsonArray {
                    config.bandsDb.forEach { add(JsonPrimitive(it)) }
                }
            )
            put("bitPerfect", JsonPrimitive(config.bitPerfect))
            put("gapless", JsonPrimitive(config.gapless))
            put("dsp", json.encodeToJsonElement(config.dsp))
            put("scenes", json.encodeToJsonElement(config.scenes))
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "audio.setEq",
            payload = payload
        )
        return sendEnvelopeAndExtractResponse(envelope)
    }

    override suspend fun getEqConfig(): Result<CoreEqConfigView> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            epoch = currentEpoch,
            kind = "query",
            name = "audio.getEq",
            payload = JsonObject(emptyMap())
        )
        val response = transport.sendRequest(envelope)
        if (!response.ok || response.value == null) {
            val msg = response.error?.message ?: "Failed to execute audio.getEq"
            return Result.failure(IllegalStateException(msg))
        }
        return runCatching {
            json.decodeFromJsonElement<CoreEqConfigView>(response.value)
        }
    }

    override suspend fun fetchOnlineLyrics(
        trackId: String?,
        customUrl: String?
    ): Result<CoreLyricsView> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            trackId?.let { put("trackId", JsonPrimitive(it)) }
            customUrl?.let { put("url", JsonPrimitive(it)) }
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            epoch = currentEpoch,
            kind = "query",
            name = "lyrics.fetchOnline",
            payload = payload
        )
        val response = transport.sendRequest(envelope)
        if (!response.ok || response.value == null) {
            val msg = response.error?.message ?: "Failed to execute lyrics.fetchOnline"
            return Result.failure(IllegalStateException(msg))
        }
        return runCatching {
            json.decodeFromJsonElement<CoreLyricsView>(response.value)
        }
    }

    override suspend fun importLrcLyrics(
        trackId: String,
        lrcText: String
    ): Result<CoreResponseEnvelope> {
        val requestId = "req-${requestCounter.getAndIncrement()}"
        val payload = buildJsonObject {
            put("trackId", JsonPrimitive(trackId))
            put("lrcText", JsonPrimitive(lrcText))
        }
        val envelope = CoreRequestEnvelope(
            requestId = requestId,
            commandId = "cmd-${UUID.randomUUID()}",
            epoch = currentEpoch,
            kind = "command",
            name = "lyrics.importLrc",
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
        val previous = _snapshot.value
        val applied = applySnapshotIfNewer(event.snapshot, forceEpochReset = false,
            expectedGeneration = event.transportGeneration)
        if (applied && (event.snapshot.epoch != previous?.epoch || event.snapshot.queue != previous.queue)) {
            refreshQueueWindowForSnapshot(event.snapshot)
        }
    }

    private suspend fun applySnapshotIfNewer(
        incoming: CoreSnapshot,
        forceEpochReset: Boolean,
        expectedGeneration: Long? = null
    ): Boolean = reconcileMutex.withLock {
        if (expectedGeneration != null && expectedGeneration != transport.generation) return@withLock false
        resetChangedSource()
        val epochChanged = incoming.epoch != currentEpoch
        if (epochChanged || lastAppliedSequence < 0L || (forceEpochReset && incoming.sequence == lastAppliedSequence)) {
            if (epochChanged) _queueWindow.value = CoreQueueWindowView()
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
        } else {
            _queueWindow.value = CoreQueueWindowView()
        }
    }

    private fun resetChangedSource() {
        if (sourceGeneration == transport.generation) return
        sourceGeneration = transport.generation
        currentEpoch = "epoch-boot"
        lastAppliedSequence = -1L
        _snapshot.value = null
        _queueWindow.value = CoreQueueWindowView()
    }

    private fun encodeCommandPayload(
        command: CoreTypedCommand,
        resolvedRevision: Long?
    ): JsonObject = when (command) {
        is CoreTypedCommand.PlayCatalogSource -> buildJsonObject {
            put("sourceId", JsonPrimitive(command.sourceId)); put("revision", JsonPrimitive(command.revision))
        }
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
                    command.tracks.forEach { track -> add(if (transport.isRemote) buildJsonObject {
                        put("id", JsonPrimitive(track.id))
                    } else json.encodeToJsonElement(track)) }
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
        is CoreTypedCommand.CreatePlaylist -> buildJsonObject {
            put("name", JsonPrimitive(command.playlistName))
            put(
                "trackIds",
                buildJsonArray {
                    command.trackIds.forEach { add(JsonPrimitive(it)) }
                }
            )
        }
        is CoreTypedCommand.DeletePlaylist -> buildJsonObject {
            put("playlistId", JsonPrimitive(command.playlistId))
        }
        is CoreTypedCommand.RenamePlaylist -> buildJsonObject {
            put("playlistId", JsonPrimitive(command.playlistId))
            put("name", JsonPrimitive(command.newName))
        }
        is CoreTypedCommand.AddTracksToPlaylist -> buildJsonObject {
            put("playlistId", JsonPrimitive(command.playlistId))
            put(
                "trackIds",
                buildJsonArray {
                    command.trackIds.forEach { add(JsonPrimitive(it)) }
                }
            )
        }
        is CoreTypedCommand.RemoveTrackFromPlaylist -> buildJsonObject {
            put("playlistId", JsonPrimitive(command.playlistId))
            put("trackId", JsonPrimitive(command.trackId))
        }
        is CoreTypedCommand.SetEqConfig -> buildJsonObject {
            put("enabled", JsonPrimitive(command.config.enabled))
            put("preset", JsonPrimitive(command.config.preset))
            put("preampDb", JsonPrimitive(command.config.preampDb))
            put(
                "bandsDb",
                buildJsonArray {
                    command.config.bandsDb.forEach { add(JsonPrimitive(it)) }
                }
            )
            put("bitPerfect", JsonPrimitive(command.config.bitPerfect))
            put("gapless", JsonPrimitive(command.config.gapless))
            put("dsp", json.encodeToJsonElement(command.config.dsp))
            put("scenes", json.encodeToJsonElement(command.config.scenes))
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
