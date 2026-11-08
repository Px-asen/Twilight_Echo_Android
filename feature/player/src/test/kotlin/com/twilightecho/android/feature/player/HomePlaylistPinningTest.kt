package com.twilightecho.android.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePlaylistPinningTest {
    private val playlists = (1..4).map { StreamingCollection("$it", "playlist", "歌单 $it") }

    @Test fun pinsLeadTheShelfAndUnpinningRestoresTheProviderOrder() {
        assertEquals(listOf("3", "2", "1", "4"), orderHomePlaylists(playlists, listOf("3", "2")).map { it.id })
        assertEquals(listOf("2", "1", "3", "4"), orderHomePlaylists(playlists, listOf("2")).map { it.id })
        assertEquals(playlists, orderHomePlaylists(playlists, emptyList()))
        assertEquals(listOf("1", "2", "3", "4"), playlists.map { it.id })
    }

    @Test fun refreshUsesCurrentMetadataAndIgnoresPinsMissingFromThisShelf() {
        val updated = playlists[2].copy(title = "更新后的歌单", count = 20)
        val refreshed = listOf(playlists[3], updated, playlists[0], updated)
        assertEquals(listOf(updated, playlists[3], playlists[0]), orderHomePlaylists(refreshed, listOf("2", "3")))
    }
}
