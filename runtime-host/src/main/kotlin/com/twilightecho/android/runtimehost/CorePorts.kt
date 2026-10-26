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

interface ResourceAccessPort {
    suspend fun stat(resourceId: ResourceId): ResourceAccessResult<ResourceStat>
    suspend fun openReadStream(resourceId: ResourceId, offsetBytes: Long = 0L): ResourceAccessResult<InputStream>
    suspend fun resolveNativeUri(resourceId: ResourceId): ResourceAccessResult<String>
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
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val outputBackend: String? = "oboe",
    val actualSampleRate: Int? = null,
    val actualBitDepth: Int? = null,
    val outputPerfect: Boolean = false
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
    suspend fun selectQueueIndex(index: Int)
    suspend fun next()
    suspend fun previous()
    suspend fun getSnapshot(): AudioSnapshot
    fun subscribe(listener: (AudioEvent) -> Unit): CancelHandle
}

/**
 * Aggregated Ports bundle injected into `QuickJsRuntimeHost`.
 */
data class CoreHostPorts(
    val clock: ClockPort,
    val scheduler: SchedulerPort,
    val sessionStore: SessionStorePort,
    val resourceAccess: ResourceAccessPort,
    val audioEngine: AudioEnginePort
)
