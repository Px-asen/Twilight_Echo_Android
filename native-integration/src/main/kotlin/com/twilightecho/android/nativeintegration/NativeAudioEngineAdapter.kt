package com.twilightecho.android.nativeintegration

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaDataSource
import android.media.audiofx.Equalizer
import android.net.Uri
import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CueSource
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.PlayMode
import com.twilightecho.android.coreclient.CoreEqConfigView
import com.twilightecho.android.coreclient.CoreDspStatus
import com.twilightecho.android.coreclient.DesktopMediaReference
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Implements `AudioEnginePort` over `TwilightAudioEngineJni` (`twilight_audio_engine.h`) with a
 * shared FFmpeg -> C++ DSP -> AAudio path for local files and authenticated desktop bytes.
 * MediaPlayer is retained only for explicitly JNI-disabled development builds.
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
    private val json: Json = DefaultCoreClient.DefaultJson,
    private val desktopStream: ((String) -> MediaDataSource)? = null,
    private val providerStream: ((String) -> MediaDataSource)? = null,
    private val onUserPause: () -> Unit = {}
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

    private data class RawNativeEvent(val eventType: String, val payloadJson: String, val fromNative: Boolean = false)

    private val nonRtEventQueue = Channel<RawNativeEvent>(capacity = 256)

    @Volatile
    private var engineHandle: Long = 0L

    // MediaPlayer + Equalizer for explicitly JNI-disabled development builds.
    private var platformMediaPlayer: MediaPlayer? = null
    @Volatile private var platformDataSource: MediaDataSource? = null
    private var platformPreparationError: Throwable? = null
    private var platformEqualizer: Equalizer? = null
    private var loadedMediaSourceKey: String? = null
    private var progressTickerJob: Job? = null

    // Cached state updated by C++ engine or Android hardware MediaPlayer
    @Volatile
    private var cachedSnapshot = AudioSnapshot(
        engineSessionId = "tae-android-session",
        state = "idle",
        positionMs = 0L,
        durationMs = null,
        volume = 1.0,
        playbackRate = 1.0,
        playMode = "sequence",
        queueIndex = -1,
        outputBackend = "oboe",
        outputPerfect = false
    )

    private var queuedTracks: List<CoreTrackView> = emptyList()
    /** Core generation of [queuedTracks]; stamped on every snapshot this adapter reports. */
    @Volatile private var queueGeneration = 0L
    private val gain = PlaybackGain()
    val isPlaying: Boolean get() = cachedSnapshot.state == "playing"
    private var activeResourceId: String? = null
    private var lastResolvedNativePath: String? = null
    private var lastDspConfigJson: String = "{}"
    private var lastAppliedConfig: CoreEqConfigView? = null
    private var dspRevision = 0L
    @Volatile private var preparingNative = false
    @Volatile private var nativePlaybackEventsEnabled = false
    private var nativeSourceLoaded = false
    private var nativePlayMode = "sequential"
    private val inputProvider = NativeAudioInputProvider { source ->
        when {
            source.startsWith("provider://") -> providerStream?.invoke(source)
                ?: throw java.io.IOException("流媒体插件尚未启用")
            source.startsWith(DesktopMediaReference.PREFIX) -> desktopStream?.invoke(source)
                ?: throw java.io.IOException("电脑串流尚未连接")
            source.startsWith("content://", ignoreCase = true) -> ContentAudioDataSource(
                appContext ?: throw java.io.IOException("无法访问音频文件"), source)
            else -> null // FFmpeg's ordinary file IO remains unchanged.
        }
    }

    val currentResolvedNativePath: String?
        get() = lastResolvedNativePath

    private suspend fun resolveAndLeasePlayablePath(rawSource: String): String {
        val prevId = activeResourceId
        if (prevId != null && prevId != rawSource) {
            resourceAccess?.releasePlayableResource(prevId)
        }
        activeResourceId = rawSource
        if (rawSource.startsWith(DesktopMediaReference.PREFIX) || rawSource.startsWith("provider://")) return rawSource
        if (appContext != null && engineHandle != 0L && rawSource.startsWith("content://")) {
            return rawSource.also { lastResolvedNativePath = it }
        }
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
        nonRtEventQueue.trySend(RawNativeEvent(eventType, payloadJson, fromNative = true))
    }

    init {
        if (jni.isNativeLibraryLoaded) {
            engineHandle = runCatching { jni.nativeCreateEngine() }.getOrDefault(0L)
            if (engineHandle != 0L) {
                check(jni.nativeSetInputProvider(engineHandle, inputProvider) == 0)
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
        if (closed.get()) return
        if (preparingNative) inputProvider.cancelAll()
        if (platformMediaPlayer == null) platformDataSource?.close()
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            runCatching { jni.nativeSetPaused(handle, true) }
        }
        runCatching {
            platformMediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.pause()
            }
        }
        cachedSnapshot = cachedSnapshot.copy(state = "paused")
        nonRtEventQueue.trySend(
            RawNativeEvent(
                eventType = "safety-brake",
                payloadJson = "{}"
            )
        )
    }

    override suspend fun load(source: AudioSourceDescriptor) = withContext(controlDispatcher) {
        requireNativeAvailable()
        nativeSourceLoaded = false
        cachedSnapshot = cachedSnapshot.withoutOutputFacts()
        val playablePath = resolveAndLeasePlayablePath(CueSource.audio(source.resourceId.ifBlank { source.nativeUri }))
        inputProvider.resume()
        nativePlaybackEventsEnabled = true
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            preparingNative = true
            try { requireNativeSuccess(jni.nativeLoad(handle, playablePath, source.startTimeSeconds)) }
            finally { preparingNative = false }
            nativeSourceLoaded = true
        }
        cachedSnapshot = cachedSnapshot.copy(
            state = "paused",
            positionMs = (source.startTimeSeconds * 1000.0).toLong()
        )
        if (nativeSourceLoaded) nativeSnapshot()?.let { cachedSnapshot = it }
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun play(startTimeSeconds: Double?) = withContext(controlDispatcher) {
        requireNativeAvailable()
        inputProvider.resume()
        nativePlaybackEventsEnabled = true
        val handle = engineHandle
        val currentTrack = queuedTracks.getOrNull(cachedSnapshot.queueIndex.coerceAtLeast(0))
        val rawUri = currentTrack?.let { CueSource.audio(it.source ?: it.id) } ?: activeResourceId ?: ""
        val playablePath = if (rawUri.isNotBlank()) nativeSource(resolveAndLeasePlayablePath(rawUri), currentTrack) else ""

        var nativeHandled = false
        if (jni.isNativeLibraryLoaded && handle != 0L && playablePath.isNotBlank()) {
            preparingNative = true
            try {
                requireNativeSuccess(jni.nativePlay(handle, playablePath, startTimeSeconds ?: (cachedSnapshot.positionMs / 1000.0)))
            } finally { preparingNative = false }
            nativeHandled = true
            nativeSourceLoaded = true
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
                cachedSnapshot = cachedSnapshot.copy(outputBackend = "Android MediaPlayer",
                    codec = currentTrack?.format, sampleRate = currentTrack?.sampleRate,
                    bitDepth = currentTrack?.bitDepth, actualSampleRate = null, actualBitDepth = null,
                    outputPerfect = false)
            } else {
                stopProgressTicker()
                releasePlatformMediaPlayer()
                val message = platformPreparationError?.message ?: "音频无法打开，请检查文件或访问权限"
                cachedSnapshot = cachedSnapshot.withoutOutputFacts().copy(state = "error", errorMessage = message)
                broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
                broadcastEvent(AudioEvent.EngineError("SOURCE_UNAVAILABLE", message, true))
                throw java.io.IOException(message)
            }
        } else if (nativeHandled) {
            releasePlatformMediaPlayer()
            cachedSnapshot = nativeSnapshot() ?: cachedSnapshot
            broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
            return@withContext
        }

        cachedSnapshot = cachedSnapshot.copy(
            state = "playing",
            errorMessage = null,
            positionMs = actualPositionMs,
            durationMs = actualDurationMs
        )
        ensureProgressTickerRunning()
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun pause() {
        onUserPause()
        withContext(controlDispatcher) {
            val handle = engineHandle
            if (jni.isNativeLibraryLoaded && handle != 0L) {
                requireNativeSuccess(jni.nativeSetPaused(handle, true))
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
    }

    override suspend fun stop() {
        onUserPause()
        nativePlaybackEventsEnabled = false
        // Unblock decoder preparation/reads before waiting for the serial control dispatcher.
        platformDataSource?.close()
        inputProvider.cancelAll()
        withContext(controlDispatcher) {
            nativeSourceLoaded = false
            val handle = engineHandle
            if (jni.isNativeLibraryLoaded && handle != 0L) {
                jni.nativeStop(handle)
            }
            stopProgressTicker()
            releasePlatformMediaPlayer()
            releaseActivePlayableLease()
            cachedSnapshot = cachedSnapshot.withoutOutputFacts().copy(state = "idle", positionMs = 0L)
            broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
        }
    }

    override suspend fun seek(positionMs: Long) = withContext(controlDispatcher) {
        val clampedMs = positionMs.coerceAtLeast(0L)
        val handle = engineHandle
        if (nativeSourceLoaded && jni.isNativeLibraryLoaded && handle != 0L) {
            requireNativeSuccess(jni.nativeSeek(handle, nativeSeekTargetMs(clampedMs, nativeSnapshot()?.durationMs) / 1000.0))
        }
        runCatching {
            platformMediaPlayer?.seekTo(clampedMs.toInt())
        }
        // The engine keeps a seek inside the song, which for a CUE song ends where its part of the file does.
        cachedSnapshot = (if (nativeSourceLoaded) nativeSnapshot() else null) ?: cachedSnapshot.copy(positionMs = clampedMs)
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun setVolume(volume: Double) = withContext(controlDispatcher) {
        gain.volume = volume
        applyGain()
        cachedSnapshot = cachedSnapshot.copy(volume = gain.volume)
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    suspend fun setDucked(ducked: Boolean) = withContext(controlDispatcher) {
        gain.ducked = ducked
        applyGain()
    }

    private fun applyGain() {
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            jni.nativeSetVolume(handle, gain.effective)
        }
        runCatching {
            val v = gain.effective.toFloat()
            platformMediaPlayer?.setVolume(v, v)
        }
    }

    override suspend fun setPlayMode(mode: PlayMode) = withContext(controlDispatcher) {
        val wire = mode.toWireValue()
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            nativePlayMode = when (mode) {
                PlayMode.SINGLE -> "repeat"
                PlayMode.LOOP -> "listLoop"
                PlayMode.SHUFFLE -> "shuffle"
                else -> "sequential"
            }
            jni.nativeSetPlayMode(handle, nativePlayMode)
        }
        cachedSnapshot = cachedSnapshot.copy(playMode = wire)
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int, queueGeneration: Long) {
        withContext(controlDispatcher) { this@NativeAudioEngineAdapter.queueGeneration = queueGeneration }
        loadQueue(tracks, startIndex)
    }

    override suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int) =
        withContext(controlDispatcher) {
            val previous = queuedTracks.getOrNull(cachedSnapshot.queueIndex)
            val selected = tracks.getOrNull(if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.lastIndex))
            val changedSource = previous?.id != selected?.id || previous?.source != selected?.source ||
                previous?.queueEntryId != selected?.queueEntryId
            queuedTracks = tracks
            val safeIndex = if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.lastIndex)
            val handle = engineHandle
            if (jni.isNativeLibraryLoaded && handle != 0L) {
                if (changedSource) {
                    jni.nativeStop(handle)
                    nativeSourceLoaded = false
                }
                val queueJson = nativeQueueJson(tracks, ::nativeSource)
                jni.nativeLoadQueue(handle, queueJson, safeIndex.coerceAtLeast(0))
                // A new queue leaves the engine's lookahead as it was. Sending the play mode again makes the engine
                // preload the new next song, as its own queue handoff does, so the current song runs into it.
                if (!changedSource && nativeSourceLoaded) jni.nativeSetPlayMode(handle, nativePlayMode)
            }
            if (changedSource) {
                onUserPause()
                nativeSourceLoaded = false
                cachedSnapshot = cachedSnapshot.withoutOutputFacts()
                releasePlatformMediaPlayer()
                stopProgressTicker()
            }
            cachedSnapshot = cachedSnapshot.copy(
                state = if (changedSource) "paused" else cachedSnapshot.state,
                errorMessage = if (changedSource) null else cachedSnapshot.errorMessage,
                queueIndex = safeIndex,
                positionMs = if (changedSource) 0L else cachedSnapshot.positionMs,
                durationMs = selected?.let { (it.duration * 1000.0).toLong() },
                codec = selected?.format,
                sampleRate = selected?.sampleRate,
                bitDepth = selected?.bitDepth,
                outputPerfect = false
            )
            broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
        }

    override suspend fun selectQueueIndex(index: Int) = withContext(controlDispatcher) {
        if (index !in queuedTracks.indices) return@withContext
        onUserPause()
        val wasPlaying = cachedSnapshot.state == "playing"
        val selected = queuedTracks[index]
        val rawUri = CueSource.audio(selected.source ?: selected.id)
        resolveAndLeasePlayablePath(rawUri)
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            if (!wasPlaying) {
                jni.nativeStop(handle)
                nativeSourceLoaded = false
            }
            requireNativeSuccess(jni.nativeSelectQueueIndex(handle, index))
        }
        loadedMediaSourceKey = null
        cachedSnapshot = cachedSnapshot.withoutOutputFacts().copy(
            queueIndex = index,
            positionMs = 0L,
            durationMs = (selected.duration * 1000.0).toLong(),
            codec = selected.format,
            sampleRate = selected.sampleRate,
            bitDepth = selected.bitDepth,
            outputPerfect = false
        )
        releasePlatformMediaPlayer()
        if (wasPlaying && appContext != null) play(0.0)
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    override suspend fun setDspConfig(configJson: String) = withContext(controlDispatcher) {
        val obj = json.parseToJsonElement(configJson).jsonObject
        // Existing native JSON entry points remain usable for hosts without phone EQ controls.
        val phoneConfig = if ("bandsDb" in obj || "bitPerfect" in obj || "dsp" in obj)
            json.decodeFromJsonElement<CoreEqConfigView>(obj) else null
        if (phoneConfig != null && lastAppliedConfig?.copy(scenes = emptyList()) == phoneConfig.copy(scenes = emptyList())) {
            lastDspConfigJson = configJson
            lastAppliedConfig = phoneConfig
            return@withContext
        }
        val state = phoneConfig?.let { nativeDspState(it, json, dspRevision + 1L) }
        val handle = engineHandle
        if (jni.isNativeLibraryLoaded && handle != 0L) {
            fun applied(result: Int) {
                if (result != 0) {
                    val error = json.parseToJsonElement(jni.nativeGetLastErrorJson(handle)).jsonObject
                    throw java.io.IOException(error["message"]?.jsonPrimitive?.contentOrNull ?: "音频设置应用失败 ($result)")
                }
            }
            if (phoneConfig != null && state != null) {
                applied(jni.nativeApplyDspState(handle, ++dspRevision, state))
                val previous = lastAppliedConfig
                if (previous == null || nativeOutputConfig(previous) != nativeOutputConfig(phoneConfig))
                    applied(jni.nativeSetOutputConfig(handle, nativeOutputConfig(phoneConfig)))
                if (previous == null || previous.dsp.outputDeviceId != phoneConfig.dsp.outputDeviceId || previous.dsp.exclusive != phoneConfig.dsp.exclusive)
                    applied(jni.nativeSetOutputDevice(handle, phoneConfig.dsp.outputDeviceId + if (phoneConfig.dsp.exclusive) ":exclusive" else ""))
                if (previous == null || previous.dsp.playbackRate != phoneConfig.dsp.playbackRate)
                    applied(jni.nativeSetPlaybackRate(handle, phoneConfig.dsp.playbackRate))
            } else applied(jni.nativeSetDspConfig(handle, configJson))
        } else if (phoneConfig != null && (phoneConfig.dsp.nodes.any { it.enabled } || phoneConfig.dsp.eqMode != "graphic" ||
                phoneConfig.dsp.playbackRate != 1.0 || phoneConfig.dsp.outputStage.targetSampleRate != 0 || phoneConfig.dsp.crossfadeSeconds > 0)) {
            throw java.io.IOException("这些 DSP 功能需要包含共享原生引擎的版本")
        }
        lastDspConfigJson = configJson
        lastAppliedConfig = phoneConfig
        applyDspConfigToPlatformEqualizer(obj)
        if (nativeSourceLoaded) nativeSnapshot()?.let { cachedSnapshot = it }
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
    }

    suspend fun getDspStatus(): CoreDspStatus = withContext(controlDispatcher) {
        if (engineHandle == 0L) CoreDspStatus() else
            json.decodeFromString<CoreDspStatus>(jni.nativeGetDspGraphStatusJson(engineHandle))
    }

    suspend fun setVisualizationDemand(enabled: Boolean) = withContext(controlDispatcher) {
        if (engineHandle != 0L) jni.nativeSetVisualizationDemand(engineHandle, enabled)
        Unit
    }

    suspend fun getVisualization(): JsonObject = withContext(controlDispatcher) {
        if (engineHandle == 0L) JsonObject(emptyMap()) else json.parseToJsonElement(
            jni.nativeGetVisualizationDataJson(engineHandle,
                """{"spectrumPoints":48,"waveformPoints":0,"spectrogramFrames":0,"oscilloscopePoints":0}""")
        ).jsonObject
    }

    override suspend fun next() = withContext(controlDispatcher) {
        if (queuedTracks.isEmpty()) return@withContext
        val nextIdx = (cachedSnapshot.queueIndex + 1) % queuedTracks.size
        selectQueueIndex(nextIdx)
    }

    override suspend fun previous() = withContext(controlDispatcher) {
        if (queuedTracks.isEmpty()) return@withContext
        val prevIdx = if (cachedSnapshot.queueIndex - 1 < 0) {
            queuedTracks.lastIndex
        } else {
            cachedSnapshot.queueIndex - 1
        }
        selectQueueIndex(prevIdx)
    }

    override suspend fun getSnapshot(): AudioSnapshot = withContext(controlDispatcher) {
        val handle = engineHandle
        if (nativeSourceLoaded && platformMediaPlayer == null && jni.isNativeLibraryLoaded && handle != 0L && cachedSnapshot.state != "error") {
            val rawJson = runCatching { jni.nativeGetPlaybackInfoJson(handle) }.getOrNull()
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                parsePlaybackInfoSnapshot(rawJson)?.let { parsed ->
                    cachedSnapshot = parsed
                }
            }
        }
        runCatching {
            platformMediaPlayer?.let { mp ->
                cachedSnapshot = cachedSnapshot.copy(
                    state = if (mp.isPlaying) "playing" else cachedSnapshot.state,
                    positionMs = mp.currentPosition.toLong().coerceAtLeast(0L),
                    durationMs = mp.duration.toLong().coerceAtLeast(0L),
                    outputBackend = "Android MediaPlayer", actualSampleRate = null,
                    actualBitDepth = null, outputPerfect = false
                )
            }
        }
        cachedSnapshot.copy(queueGeneration = queueGeneration)
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
        // MediaPlayer cannot stop at a CUE song's end; playing the whole file instead would be wrong.
        if (track?.source?.let { CueSource.decode(it).second } != null) {
            throw java.io.IOException("这个版本没有原生音频引擎，无法播放 CUE 分轨")
        }
        val sourceKey = "${track?.id ?: rawUri}|$playablePath"
        val existing = platformMediaPlayer
        if (existing != null && loadedMediaSourceKey == sourceKey) {
            if (startTimeSeconds != null) {
                existing.seekTo((startTimeSeconds * 1000.0).toInt().coerceAtLeast(0))
            }
            val vol = gain.effective.toFloat()
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

        try { mp.prepare() } catch (error: Throwable) { mp.release(); platformDataSource?.close(); platformDataSource = null; throw error }
        val startMs = startTimeSeconds?.let { (it * 1000.0).toInt().coerceAtLeast(0) }
            ?: cachedSnapshot.positionMs.toInt().coerceAtLeast(0)
        if (startMs > 0 && startMs < mp.duration) {
            mp.seekTo(startMs)
        }
        val vol = gain.effective.toFloat()
        mp.setVolume(vol, vol)

        mp.setOnCompletionListener {
            if (platformMediaPlayer === mp) nonRtEventQueue.trySend(RawNativeEvent("end-file", """{"reason":"eof"}"""))
        }
        mp.setOnErrorListener { _, _, _ ->
            controlScope.launch {
                if (platformMediaPlayer !== mp) return@launch
                val message = "音频读取中断，请检查电脑连接后重试"
                cachedSnapshot = cachedSnapshot.withoutOutputFacts().copy(state = "error", errorMessage = message)
                stopProgressTicker()
                releasePlatformMediaPlayer()
                broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
                broadcastEvent(AudioEvent.EngineError("SOURCE_UNAVAILABLE", message, true))
            }
            true
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
    }.onFailure { platformPreparationError = it }.getOrNull()

    private fun configureMediaPlayerDataSource(
        context: Context,
        mp: MediaPlayer,
        track: CoreTrackView?,
        rawUri: String,
        playablePath: String
    ): Boolean {
        if (rawUri.startsWith(DesktopMediaReference.PREFIX)) {
            val source = desktopStream?.invoke(rawUri) ?: throw java.io.IOException("电脑串流尚未连接")
            platformDataSource = source
            mp.setDataSource(source)
            return true
        }
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
        val demoId = track?.id ?: return false
        if (demoId !in setOf("track-twilight-overture", "track-nocturne-in-cobalt", "track-solstice-drift")) return false
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
        platformDataSource?.close()
        platformDataSource = null
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
        if (raw.fromNative && platformMediaPlayer != null) return
        when (raw.eventType) {
            "safety-brake" -> {
                if (nativeSourceLoaded) nativeSnapshot()?.let { cachedSnapshot = it }
                broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
            }
            "property-change", "playback-info", "start-file", "queue-change", "config-applied" -> {
                if (raw.fromNative && !nativeSourceLoaded) return
                // The inactive native backend must not overwrite the platform player's clock/state.
                if (platformMediaPlayer != null) return
                nativeSnapshot()?.let { parsed ->
                    if (cachedSnapshot.state == "error" && parsed.state == "idle") return
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
                    if (!raw.fromNative && platformMediaPlayer != null) {
                        // MediaPlayer stays "playing"-shaped after completion; the Core must see an
                        // ended engine when it re-reads the snapshot for this track end.
                        stopProgressTicker()
                        cachedSnapshot = cachedSnapshot.copy(
                            state = "ended",
                            positionMs = cachedSnapshot.durationMs ?: cachedSnapshot.positionMs
                        )
                        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
                    }
                    broadcastEvent(AudioEvent.TrackEnded(cachedSnapshot.queueIndex, reason))
                }
            }
            "error" -> {
                if (raw.fromNative && !nativePlaybackEventsEnabled) return
                val obj = runCatching {
                    json.parseToJsonElement(raw.payloadJson) as? JsonObject
                }.getOrNull()
                // Rejected graph edits leave the live graph and playback intact. The command
                // response reports the error; it must not turn a playing track into an error.
                if (obj?.get("context")?.jsonPrimitive?.contentOrNull in setOf("dsp-state", "dsp-config", "output-config")) return
                val code = obj?.get("code")?.jsonPrimitive?.contentOrNull ?: "TAE_INTERNAL_ERROR"
                val message = inputFailure(obj, currentInputSource())?.message
                    ?: obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Audio engine error"
                // A successful platform fallback supersedes the native decoder's availability error.
                // If fallback setup failed, keep reporting the native failure to the caller.
                if (platformMediaPlayer != null && code == "TAE_RESULT_BACKEND_UNAVAILABLE") return
                cachedSnapshot = cachedSnapshot.withoutOutputFacts().copy(state = "error", errorMessage = message)
                broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
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

    private fun parsePlaybackInfoSnapshot(payloadJson: String): AudioSnapshot? =
        parseNativePlaybackSnapshot(payloadJson, cachedSnapshot, gain.volume, json)

    private fun broadcastEvent(raw: AudioEvent) {
        val event = if (raw is AudioEvent.SnapshotUpdated) {
            AudioEvent.SnapshotUpdated(raw.snapshot.copy(queueGeneration = queueGeneration))
        } else raw
        for (listener in listeners) {
            runCatching { listener(event) }
        }
    }

    private fun nativeSource(source: String, track: CoreTrackView?): String =
        if (appContext != null && track?.id in setOf("track-twilight-overture", "track-nocturne-in-cobalt", "track-solstice-drift"))
            DemoAudioSynthesizer.ensureDemoWavFile(appContext, track!!.id).absolutePath else source

    private fun nativeSnapshot(): AudioSnapshot? =
        if (engineHandle == 0L) null else runCatching {
            parsePlaybackInfoSnapshot(jni.nativeGetPlaybackInfoJson(engineHandle))
        }.getOrNull()

    private fun requireNativeSuccess(result: Int) {
        if (result == 0) return
        val error = runCatching { json.parseToJsonElement(jni.nativeGetLastErrorJson(engineHandle)).jsonObject }.getOrNull()
        val source = if (error?.get("context")?.jsonPrimitive?.contentOrNull == "seek") currentInputSource() else activeResourceId
        val message = inputFailure(error, source)?.message
            ?: error?.get("message")?.jsonPrimitive?.contentOrNull ?: "原生音频操作失败 ($result)"
        cachedSnapshot = cachedSnapshot.withoutOutputFacts().copy(state = "error", errorMessage = message)
        broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
        broadcastEvent(AudioEvent.EngineError("NATIVE_AUDIO_$result", message, true))
        throw java.io.IOException(message)
    }

    private fun inputFailure(error: JsonObject?, source: String?): Exception? =
        if (error?.get("context")?.jsonPrimitive?.contentOrNull in setOf("play", "load", "seek", "decode"))
            inputProvider.failure(source) else null

    private fun currentInputSource(): String? =
        queuedTracks.getOrNull(nativeSnapshot()?.queueIndex ?: cachedSnapshot.queueIndex)
            ?.let { CueSource.audio(it.source ?: it.id) } ?: activeResourceId

    private fun requireNativeAvailable() {
        if (appContext != null && BuildConfig.NATIVE_AUDIO_ENABLED && engineHandle == 0L) {
            val message = "共享音频引擎加载失败，请安装包含对应架构原生库的版本"
            cachedSnapshot = cachedSnapshot.withoutOutputFacts().copy(state = "error", errorMessage = message)
            broadcastEvent(AudioEvent.SnapshotUpdated(cachedSnapshot))
            broadcastEvent(AudioEvent.EngineError("NATIVE_AUDIO_UNAVAILABLE", message, false))
            throw java.io.IOException(message)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            inputProvider.cancelAll()
            stopProgressTicker()
            listeners.clear()
            nonRtEventQueue.close()
            controlScope.cancel()
            // Cleanup follows the in-flight native operation on the same serial executor.
            // cancelAll above unblocks its IO; never destroy a handle still in nativePlay.
            controlExecutor.execute {
                releasePlatformMediaPlayer()
                val handle = engineHandle
                engineHandle = 0L
                if (jni.isNativeLibraryLoaded && handle != 0L) {
                    runCatching {
                        jni.nativeSetEventListener(handle, null)
                        jni.nativeDestroyEngine(handle)
                    }
                }
            }
            controlExecutor.shutdown()
        }
    }
}

/** How far before a song's end a seek into its last moment lands. */
internal const val SEEK_END_LEAD_MS = 300L

/**
 * Where a seek sends the engine. One into a song's last [SEEK_END_LEAD_MS] lands that far before the end, so the
 * engine plays the end and moves on as at any song's end; a CUE song's seek past its end lands there too. Sent to
 * the end itself, the engine can miss the song's end while it seeks and stay playing at the end.
 */
internal fun nativeSeekTargetMs(positionMs: Long, durationMs: Long?): Long =
    if (durationMs == null || durationMs <= 2 * SEEK_END_LEAD_MS) positionMs
    else minOf(positionMs, durationMs - SEEK_END_LEAD_MS)

/**
 * The engine's queue. A CUE song's source names the whole file plus its range ([CueSource]); the engine gets
 * the file as the source and the range as `cueRange`, so positions, seeking and the end follow the song, and
 * songs that continue each other in one file play without a gap.
 */
internal fun nativeQueueJson(tracks: List<CoreTrackView>, source: (String, CoreTrackView) -> String): String =
    buildJsonArray {
        tracks.forEachIndexed { index, track ->
            val (audio, range) = CueSource.decode(track.source ?: track.id)
            add(buildJsonObject {
                put("id", JsonPrimitive(track.id))
                put("queueEntryId", JsonPrimitive(track.queueEntryId ?: "occ-$index"))
                put("source", JsonPrimitive(source(audio, track)))
                put("title", JsonPrimitive(track.title))
                put("artist", JsonPrimitive(track.artist))
                put("album", JsonPrimitive(track.album))
                put("duration", JsonPrimitive(track.duration))
                if (range != null) put("cueRange", buildJsonObject {
                    put("startSeconds", JsonPrimitive(range.startSeconds))
                    put("endSeconds", JsonPrimitive(range.endSeconds))
                    put("pregapSeconds", JsonPrimitive(range.pregapSeconds))
                    put("virtualPregapSeconds", JsonPrimitive(range.virtualPregapSeconds))
                    put("sourcePregapSeconds", JsonPrimitive(range.sourcePregapSeconds))
                })
            })
        }
    }.toString()
