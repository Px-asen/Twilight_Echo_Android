package com.twilightecho.android.runtimehost

import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.PlayMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.InputStream

typealias ResourceId = String

fun interface CancelHandle {
    fun cancel()
}

/**
 * ClockPort (Section 8.1): Injects deterministic wall-clock and monotonic timestamps into Music Core.
 */
interface ClockPort {
    fun nowUnixMs(): Long
    fun nowMonotonicMs(): Long
}

/**
 * SchedulerPort (Section 8.1): Schedules delayed tasks that execute on the Core serial runtime thread.
 */
interface SchedulerPort {
    fun schedule(delayMs: Long, task: () -> Unit): CancelHandle
}

/**
 * Stored session snapshot with optimistic concurrency revision (Section 8.1).
 */
@Serializable
data class StoredSession(
    val revision: String,
    val payloadJson: String
)

@Serializable
sealed class SaveSessionResult {
    @Serializable
    @SerialName("ok")
    data class Ok(val revision: String) : SaveSessionResult()

    @Serializable
    @SerialName("conflict")
    data class Conflict(
        val actualRevision: String?,
        val reason: String = "conflict"
    ) : SaveSessionResult()
}

/**
 * SessionStorePort (Section 8.1): Persists recovery state with Compare-And-Swap (`expectedRevision`)
 * conflict detection.
 */
interface SessionStorePort {
    suspend fun load(): StoredSession?
    suspend fun save(payloadJson: String, expectedRevision: String?): SaveSessionResult
}

/**
 * Resource metadata and semantic error taxonomy (Sections 8.2, 8.3, 8.4).
 * Distinguishes `PERMISSION_EXPIRED` from `NOT_FOUND` so revoking SAF/MediaStore permissions
 * never causes Music Core to delete valid library tracks.
 */
@Serializable
data class ResourceStat(
    val resourceId: ResourceId,
    val uri: String,
    val displayName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = -1L,
    val canSeek: Boolean = true,
    val lastModifiedUnixMs: Long? = null
)

sealed class ResourceAccessResult<out T> {
    data class Ok<T>(val value: T) : ResourceAccessResult<T>()
    data class Error(
        val code: CoreErrorCode,
        val message: String,
        val retryable: Boolean = false
    ) : ResourceAccessResult<Nothing>()
}

@Serializable
data class NativeFdResource(
    val resourceId: ResourceId,
    val nativePath: String,
    val detachedFd: Int? = null,
    val mimeType: String? = null
)

interface ResourceAccessPort {
    suspend fun stat(resourceId: ResourceId): ResourceAccessResult<ResourceStat>
    suspend fun openReadStream(resourceId: ResourceId, offsetBytes: Long = 0L): ResourceAccessResult<InputStream>
    suspend fun resolveNativeUri(resourceId: ResourceId): ResourceAccessResult<String>

    /**
     * Resolves a resource (such as an Android `content://` URI) into a playable native path
     * (e.g. `/proc/self/fd/<fd>`) and retains the underlying descriptor lease until
     * `releasePlayableResource` is called (Sections 8.2, 8.3, 14.3, 14.4).
     */
    suspend fun acquirePlayableResource(resourceId: ResourceId): ResourceAccessResult<NativeFdResource> =
        when (val res = resolveNativeUri(resourceId)) {
            is ResourceAccessResult.Ok -> ResourceAccessResult.Ok(
                NativeFdResource(resourceId = resourceId, nativePath = res.value)
            )
            is ResourceAccessResult.Error -> res
        }

    suspend fun releasePlayableResource(resourceId: ResourceId) {}
}

/**
 * AudioEnginePort (Section 8.1 & 13.4):
 * Control plane contract between Music Core and the C++ Audio Core (`twilight_audio_engine.h`).
 * PCM data never flows across the JS bridge.
 */
@Serializable
data class AudioSourceDescriptor(
    val resourceId: ResourceId,
    val nativeUri: String,
    val mimeType: String? = null,
    val startTimeSeconds: Double = 0.0
)

@Serializable
data class AudioSnapshot(
    val engineSessionId: String = "native-session-1",
    val state: String = "idle", // "idle" | "loading" | "playing" | "paused" | "ended" | "error"
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val volume: Double = 1.0,
    val playbackRate: Double = 1.0,
    val playMode: String = "sequence",
    val queueIndex: Int = -1,
    /** The `loadQueue` generation [queueIndex] refers to; 0 before any queue was loaded. */
    val queueGeneration: Long = 0L,
    val errorMessage: String? = null,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val outputBackend: String? = "oboe",
    val actualSampleRate: Int? = null,
    val actualBitDepth: Int? = null,
    val outputPerfect: Boolean = false,
    val dspActive: Boolean = false,
    val eqActive: Boolean = false,
    val actualChannels: Int? = null,
    val actualSampleFormat: String? = null,
    val requestedSampleRate: Int? = null,
    val usbPcmPreferenceAccepted: Boolean = false,
    val usbPcmFormatMatched: Boolean = false,
    val requestedSampleFormat: String? = null,
    val hardwareSampleRate: Int? = null,
    val hardwareBitDepth: Int? = null,
    val hardwareChannels: Int? = null,
    val hardwareSampleFormat: String? = null,
    val perfectReason: String? = null,
    val perfectReasonCode: String? = null,
    val exclusive: Boolean = false,
    val actualDeviceId: String? = null,
    val actualDeviceName: String? = null,
    val latencyMs: Double = 0.0,
    val bufferSizeFrames: Int = 0,
    val gaplessActive: Boolean = false,
    val gaplessBlockedReason: String? = null,
    val preloadReady: Boolean = false,
    val crossfadeActive: Boolean = false,
    val crossfadeSeconds: Double = 0.0,
    val crossfadeMixActive: Boolean = false,
    val crossfadeEffectiveSeconds: Double = 0.0,
    val crossfadeCurve: String? = null,
    val crossfadeBlockedReason: String? = null,
    val upcomingQueueIndex: Int = -1,
    val isDsd: Boolean = false,
    val autoMixMode: String = "off",
    val autoMixState: String = "off",
    val autoMixReason: String = "",
    val autoMixStrategy: String = "",
    val autoMixPlanId: Long = 0,
    val autoMixDurationSeconds: Double = 0.0,
    val autoMixDecodeMs: Long = 0,
    val autoMixPrepareMs: Long = 0
)

@Serializable
sealed class AudioEvent {
    @Serializable
    @SerialName("snapshot")
    data class SnapshotUpdated(val snapshot: AudioSnapshot) : AudioEvent()

    @Serializable
    @SerialName("track-ended")
    data class TrackEnded(val queueIndex: Int, val reason: String = "eof") : AudioEvent()

    @Serializable
    @SerialName("error")
    data class EngineError(
        val code: String,
        val message: String,
        val recoverable: Boolean
    ) : AudioEvent()
}

interface AudioEnginePort : Closeable {
    suspend fun load(source: AudioSourceDescriptor)
    suspend fun play(startTimeSeconds: Double? = null)
    suspend fun pause()
    suspend fun stop()
    suspend fun seek(positionMs: Long)
    suspend fun setVolume(volume: Double)
    suspend fun setPlayMode(mode: PlayMode)
    suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int)

    /**
     * Loads a queue tagged with the Core's generation counter. Engines that can report it
     * stamp [AudioSnapshot.queueGeneration] so late events never map onto a newer queue.
     */
    suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int, queueGeneration: Long) =
        loadQueue(tracks, startIndex)
    suspend fun selectQueueIndex(index: Int)
    suspend fun next()
    suspend fun previous()
    suspend fun setDspConfig(configJson: String) {}
    suspend fun setAutoMixMode(mode: String): Boolean = mode == "off"
    suspend fun selectAutoMix(requestJson: String): String = """{"ok":false,"reason":"analysis_service_unavailable"}"""
    suspend fun prepareAutoMix(requestJson: String): Boolean = false
    suspend fun submitAutoMix(planId: Long): Boolean = false
    suspend fun cancelAutoMix(): Boolean = true
    suspend fun identifyAutoMixSource(source: String, requestId: String): String =
        """{"ok":false,"reason":"actual_source_identity_unavailable"}"""
    suspend fun analyzeAutoMix(source: String, optionsJson: String, requestId: String): String =
        """{"ok":false,"reason":"analysis_service_unavailable"}"""
    fun cancelAutoMixAnalysis(requestId: String) {}
    suspend fun readAutoMixCache(): String? = null
    suspend fun writeAutoMixCache(text: String?) {}
    suspend fun getSnapshot(): AudioSnapshot
    fun subscribe(listener: (AudioEvent) -> Unit): CancelHandle
}

/**
 * NetworkClientPort (Section 8.1 & 14.5):
 * Host-controlled HTTP execution for metadata, lyrics, and provider API queries.
 */
@Serializable
data class NetworkRequestSpec(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val bodyUtf8: String? = null,
    val contentType: String = "application/json; charset=utf-8",
    /** Whole-call deadline; null keeps the client's default timeouts. */
    val timeoutMs: Long? = null
)

@Serializable
data class NetworkResponsePayload(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val bodyUtf8: String = ""
)

interface NetworkClientPort {
    suspend fun execute(spec: NetworkRequestSpec): ResourceAccessResult<NetworkResponsePayload>

    companion object {
        val NoOp: NetworkClientPort = object : NetworkClientPort {
            override suspend fun execute(spec: NetworkRequestSpec): ResourceAccessResult<NetworkResponsePayload> =
                ResourceAccessResult.Error(
                    code = CoreErrorCode.UNSUPPORTED_CAPABILITY,
                    message = "NetworkClientPort is not configured on this host"
                )
        }
    }
}

/**
 * AudioDevicePort (Sections 11.7 & 13.6):
 * Device inventory reports capabilities; the active route is resolved from the playback stream.
 * Empty capabilities and zero-valued formats mean the platform supplied no value.
 */
@Serializable
data class AudioDeviceRouteInfo(
    val deviceId: Int = 0,
    val deviceName: String = "系统输出",
    val routeType: String = "unknown", // "unknown" | "speaker" | "wired_headset" | "bluetooth" | "usb_dac"
    val outputBackend: String = "",
    val supportedSampleRates: List<Int> = emptyList(),
    val activeSampleRate: Int = 0,
    val maxBitDepth: Int = 0,
    val isUsbHiResCapable: Boolean = false,
    /** How a Bluetooth output carries the sound: "a2dp", "le_audio" or "call" (the headset's call link). */
    val bluetoothProfile: String = ""
)

interface AudioDevicePort : Closeable {
    fun getActiveRoute(): AudioDeviceRouteInfo
    fun listOutputDevices(): List<AudioDeviceRouteInfo> = listOf(getActiveRoute())
    fun subscribeRouteChanges(listener: (AudioDeviceRouteInfo) -> Unit): CancelHandle = CancelHandle {}
    override fun close() {}

    companion object {
        val Default: AudioDevicePort = object : AudioDevicePort {
            override fun getActiveRoute(): AudioDeviceRouteInfo = AudioDeviceRouteInfo()
        }
    }
}

/**
 * Aggregated Ports bundle injected into `QuickJsRuntimeHost`.
 */
data class CoreHostPorts(
    val clock: ClockPort,
    val scheduler: SchedulerPort,
    val sessionStore: SessionStorePort,
    val resourceAccess: ResourceAccessPort,
    val audioEngine: AudioEnginePort,
    val networkClient: NetworkClientPort = NetworkClientPort.NoOp,
    val audioDevice: AudioDevicePort = AudioDevicePort.Default
)
