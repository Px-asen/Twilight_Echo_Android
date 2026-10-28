package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreAlbumCollectionView
import com.twilightecho.android.coreclient.CoreArtistCollectionView
import com.twilightecho.android.coreclient.CoreTrackView
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
    return SharedLibraryView(sorted, libraryAlbums(sorted), libraryArtists(sorted))
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

internal fun libraryAlbums(tracks: List<CoreTrackView>): List<CoreAlbumCollectionView> =
    tracks.groupBy { it.album.trim().lowercase() }.map { (key, members) ->
        CoreAlbumCollectionView(id = "album:$key", title = members.first().album.trim().ifBlank { "未知专辑" },
            artist = members.groupingBy { it.artist.trim() }.eachCount().maxByOrNull { it.value }?.key.orEmpty(),
            trackCount = members.size, totalDurationSec = members.sumOf { it.duration }, trackIds = members.map { it.id })
    }.let { albums -> sortedByKey(albums) { it.title } }

internal fun libraryArtists(tracks: List<CoreTrackView>): List<CoreArtistCollectionView> =
    tracks.groupBy { it.artist.trim().lowercase() }.map { (key, members) ->
        CoreArtistCollectionView(id = "artist:$key", name = members.first().artist.trim().ifBlank { "未知艺术家" },
            albumCount = members.map { it.album.trim().lowercase() }.distinct().size,
            trackCount = members.size, trackIds = members.map { it.id })
    }.let { artists -> sortedByKey(artists) { it.name } }

private fun songKey(track: CoreTrackView) = listOf(track.title, track.artist, track.album)
    .joinToString("\u0000") { value -> value.lowercase().filter { it.isLetterOrDigit() } }

private fun sortedByTitle(tracks: List<CoreTrackView>): List<CoreTrackView> = sortedByKey(tracks) { it.title + "\u0000" + it.artist }

/** Chinese titles sort by pronunciation, so the list reads like a music app's, not by code point. */
private fun <T> sortedByKey(items: List<T>, key: (T) -> String): List<T> {
    val collator = Collator.getInstance(Locale.CHINA)
    return items.map { collator.getCollationKey(key(it)) to it }.sortedBy { it.first }.map { it.second }
}
