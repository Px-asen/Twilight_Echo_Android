package com.twilightecho.android.coreclient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Display identity projection for a track (aligned with `src/shared/coreViews.ts`).
 * Display identity is not an authorization to open a source directly.
 */
@Serializable
data class CoreTrackView(
    val id: String,
    val queueEntryId: String? = null,
    val title: String,
    val artist: String = "",
    val artists: List<String> = emptyList(),
    val album: String = "",
    val albumId: String? = null,
    val source: String? = null,
    val ncmSongId: String? = null,
    val duration: Double = 0.0,
    val size: Long? = null,
    val format: String? = null,
    val sampleRate: Int? = null,
    val bitrate: Int? = null,
    val bitDepth: Int? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val releaseDate: String? = null,
    val genre: String? = null,
    val albumArtist: String? = null,
    val addedAt: Long? = null,
    val bpm: Double? = null,
    val isrc: String? = null
)

@Serializable
data class CoreUpcomingTrack(
    val id: String,
    val queueEntryId: String? = null,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: Double = 0.0
)

@Serializable
enum class PlayMode {
    @SerialName("sequence") SEQUENCE,
    @SerialName("loop") LOOP,
    @SerialName("single") SINGLE,
    @SerialName("shuffle") SHUFFLE,
    @SerialName("heart") HEART;

    fun toWireValue(): String = name.lowercase()

    companion object {
        fun fromWireValue(value: String?): PlayMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SEQUENCE
    }
}

/**
 * Bounded playback projection aligned with `CORE_PLAYBACK_FIELDS` in `src/shared/coreViews.ts`.
 */
@Serializable
data class CorePlaybackView(
    val streamTitle: String? = null,
    val dspActive: Boolean = false,
    val gaplessBlockedReason: String? = null,
    val state: String = "stopped", // "stopped" | "loading" | "playing" | "paused" | "buffering" | "error"
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val volume: Double = 1.0,
    val playbackRate: Double = 1.0,
    val playMode: String = "sequence",
    val queueIndex: Int = -1,
    val queueToken: String? = null,
    val queueRevision: Long = 0L,
    val upcomingQueueIndex: Int? = null,
    val requestedConfigRevision: Long = 0L,
    val appliedConfigRevision: Long = 0L,
    val nativePlaybackActive: Boolean = false,
    val codec: String? = null,
    val bitrate: Int? = null,
    val sourceSampleRate: Int? = null,
    val sourceChannels: Int? = null,
    val sourceBitDepth: Int? = null,
    val decodedSampleRate: Int? = null,
    val decodedBitDepth: Int? = null,
    val decodedChannels: Int? = null,
    val decodedSampleFormat: String? = null,
    val outputBackend: String? = null,
    val actualSampleRate: Int? = null,
    val actualBitDepth: Int? = null,
    val actualChannels: Int? = null,
    val outputPerfect: Boolean = false,
    val sourceExact: Boolean = false,
    val pcmPassthrough: Boolean = false,
    val isDsd: Boolean = false,
    val dsdMode: String? = null,
    val dsdRate: Int? = null,
    val gaplessActive: Boolean = false,
    val preloadReady: Boolean = false,
    val crossfadeActive: Boolean = false,
    val crossfadeSeconds: Double = 0.0,
    val crossfadeMixActive: Boolean = false,
    val crossfadeEffectiveSeconds: Double = 0.0,
    val crossfadeCurve: String? = null,
    val crossfadeBlockedReason: String? = null,
    val autoMixMode: String = "off",
    val autoMixState: String = "idle",
    val clipCount: Long = 0L,
    val upcomingTrack: CoreUpcomingTrack? = null
)

@Serializable
data class CoreErrorView(
    val code: CoreErrorCode,
    val message: String,
    val retryable: Boolean = false,
    val details: String? = null
)

@Serializable
data class CoreQueueEntryView(
    val occurrenceId: String,
    val index: Int,
    val track: CoreTrackView? = null
)

/**
 * Windowed queue projection returned by `playback.getQueueWindow` / `queue.page`.
 */
@Serializable
data class CoreQueueWindowView(
    val queueId: String = "default",
    val revision: Long = 0L,
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 50,
    val currentIndex: Int = -1,
    val currentOccurrenceId: String? = null,
    val items: List<CoreQueueEntryView> = emptyList()
)

/**
 * Phase 2 DTOs: Synchronized Lyrics, Bookmarks, and Local Library Search Page.
 */
@Serializable
data class CoreLyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
)

@Serializable
data class CoreLyricsView(
    val trackId: String,
    val source: String = "embedded",
    val synced: Boolean = true,
    val lines: List<CoreLyricLine> = emptyList()
)

@Serializable
data class CoreBookmarkView(
    val id: String,
    val trackId: String,
    val positionSec: Double,
    val label: String,
    val createdAtMs: Long
)

@Serializable
data class CoreLibrarySearchPage(
    val query: String,
    val total: Int,
    val items: List<CoreTrackView>
)
