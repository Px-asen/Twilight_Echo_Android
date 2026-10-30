package com.twilightecho.android.playbackhost

import com.twilightecho.android.coreclient.CoreTrackView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

private val DIGITS_REGEX = Regex("^[0-9]+$")
private val DIRECT_NCM_SOURCE_REGEX = Regex("^provider://ncm/([0-9]+)$")

/**
 * Lazy `provider://ncm/match?...` URI encoding and helpers for playing offline desktop tracks
 * seamlessly through NetEase Cloud Music without resolving an entire playlist up front.
 */
object NcmFallbackUri {
    const val PREFIX = "provider://ncm/match?"
    private const val MAX_FIELD_LEN = 256

    fun directSongId(source: String?): String? =
        source?.let { DIRECT_NCM_SOURCE_REGEX.matchEntire(it)?.groupValues?.get(1) }

    fun isFallbackMatchSource(source: String?): Boolean =
        source?.startsWith(PREFIX) == true

    fun encode(track: CoreTrackView): String {
        val params = buildList {
            add("title" to track.title.trim().take(MAX_FIELD_LEN))
            if (track.artist.isNotBlank()) add("artist" to track.artist.trim().take(MAX_FIELD_LEN))
            if (track.album.isNotBlank()) add("album" to track.album.trim().take(MAX_FIELD_LEN))
            if (track.duration > 0.0) {
                add("duration" to String.format(Locale.US, "%.3f", track.duration))
            }
            track.ncmSongId?.takeIf { DIGITS_REGEX.matches(it) }?.let { add("ncmSongId" to it) }
        }
        return PREFIX + params.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
    }

    fun parse(source: String?): CoreTrackView? {
        if (source == null || !source.startsWith(PREFIX)) return null
        val query = source.removePrefix(PREFIX)
        if (query.isBlank() || query.length > 4096) return null
        val values = HashMap<String, String>()
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = runCatching { URLDecoder.decode(part.substring(0, eq), "UTF-8") }.getOrNull() ?: continue
            val value = runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull() ?: continue
            values[key] = value
        }
        val title = values["title"]?.trim().orEmpty()
        val ncmSongId = values["ncmSongId"]?.trim()?.takeIf { DIGITS_REGEX.matches(it) }
        if (title.isEmpty() && ncmSongId == null) return null
        val duration = values["duration"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        return CoreTrackView(
            id = source,
            source = source,
            ncmSongId = ncmSongId,
            title = title,
            artist = values["artist"]?.trim().orEmpty(),
            album = values["album"]?.trim().orEmpty(),
            duration = duration
        )
    }

    fun cacheKey(track: CoreTrackView): String {
        val direct = track.ncmSongId?.takeIf { DIGITS_REGEX.matches(it) }
            ?: directSongId(track.source)
        if (direct != null) return "id:$direct"
        val durationTenths = (track.duration.coerceAtLeast(0.0) * 10.0 + 0.5).toLong()
        return "meta:${track.title.trim().lowercase()}\u0000${track.artist.trim().lowercase()}\u0000$durationTenths"
    }
}

/**
 * Wraps an offline computer song so Music Core queues it immediately on the phone via NCM while
 * preserving [CoreTrackView.id] for active list highlighting and cached desktop cover lookup.
 */
fun offlineNcmFallbackTrack(track: CoreTrackView, cachedMatch: CoreTrackView? = null): CoreTrackView {
    val matchedId = cachedMatch?.let {
        NcmFallbackUri.directSongId(it.source) ?: it.ncmSongId?.takeIf { id -> DIGITS_REGEX.matches(id) }
    } ?: track.ncmSongId?.takeIf { DIGITS_REGEX.matches(it) }

    val resolvedSource = if (matchedId != null) {
        "provider://ncm/$matchedId"
    } else {
        NcmFallbackUri.encode(track)
    }
    return track.copy(
        queueEntryId = null,
        source = resolvedSource,
        ncmSongId = matchedId ?: track.ncmSongId,
        cover = track.cover ?: cachedMatch?.cover,
        duration = if (track.duration > 0.0) track.duration else (cachedMatch?.duration ?: 0.0)
    )
}

/**
 * Picks the search hit that is the same recording: the title must agree, and when an artist is
 * known it must agree too unless the lengths match within a few seconds. Null when nothing fits.
 */
fun bestNcmStreamingMatch(track: CoreTrackView, candidates: List<CoreTrackView>): CoreTrackView? {
    val title = ncmMatchKey(track.title)
    if (title.isEmpty()) return null
    val artists = ncmArtistKeys(track.artist)
    return candidates.mapNotNull { candidate ->
        val other = ncmMatchKey(candidate.title)
        val titleScore = when {
            other == title -> 3
            other.isNotEmpty() && (title in other || other in title) -> 1
            else -> return@mapNotNull null
        }
        val artistMatch = artists.isNotEmpty() && ncmArtistKeys(candidate.artist).any { it in artists }
        val lengthMatch = track.duration > 0 && candidate.duration > 0 && abs(track.duration - candidate.duration) <= 3
        if (artists.isNotEmpty() && !artistMatch && !lengthMatch) return@mapNotNull null
        candidate to titleScore + (if (artistMatch) 2 else 0) + (if (lengthMatch) 1 else 0)
    }.maxByOrNull { it.second }?.first
}

internal fun parseNcmSearchCandidates(raw: JsonElement?): List<CoreTrackView> {
    val items = raw as? JsonArray
        ?: (raw as? JsonObject)?.let { page ->
            listOf("items", "songs", "tracks").firstNotNullOfOrNull { page[it] as? JsonArray }
        }
        ?: JsonArray(emptyList())
    return items.mapNotNull { element ->
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        parseNcmSearchTrack(obj)?.takeIf { it.unavailableReason == null }
    }
}

internal fun parseNcmSearchTrack(track: JsonObject): CoreTrackView? {
    fun text(value: JsonElement?): String = when (value) {
        is JsonPrimitive -> value.contentOrNull?.trim().orEmpty()
        is JsonObject -> text(value["name"] ?: value["title"])
        else -> ""
    }
    fun JsonObject.str(key: String, fallback: String = "") =
        text(get(key)).ifEmpty { fallback }
    val id = track.str("ncmSongId").ifBlank {
        track.str("platformId").ifBlank { track.str("id").removePrefix("ncm:") }
    }
    if (!DIGITS_REGEX.matches(id)) return null
    val artists = (track["artists"] as? JsonArray).orEmpty().map(::text).filter { it.isNotEmpty() }
    val playableFlag = (track["playable"] as? JsonPrimitive)?.contentOrNull
    val unavailable = track.str("unavailableReason").ifBlank {
        if (playableFlag == "false") "暂不可播放" else null
    }
    return CoreTrackView(
        id = "provider://ncm/$id",
        source = "provider://ncm/$id",
        ncmSongId = id,
        title = track.str("title").ifBlank { track.str("name", "未知歌曲") },
        artist = track.str("artist").ifBlank { artists.joinToString(" / ") },
        artists = artists,
        album = track.str("album"),
        albumId = track.str("albumId").ifBlank { null },
        cover = track.str("cover").ifBlank { null },
        unavailableReason = unavailable,
        duration = track["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        format = track.str("format").ifBlank { null },
        sampleRate = track["sampleRate"]?.jsonPrimitive?.intOrNull,
        bitrate = track["bitrate"]?.jsonPrimitive?.doubleOrNull
    )
}

private fun ncmMatchKey(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

private fun ncmArtistKeys(value: String) =
    value.split('/', '、', ',', '，', '&', ';', '；').map(::ncmMatchKey).filter { it.isNotEmpty() }.toSet()
