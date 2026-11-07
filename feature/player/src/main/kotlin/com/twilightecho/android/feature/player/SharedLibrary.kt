package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreAlbumCollectionView
import com.twilightecho.android.coreclient.CoreArtistCollectionView
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CatalogEntity
import com.twilightecho.android.coreclient.artistNameKey
import com.twilightecho.android.coreclient.libraryArtistNames
import java.text.Collator
import java.util.Locale

/** Library filter values besides a computer's server ID. */
const val LIBRARY_ALL = "all"
const val LIBRARY_PHONE = "phone"

/** One entry of the library's device menu. */
data class LibraryDeviceOption(val id: String, val label: String, val connected: Boolean = true)

/** The songs the phone remembers from one computer, whether or not it is reachable right now. */
internal data class DesktopShelf(val serverId: String, val hostLabel: String, val connected: Boolean,
    val tracks: List<CoreTrackView>)

internal data class SharedLibraryView(val tracks: List<CoreTrackView> = emptyList(),
    val albums: List<CoreAlbumCollectionView> = emptyList(), val artists: List<CoreArtistCollectionView> = emptyList())

internal data class SharedLibraryProjection(val all: SharedLibraryView, val visible: SharedLibraryView,
    val owners: Map<String, String>, val phoneTrackIds: Set<String>)

/** Confined to the library collector's worker; loading/error updates reuse the existing catalog. */
internal class SharedLibraryProjector {
    private var phone: List<CoreTrackView>? = null
    private var desktops: List<DesktopShelf>? = null
    private var device: String? = null
    private var projection: SharedLibraryProjection? = null

    fun project(phone: List<CoreTrackView>, desktops: List<DesktopShelf>, device: String): SharedLibraryProjection {
        val previous = projection
        val sameLibrary = previous != null && this.phone == phone && this.desktops == desktops
        val all = if (sameLibrary) previous!!.all else sharedLibrary(phone, desktops, LIBRARY_ALL)
        val visible = when {
            device == LIBRARY_ALL -> all
            sameLibrary && this.device == device -> previous!!.visible
            else -> sharedLibrary(phone, desktops, device)
        }
        return SharedLibraryProjection(all, visible,
            if (sameLibrary) previous!!.owners else desktopOwners(desktops),
            if (previous != null && this.phone == phone) previous.phoneTrackIds else phone.mapTo(HashSet()) { it.id }
        ).also {
            this.phone = phone
            this.desktops = desktops
            this.device = device
            projection = it
        }
    }
}

/**
 * One library for the phone and its computers. "All" lists each song once, preferring the copy that
 * plays without a network (phone, then a connected computer); a single device shows exactly its songs.
 */
internal fun sharedLibrary(phone: List<CoreTrackView>, desktops: List<DesktopShelf>, device: String): SharedLibraryView {
    val tracks = when (device) {
        LIBRARY_PHONE -> phone
        LIBRARY_ALL -> {
            val seen = HashSet<String>()
            val ordered = phone + desktops.filter { it.connected }.flatMap { it.tracks } +
                desktops.filterNot { it.connected }.flatMap { it.tracks }
            ordered.filter { seen.add(songKey(it)) }
        }
        else -> desktops.find { it.serverId == device }?.tracks.orEmpty()
    }.distinctBy { it.id }
    val sorted = sortedByTitle(tracks)
    val catalogAlbums = device != LIBRARY_ALL && device != LIBRARY_PHONE
    return SharedLibraryView(sorted, libraryAlbums(sorted, catalogAlbums), libraryArtists(sorted, catalogAlbums))
}

/** Which computer each remembered song belongs to; phone songs are absent. */
internal fun desktopOwners(desktops: List<DesktopShelf>): Map<String, String> =
    HashMap<String, String>().apply { desktops.forEach { shelf -> shelf.tracks.forEach { put(it.id, shelf.serverId) } } }

/** The same plain matching as the phone's Music Core: title, artist or album contains the words. */
internal fun searchSharedLibrary(tracks: List<CoreTrackView>, query: String): List<CoreTrackView> {
    val words = query.trim().lowercase()
    if (words.isEmpty()) return tracks
    return tracks.filter { it.title.lowercase().contains(words) || it.artist.lowercase().contains(words) ||
        it.album.lowercase().contains(words) }
}

internal fun libraryAlbums(tracks: List<CoreTrackView>, catalogAlbums: Boolean = false): List<CoreAlbumCollectionView> =
    tracks.groupBy { albumKey(it, catalogAlbums) }.map { (key, members) ->
        val release = members.first().catalog?.items?.firstOrNull { catalogAlbums && it.kind == "release" }
        CoreAlbumCollectionView(id = "album:$key", title = release?.title?.takeIf { it.isNotBlank() }
            ?: members.first().album.trim().ifBlank { "未知专辑" },
            artist = release?.artist?.takeIf { it.isNotBlank() }
                ?: members.groupingBy { it.artist.trim() }.eachCount().maxByOrNull { it.value }?.key.orEmpty(),
            trackCount = members.size, totalDurationSec = members.sumOf { it.duration }, trackIds = members.map { it.id })
    }.let { albums -> sortedByKey(albums) { it.title } }

internal fun libraryArtists(tracks: List<CoreTrackView>, catalogAlbums: Boolean = false): List<CoreArtistCollectionView> =
    tracks.flatMap { track -> libraryArtistNames(track).map { it to track } }
        .groupBy { (name, _) -> artistNameKey(name) }.map { (key, credits) ->
        val members = credits.map { it.second }.distinctBy { it.id }
        CoreArtistCollectionView(id = "artist:$key", name = credits.first().first,
            albumCount = members.map { albumKey(it, catalogAlbums) }.distinct().size,
            trackCount = members.size, trackIds = members.map { it.id })
    }.let { artists -> sortedByKey(artists) { it.name } }

/** A computer's same-title releases stay separate when drilling into a merged artist. */
private fun albumKey(track: CoreTrackView, catalogAlbums: Boolean): String =
    track.catalog?.items?.firstOrNull { catalogAlbums && it.kind == "release" }?.let { "catalog:${it.id}" }
        ?: track.album.trim().lowercase()

/** A phone-preferred song still carries its computer copy's exact catalog membership. */
internal fun desktopArtistSources(artist: CoreArtistCollectionView, visible: List<CoreTrackView>,
    desktop: List<CoreTrackView>): Set<String> {
    val ids = artist.trackIds.toHashSet()
    val songs = visible.filter { it.id in ids }.mapTo(HashSet(), ::songKey)
    val name = artistNameKey(artist.name)
    return desktop.asSequence().filter { track ->
        (track.id in ids || songKey(track) in songs) && libraryArtistNames(track).any { artistNameKey(it) == name }
    }.mapNotNull { it.catalog?.sourceId }.toSet()
}

private fun songKey(track: CoreTrackView) = listOf(track.title, track.artist, track.album)
    .joinToString("\u0000") { value -> value.lowercase().filter { it.isLetterOrDigit() } }

/** Editing choices come only from exact Catalog memberships of the selected computer's collection. */
internal fun desktopArtistEditTargets(trackIds: List<String>, name: String, tracks: List<CoreTrackView>): List<Pair<CatalogEntity, String>> {
    val ids = trackIds.toSet()
    return tracks.filter { it.id in ids }.flatMap { track ->
        track.catalog?.items.orEmpty().filter { it.kind == "artist" && artistNameKey(it.title) == artistNameKey(name) }
            .map { entity -> entity to listOf(track.album, track.title).filter(String::isNotBlank).joinToString(" · ") }
    }.distinctBy { it.first.id }
}

private fun sortedByTitle(tracks: List<CoreTrackView>): List<CoreTrackView> = sortedByKey(tracks) { it.title + "\u0000" + it.artist }

/** Chinese titles sort by pronunciation, so the list reads like a music app's, not by code point. */
private fun <T> sortedByKey(items: List<T>, key: (T) -> String): List<T> {
    val collator = Collator.getInstance(Locale.CHINA)
    return items.map { collator.getCollationKey(key(it)) to it }.sortedBy { it.first }.map { it.second }
}
