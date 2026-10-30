package com.twilightecho.android.coreclient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

const val CORE_PROTOCOL_VERSION: Int = 1

typealias CoreEpoch = String

@Serializable
enum class CorePhase {
    @SerialName("starting") STARTING,
    @SerialName("ready") READY,
    @SerialName("recovering") RECOVERING,
    @SerialName("failed") FAILED,
    @SerialName("stopped") STOPPED
}

@Serializable
enum class CoreClientRole {
    @SerialName("desktop") DESKTOP,
    @SerialName("mini-player") MINI_PLAYER,
    @SerialName("system") SYSTEM,
    @SerialName("remote") REMOTE,
    @SerialName("plugin") PLUGIN
}

@Serializable
enum class CorePermission(val wireName: String) {
    @SerialName("playback:read") PLAYBACK_READ("playback:read"),
    @SerialName("playback:write") PLAYBACK_WRITE("playback:write"),
    @SerialName("library:read") LIBRARY_READ("library:read"),
    @SerialName("library:write") LIBRARY_WRITE("library:write"),
    @SerialName("settings:read") SETTINGS_READ("settings:read"),
    @SerialName("settings:write") SETTINGS_WRITE("settings:write"),
    @SerialName("migration:write") MIGRATION_WRITE("migration:write"),
    @SerialName("statistics:read") STATISTICS_READ("statistics:read"),
    @SerialName("statistics:write") STATISTICS_WRITE("statistics:write"),
    @SerialName("plugins:read") PLUGINS_READ("plugins:read"),
    @SerialName("plugins:manage") PLUGINS_MANAGE("plugins:manage"),
    @SerialName("plugins:resources") PLUGINS_RESOURCES("plugins:resources"),
    @SerialName("providers:call") PROVIDERS_CALL("providers:call"),
    @SerialName("extensions:execute") EXTENSIONS_EXECUTE("extensions:execute"),
    @SerialName("downloads:read") DOWNLOADS_READ("downloads:read"),
    @SerialName("downloads:write") DOWNLOADS_WRITE("downloads:write")
}

/**
 * Host assigns identity/permissions; no untrusted caller-supplied identity is trusted.
 */
@Serializable
data class CoreClientIdentity(
    val id: String,
    val role: CoreClientRole,
    val permissions: List<CorePermission>
) {
    companion object {
        fun androidPrimaryClient(id: String = "android-ui-main"): CoreClientIdentity =
            CoreClientIdentity(
                id = id,
                role = CoreClientRole.DESKTOP,
                permissions = CorePermission.entries
            )

        fun androidSystemMediaClient(id: String = "android-media3-session"): CoreClientIdentity =
            CoreClientIdentity(
                id = id,
                role = CoreClientRole.SYSTEM,
                permissions = listOf(
                    CorePermission.PLAYBACK_READ,
                    CorePermission.PLAYBACK_WRITE,
                    CorePermission.LIBRARY_READ
                )
            )
    }
}

@Serializable
data class CoreQueueReference(
    val id: String = "default",
    val revision: Long = 0L,
    val total: Int = 0,
    val index: Int = -1,
    val occurrenceId: String? = null,
    val canUndo: Boolean = false,
    val undoLabel: String? = null
)

@Serializable
data class CoreMuteState(
    val muted: Boolean = false,
    val userVolume: Double = 1.0,
    val lastAudibleVolume: Double = 1.0
)

/**
 * Bounded projection aligned with `CoreSnapshot` in `src/shared/coreProtocol.ts` + Phase 2 extensions.
 */
@Serializable
data class CoreSnapshot(
    val protocolVersion: Int = CORE_PROTOCOL_VERSION,
    val epoch: CoreEpoch,
    val sequence: Long,
    val phase: CorePhase = CorePhase.READY,
    val playback: CorePlaybackView? = null,
    val track: CoreTrackView? = null,
    val queue: CoreQueueReference? = null,
    val error: String? = null,
    val isStreamBuffering: Boolean = false,
    val loading: Boolean = false,
    val migrationRequired: Boolean = false,
    val mute: CoreMuteState? = null,
    val libraryRevision: Long? = null,
    val catalogRevision: Long? = null,
    val entityMetadataRevision: Long? = null,
    val presentationRevision: Long? = null,
    val playlistsRevision: Long? = null,
    val audioRevision: Long? = null,
    val settingsRevision: Long? = null,
    val lyricsRevision: Long? = null,
    val lyricsContentRevision: Long? = null,
    val favoriteTrackIds: List<String> = emptyList(),
    val bookmarks: List<CoreBookmarkView> = emptyList(),
    val playlists: List<CorePlaylistView> = emptyList(),
    val albums: List<CoreAlbumCollectionView> = emptyList(),
    val artists: List<CoreArtistCollectionView> = emptyList(),
    val eqConfig: CoreEqConfigView = CoreEqConfigView(),
    val currentLyrics: CoreLyricsView? = null,
    val libraryTrackCount: Int = 0
)

@Serializable
enum class CoreErrorCode {
    @SerialName("NOT_READY") NOT_READY,
    @SerialName("STALE_EPOCH") STALE_EPOCH,
    @SerialName("STALE_REVISION") STALE_REVISION,
    @SerialName("FORBIDDEN") FORBIDDEN,
    @SerialName("INVALID_REQUEST") INVALID_REQUEST,
    @SerialName("BUSY") BUSY,
    @SerialName("CANCELLED") CANCELLED,
    @SerialName("OUTCOME_UNKNOWN") OUTCOME_UNKNOWN,
    @SerialName("FAILED") FAILED,
    @SerialName("NOT_FOUND") NOT_FOUND,
    @SerialName("PERMISSION_EXPIRED") PERMISSION_EXPIRED,
    @SerialName("SOURCE_CHANGED") SOURCE_CHANGED,
    @SerialName("UNSUPPORTED_CAPABILITY") UNSUPPORTED_CAPABILITY,
    // Desktop LAN bridge only: the request's protocol version, target computer or zone is not served there.
    @SerialName("UNSUPPORTED_PROTOCOL") UNSUPPORTED_PROTOCOL,
    @SerialName("WRONG_TARGET") WRONG_TARGET,
    @SerialName("UNKNOWN_ZONE") UNKNOWN_ZONE
}

/**
 * Request envelope sent across the Host/Core boundary (Section 9.3).
 */
@Serializable
data class CoreRequestEnvelope(
    val protocolVersion: Int = CORE_PROTOCOL_VERSION,
    val requestId: String,
    val commandId: String? = null,
    val epoch: CoreEpoch,
    val expectedRevision: Long? = null,
    val kind: String, // "command" | "query"
    val name: String,
    val payload: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class CoreCancelEnvelope(
    val protocolVersion: Int = CORE_PROTOCOL_VERSION,
    val requestId: String,
    val epoch: CoreEpoch
)

@Serializable
data class CoreResponseEnvelope(
    val kind: String = "response",
    val requestId: String,
    val epoch: CoreEpoch,
    val ok: Boolean,
    val value: JsonElement? = null,
    val error: CoreErrorView? = null
)

@Serializable
data class CoreEventMessage(
    val kind: String = "state",
    val epoch: CoreEpoch,
    val sequence: Long,
    val snapshot: CoreSnapshot,
    @kotlinx.serialization.Transient val transportGeneration: Long? = null
)

/**
 * Typed command definitions matching `src/shared/coreProtocol.ts` and Portable Core contracts.
 */
sealed interface CoreTypedCommand {
    val name: String
    val expectedRevision: Long?
        get() = null

    data class PlayCatalogSource(val sourceId: String, val revision: Long) : CoreTypedCommand {
        override val name = "catalog.play"
    }

    @Serializable
    data class Play(val position: Double? = null) : CoreTypedCommand {
        override val name: String = "playback.play"
    }

    @Serializable
    data object Pause : CoreTypedCommand {
        override val name: String = "playback.pause"
    }

    @Serializable
    data object Stop : CoreTypedCommand {
        override val name: String = "playback.stop"
    }

    @Serializable
    data class Seek(val position: Double) : CoreTypedCommand {
        override val name: String = "playback.seek"
    }

    @Serializable
    data object Next : CoreTypedCommand {
        override val name: String = "playback.next"
    }

    @Serializable
    data object Previous : CoreTypedCommand {
        override val name: String = "playback.previous"
    }

    @Serializable
    data class SetVolume(val volume: Double) : CoreTypedCommand {
        override val name: String = "playback.setVolume"
    }

    @Serializable
    data class SetPlayMode(val mode: PlayMode) : CoreTypedCommand {
        override val name: String = "playback.setPlayMode"
    }

    @Serializable
    data class SelectQueueIndex(
        val id: String = "default",
        val revision: Long,
        val index: Int,
        val occurrenceId: String = "occ-$index"
    ) : CoreTypedCommand {
        override val name: String = "playback.selectQueueIndex"
        override val expectedRevision: Long = revision
    }

    @Serializable
    data class ReplaceQueue(
        val id: String = "default",
        val revision: Long? = null,
        val tracks: List<CoreTrackView>,
        val startIndex: Int = 0,
        val play: Boolean = true
    ) : CoreTypedCommand {
        override val name: String = "playback.replaceQueue"
        override val expectedRevision: Long? = revision
    }

    @Serializable
    data class QueueEdit(
        val id: String,
        val revision: Long,
        val edit: JsonObject
    ) : CoreTypedCommand {
        override val name: String = "queue.edit"
        override val expectedRevision: Long = revision
    }

    @Serializable
    data class SettingsEdit(
        val revision: Long? = null,
        val key: String,
        val valueJson: String
    ) : CoreTypedCommand {
        override val name: String = "settings.edit"
        override val expectedRevision: Long? = revision
    }

    @Serializable
    data class PlatformInterruption(
        val reason: String,
        val shouldPause: Boolean = true,
        val transientCanResume: Boolean = false
    ) : CoreTypedCommand {
        override val name: String = "playback.platformInterruption"
    }

    @Serializable
    data class SyncLocalTracks(
        val tracks: List<CoreTrackView>
    ) : CoreTypedCommand {
        override val name: String = "library.syncTracks"
    }

    @Serializable
    data class ToggleFavorite(
        val trackId: String
    ) : CoreTypedCommand {
        override val name: String = "playlists.toggleFavorite"
    }

    @Serializable
    data class AddBookmark(
        val trackId: String,
        val positionSec: Double,
        val label: String
    ) : CoreTypedCommand {
        override val name: String = "bookmarks.add"
    }

    @Serializable
    data class RemoveBookmark(
        val bookmarkId: String
    ) : CoreTypedCommand {
        override val name: String = "bookmarks.remove"
    }

    @Serializable
    data class CreatePlaylist(
        val playlistName: String,
        val trackIds: List<String> = emptyList()
    ) : CoreTypedCommand {
        override val name: String = "playlists.create"
    }

    @Serializable
    data class DeletePlaylist(
        val playlistId: String
    ) : CoreTypedCommand {
        override val name: String = "playlists.delete"
    }

    @Serializable
    data class RenamePlaylist(
        val playlistId: String,
        val newName: String
    ) : CoreTypedCommand {
        override val name: String = "playlists.rename"
    }

    @Serializable
    data class AddTracksToPlaylist(
        val playlistId: String,
        val trackIds: List<String>
    ) : CoreTypedCommand {
        override val name: String = "playlists.addTracks"
    }

    @Serializable
    data class RemoveTrackFromPlaylist(
        val playlistId: String,
        val trackId: String
    ) : CoreTypedCommand {
        override val name: String = "playlists.removeTrack"
    }

    @Serializable
    data class SetEqConfig(
        val config: CoreEqConfigView
    ) : CoreTypedCommand {
        override val name: String = "audio.setEq"
    }
}

/**
 * Typed queries matching `src/shared/coreProtocol.ts` and Portable Core contracts.
 */
sealed interface CoreTypedQuery {
    val name: String

    @Serializable
    data object GetSnapshot : CoreTypedQuery {
        override val name: String = "core.getSnapshot"
    }

    @Serializable
    data class GetQueueWindow(
        val id: String = "default",
        val revision: Long = 0L,
        val offset: Int = 0,
        val limit: Int = 50,
        val search: String? = null
    ) : CoreTypedQuery {
        override val name: String = "playback.getQueueWindow"
    }

    @Serializable
    data class SearchSongs(
        val query: String,
        val offset: Int = 0,
        val limit: Int = 100
    ) : CoreTypedQuery {
        override val name: String = "search.songs"
    }

    @Serializable
    data class GetCurrentLyrics(
        val trackId: String? = null
    ) : CoreTypedQuery {
        override val name: String = "lyrics.current"
    }
}

sealed class CoreResult<out T> {
    data class Success<T>(val value: T, val epoch: CoreEpoch) : CoreResult<T>()
    data class Failure(val error: CoreErrorView, val epoch: CoreEpoch) : CoreResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): CoreErrorView? = (this as? Failure)?.error
}

/**
 * Builders for `queue.edit` mutation payloads matching Portable Core's wire validator.
 */
object CoreQueueEdits {
    fun clear(): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("clear"))
    }

    fun remove(entryIds: List<String>): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("remove"))
        put("entryIds", buildJsonArray { entryIds.forEach { add(JsonPrimitive(it)) } })
    }

    fun insertTrackIds(
        trackIds: List<String>,
        afterEntryId: String? = null,
        beforeEntryId: String? = null
    ): JsonObject = buildJsonObject {
        require(afterEntryId == null || beforeEntryId == null) {
            "queue.edit insert accepts at most one of afterEntryId or beforeEntryId"
        }
        put("kind", JsonPrimitive("insert"))
        put("trackIds", buildJsonArray { trackIds.forEach { add(JsonPrimitive(it)) } })
        afterEntryId?.takeIf { it.isNotBlank() }?.let { put("afterEntryId", JsonPrimitive(it)) }
        beforeEntryId?.takeIf { it.isNotBlank() }?.let { put("beforeEntryId", JsonPrimitive(it)) }
    }

    fun insertTracks(
        tracks: List<CoreTrackView>,
        afterEntryId: String? = null,
        beforeEntryId: String? = null
    ): JsonObject = buildJsonObject {
        require(afterEntryId == null || beforeEntryId == null) {
            "queue.edit insert accepts at most one of afterEntryId or beforeEntryId"
        }
        put("kind", JsonPrimitive("insert"))
        put("items", buildJsonArray {
            tracks.forEach { track ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(track.id))
                    put("snapshot", trackToCoreRecordSnapshot(track))
                })
            }
        })
        afterEntryId?.takeIf { it.isNotBlank() }?.let { put("afterEntryId", JsonPrimitive(it)) }
        beforeEntryId?.takeIf { it.isNotBlank() }?.let { put("beforeEntryId", JsonPrimitive(it)) }
    }

    private fun trackToCoreRecordSnapshot(track: CoreTrackView): JsonObject {
        val filePath = track.source?.takeIf { it.isNotBlank() } ?: track.id
        val rawTail = filePath.substringAfterLast('/').substringAfterLast('\\')
        val fileName = runCatching { java.net.URLDecoder.decode(rawTail, "UTF-8") }
            .getOrDefault(rawTail).ifBlank { track.id }
        val artistNames = track.artists.filter { it.isNotBlank() }
            .ifEmpty { listOfNotNull(track.artist.takeIf { it.isNotBlank() }) }
        return buildJsonObject {
            put("id", JsonPrimitive(track.id))
            put("title", JsonPrimitive(track.title.ifBlank { fileName }))
            put("artist", JsonPrimitive(track.artist))
            put("album", JsonPrimitive(track.album))
            put("filePath", JsonPrimitive(filePath))
            put("fileName", JsonPrimitive(fileName))
            put("duration", JsonPrimitive(track.duration.coerceAtLeast(0.0)))
            put("size", JsonPrimitive((track.size ?: 0L).coerceAtLeast(0L)))
            put("cover", track.cover?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
            put("lyrics", JsonNull)
            put("source", JsonPrimitive("local"))
            put("artists", buildJsonArray {
                artistNames.forEach { name -> add(buildJsonObject { put("name", JsonPrimitive(name)) }) }
            })
            track.ncmSongId?.toLongOrNull()?.let { put("ncmSongId", JsonPrimitive(it)) }
            track.albumId?.takeIf { it.isNotBlank() }?.let { put("albumId", JsonPrimitive(it)) }
            track.format?.takeIf { it.isNotBlank() }?.let { put("format", JsonPrimitive(it)) }
            track.releaseDate?.takeIf { it.isNotBlank() }?.let { put("releaseDate", JsonPrimitive(it)) }
            track.genre?.takeIf { it.isNotBlank() }?.let { put("genre", JsonPrimitive(it)) }
            track.albumArtist?.takeIf { it.isNotBlank() }?.let { put("albumArtist", JsonPrimitive(it)) }
            track.isrc?.takeIf { it.isNotBlank() }?.let { put("isrc", JsonPrimitive(it)) }
            track.sampleRate?.takeIf { it > 0 }?.let { put("sampleRate", JsonPrimitive(it)) }
            track.bitDepth?.takeIf { it > 0 }?.let { put("bitDepth", JsonPrimitive(it)) }
            track.discNumber?.takeIf { it > 0 }?.let { put("discNumber", JsonPrimitive(it)) }
            track.trackNumber?.takeIf { it > 0 }?.let { put("trackNumber", JsonPrimitive(it)) }
            track.bitrate?.takeIf { it > 0.0 }?.let { put("bitrate", JsonPrimitive(it)) }
            track.bpm?.takeIf { it > 0.0 }?.let { put("bpm", JsonPrimitive(it)) }
            track.addedAt?.takeIf { it >= 0L }?.let { put("addedAt", JsonPrimitive(it)) }
            track.cueRange?.let { cue ->
                put("cueRange", buildJsonObject {
                    put("startSeconds", JsonPrimitive(cue.startSeconds))
                    put("endSeconds", JsonPrimitive(cue.endSeconds))
                    put("virtualPregapSeconds", JsonPrimitive(cue.virtualPregapSeconds))
                    put("sourcePregapSeconds", JsonPrimitive(cue.sourcePregapSeconds))
                    put("pregapSeconds", JsonPrimitive(cue.pregapSeconds))
                })
            }
        }
    }
}
