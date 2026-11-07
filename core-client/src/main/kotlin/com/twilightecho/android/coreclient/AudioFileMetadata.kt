package com.twilightecho.android.coreclient

import kotlinx.serialization.Serializable

/** Container metadata from the shared reader. DSD sampleRate is the bit clock, not a PCM carrier rate. */
@Serializable
data class AudioFileMetadata(
    val source: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val composer: String = "",
    val year: String = "",
    val genre: String = "",
    val trackNumber: String = "",
    val discNumber: String = "",
    val codec: String = "",
    val container: String = "",
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val bitDepth: Int = 0,
    val bitrate: Long = 0,
    val duration: Double = 0.0,
    val playable: Boolean = true,
    val reasonCode: String = "",
    val comment: String = "",
    val isDsd: Boolean = false,
    val dsdRate: Int = 0,
    val isoTracks: List<AudioFileMetadata> = emptyList(),
    val error: String = ""
)

data class SacdTrackSource(val source: String, val area: String, val track: Int) {
    fun encode(): String = "$source#twilight-sacd=$area:$track"

    companion object {
        private const val MARKER = "#twilight-sacd="
        fun decode(value: String): SacdTrackSource? {
            val start = value.lastIndexOf(MARKER)
            if (start < 0) return null
            val selector = value.substring(start + MARKER.length).split(':')
            if (selector.size != 2 || selector[0] !in setOf("stereo", "multichannel")) return null
            val track = selector[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return SacdTrackSource(value.substring(0, start), selector[0], track)
        }
    }
}
