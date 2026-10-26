package com.twilightecho.android.runtimehost

import android.content.Context
import com.twilightecho.android.coreclient.CORE_PROTOCOL_VERSION
import com.twilightecho.android.coreclient.CoreAlbumCollectionView
import com.twilightecho.android.coreclient.CoreArtistCollectionView
import com.twilightecho.android.coreclient.CoreBookmarkView
import com.twilightecho.android.coreclient.CoreCancelEnvelope
import com.twilightecho.android.coreclient.CoreCollectionsView
import com.twilightecho.android.coreclient.CoreEqConfigView
import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreErrorView
import com.twilightecho.android.coreclient.CoreEventMessage
import com.twilightecho.android.coreclient.CoreLibrarySearchPage
import com.twilightecho.android.coreclient.CoreLyricLine
import com.twilightecho.android.coreclient.CoreLyricsView
import com.twilightecho.android.coreclient.CorePhase
import com.twilightecho.android.coreclient.CorePlaybackView
import com.twilightecho.android.coreclient.CorePlaylistView
import com.twilightecho.android.coreclient.CoreQueueEntryView
import com.twilightecho.android.coreclient.CoreQueueReference
import com.twilightecho.android.coreclient.CoreQueueWindowView
import com.twilightecho.android.coreclient.CoreRequestEnvelope
import com.twilightecho.android.coreclient.CoreResponseEnvelope
import com.twilightecho.android.coreclient.CoreSnapshot
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CoreTransport
import com.twilightecho.android.coreclient.CoreUpcomingTrack
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.util.UUID

/**
 * Single-threaded embedded QuickJS host executing the shared Music Core bundle (`music-core.js`)
 * and exposing `CoreTransport` for `CoreClient` consumers (Sections 10.1, 10.4, 10.5 + Phase 2).
 *
 * Lifecycle:
 * 1. Initializes `CoreRuntimeThreadDispatcher` (dedicated single thread).
 * 2. Loads `assets/core/music-core.js` and invokes `globalThis.__TWILIGHT_PORTABLE_CORE__.createHost(...)`.
 * 3. Subscribes to `AudioEnginePort` events and routes them onto the serial runtime thread.
 * 4. Restores persisted session state (including `favoriteTrackIds` and `bookmarks`) via `SessionStorePort.load()`.
 */
class QuickJsRuntimeHost(
    private val appContext: Context?,
    private val ports: CoreHostPorts,
    private val runtimeDispatcher: CoreRuntimeThreadDispatcher = CoreRuntimeThreadDispatcher(),
    private val json: Json = DefaultCoreClient.DefaultJson
) : CoreTransport, Closeable {

    private val runtimeScope = CoroutineScope(SupervisorJob() + runtimeDispatcher.dispatcher)
    private val _stateEvents = MutableSharedFlow<CoreEventMessage>(replay = 1, extraBufferCapacity = 64)
    override val stateEvents: Flow<CoreEventMessage> = _stateEvents.asSharedFlow()

    private var jsEngine: JsRuntimeEngine? = null
    private var portBridge: CorePortBridge? = null
    private var audioSubscription: CancelHandle? = null
    private var routeSubscription: CancelHandle? = null

    // Authoritative core state mirror on the serial runtime thread
    private val epoch: String = "epoch-android-${UUID.randomUUID().toString().take(8)}"
    private var sequence: Long = 1L
    private var queueRevision: Long = 1L
    private var libraryRevision: Long = 1L
    private var playlistsRevision: Long = 1L
    private var lyricsRevision: Long = 1L
    private var sessionStoreRevision: String? = null

    private var libraryTracks: MutableList<CoreTrackView> = defaultDemoTracks().toMutableList()
    private var queueTracks: MutableList<CoreTrackView> = defaultDemoTracks().toMutableList()
    private var favoriteTrackIds: LinkedHashSet<String> = linkedSetOf()
    private var bookmarks: MutableList<CoreBookmarkView> = mutableListOf()
    private var playlists: MutableList<CorePlaylistView> = mutableListOf()
    private var customLyrics: MutableMap<String, CoreLyricsView> = linkedMapOf()
    private var eqConfig: CoreEqConfigView = CoreEqConfigView()
    private var currentQueueIndex: Int = 0
    private var currentPlayMode: PlayMode = PlayMode.SEQUENCE
    private var latestAudioSnapshot: AudioSnapshot = AudioSnapshot()

    suspend fun start() {
        runtimeDispatcher.runOnRuntimeThread {
            val bridge = CorePortBridge(
                ports = ports,
                runtimeScope = runtimeScope,
                onStateEventJson = { eventJson ->
                    runCatching {
                        val event = json.decodeFromJsonElement<CoreEventMessage>(
                            json.parseToJsonElement(eventJson)
                        )
                        _stateEvents.tryEmit(event)
                    }
                },
                json = json
            )
            portBridge = bridge

            val engine = CashQuickJsRuntimeEngine(runtimeDispatcher)
            engine.bindHostBridge(bridge)

            val bundleScript = loadPortableCoreScript()
            engine.evaluateScript(bundleScript, "assets/core/music-core.js")
            engine.invokeGlobalFunction(
                "globalThis.__TWILIGHT_PORTABLE_CORE__.bootHost",
                buildJsonObject {
                    put("epoch", JsonPrimitive(epoch))
                    put("protocolVersion", JsonPrimitive(CORE_PROTOCOL_VERSION))
                }.toString()
            )
            jsEngine = engine

            // Restore persisted session (including favoriteTrackIds & bookmarks) if available
            ports.sessionStore.load()?.let { stored ->
                sessionStoreRevision = stored.revision
                restoreSessionFromJson(stored.payloadJson)
            }

            // Load initial queue into native audio engine
            if (queueTracks.isNotEmpty()) {
                ports.audioEngine.loadQueue(queueTracks, currentQueueIndex)
            }
            latestAudioSnapshot = ports.audioEngine.getSnapshot()

            // Subscribe to native AudioEnginePort events and serialize onto runtime thread
            audioSubscription = ports.audioEngine.subscribe { audioEvent ->
                runtimeScope.launch {
                    handleNativeAudioEvent(audioEvent)
                }
            }

            // Subscribe to hardware AudioDevicePort route changes and serialize onto runtime thread
            routeSubscription = ports.audioDevice.subscribeRouteChanges {
                runtimeScope.launch {
                    handleNativeAudioEvent(AudioEvent.SnapshotUpdated(ports.audioEngine.getSnapshot()))
                }
            }

            publishSnapshot()
        }
    }

    override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope =
        runtimeDispatcher.runOnRuntimeThread {
            when (envelope.kind) {
                "query" -> handleQueryOnRuntimeThread(envelope)
                "command" -> handleCommandOnRuntimeThread(envelope)
                else -> CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = false,
                    error = CoreErrorView(
                        code = CoreErrorCode.INVALID_REQUEST,
                        message = "Unsupported request kind: ${envelope.kind}"
                    )
                )
            }
        }

    override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {
        runtimeDispatcher.runOnRuntimeThread {
            jsEngine?.invokeGlobalFunction(
                "globalThis.__TWILIGHT_PORTABLE_CORE__.cancelRequest",
                json.encodeToString(envelope)
            )
        }
    }

    private suspend fun handleQueryOnRuntimeThread(
        envelope: CoreRequestEnvelope
    ): CoreResponseEnvelope {
        val engine = jsEngine
        if (engine != null && engine.isNativeQuickJsAvailable) {
            val rawResponse = engine.invokeGlobalFunction(
                "globalThis.__TWILIGHT_PORTABLE_CORE__.handleRequest",
                json.encodeToString(envelope)
            )
            val jsResp = runCatching {
                json.decodeFromJsonElement<CoreResponseEnvelope>(json.parseToJsonElement(rawResponse))
            }.getOrNull()
            if (jsResp != null && (jsResp.value != null || jsResp.error != null)) {
                return jsResp
            }
        } else {
            engine?.invokeGlobalFunction(
                "globalThis.__TWILIGHT_PORTABLE_CORE__.handleRequest",
                json.encodeToString(envelope)
            )
        }

        return when (envelope.name) {
            "core.getSnapshot", "state.snapshot" -> {
                val snap = buildCurrentSnapshot()
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = true,
                    value = json.encodeToJsonElement(snap)
                )
            }
            "playback.getQueueWindow", "queue.page" -> {
                val offset = envelope.payload["offset"]?.jsonPrimitive?.intOrNull ?: 0
                val limit = envelope.payload["limit"]?.jsonPrimitive?.intOrNull ?: 50
                val entries = queueTracks
                    .drop(offset)
                    .take(limit)
                    .mapIndexed { idx, track ->
                        val absIndex = offset + idx
                        CoreQueueEntryView(
                            occurrenceId = track.queueEntryId ?: "occ-$absIndex-${track.id}",
                            index = absIndex,
                            track = track
                        )
                    }
                val window = CoreQueueWindowView(
                    queueId = "default",
                    revision = queueRevision,
                    total = queueTracks.size,
                    offset = offset,
                    limit = limit,
                    currentIndex = currentQueueIndex,
                    currentOccurrenceId = queueTracks.getOrNull(currentQueueIndex)?.let {
                        it.queueEntryId ?: "occ-$currentQueueIndex-${it.id}"
                    },
                    items = entries
                )
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = true,
                    value = json.encodeToJsonElement(window)
                )
            }
            "search.songs" -> {
                val rawQuery = envelope.payload["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val matched = if (rawQuery.isEmpty()) {
                    libraryTracks.toList()
                } else {
                    libraryTracks.filter { track ->
                        track.title.contains(rawQuery, ignoreCase = true) ||
                            track.artist.contains(rawQuery, ignoreCase = true) ||
                            track.album.contains(rawQuery, ignoreCase = true)
                    }
                }
                val page = CoreLibrarySearchPage(
                    query = rawQuery,
                    total = matched.size,
                    items = matched
                )
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = true,
                    value = json.encodeToJsonElement(page)
                )
            }
            "lyrics.current" -> {
                val targetTrackId = envelope.payload["trackId"]?.jsonPrimitive?.contentOrNull
                val targetTrack = if (targetTrackId != null) {
                    libraryTracks.firstOrNull { it.id == targetTrackId }
                        ?: queueTracks.firstOrNull { it.id == targetTrackId }
                } else {
                    queueTracks.getOrNull(currentQueueIndex)
                }
                val lyricsView = targetTrack?.let { buildLyricsForTrack(it) }
                    ?: CoreLyricsView(trackId = targetTrackId ?: "none", lines = emptyList())
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = true,
                    value = json.encodeToJsonElement(lyricsView)
                )
            }
            "library.collections" -> {
                val collections = buildCollectionsFromLibrary()
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = true,
                    value = json.encodeToJsonElement(collections)
                )
            }
            "playlists.list" -> {
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = true,
                    value = json.encodeToJsonElement(playlists.toList())
                )
            }
            "audio.getEq" -> {
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = true,
                    value = json.encodeToJsonElement(eqConfig)
                )
            }
            "audioDevice.getRoute" -> {
                val route = ports.audioDevice.getActiveRoute()
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = true,
                    value = json.encodeToJsonElement(route)
                )
            }
            "audioDevice.listDevices" -> {
                val list = ports.audioDevice.listOutputDevices()
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = true,
                    value = json.encodeToJsonElement(list)
                )
            }
            "lyrics.fetchOnline" -> {
                val targetTrackId = envelope.payload["trackId"]?.jsonPrimitive?.contentOrNull
                val targetTrack = if (targetTrackId != null) {
                    libraryTracks.firstOrNull { it.id == targetTrackId }
                        ?: queueTracks.firstOrNull { it.id == targetTrackId }
                } else {
                    queueTracks.getOrNull(currentQueueIndex)
                }
                val resolvedId = targetTrack?.id ?: targetTrackId ?: "unknown"
                val fetched = fetchOnlineLyricsViaPort(
                    trackId = resolvedId,
                    title = envelope.payload["title"]?.jsonPrimitive?.contentOrNull ?: targetTrack?.title.orEmpty(),
                    artist = envelope.payload["artist"]?.jsonPrimitive?.contentOrNull ?: targetTrack?.artist.orEmpty(),
                    album = envelope.payload["album"]?.jsonPrimitive?.contentOrNull ?: targetTrack?.album.orEmpty(),
                    customUrl = envelope.payload["url"]?.jsonPrimitive?.contentOrNull
                )
                val resultView = fetched ?: targetTrack?.let { buildLyricsForTrack(it) }
                    ?: CoreLyricsView(trackId = resolvedId, lines = emptyList())
                CoreResponseEnvelope(
                    requestId = envelope.requestId,
                    epoch = epoch,
                    ok = true,
                    value = json.encodeToJsonElement(resultView)
                )
            }
            else -> CoreResponseEnvelope(
                requestId = envelope.requestId,
                epoch = epoch,
                ok = false,
                error = CoreErrorView(
                    code = CoreErrorCode.INVALID_REQUEST,
                    message = "Unsupported query: ${envelope.name}"
                )
            )
        }
    }

    private suspend fun handleCommandOnRuntimeThread(
        envelope: CoreRequestEnvelope
    ): CoreResponseEnvelope {
        // Optimistic concurrency check (Section 9.3 & 9.4)
        val expectedRev = envelope.expectedRevision
        val requiresRevisionCheck = envelope.name in setOf(
            "playback.selectQueueIndex",
            "playback.replaceQueue",
            "queue.select",
            "queue.edit"
        )
        if (requiresRevisionCheck && expectedRev != null && expectedRev != queueRevision) {
            return CoreResponseEnvelope(
                requestId = envelope.requestId,
                epoch = epoch,
                ok = false,
                error = CoreErrorView(
                    code = CoreErrorCode.STALE_REVISION,
                    message = "Expected queue revision $expectedRev, but authoritative revision is $queueRevision",
                    retryable = true
                )
            )
        }

        val engine = jsEngine
        if (engine != null && engine.isNativeQuickJsAvailable) {
            val rawResponse = engine.invokeGlobalFunction(
                "globalThis.__TWILIGHT_PORTABLE_CORE__.handleRequest",
                json.encodeToString(envelope)
            )
            val jsResp = runCatching {
                json.decodeFromJsonElement<CoreResponseEnvelope>(json.parseToJsonElement(rawResponse))
            }.getOrNull()
            if (jsResp != null && (jsResp.value != null || jsResp.error != null)) {
                return jsResp
            }
        } else {
            engine?.invokeGlobalFunction(
                "globalThis.__TWILIGHT_PORTABLE_CORE__.handleRequest",
                json.encodeToString(envelope)
            )
        }

        when (envelope.name) {
            "playback.play" -> {
                val positionSec = envelope.payload["position"]?.jsonPrimitive?.doubleOrNull
                val activeTrack = queueTracks.getOrNull(currentQueueIndex)
                if (activeTrack != null) {
                    val uriResult = ports.resourceAccess.resolveNativeUri(activeTrack.source ?: activeTrack.id)
                    if (uriResult is ResourceAccessResult.Error &&
                        uriResult.code == CoreErrorCode.PERMISSION_EXPIRED
                    ) {
                        return CoreResponseEnvelope(
                            requestId = envelope.requestId,
                            epoch = epoch,
                            ok = false,
                            error = CoreErrorView(
                                code = CoreErrorCode.PERMISSION_EXPIRED,
                                message = uriResult.message,
                                retryable = false
                            )
                        )
                    }
                }
                ports.audioEngine.play(positionSec)
            }
            "playback.pause" -> {
                ports.audioEngine.pause()
            }
            "playback.stop" -> {
                ports.audioEngine.stop()
            }
            "playback.seek" -> {
                val positionSec = envelope.payload["position"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                ports.audioEngine.seek((positionSec * 1000.0).toLong())
            }
            "playback.next" -> {
                if (queueTracks.isNotEmpty()) {
                    currentQueueIndex = (currentQueueIndex + 1) % queueTracks.size
                    lyricsRevision += 1L
                    ports.audioEngine.next()
                    persistSessionSnapshot()
                }
            }
            "playback.previous" -> {
                if (queueTracks.isNotEmpty()) {
                    currentQueueIndex = if (currentQueueIndex - 1 < 0) {
                        queueTracks.lastIndex
                    } else {
                        currentQueueIndex - 1
                    }
                    lyricsRevision += 1L
                    ports.audioEngine.previous()
                    persistSessionSnapshot()
                }
            }
            "playback.setVolume", "playback.volume" -> {
                val vol = envelope.payload["volume"]?.jsonPrimitive?.doubleOrNull ?: 1.0
                ports.audioEngine.setVolume(vol.coerceIn(0.0, 1.0))
            }
            "playback.setPlayMode", "playback.mode" -> {
                val modeStr = envelope.payload["mode"]?.jsonPrimitive?.contentOrNull
                currentPlayMode = PlayMode.fromWireValue(modeStr)
                ports.audioEngine.setPlayMode(currentPlayMode)
                persistSessionSnapshot()
            }
            "playback.selectQueueIndex", "queue.select" -> {
                val targetIndex = envelope.payload["index"]?.jsonPrimitive?.intOrNull ?: 0
                if (targetIndex in queueTracks.indices) {
                    currentQueueIndex = targetIndex
                    queueRevision += 1L
                    lyricsRevision += 1L
                    ports.audioEngine.selectQueueIndex(targetIndex)
                    persistSessionSnapshot()
                }
            }
            "playback.replaceQueue" -> {
                val incomingTracks = envelope.payload["tracks"]?.jsonArray?.mapNotNull {
                    runCatching { json.decodeFromJsonElement<CoreTrackView>(it) }.getOrNull()
                }.orEmpty()
                val startIndex = envelope.payload["startIndex"]?.jsonPrimitive?.intOrNull ?: 0
                val playImmediately = envelope.payload["play"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                if (incomingTracks.isNotEmpty()) {
                    queueTracks = incomingTracks.toMutableList()
                    currentQueueIndex = startIndex.coerceIn(0, queueTracks.lastIndex)
                    queueRevision += 1L
                    lyricsRevision += 1L
                    ports.audioEngine.loadQueue(queueTracks, currentQueueIndex)
                    if (playImmediately) {
                        ports.audioEngine.play(0.0)
                    }
                    persistSessionSnapshot()
                }
            }
            "library.syncTracks" -> {
                val scannedTracks = envelope.payload["tracks"]?.jsonArray?.mapNotNull {
                    runCatching { json.decodeFromJsonElement<CoreTrackView>(it) }.getOrNull()
                }.orEmpty()

                val mergedById = linkedMapOf<String, CoreTrackView>()
                // Keep demo tracks available alongside scanned device tracks
                defaultDemoTracks().forEach { mergedById[it.id] = it }
                scannedTracks.forEach { mergedById[it.id] = it }
                libraryTracks = mergedById.values.toMutableList()
                libraryRevision += 1L

                if (scannedTracks.isNotEmpty()) {
                    queueTracks = libraryTracks.toMutableList()
                    currentQueueIndex = currentQueueIndex.coerceIn(0, queueTracks.lastIndex)
                    queueRevision += 1L
                    lyricsRevision += 1L
                    ports.audioEngine.loadQueue(queueTracks, currentQueueIndex)
                }
                persistSessionSnapshot()
            }
            "playlists.toggleFavorite" -> {
                val trackId = envelope.payload["trackId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (trackId.isNotEmpty()) {
                    if (!favoriteTrackIds.add(trackId)) {
                        favoriteTrackIds.remove(trackId)
                    }
                    playlistsRevision += 1L
                    persistSessionSnapshot()
                }
            }
            "playlists.create" -> {
                val plName = envelope.payload["name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "Untitled Playlist"
                val rawTrackIds = envelope.payload["trackIds"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull?.trim()?.takeIf { id -> id.isNotEmpty() }
                }?.distinct().orEmpty()
                val created = CorePlaylistView(
                    id = "pl-${UUID.randomUUID().toString().take(8)}",
                    name = plName,
                    trackIds = rawTrackIds,
                    trackCount = rawTrackIds.size,
                    updatedAtMs = ports.clock.nowUnixMs()
                )
                playlists.add(created)
                playlistsRevision += 1L
                persistSessionSnapshot()
            }
            "playlists.delete" -> {
                val playlistId = envelope.payload["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (playlistId.isNotEmpty() && playlists.removeAll { it.id == playlistId }) {
                    playlistsRevision += 1L
                    persistSessionSnapshot()
                }
            }
            "playlists.rename" -> {
                val playlistId = envelope.payload["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val newName = envelope.payload["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val idx = playlists.indexOfFirst { it.id == playlistId }
                if (idx >= 0 && newName.isNotEmpty()) {
                    val existing = playlists[idx]
                    playlists[idx] = existing.copy(
                        name = newName,
                        updatedAtMs = ports.clock.nowUnixMs()
                    )
                    playlistsRevision += 1L
                    persistSessionSnapshot()
                }
            }
            "playlists.addTracks" -> {
                val playlistId = envelope.payload["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val incomingIds = envelope.payload["trackIds"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull?.trim()?.takeIf { id -> id.isNotEmpty() }
                }.orEmpty()
                val idx = playlists.indexOfFirst { it.id == playlistId }
                if (idx >= 0 && incomingIds.isNotEmpty()) {
                    val existing = playlists[idx]
                    val merged = (existing.trackIds + incomingIds).distinct()
                    playlists[idx] = existing.copy(
                        trackIds = merged,
                        trackCount = merged.size,
                        updatedAtMs = ports.clock.nowUnixMs()
                    )
                    playlistsRevision += 1L
                    persistSessionSnapshot()
                }
            }
            "playlists.removeTrack" -> {
                val playlistId = envelope.payload["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val trackId = envelope.payload["trackId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val idx = playlists.indexOfFirst { it.id == playlistId }
                if (idx >= 0 && trackId.isNotEmpty()) {
                    val existing = playlists[idx]
                    val filtered = existing.trackIds.filterNot { it == trackId }
                    playlists[idx] = existing.copy(
                        trackIds = filtered,
                        trackCount = filtered.size,
                        updatedAtMs = ports.clock.nowUnixMs()
                    )
                    playlistsRevision += 1L
                    persistSessionSnapshot()
                }
            }
            "audio.setEq" -> {
                val enabled = envelope.payload["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: eqConfig.enabled
                val preset = envelope.payload["preset"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: eqConfig.preset
                val preampDb = (envelope.payload["preampDb"]?.jsonPrimitive?.doubleOrNull ?: eqConfig.preampDb)
                    .coerceIn(-12.0, 12.0)
                val incomingBands = envelope.payload["bandsDb"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.doubleOrNull?.coerceIn(-12.0, 12.0)
                }
                val resolvedBands = when {
                    incomingBands != null && incomingBands.size == 10 -> incomingBands
                    CoreEqConfigView.PRESETS.containsKey(preset) -> CoreEqConfigView.PRESETS.getValue(preset)
                    else -> eqConfig.bandsDb
                }
                val bitPerfect = envelope.payload["bitPerfect"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: eqConfig.bitPerfect
                val gapless = envelope.payload["gapless"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: eqConfig.gapless
                eqConfig = CoreEqConfigView(
                    enabled = enabled,
                    preset = preset,
                    preampDb = preampDb,
                    bandsDb = resolvedBands,
                    bitPerfect = bitPerfect,
                    gapless = gapless
                )
                ports.audioEngine.setDspConfig(json.encodeToString(eqConfig))
                persistSessionSnapshot()
            }
            "bookmarks.add" -> {
                val trackId = envelope.payload["trackId"]?.jsonPrimitive?.contentOrNull
                    ?: queueTracks.getOrNull(currentQueueIndex)?.id
                    ?: ""
                val positionSec = envelope.payload["positionSec"]?.jsonPrimitive?.doubleOrNull
                    ?: (latestAudioSnapshot.positionMs / 1000.0)
                val label = envelope.payload["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: "Bookmark @ ${formatSecondsLabel(positionSec)}"
                if (trackId.isNotEmpty()) {
                    val bookmark = CoreBookmarkView(
                        id = "bm-${UUID.randomUUID().toString().take(8)}",
                        trackId = trackId,
                        positionSec = positionSec,
                        label = label,
                        createdAtMs = ports.clock.nowUnixMs()
                    )
                    bookmarks.add(bookmark)
                    persistSessionSnapshot()
                }
            }
            "bookmarks.remove" -> {
                val bookmarkId = envelope.payload["bookmarkId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (bookmarkId.isNotEmpty()) {
                    bookmarks.removeAll { it.id == bookmarkId }
                    persistSessionSnapshot()
                }
            }
            "playback.platformInterruption" -> {
                val shouldPause = envelope.payload["shouldPause"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                if (shouldPause) {
                    ports.audioEngine.pause()
                }
            }
            "lyrics.fetchOnline" -> {
                val targetTrackId = envelope.payload["trackId"]?.jsonPrimitive?.contentOrNull
                val targetTrack = if (targetTrackId != null) {
                    libraryTracks.firstOrNull { it.id == targetTrackId }
                        ?: queueTracks.firstOrNull { it.id == targetTrackId }
                } else {
                    queueTracks.getOrNull(currentQueueIndex)
                }
                val resolvedId = targetTrack?.id ?: targetTrackId ?: "unknown"
                fetchOnlineLyricsViaPort(
                    trackId = resolvedId,
                    title = envelope.payload["title"]?.jsonPrimitive?.contentOrNull ?: targetTrack?.title.orEmpty(),
                    artist = envelope.payload["artist"]?.jsonPrimitive?.contentOrNull ?: targetTrack?.artist.orEmpty(),
                    album = envelope.payload["album"]?.jsonPrimitive?.contentOrNull ?: targetTrack?.album.orEmpty(),
                    customUrl = envelope.payload["url"]?.jsonPrimitive?.contentOrNull
                )
            }
            "lyrics.importLrc" -> {
                val trackId = envelope.payload["trackId"]?.jsonPrimitive?.contentOrNull
                    ?: queueTracks.getOrNull(currentQueueIndex)?.id
                    ?: ""
                val rawLrc = envelope.payload["lrcText"]?.jsonPrimitive?.contentOrNull
                    ?: envelope.payload["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                if (trackId.isNotEmpty() && rawLrc.isNotBlank()) {
                    val parsed = parseLrcLines(rawLrc)
                    if (parsed.second.isNotEmpty()) {
                        customLyrics[trackId] = CoreLyricsView(
                            trackId = trackId,
                            source = "imported",
                            synced = parsed.first,
                            lines = parsed.second
                        )
                        lyricsRevision += 1L
                    }
                }
            }
            "settings.edit" -> {
                persistSessionSnapshot()
            }
        }

        latestAudioSnapshot = ports.audioEngine.getSnapshot()
        val updatedSnapshot = publishSnapshot()

        return CoreResponseEnvelope(
            requestId = envelope.requestId,
            epoch = epoch,
            ok = true,
            value = buildJsonObject {
                put("snapshot", json.encodeToJsonElement(updatedSnapshot))
            }
        )
    }

    private suspend fun handleNativeAudioEvent(event: AudioEvent) {
        val engine = jsEngine
        if (engine != null && engine.isNativeQuickJsAvailable) {
            val payloadJson = when (event) {
                is AudioEvent.SnapshotUpdated -> buildJsonObject {
                    put("type", JsonPrimitive("snapshot"))
                    put("snapshot", json.encodeToJsonElement(event.snapshot))
                }.toString()
                is AudioEvent.TrackEnded -> buildJsonObject {
                    put("type", JsonPrimitive("track-ended"))
                    put("queueIndex", JsonPrimitive(event.queueIndex))
                    put("reason", JsonPrimitive(event.reason))
                }.toString()
                is AudioEvent.EngineError -> buildJsonObject {
                    put("type", JsonPrimitive("error"))
                    put("code", JsonPrimitive(event.code))
                    put("message", JsonPrimitive("${event.code}: ${event.message}"))
                }.toString()
            }
            engine.invokeGlobalFunction(
                "globalThis.__TWILIGHT_PORTABLE_CORE__.onAudioEngineEvent",
                payloadJson
            )
            return
        }
        when (event) {
            is AudioEvent.SnapshotUpdated -> {
                latestAudioSnapshot = event.snapshot
                if (event.snapshot.queueIndex in queueTracks.indices) {
                    currentQueueIndex = event.snapshot.queueIndex
                }
                publishSnapshot()
            }
            is AudioEvent.TrackEnded -> {
                if (queueTracks.isNotEmpty()) {
                    when (currentPlayMode) {
                        PlayMode.SINGLE -> {
                            ports.audioEngine.seek(0L)
                            ports.audioEngine.play(0.0)
                        }
                        else -> {
                            currentQueueIndex = (currentQueueIndex + 1) % queueTracks.size
                            lyricsRevision += 1L
                            ports.audioEngine.selectQueueIndex(currentQueueIndex)
                        }
                    }
                }
                latestAudioSnapshot = ports.audioEngine.getSnapshot()
                publishSnapshot()
            }
            is AudioEvent.EngineError -> {
                latestAudioSnapshot = ports.audioEngine.getSnapshot()
                publishSnapshot(errorMessage = "${event.code}: ${event.message}")
            }
        }
    }

    private fun publishSnapshot(errorMessage: String? = null): CoreSnapshot {
        sequence += 1L
        val snapshot = buildCurrentSnapshot(errorMessage)
        val eventMessage = CoreEventMessage(
            epoch = epoch,
            sequence = sequence,
            snapshot = snapshot
        )
        _stateEvents.tryEmit(eventMessage)
        return snapshot
    }

    private fun buildCurrentSnapshot(errorMessage: String? = null): CoreSnapshot {
        val currentTrack = queueTracks.getOrNull(currentQueueIndex)
        val nextIndex = if (queueTracks.isNotEmpty()) {
            (currentQueueIndex + 1) % queueTracks.size
        } else {
            -1
        }
        val upcomingTrack = queueTracks.getOrNull(nextIndex)?.let { next ->
            CoreUpcomingTrack(
                id = next.id,
                queueEntryId = next.queueEntryId ?: "occ-$nextIndex-${next.id}",
                title = next.title,
                artist = next.artist,
                album = next.album,
                duration = next.duration
            )
        }

        val mappedState = when (latestAudioSnapshot.state) {
            "playing" -> "playing"
            "paused" -> "paused"
            "loading" -> "loading"
            "error" -> "error"
            else -> "stopped"
        }

        val activeRoute = runCatching { ports.audioDevice.getActiveRoute() }.getOrNull()
        val resolvedOutputBackend = if (activeRoute != null && activeRoute.routeType != "speaker") {
            "${latestAudioSnapshot.outputBackend ?: "oboe"}:${activeRoute.routeType}"
        } else {
            latestAudioSnapshot.outputBackend ?: "oboe"
        }
        val resolvedActualSampleRate = activeRoute?.activeSampleRate?.takeIf { it > 0 }
            ?: latestAudioSnapshot.actualSampleRate
            ?: 96000
        val resolvedActualBitDepth = activeRoute?.maxBitDepth?.takeIf { it > 0 }
            ?: latestAudioSnapshot.actualBitDepth
            ?: 24
        val routeBitPerfectCapable = activeRoute?.isUsbHiResCapable ?: true

        val playbackView = CorePlaybackView(
            state = mappedState,
            position = latestAudioSnapshot.positionMs / 1000.0,
            duration = (latestAudioSnapshot.durationMs?.div(1000.0))
                ?.takeIf { it > 0.0 }
                ?: (currentTrack?.duration ?: 0.0),
            volume = latestAudioSnapshot.volume,
            playbackRate = latestAudioSnapshot.playbackRate,
            playMode = currentPlayMode.toWireValue(),
            queueIndex = currentQueueIndex,
            queueRevision = queueRevision,
            upcomingQueueIndex = nextIndex.takeIf { it >= 0 },
            nativePlaybackActive = mappedState == "playing",
            codec = latestAudioSnapshot.codec ?: currentTrack?.format ?: "FLAC",
            bitrate = currentTrack?.bitrate ?: 1411,
            sourceSampleRate = latestAudioSnapshot.sampleRate ?: currentTrack?.sampleRate ?: 96000,
            sourceBitDepth = latestAudioSnapshot.bitDepth ?: currentTrack?.bitDepth ?: 24,
            sourceChannels = latestAudioSnapshot.channels ?: 2,
            outputBackend = resolvedOutputBackend,
            actualSampleRate = resolvedActualSampleRate,
            actualBitDepth = resolvedActualBitDepth,
            actualChannels = 2,
            outputPerfect = eqConfig.bitPerfect && !eqConfig.enabled && routeBitPerfectCapable,
            gaplessActive = eqConfig.gapless,
            preloadReady = upcomingTrack != null,
            upcomingTrack = upcomingTrack
        )

        val queueRef = CoreQueueReference(
            id = "default",
            revision = queueRevision,
            total = queueTracks.size,
            index = currentQueueIndex,
            occurrenceId = currentTrack?.let { it.queueEntryId ?: "occ-$currentQueueIndex-${it.id}" }
        )

        val currentLyrics = currentTrack?.let { buildLyricsForTrack(it) }
        val collections = buildCollectionsFromLibrary()

        return CoreSnapshot(
            protocolVersion = CORE_PROTOCOL_VERSION,
            epoch = epoch,
            sequence = sequence,
            phase = CorePhase.READY,
            playback = playbackView,
            track = currentTrack,
            queue = queueRef,
            error = errorMessage,
            libraryRevision = libraryRevision,
            playlistsRevision = playlistsRevision,
            lyricsRevision = lyricsRevision,
            favoriteTrackIds = favoriteTrackIds.toList(),
            bookmarks = bookmarks.toList(),
            playlists = playlists.toList(),
            albums = collections.albums,
            artists = collections.artists,
            eqConfig = eqConfig,
            currentLyrics = currentLyrics,
            libraryTrackCount = libraryTracks.size
        )
    }

    private fun buildCollectionsFromLibrary(): CoreCollectionsView {
        val albumsByKey = linkedMapOf<String, CoreAlbumCollectionView>()
        val artistAlbums = linkedMapOf<String, LinkedHashSet<String>>()
        val artistTracks = linkedMapOf<String, MutableList<String>>()
        val artistDisplayNames = linkedMapOf<String, String>()

        libraryTracks.forEachIndexed { idx, track ->
            val albumTitle = track.album.trim().ifEmpty { "Unknown Album" }
            val artistName = track.artist.trim().ifEmpty { "Unknown Artist" }
            val albumKey = albumTitle.lowercase()
            val existingAlbum = albumsByKey[albumKey]
            if (existingAlbum == null) {
                val slug = albumKey.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "$idx" }
                albumsByKey[albumKey] = CoreAlbumCollectionView(
                    id = "alb-$slug",
                    title = albumTitle,
                    artist = artistName,
                    trackCount = 1,
                    totalDurationSec = track.duration,
                    trackIds = listOf(track.id)
                )
            } else {
                albumsByKey[albumKey] = existingAlbum.copy(
                    trackCount = existingAlbum.trackCount + 1,
                    totalDurationSec = existingAlbum.totalDurationSec + track.duration,
                    trackIds = existingAlbum.trackIds + track.id
                )
            }

            val artistKey = artistName.lowercase()
            artistDisplayNames.putIfAbsent(artistKey, artistName)
            artistAlbums.getOrPut(artistKey) { linkedSetOf() }.add(albumKey)
            artistTracks.getOrPut(artistKey) { mutableListOf() }.add(track.id)
        }

        val artists = artistDisplayNames.entries.mapIndexed { idx, (key, displayName) ->
            val slug = key.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "$idx" }
            val tracksForArtist = artistTracks[key].orEmpty()
            CoreArtistCollectionView(
                id = "art-$slug",
                name = displayName,
                albumCount = artistAlbums[key]?.size ?: 1,
                trackCount = tracksForArtist.size,
                trackIds = tracksForArtist.toList()
            )
        }

        return CoreCollectionsView(
            albums = albumsByKey.values.toList(),
            artists = artists
        )
    }

    private fun buildLyricsForTrack(track: CoreTrackView): CoreLyricsView {
        val custom = customLyrics[track.id]
        if (custom != null && custom.lines.isNotEmpty()) {
            return custom
        }
        val demoLines = DEMO_LYRICS[track.id]
        if (demoLines != null) {
            return CoreLyricsView(
                trackId = track.id,
                source = "embedded",
                synced = true,
                lines = demoLines
            )
        }
        val durationSec = track.duration.coerceAtLeast(30.0)
        val stepMs = ((durationSec / 7.0).coerceAtLeast(4.0) * 1000.0).toLong()
        return CoreLyricsView(
            trackId = track.id,
            source = "generated",
            synced = true,
            lines = listOf(
                CoreLyricLine(0L, "♪ ${track.title} — ${track.artist}", "专辑：${track.album}"),
                CoreLyricLine(stepMs, "Streaming through Twilight Echo Audio Core", "通过 Twilight Echo 原生音频核心播放"),
                CoreLyricLine(stepMs * 2, "Every detail rendered in pure resolution", "每一处细节皆以纯净解析呈现"),
                CoreLyricLine(stepMs * 3, "Tap any lyric line to seek directly", "点击任意歌词行即可精准跳转进度"),
                CoreLyricLine(stepMs * 4, "Harmonic waves across the night sky", "夜空下流淌的泛音涟漪"),
                CoreLyricLine(stepMs * 5, "Resonance lingering in the twilight", "暮色中回响的余韵")
            )
        )
    }

    private suspend fun fetchOnlineLyricsViaPort(
        trackId: String,
        title: String,
        artist: String,
        album: String,
        customUrl: String?
    ): CoreLyricsView? {
        val targetUrl = customUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://lrclib.net/api/get?track_name=${java.net.URLEncoder.encode(title, "UTF-8")}&artist_name=${java.net.URLEncoder.encode(artist, "UTF-8")}&album_name=${java.net.URLEncoder.encode(album, "UTF-8")}"
        val netResult = runCatching {
            ports.networkClient.execute(
                NetworkRequestSpec(
                    url = targetUrl,
                    method = "GET",
                    headers = mapOf("Accept" to "application/json, text/plain;q=0.9")
                )
            )
        }.getOrNull() ?: return null

        val response = (netResult as? ResourceAccessResult.Ok)?.value ?: return null
        if (response.statusCode !in 200..299 || response.bodyUtf8.isBlank()) return null

        val rawLrc = runCatching {
            val obj = json.parseToJsonElement(response.bodyUtf8) as? JsonObject
            obj?.get("syncedLyrics")?.jsonPrimitive?.contentOrNull
                ?: obj?.get("plainLyrics")?.jsonPrimitive?.contentOrNull
                ?: obj?.get("lrc")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: response.bodyUtf8

        val (synced, lines) = parseLrcLines(rawLrc)
        if (lines.isEmpty()) return null
        val view = CoreLyricsView(
            trackId = trackId,
            source = "online",
            synced = synced,
            lines = lines
        )
        customLyrics[trackId] = view
        lyricsRevision += 1L
        return view
    }

    private fun parseLrcLines(rawText: String): Pair<Boolean, List<CoreLyricLine>> {
        val timedMap = sortedMapOf<Long, CoreLyricLine>()
        val untimed = mutableListOf<CoreLyricLine>()
        val timeRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

        rawText.replace("\r\n", "\n").lines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach
            val matches = timeRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) {
                if (!trimmed.matches(Regex("""^\[(ti|ar|al|by|offset):.*""", RegexOption.IGNORE_CASE))) {
                    untimed.add(CoreLyricLine(timeMs = untimed.size * 4000L, text = trimmed))
                }
                return@forEach
            }
            val textContent = trimmed.replace(timeRegex, "").trim()
            if (textContent.isEmpty()) return@forEach
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fracRaw = match.groupValues.getOrNull(3).orEmpty()
                val millis = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                val timeMs = minutes * 60_000L + seconds * 1_000L + millis
                val existing = timedMap[timeMs]
                if (existing != null) {
                    if (existing.translation == null) {
                        timedMap[timeMs] = existing.copy(translation = textContent)
                    }
                } else {
                    timedMap[timeMs] = CoreLyricLine(timeMs = timeMs, text = textContent)
                }
            }
        }

        return if (timedMap.isNotEmpty()) {
            true to timedMap.values.toList()
        } else {
            false to untimed.toList()
        }
    }

    private suspend fun persistSessionSnapshot() {
        val payload = buildJsonObject {
            put("queueRevision", JsonPrimitive(queueRevision))
            put("currentQueueIndex", JsonPrimitive(currentQueueIndex))
            put("playMode", JsonPrimitive(currentPlayMode.toWireValue()))
            put(
                "favoriteTrackIds",
                buildJsonArray {
                    favoriteTrackIds.forEach { add(JsonPrimitive(it)) }
                }
            )
            put(
                "bookmarks",
                buildJsonArray {
                    bookmarks.forEach { add(json.encodeToJsonElement(it)) }
                }
            )
            put(
                "playlists",
                buildJsonArray {
                    playlists.forEach { add(json.encodeToJsonElement(it)) }
                }
            )
            put("eqConfig", json.encodeToJsonElement(eqConfig))
        }.toString()
        when (val result = ports.sessionStore.save(payload, sessionStoreRevision)) {
            is SaveSessionResult.Ok -> sessionStoreRevision = result.revision
            is SaveSessionResult.Conflict -> {
                sessionStoreRevision = result.actualRevision
            }
        }
    }

    private fun restoreSessionFromJson(payloadJson: String) {
        runCatching {
            val obj = json.parseToJsonElement(payloadJson) as? JsonObject ?: return
            obj["queueRevision"]?.jsonPrimitive?.longOrNull?.let { queueRevision = it }
            obj["currentQueueIndex"]?.jsonPrimitive?.intOrNull?.let { idx ->
                if (idx in queueTracks.indices) currentQueueIndex = idx
            }
            obj["playMode"]?.jsonPrimitive?.contentOrNull?.let { mode ->
                currentPlayMode = PlayMode.fromWireValue(mode)
            }
            obj["favoriteTrackIds"]?.jsonArray?.let { arr ->
                favoriteTrackIds.clear()
                arr.mapNotNull { it.jsonPrimitive.contentOrNull }.forEach { favoriteTrackIds.add(it) }
            }
            obj["bookmarks"]?.jsonArray?.let { arr ->
                bookmarks.clear()
                arr.mapNotNull {
                    runCatching { json.decodeFromJsonElement<CoreBookmarkView>(it) }.getOrNull()
                }.forEach { bookmarks.add(it) }
            }
            obj["playlists"]?.jsonArray?.let { arr ->
                playlists.clear()
                arr.mapNotNull {
                    runCatching { json.decodeFromJsonElement<CorePlaylistView>(it) }.getOrNull()
                }.forEach { playlists.add(it) }
            }
            obj["eqConfig"]?.let { eqElem ->
                runCatching { json.decodeFromJsonElement<CoreEqConfigView>(eqElem) }
                    .getOrNull()
                    ?.let { eqConfig = it }
            }
        }
    }

    private fun formatSecondsLabel(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        val m = total / 60
        val s = total % 60
        return "%02d:%02d".format(m, s)
    }

    private fun loadPortableCoreScript(): String {
        val assetStream = runCatching {
            appContext?.assets?.open("core/music-core.js")
        }.getOrNull()
        if (assetStream != null) {
            return assetStream.bufferedReader().use { it.readText() }
        }
        return FALLBACK_PORTABLE_CORE_JS
    }

    override fun close() {
        audioSubscription?.cancel()
        audioSubscription = null
        routeSubscription?.cancel()
        routeSubscription = null
        runtimeScope.cancel()
        runCatching {
            jsEngine?.close()
        }
        runtimeDispatcher.close()
    }

    companion object {
        private val DEMO_LYRICS: Map<String, List<CoreLyricLine>> = mapOf(
            "track-twilight-overture" to listOf(
                CoreLyricLine(0L, "Twilight Overture — Aether Ensemble", "暮光序曲 — 以太合奏团"),
                CoreLyricLine(6500L, "When the amber sun sinks beneath the glass horizon", "当琥珀色的夕阳沉入琉璃地平线"),
                CoreLyricLine(14000L, "Strings awaken in the hush of the concert hall", "弦音在音乐厅的静谧中苏醒"),
                CoreLyricLine(22400L, "A distant echo answers from the northern ridge", "远方的回声自北岭应和"),
                CoreLyricLine(31000L, "Twenty-four bits of dynamic breath and shadow", "二十四比特的动态呼吸与光影"),
                CoreLyricLine(40200L, "Every bow stroke shimmers in the twilight air", "每一次运弓都在暮色中闪烁"),
                CoreLyricLine(50000L, "Rising through the velvet nocturnal tide", "穿过天鹅绒般的夜潮缓缓升起"),
                CoreLyricLine(62500L, "Until only starlight and resonance remain", "直至唯有星光与共鸣长存")
            ),
            "track-nocturne-in-cobalt" to listOf(
                CoreLyricLine(0L, "Nocturne in Cobalt — Lumina Quartet", "钴蓝夜曲 — 流光四重奏"),
                CoreLyricLine(8000L, "Cobalt rain traces silver lines on the windowpane", "钴蓝色的雨在窗棂勾勒银线"),
                CoreLyricLine(17200L, "Piano notes fall like ripples across still water", "琴音如涟漪落入静水"),
                CoreLyricLine(27500L, "In the stillness between two heartbeats", "在两次心跳之间的寂静里"),
                CoreLyricLine(38000L, "Memory unfolds its quiet lantern", "记忆悄然点亮幽微的灯火"),
                CoreLyricLine(49500L, "Cello whispers beneath the midnight arch", "大提琴在午夜拱廊下低语"),
                CoreLyricLine(61000L, "Carrying our unspoken song into the dawn", "将未竟之歌带向黎明")
            ),
            "track-solstice-drift" to listOf(
                CoreLyricLine(0L, "Solstice Drift — Kairo & Sorel", "至日漂流 — Kairo & Sorel"),
                CoreLyricLine(5800L, "Warm analog waves meet the winter solstice wind", "温暖的模拟声浪邂逅冬至的微风"),
                CoreLyricLine(13600L, "Drifting weightless above the sleeping city lights", "失重般漂浮于沉睡的城市灯火之上"),
                CoreLyricLine(22000L, "Time slows down to forty-eight thousand pulses", "时间放缓为每秒四万八千次脉动"),
                CoreLyricLine(31500L, "Hold this fleeting moment in a golden bookmark", "将这转瞬即逝的时刻珍藏进金色书签"),
                CoreLyricLine(42000L, "Where the horizon line meets the endless sea", "在地平线与无垠大海交汇之处")
            )
        )

        private fun defaultDemoTracks(): List<CoreTrackView> = listOf(
            CoreTrackView(
                id = "track-twilight-overture",
                queueEntryId = "occ-0-track-twilight-overture",
                title = "Twilight Overture (Hi-Res Master)",
                artist = "Aether Ensemble",
                artists = listOf("Aether Ensemble"),
                album = "Echoes of Dusk",
                source = "content://media/external/audio/media/1001",
                duration = 248.0,
                format = "FLAC",
                sampleRate = 96000,
                bitDepth = 24,
                bitrate = 2822
            ),
            CoreTrackView(
                id = "track-nocturne-in-cobalt",
                queueEntryId = "occ-1-track-nocturne-in-cobalt",
                title = "Nocturne in Cobalt",
                artist = "Lumina Quartet",
                artists = listOf("Lumina Quartet"),
                album = "Midnight Resonance",
                source = "content://media/external/audio/media/1002",
                duration = 312.0,
                format = "FLAC",
                sampleRate = 192000,
                bitDepth = 24,
                bitrate = 4608
            ),
            CoreTrackView(
                id = "track-solstice-drift",
                queueEntryId = "occ-2-track-solstice-drift",
                title = "Solstice Drift",
                artist = "Kairo & Sorel",
                artists = listOf("Kairo", "Sorel"),
                album = "Horizon Line",
                source = "content://media/external/audio/media/1003",
                duration = 196.0,
                format = "WAV",
                sampleRate = 48000,
                bitDepth = 24,
                bitrate = 2304
            )
        )

        private const val FALLBACK_PORTABLE_CORE_JS = """
            (function (global) {
              var hostState = {
                epoch: "epoch-js-default",
                protocolVersion: 1,
                lastRequestId: null
              };
              global.__TWILIGHT_PORTABLE_CORE__ = {
                createHost: function (config) {
                  if (config && config.epoch) hostState.epoch = config.epoch;
                  return this;
                },
                bootHost: function (config) {
                  if (config && config.epoch) hostState.epoch = config.epoch;
                  return { ok: true, epoch: hostState.epoch };
                },
                handleRequest: function (envelope) {
                  hostState.lastRequestId = envelope ? envelope.requestId : null;
                  return { ok: true, requestId: hostState.lastRequestId, epoch: hostState.epoch };
                },
                cancelRequest: function (cancelEnvelope) {
                  return { ok: true, cancelledRequestId: cancelEnvelope ? cancelEnvelope.requestId : null };
                }
              };
            })(globalThis);
        """
    }
}
