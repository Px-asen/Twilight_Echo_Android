package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import org.junit.Assert.*
import org.junit.Test

class CollectionBrowsingTest {
    private val tracks = listOf(
        CoreTrackView("1", title = "Zulu", artist = "北岸", album = "日落", queueEntryId = "first"),
        CoreTrackView("2", title = "alpha", artist = "晨光", album = "夜航"),
        CoreTrackView("1", title = "Zulu", artist = "北岸", album = "日落", queueEntryId = "again"),
        CoreTrackView("3", title = "Beta", artist = "北岸", album = "夜航", unavailableReason = "已下架"))

    @Test fun originalAndReverseKeepEveryOccurrenceIncludingUnavailableSongs() {
        assertEquals(tracks, browseCollection(tracks, "", CollectionSort()))
        assertEquals(tracks.reversed(), browseCollection(tracks, "", CollectionSort(descending = true)))
    }

    @Test fun searchCombinesTitleArtistAndAlbumAndIgnoresCaseAndExtraSpace() {
        assertEquals(listOf("2"), browseCollection(tracks, " ALPHA  夜航 ", CollectionSort()).map { it.id })
        assertEquals(listOf("3"), browseCollection(tracks, "北岸 夜航", CollectionSort()).map { it.id })
        assertTrue(browseCollection(tracks, "不存在", CollectionSort()).isEmpty())
    }

    @Test fun nameSortsAreStableInEitherDirection() {
        assertEquals(listOf("2", "3", "1", "1"), browseCollection(tracks, "", CollectionSort(CollectionSortField.TITLE)).map { it.id })
        val reverse = browseCollection(tracks, "", CollectionSort(CollectionSortField.TITLE, true))
        assertEquals(listOf("1", "1", "3", "2"), reverse.map { it.id })
        assertEquals(listOf("first", "again"), reverse.take(2).map { it.queueEntryId })
        assertEquals(listOf("1", "1", "3", "2"), browseCollection(tracks, "", CollectionSort(CollectionSortField.ARTIST)).map { it.id })
        assertEquals(listOf("1", "1", "2", "3"), browseCollection(tracks, "", CollectionSort(CollectionSortField.ALBUM)).map { it.id })
    }
}
