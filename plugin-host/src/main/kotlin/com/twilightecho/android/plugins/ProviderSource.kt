package com.twilightecho.android.plugins

import com.twilightecho.android.coreclient.CoreTrackView
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

/** The bundled NCM provider keeps its own `provider://ncm/<song id>` sources. */
const val NCM_PROVIDER_ID = "ncm"
private val PROVIDER_ID = Regex("[a-z0-9][a-z0-9-]{0,31}")
private val MEDIA_URL = Regex("^(?:https?|data):", RegexOption.IGNORE_CASE)
private const val MAX_TRACK_ID = 512
private const val MAX_TRACK_STRING = 512
private const val MAX_TRACK_NESTED = 384
private const val MAX_TRACK_JSON = 2048

/** A v2 provider's track as the player queued it: what `getPlaybackUrl` and `getLyrics` need again. */
data class ProviderTrackRef(val providerId: String, val trackId: String, val track: JsonObject)

/** Provider ids become the host of `provider://` sources; NCM's id stays with the bundled plugin. */
internal fun isPluginProviderId(id: String) = PROVIDER_ID.matches(id) && id != NCM_PROVIDER_ID

private fun JsonObject.trackKey(): String? = (get("id") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    ?.takeIf { it.isNotBlank() && it.length <= MAX_TRACK_ID }

/**
 * The track fields that ride in the queued source: short strings that are not media URLs, numbers, booleans and
 * small nested values. Past [MAX_TRACK_JSON] the longest entries go first; `id` always stays.
 */
internal fun minimalTrack(track: JsonObject): JsonObject {
    val kept = track.filter { (key, value) -> key == "id" || when (value) {
        is JsonNull -> false
        is JsonPrimitive -> !value.isString || value.content.length <= MAX_TRACK_STRING && !MEDIA_URL.containsMatchIn(value.content)
        else -> value.toString().length <= MAX_TRACK_NESTED
    } }.toMutableMap()
    while (JsonObject(kept).toString().length > MAX_TRACK_JSON) {
        kept.remove(kept.entries.filter { it.key != "id" }.maxByOrNull { it.value.toString().length }?.key ?: break)
    }
    return JsonObject(kept)
}

/** `provider://<provider>/<track id>`: a v2 track's queue identity. */
fun providerTrackId(providerId: String, trackId: String): String =
    "provider://$providerId/" + URLEncoder.encode(trackId, "UTF-8").replace("+", "%20")

/** The queue identity plus `?t=` the minimal track, base64url. */
fun providerSource(providerId: String, track: JsonObject): String {
    require(isPluginProviderId(providerId)) { "音源 ID 无效" }
    val id = track.trackKey() ?: throw IllegalArgumentException("音源返回了无效歌曲 ID")
    return providerTrackId(providerId, id) + "?t=" + Base64.getUrlEncoder().withoutPadding().encodeToString(minimalTrack(track).toString().utf8())
}

/** Null for NCM, foreign or damaged sources. */
fun parseProviderSource(source: String): ProviderTrackRef? {
    if (!source.startsWith("provider://")) return null
    val rest = source.removePrefix("provider://")
    val providerId = rest.substringBefore('/')
    if (!isPluginProviderId(providerId) || '/' !in rest) return null
    val path = rest.substringAfter('/').substringBefore('?')
    val payload = rest.substringAfter('?', "").split('&').firstOrNull { it.startsWith("t=") }?.removePrefix("t=") ?: return null
    return runCatching {
        val trackId = URLDecoder.decode(path, "UTF-8")
        val track = pluginJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).jsonObject
        ProviderTrackRef(providerId, trackId, track).takeIf { track.trackKey() == trackId }
    }.getOrNull()
}

/** A desktop-shaped provider track (durations in seconds) as a queue entry. */
fun providerTrackView(providerId: String, track: JsonObject): CoreTrackView {
    val source = providerSource(providerId, track)
    fun text(value: JsonElement?): String = when (value) {
        is JsonPrimitive -> value.takeIf { it.isString }?.content?.trim().orEmpty()
        is JsonObject -> text(value["name"] ?: value["title"])
        else -> ""
    }
    fun number(key: String) = (track[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
    val artists = (track["artists"] as? JsonArray).orEmpty().map(::text).filter { it.isNotEmpty() }
    return CoreTrackView(id = source.substringBefore('?'), source = source,
        title = text(track["title"]).ifEmpty { text(track["name"]) }.ifEmpty { "未知歌曲" },
        artist = text(track["artist"]).ifEmpty { artists.joinToString(" / ") }, artists = artists,
        album = text(track["album"]), albumId = text(track["albumId"]).ifEmpty { null },
        cover = text(track["cover"]).takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) },
        unavailableReason = text(track["unavailableReason"]).ifEmpty { null },
        duration = number("duration") ?: 0.0, size = number("size")?.toLong(),
        format = text(track["format"]).ifEmpty { null }, sampleRate = number("sampleRate")?.toInt(), bitrate = number("bitrate"))
}

private val LRC_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

/**
 * A provider's `getLyrics` result as `[mm:ss.xxx]text` lines in time order; null when it has no timed lines.
 * Lines with several timestamps repeat; tags such as `[ar:...]` and untimed text are dropped.
 */
fun providerLrc(result: JsonElement?): String? {
    val text = ((result as? JsonObject)?.get("lyrics") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val lines = mutableListOf<Pair<Long, String>>()
    for (line in text.lineSequence()) {
        var rest = line.trim(); val times = mutableListOf<Long>()
        while (true) {
            val match = LRC_TIME.matchAt(rest, 0) ?: break
            val (minutes, seconds, fraction) = match.destructured
            if (seconds.toInt() >= 60) break
            val millis = when (fraction.length) { 0 -> 0; 1 -> fraction.toInt() * 100; 2 -> fraction.toInt() * 10; else -> fraction.toInt() }
            times += (minutes.toLong() * 60 + seconds.toLong()) * 1000 + millis
            rest = rest.substring(match.range.last + 1)
        }
        times.forEach { lines += it to rest.trim() }
    }
    if (lines.isEmpty()) return null
    return lines.sortedBy { it.first }.joinToString("\n") { (time, words) ->
        String.format(Locale.ROOT, "[%02d:%02d.%03d]%s", time / 60_000, time / 1000 % 60, time % 1000, words)
    }
}
