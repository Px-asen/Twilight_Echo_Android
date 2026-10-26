package com.twilightecho.android.nativeintegration

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import com.twilightecho.android.coreclient.CoreErrorCode
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Implements `AudioEnginePort` over `TwilightAudioEngineJni` (`twilight_audio_engine.h`) with a
 * hardware-backed Android `MediaPlayer` + `Equalizer` execution fallback when C++ FFmpeg decoders
 * are not linked or when playing synthesized demo tracks on device.
 *
 * Non-RT Event Dispatch (Section 13.5):
 * `TAE_SetEventCallback` and hardware completion notifications arrive on worker threads and are
 * offered into a lock-free bounded `Channel` (`nonRtEventQueue`) drained by a dedicated non-RT
 * control dispatcher before notifying `runtime-host` subscribers.
 */
class NativeAudioEngineAdapter(
    private val jni: TwilightAudioEngineJni = TwilightAudioEngineJni(),
    private val resourceAccess: ResourceAccessPort? = null,
    private val appContext: Context? = null,
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

    // Android hardware MediaPlayer + Equalizer fallback when C++ FFmpeg is not linked
    private var platformMediaPlayer: MediaPlayer? = null
    private var platformEqualizer: Equalizer? = null
    private var loadedMediaSourceKey: String? = null
    private var progressTickerJob: Job? = null

    // Cached state updated by C++ engine or Android hardware MediaPlayer
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
                if (res.code == CoreErrorCode.PERMISSION_EXPIRED) {
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
     * `AUDIOFOCUS_LOSS`, `ACTION_AUDIO_BECOMING_NOISY`, or physical headset disconnect occurs (Section 5.4).
     */
    fun emergencyBrakePause() {
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            runCatching { jni.nativePause(handle) }
        }
        runCatching {
            platformMediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.pause()
            }
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

        var nativeHandled = false
        if (jni.isNativeLibraryLoaded && handle != 0L && playablePath.isNotBlank()) {
            val rc = runCatching {
                jni.nativePlay(handle, playablePath, startTimeSeconds ?: (cachedSnapshot.positionMs / 1000.0))
            }.getOrDefault(-1)
            nativeHandled = (rc == 0)
        }

        var actualDurationMs = currentTrack?.let { (it.duration * 1000.0).toLong() } ?: cachedSnapshot.durationMs
        var actualPositionMs = startTimeSeconds?.let { (it * 1000.0).toLong() } ?: cachedSnapshot.positionMs

        if (!nativeHandled && appContext != null) {
            val mpResult = startOrResumePlatformMediaPlayer(
                context = appContext,
                track = currentTrack,
                rawUri = rawUri,
                playablePath = playablePath,
                startTimeSeconds = startTimeSeconds
            )
            if (mpResult != null) {
                actualPositionMs = mpResult.first
                if (mpResult.second > 0L) {
                    actualDurationMs = mpResult.second
                }
            }
        }

        cachedSnapshot = cachedSnapshot.copy(
            state = "playing",
            positionMs = actualPositionMs,
            durationMs = actualDurationMs
        )
        ensureProgressTickerRunning()
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun pause() = withContext(controlDispatcher) {
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativePause(handle)
        }
        runCatching {
            platformMediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    cachedSnapshot = cachedSnapshot.copy(positionMs = mp.currentPosition.toLong())
                    mp.pause()
                }
            }
        }
        stopProgressTicker()
        cachedSnapshot = cachedSnapshot.copy(state = "paused")
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun stop() = withContext(controlDispatcher) {
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativeStop(handle)
        }
        stopProgressTicker()
        releasePlatformMediaPlayer()
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
        runCatching {
            platformMediaPlayer?.seekTo(clampedMs.toInt())
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
        runCatching {
            val v = clamped.toFloat()
            platformMediaPlayer?.setVolume(v, v)
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
            val prevTrackId = queuedTracks.getOrNull(cachedSnapshot.queueIndex)?.id
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
            if (selected?.id != prevTrackId) {
                loadedMediaSourceKey = null
            }
            cachedSnapshot = cachedSnapshot.copy(
                queueIndex = safeIndex,
                positionMs = if (selected?.id == prevTrackId) cachedSnapshot.positionMs else 0L,
                durationMs = selected?.let { (it.duration * 1000.0).toLong() } ?: cachedSnapshot.durationMs,
                codec = selected?.format ?: "FLAC",
                sampleRate = selected?.sampleRate ?: 96000,
                bitDepth = selected?.bitDepth ?: 24
            )
            broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
        }

    override suspend fun selectQueueIndex(index: Int) = withContext(controlDispatcher) {
        if (index !in queuedTracks.indices) return@withContext
        val wasPlaying = cachedSnapshot.state == "playing"
        val selected = queuedTracks[index]
        val rawUri = selected.source ?: selected.id
        val playablePath = resolveAndLeasePlayablePath(rawUri)
        val handle = engineHandle
        var nativeHandled = false
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            val rc = runCatching { jni.nativeSelectQueueIndex(handle, index) }.getOrDefault(-1)
            nativeHandled = (rc == 0)
        }
        loadedMediaSourceKey = null
        cachedSnapshot = cachedSnapshot.copy(
            queueIndex = index,
            positionMs = 0L,
            durationMs = (selected.duration * 1000.0).toLong(),
            codec = selected.format ?: "FLAC",
            sampleRate = selected.sampleRate ?: 96000,
            bitDepth = selected.bitDepth ?: 24
        )
        if (wasPlaying && !nativeHandled && appContext != null) {
            val mpResult = startOrResumePlatformMediaPlayer(
                context = appContext,
                track = selected,
                rawUri = rawUri,
                playablePath = playablePath,
                startTimeSeconds = 0.0
            )
            if (mpResult != null && mpResult.second > 0L) {
                cachedSnapshot = cachedSnapshot.copy(
                    positionMs = mpResult.first,
                    durationMs = mpResult.second
                )
            }
        }
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
        applyDspConfigToPlatformEqualizer(obj)
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
        runCatching {
            platformMediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    cachedSnapshot = cachedSnapshot.copy(
                        positionMs = mp.currentPosition.toLong().coerceAtLeast(0L)
                    )
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

    private fun startOrResumePlatformMediaPlayer(
        context: Context,
        track: CoreTrackView?,
        rawUri: String,
        playablePath: String,
        startTimeSeconds: Double?
    ): Pair<Long, Long>? = runCatching {
        val sourceKey = "${track?.id ?: rawUri}|$playablePath"
        val existing = platformMediaPlayer
        if (existing != null && loadedMediaSourceKey == sourceKey) {
            if (startTimeSeconds != null) {
                existing.seekTo((startTimeSeconds * 1000.0).toInt().coerceAtLeast(0))
            }
            val vol = cachedSnapshot.volume.toFloat()
            existing.setVolume(vol, vol)
            if (!existing.isPlaying) {
                existing.start()
            }
            return@runCatching Pair(
                existing.currentPosition.toLong().coerceAtLeast(0L),
                existing.duration.toLong().coerceAtLeast(0L)
            )
        }

        releasePlatformMediaPlayer()

        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )

        val configured = configureMediaPlayerDataSource(context, mp, track, rawUri, playablePath)
        if (!configured) {
            mp.release()
            return@runCatching null
        }

        mp.prepare()
        val startMs = startTimeSeconds?.let { (it * 1000.0).toInt().coerceAtLeast(0) }
            ?: cachedSnapshot.positionMs.toInt().coerceAtLeast(0)
        if (startMs > 0 && startMs < mp.duration) {
            mp.seekTo(startMs)
        }
        val vol = cachedSnapshot.volume.toFloat()
        mp.setVolume(vol, vol)

        mp.setOnCompletionListener {
            nonRtEventQueue.trySend(RawNativeEvent("end-file", """{"reason":"eof"}"""))
        }

        platformMediaPlayer = mp
        loadedMediaSourceKey = sourceKey

        // Bind hardware Equalizer to MediaPlayer's audioSessionId
        runCatching {
            val eq = Equalizer(0, mp.audioSessionId)
            platformEqualizer = eq
            val dspObj = runCatching { json.parseToJsonElement(lastDspConfigJson) as? JsonObject }.getOrNull()
            applyDspConfigToPlatformEqualizer(dspObj)
        }

        mp.start()
        Pair(mp.currentPosition.toLong().coerceAtLeast(0L), mp.duration.toLong().coerceAtLeast(0L))
    }.getOrNull()

    private fun configureMediaPlayerDataSource(
        context: Context,
        mp: MediaPlayer,
        track: CoreTrackView?,
        rawUri: String,
        playablePath: String
    ): Boolean {
        // 1. Try resolved local or /proc/self/fd/<fd> path
        if (playablePath.startsWith("/")) {
            val f = File(playablePath)
            if (f.exists() && f.canRead()) {
                val ok = runCatching {
                    mp.setDataSource(playablePath)
                    true
                }.getOrDefault(false)
                if (ok) return true
            }
        }

        // 2. Try Android content:// URI directly via ContentResolver
        if (rawUri.startsWith("content://", ignoreCase = true)) {
            val ok = runCatching {
                mp.setDataSource(context, Uri.parse(rawUri))
                true
            }.getOrDefault(false)
            if (ok) return true
        }

        // 3. Fallback to synthesized real harmonic stereo WAV for built-in demo tracks
        val demoId = track?.id?.ifBlank { "track-twilight-overture" } ?: "track-twilight-overture"
        return runCatching {
            val wavFile = DemoAudioSynthesizer.ensureDemoWavFile(context, demoId)
            mp.setDataSource(wavFile.absolutePath)
            true
        }.getOrDefault(false)
    }

    private fun applyDspConfigToPlatformEqualizer(dspObj: JsonObject?) {
        val eq = platformEqualizer ?: return
        if (dspObj == null) {
            runCatching { eq.enabled = false }
            return
        }
        runCatching {
            val enabled = dspObj["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val preampDb = dspObj["preampDb"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val bandsDb = dspObj["bandsDb"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }.orEmpty()
            eq.enabled = enabled
            if (enabled && bandsDb.isNotEmpty()) {
                val numBands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val minMb = range.getOrElse(0) { -1200 }.toInt()
                val maxMb = range.getOrElse(1) { 1200 }.toInt()
                for (bandIdx in 0 until numBands) {
                    val mappedIdx = ((bandIdx.toDouble() / numBands.coerceAtLeast(1)) * bandsDb.size)
                        .toInt()
                        .coerceIn(0, bandsDb.lastIndex)
                    val totalDb = (bandsDb[mappedIdx] + preampDb).coerceIn(-12.0, 12.0)
                    val targetMb = (totalDb * 100.0).roundToInt().coerceIn(minMb, maxMb).toShort()
                    eq.setBandLevel(bandIdx.toShort(), targetMb)
                }
            }
        }
    }

    private fun ensureProgressTickerRunning() {
        if (appContext == null) return
        if (progressTickerJob?.isActive == true) return
        progressTickerJob = controlScope.launch {
            while (isActive && cachedSnapshot.state == "playing") {
                delay(250L)
                if (cachedSnapshot.state != "playing") break
                val mp = platformMediaPlayer
                if (mp != null) {
                    val isPlaying = runCatching { mp.isPlaying }.getOrDefault(false)
                    if (isPlaying) {
                        val posMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(cachedSnapshot.positionMs)
                        val durMs = runCatching { mp.duration.toLong() }.getOrDefault(cachedSnapshot.durationMs ?: 0L)
                        cachedSnapshot = cachedSnapshot.copy(
                            positionMs = posMs.coerceAtLeast(0L),
                            durationMs = if (durMs > 0L) durMs else cachedSnapshot.durationMs
                        )
                        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
                    }
                }
            }
        }
    }

    private fun stopProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = null
    }

    private fun releasePlatformMediaPlayer() {
        loadedMediaSourceKey = null
        runCatching {
            platformEqualizer?.release()
        }
        platformEqualizer = null
        runCatching {
            platformMediaPlayer?.stop()
        }
        runCatching {
            platformMediaPlayer?.release()
        }
        platformMediaPlayer = null
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
            stopProgressTicker()
            releasePlatformMediaPlayer()
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
