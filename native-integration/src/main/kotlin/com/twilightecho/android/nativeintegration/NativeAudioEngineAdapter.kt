package com.twilightecho.android.nativeintegration

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.PlayMode
import com.twilightecho.android.runtimehost.AudioEnginePort
import com.twilightecho.android.runtimehost.AudioEvent
import com.twilightecho.android.runtimehost.AudioSnapshot
import com.twilightecho.android.runtimehost.AudioSourceDescriptor
import com.twilightecho.android.runtimehost.CancelHandle
import com.twilightecho.android.runtimehost.ResourceAccessPort
import com.twilightecho.android.runtimehost.ResourceAccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Implements `AudioEnginePort` over `TwilightAudioEngineJni` (`twilight_audio_engine.h`).
 *
 * Non-RT Event Dispatch (Section 13.5):
 * `TAE_SetEventCallback` notifications arrive on native worker/clock threads and are immediately
 * offered into a lock-free bounded `Channel` (`nonRtEventQueue`) drained by a dedicated non-RT
 * control dispatcher before notifying `runtime-host` subscribers.
 *
 * Also maintains an in-memory simulation fallback when `libtwilight_audio_jni.so` is not loaded
 * (e.g., on JVM unit test hosts or before NDK build).
 */
class NativeAudioEngineAdapter(
    private val jni: TwilightAudioEngineJni = TwilightAudioEngineJni(),
    private val resourceAccess: ResourceAccessPort? = null,
    private val json: Json = DefaultCoreClient.DefaultJson
) : AudioEnginePort {

    private val closed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(AudioEvent) -> Unit>()

    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TwilightAudio-NonRtControl").apply {
            isDaemon = true
        }
    }
    private val controlDispatcher = controlExecutor.asCoroutineDispatcher()
    private val controlScope = CoroutineScope(SupervisorJob() + controlDispatcher)

    private data class RawNativeEvent(val eventType: String, val payloadJson: String)

    private val nonRtEventQueue = Channel<RawNativeEvent>(capacity = 256)

    @Volatile
    private var engineHandle: Long = 0L

    // Fallback/cached state when running without native .so or between native polls
    @Volatile
    private var cachedSnapshot = AudioSnapshot(
        engineSessionId = "tae-android-session",
        state = "paused",
        positionMs = 0L,
        durationMs = 248_000L,
        volume = 1.0,
        playbackRate = 1.0,
        playMode = "sequence",
        queueIndex = 0,
        codec = "FLAC",
        sampleRate = 96000,
        bitDepth = 24,
        channels = 2,
        outputBackend = "oboe",
        actualSampleRate = 96000,
        actualBitDepth = 24,
        outputPerfect = true
    )

    private var queuedTracks: List<CoreTrackView> = emptyList()
    private var activeResourceId: String? = null
    private var lastResolvedNativePath: String? = null
    private var lastDspConfigJson: String = "{}"

    val currentResolvedNativePath: String?
        get() = lastResolvedNativePath

    private suspend fun resolveAndLeasePlayablePath(rawSource: String): String {
        val prevId = activeResourceId
        if (prevId != null && prevId != rawSource) {
            resourceAccess?.releasePlayableResource(prevId)
        }
        activeResourceId = rawSource
        val access = resourceAccess ?: return rawSource.also { lastResolvedNativePath = it }
        return when (val res = access.acquirePlayableResource(rawSource)) {
            is ResourceAccessResult.Ok -> res.value.nativePath.also { lastResolvedNativePath = it }
            is ResourceAccessResult.Error -> {
                if (res.code == com.twilightecho.android.coreclient.CoreErrorCode.PERMISSION_EXPIRED) {
                    broadcastEvent(
                        AudioEvent.EngineError(
                            code = res.code.name,
                            message = res.message,
                            recoverable = res.retryable
                        )
                    )
                }
                rawSource.also { lastResolvedNativePath = it }
            }
        }
    }

    private suspend fun releaseActivePlayableLease() {
        val prevId = activeResourceId
        activeResourceId = null
        if (prevId != null) {
            resourceAccess?.releasePlayableResource(prevId)
        }
    }

    private val jniEventListener = TwilightAudioEventListener { eventType, payloadJson ->
        // Non-blocking enqueue from native callback thread
        nonRtEventQueue.trySend(RawNativeEvent(eventType, payloadJson))
    }

    init {
        if (jni.isNativeLibraryLoaded) {
            engineHandle = runCatching { jni.nativeCreateEngine() }.getOrDefault(0L)
            if (engineHandle != 0L) {
                jni.nativeSetEventListener(engineHandle, jniEventListener)
                jni.nativeSetStateEventsEnabled(engineHandle, true)
            }
        }

        controlScope.launch {
            for (raw in nonRtEventQueue) {
                processNativeEventOnControlThread(raw)
            }
        }
    }

    /**
     * Immediate synchronous safety brake entrypoint callable by `AndroidPlaybackHost` when
     * `AUDIOFOCUS_LOSS` or `ACTION_AUDIO_BECOMING_NOISY` occurs (Section 5.4).
     */
    fun emergencyBrakePause() {
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            runCatching { jni.nativePause(handle) }
        }
        cachedSnapshot = cachedSnapshot.copy(state = "paused")
        nonRtEventQueue.trySend(
            RawNativeEvent(
                eventType = "playback-info",
                payloadJson = json.encodeToString(cachedSnapshot)
            )
        )
    }

    override suspend fun load(source: AudioSourceDescriptor) = withContext(controlDispatcher) {
        val playablePath = resolveAndLeasePlayablePath(source.resourceId.ifBlank { source.nativeUri })
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativePlay(handle, playablePath, source.startTimeSeconds)
            jni.nativePause(handle)
        }
        cachedSnapshot = cachedSnapshot.copy(
            state = "paused",
            positionMs = (source.startTimeSeconds * 1000.0).toLong()
        )
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun play(startTimeSeconds: Double?) = withContext(controlDispatcher) {
        val handle = engineHandle
        val currentTrack = queuedTracks.getOrNull(cachedSnapshot.queueIndex.coerceAtLeast(0))
        val rawUri = currentTrack?.source ?: currentTrack?.id ?: activeResourceId ?: ""
        val playablePath = if (rawUri.isNotBlank()) resolveAndLeasePlayablePath(rawUri) else ""
        if (jni.isNativeLibraryLoaded && handle != 0L && playablePath.isNotBlank()) {
            jni.nativePlay(handle, playablePath, startTimeSeconds ?: (cachedSnapshot.positionMs / 1000.0))
        }
        cachedSnapshot = cachedSnapshot.copy(
            state = "playing",
            positionMs = startTimeSeconds?.let { (it * 1000.0).toLong() } ?: cachedSnapshot.positionMs,
            durationMs = currentTrack?.let { (it.duration * 1000.0).toLong() } ?: cachedSnapshot.durationMs
        )
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun pause() = withContext(controlDispatcher) {
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativePause(handle)
        }
        cachedSnapshot = cachedSnapshot.copy(state = "paused")
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun stop() = withContext(controlDispatcher) {
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativeStop(handle)
        }
        releaseActivePlayableLease()
        cachedSnapshot = cachedSnapshot.copy(state = "idle", positionMs = 0L)
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun seek(positionMs: Long) = withContext(controlDispatcher) {
        val clampedMs = positionMs.coerceAtLeast(0L)
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativeSeek(handle, clampedMs / 1000.0)
        }
        cachedSnapshot = cachedSnapshot.copy(positionMs = clampedMs)
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun setVolume(volume: Double) = withContext(controlDispatcher) {
        val clamped = volume.coerceIn(0.0, 1.0)
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativeSetVolume(handle, clamped)
        }
        cachedSnapshot = cachedSnapshot.copy(volume = clamped)
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun setPlayMode(mode: PlayMode) = withContext(controlDispatcher) {
        val wire = mode.toWireValue()
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativeSetPlayMode(handle, wire)
        }
        cachedSnapshot = cachedSnapshot.copy(playMode = wire)
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int) =
        withContext(controlDispatcher) {
            queuedTracks = tracks
            val safeIndex = if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.lastIndex)
            val handle = engineHandle
            if (jni.isNativeLibraryLoaded && handle != 0L) {
                val queueJson = buildJsonArray {
                    tracks.forEachIndexed { idx, track ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(track.id))
                                put("queueEntryId", JsonPrimitive(track.queueEntryId ?: "occ-$idx"))
                                put("source", JsonPrimitive(track.source ?: track.id))
                                put("title", JsonPrimitive(track.title))
                                put("artist", JsonPrimitive(track.artist))
                                put("album", JsonPrimitive(track.album))
                                put("duration", JsonPrimitive(track.duration))
                            }
                        )
                    }
                }.toString()
                jni.nativeLoadQueue(handle, queueJson, safeIndex.coerceAtLeast(0))
            }
            val selected = tracks.getOrNull(safeIndex)
            cachedSnapshot = cachedSnapshot.copy(
                queueIndex = safeIndex,
                positionMs = 0L,
                durationMs = selected?.let { (it.duration * 1000.0).toLong() } ?: cachedSnapshot.durationMs,
                codec = selected?.format ?: "FLAC",
                sampleRate = selected?.sampleRate ?: 96000,
                bitDepth = selected?.bitDepth ?: 24
            )
            broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
        }

    override suspend fun selectQueueIndex(index: Int) = withContext(controlDispatcher) {
        if (index !in queuedTracks.indices) return@withContext
        val selected = queuedTracks[index]
        val rawUri = selected.source ?: selected.id
        resolveAndLeasePlayablePath(rawUri)
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativeSelectQueueIndex(handle, index)
        }
        cachedSnapshot = cachedSnapshot.copy(
            queueIndex = index,
            positionMs = 0L,
            durationMs = (selected.duration * 1000.0).toLong(),
            codec = selected.format ?: "FLAC",
            sampleRate = selected.sampleRate ?: 96000,
            bitDepth = selected.bitDepth ?: 24
        )
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun setDspConfig(configJson: String) = withContext(controlDispatcher) {
        lastDspConfigJson = configJson
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativeSetDspConfig(handle, configJson)
        }
        val obj = runCatching { json.parseToJsonElement(configJson) as? JsonObject }.getOrNull()
        val eqEnabled = obj?.get("enabled")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val bitPerfect = obj?.get("bitPerfect")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        cachedSnapshot = cachedSnapshot.copy(
            outputPerfect = bitPerfect && !eqEnabled
        )
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun next() = withContext(controlDispatcher) {
        if (queuedTracks.isEmpty()) return@withContext
        val nextIdx = (cachedSnapshot.queueIndex + 1) % queuedTracks.size
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativeNext(handle)
        }
        selectQueueIndex(nextIdx)
    }

    override suspend fun previous() = withContext(controlDispatcher) {
        if (queuedTracks.isEmpty()) return@withContext
        val prevIdx = if (cachedSnapshot.queueIndex - 1 < 0) {
            queuedTracks.lastIndex
        } else {
            cachedSnapshot.queueIndex - 1
        }
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativePrevious(handle)
        }
        selectQueueIndex(prevIdx)
    }

    override suspend fun getSnapshot(): AudioSnapshot = withContext(controlDispatcher) {
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            val rawJson = runCatching { jni.nativeGetPlaybackInfoJson(handle) }.getOrNull()
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                parsePlaybackInfoSnapshot(rawJson)?.let { parsed ->
                    cachedSnapshot = parsed
                }
            }
        }
        cachedSnapshot
    }

    override fun subscribe(listener: (AudioEvent) -> Unit): CancelHandle {
        listeners.add(listener)
        return CancelHandle {
            listeners.remove(listener)
        }
    }

    private fun processNativeEventOnControlThread(raw: RawNativeEvent) {
        when (raw.eventType) {
            "property-change", "playback-info", "start-file", "queue-change", "config-applied" -> {
                parsePlaybackInfoSnapshot(raw.payloadJson)?.let { parsed ->
                    cachedSnapshot = parsed
                    broadcastEvent(AudioEvent.SnapshotUpdated(parsed))
                }
            }
            "end-file" -> {
                val obj = runCatching {
                    json.parseToJsonElement(raw.payloadJson) as? JsonObject
                }.getOrNull()
                val reason = obj?.get("reason")?.jsonPrimitive?.contentOrNull ?: "eof"
                if (reason == "eof") {
                    broadcastEvent(AudioEvent.TrackEnded(cachedSnapshot.queueIndex, reason))
                }
            }
            "error" -> {
                val obj = runCatching {
                    json.parseToJsonElement(raw.payloadJson) as? JsonObject
                }.getOrNull()
                val code = obj?.get("code")?.jsonPrimitive?.contentOrNull ?: "TAE_INTERNAL_ERROR"
                val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Audio engine error"
                broadcastEvent(
                    AudioEvent.EngineError(
                        code = code,
                        message = message,
                        recoverable = true
                    )
                )
            }
        }
    }

    private fun parsePlaybackInfoSnapshot(payloadJson: String): AudioSnapshot? = runCatching {
        val obj = json.parseToJsonElement(payloadJson) as? JsonObject ?: return null
        val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: cachedSnapshot.state
        val posSec = obj["position"]?.jsonPrimitive?.doubleOrNull
        val durSec = obj["duration"]?.jsonPrimitive?.doubleOrNull
        val posMs = posSec?.let { (it * 1000.0).toLong() }
            ?: obj["positionMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: cachedSnapshot.positionMs
        val durMs = durSec?.let { (it * 1000.0).toLong() }
            ?: obj["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: cachedSnapshot.durationMs

        cachedSnapshot.copy(
            state = state,
            positionMs = posMs,
            durationMs = durMs,
            volume = obj["volume"]?.jsonPrimitive?.doubleOrNull ?: cachedSnapshot.volume,
            playbackRate = obj["playbackRate"]?.jsonPrimitive?.doubleOrNull ?: cachedSnapshot.playbackRate,
            playMode = obj["playMode"]?.jsonPrimitive?.contentOrNull ?: cachedSnapshot.playMode,
            queueIndex = obj["queueIndex"]?.jsonPrimitive?.intOrNull ?: cachedSnapshot.queueIndex,
            codec = obj["codec"]?.jsonPrimitive?.contentOrNull ?: cachedSnapshot.codec,
            sampleRate = obj["sourceSampleRate"]?.jsonPrimitive?.intOrNull ?: cachedSnapshot.sampleRate,
            bitDepth = obj["sourceBitDepth"]?.jsonPrimitive?.intOrNull ?: cachedSnapshot.bitDepth,
            outputBackend = obj["outputBackend"]?.jsonPrimitive?.contentOrNull ?: cachedSnapshot.outputBackend,
            actualSampleRate = obj["actualSampleRate"]?.jsonPrimitive?.intOrNull ?: cachedSnapshot.actualSampleRate,
            actualBitDepth = obj["actualBitDepth"]?.jsonPrimitive?.intOrNull ?: cachedSnapshot.actualBitDepth
        )
    }.getOrNull()

    private fun broadcastEvent(event: AudioEvent) {
        for (listener in listeners) {
            runCatching { listener(event) }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            listeners.clear()
            nonRtEventQueue.close()
            controlScope.cancel()
            val handle = engineHandle
            engineHandle = 0L
            if (jni.isNativeLibraryLoaded && handle != 0L) {
                runCatching {
                    jni.nativeSetEventListener(handle, null)
                    jni.nativeDestroyEngine(handle)
                }
            }
            controlExecutor.shutdownNow()
        }
    }
}
