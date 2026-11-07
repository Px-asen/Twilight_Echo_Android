package com.twilightecho.android.playbackhost

import com.twilightecho.android.coreclient.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DesktopTrackIndexTest {
    private val artist = CatalogEntity("artist-1", "artist", "北岸")
    private fun track(index: Int) = CoreTrackView("remote-track:$index", title = "歌 $index", artist = "北岸",
        queueEntryId = "queue-entry", cover = "data:image/png;base64,cover",
        catalog = CatalogTrackReferences(7, "source-$index", listOf(artist, CatalogEntity("release", "release", "专辑"))))

    @Test fun indexingRetainsArtistReferencesWithoutQueueOccurrencesOrSongBitmaps() {
        val indexed = indexedDesktopTrack(track(1))
        assertNull(indexed.queueEntryId)
        assertNull(indexed.cover)
        assertEquals(track(1).catalog, indexed.catalog)
        val json = DefaultCoreClient.DefaultJson
        val index = DesktopLibraryIndex("desktop", hostLabel = "电脑", baseUrl = "https://desktop",
            tracks = listOf(indexed), artistReferencesIncluded = true)
        assertEquals(index, json.decodeFromString<DesktopLibraryIndex>(json.encodeToString(index)))
    }

    @Test fun artistLookupUsesTheCompleteIndexAndExactSourceMembershipNotTheFirstSearchPage() {
        val tracks = (0..150).map { indexedDesktopTrack(track(it)) }
        assertEquals(listOf(artist), desktopArtistEntities(tracks, setOf("source-150"), " 北岸 "))
        assertTrue(desktopArtistEntities(tracks, setOf("source-other"), "北岸").isEmpty())
        assertTrue(desktopArtistEntities(tracks, setOf("source-150"), "北岸乐队").isEmpty())
    }

    @Test fun legacyIndexesAreRecopiedAndCatalogChangesInvalidateArtistReferences() {
        val old = DefaultCoreClient.DefaultJson.decodeFromString<DesktopLibraryIndex>(
            """{"serverId":"desktop","hostLabel":"电脑","baseUrl":"https://desktop","epoch":"e","revision":3,"complete":true}""")
        assertFalse(old.isCurrent("e", 3, null, 0))
        val current = old.copy(artistReferencesIncluded = true, catalogRevision = 7)
        assertTrue(current.isCurrent("e", 3, 7, 0))
        assertFalse(current.isCurrent("e", 3, 8, 0))
        assertFalse(current.isCurrent("new-epoch", 3, 7, 0))
    }
}
