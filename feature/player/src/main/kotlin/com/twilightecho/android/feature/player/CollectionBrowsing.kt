package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CollectionSort
import com.twilightecho.android.coreclient.CollectionSortField
import com.twilightecho.android.coreclient.CoreTrackView
import java.text.Collator
import java.util.Locale

/** Stable sorting retains duplicate occurrences and searches title, artist and album together. */
internal fun browseCollection(tracks: List<CoreTrackView>, query: String, sort: CollectionSort): List<CoreTrackView> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matches = if (words.isEmpty()) tracks else tracks.filter { track ->
        val text = "${track.title}\n${track.artist}\n${track.album}"
        words.all { text.contains(it, ignoreCase = true) }
    }
    if (sort.field == CollectionSortField.ORIGINAL) return if (sort.descending) matches.asReversed() else matches
    val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
    fun key(track: CoreTrackView) = when (sort.field) {
        CollectionSortField.TITLE -> track.title
        CollectionSortField.ARTIST -> track.artist
        CollectionSortField.ALBUM -> track.album
        CollectionSortField.ORIGINAL -> ""
    }
    val comparator = Comparator<CoreTrackView> { a, b -> collator.compare(key(a), key(b)) }
    return matches.sortedWith(if (sort.descending) comparator.reversed() else comparator)
}

internal val StreamingDetail.isPlaylist: Boolean
    get() = collection?.kind == "playlist" || group == StreamingLibraryGroup.LIKED

internal fun streamingSortKey(account: String?, detail: StreamingDetail): String =
    "ncm:${account.orEmpty()}:${detail.key}"

internal fun localSortKey(collection: UiCollection) = "library:${collection.kind}:${collection.id}"
