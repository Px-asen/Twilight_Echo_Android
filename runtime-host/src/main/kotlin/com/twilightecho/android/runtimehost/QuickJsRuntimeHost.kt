package com.twilightecho.android.runtimehost

import android.content.Context
import com.twilightecho.android.coreclient.CORE_PROTOCOL_VERSION
import com.twilightecho.android.coreclient.CoreCancelEnvelope
import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreErrorView
import com.twilightecho.android.coreclient.CoreEventMessage
import com.twilightecho.android.coreclient.CorePhase
import com.twilightecho.android.coreclient.CorePlaybackView
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
 * and exposing `CoreTransport` for `CoreClient` consumers (Sections 10.1, 10.4, 10.5).
 *
 * Lifecycle:
 * 1. Initializes `CoreRuntimeThreadDispatcher` (dedicated single thread).
 * 2. Loads `assets/core/music-core.js` and invokes `globalThis.__TWILIGHT_PORTABLE_CORE__.createHost(...)`.
 * 3. Subscribes to `AudioEnginePort` events and routes them onto the serial runtime thread.
 * 4. Restores persisted session state via `SessionStorePort.load()`.
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

    // Authoritative core state mirror on the serial runtime thread
    private val epoch: String = "epoch-android-${UUID.randomUUID().toString().take(8)}"
    private var sequence: Long = 1L
    private var queueRevision: Long = 1L
    private var sessionStoreRevision: String? = null
    private var queueTracks: MutableList<CoreTrackView> = defaultDemoTracks().toMutableList()
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

            // Restore persisted session if available
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

        // Delegate to embedded JS Core first so JS domain rules stay in sync
        jsEngine?.invokeGlobalFunction(
            "globalThis.__TWILIGHT_PORTABLE_CORE__.handleRequest",
            json.encodeToString(envelope)
        )

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
                    ports.audioEngine.loadQueue(queueTracks, currentQueueIndex)
                    if (playImmediately) {
                        ports.audioEngine.play(0.0)
                    }
                    persistSessionSnapshot()
                }
            }
            "playback.platformInterruption" -> {
                val shouldPause = envelope.payload["shouldPause"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                if (shouldPause) {
                    ports.audioEngine.pause()
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
            outputBackend = latestAudioSnapshot.outputBackend ?: "oboe",
            actualSampleRate = latestAudioSnapshot.actualSampleRate ?: 96000,
            actualBitDepth = latestAudioSnapshot.actualBitDepth ?: 24,
            actualChannels = 2,
            outputPerfect = latestAudioSnapshot.outputPerfect,
            gaplessActive = true,
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

        return CoreSnapshot(
            protocolVersion = CORE_PROTOCOL_VERSION,
            epoch = epoch,
            sequence = sequence,
            phase = CorePhase.READY,
            playback = playbackView,
            track = currentTrack,
            queue = queueRef,
            error = errorMessage
        )
    }

    private suspend fun persistSessionSnapshot() {
        val payload = buildJsonObject {
            put("queueRevision", JsonPrimitive(queueRevision))
            put("currentQueueIndex", JsonPrimitive(currentQueueIndex))
            put("playMode", JsonPrimitive(currentPlayMode.toWireValue()))
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
        }
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
        runtimeScope.cancel()
        runCatching {
            jsEngine?.close()
        }
        runtimeDispatcher.close()
    }

    companion object {
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
