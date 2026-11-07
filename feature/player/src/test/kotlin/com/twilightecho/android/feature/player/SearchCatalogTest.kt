package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.plugins.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SearchCatalogTest {
    private val tracks = listOf(
        CoreTrackView("1", title = "夜航", artist = "北岸", album = "夜航"),
        CoreTrackView("2", title = "晚风", artist = "北岸 / 南山", artists = listOf("北岸", "南山"), album = "夜航"),
        CoreTrackView("3", title = "曙光", artist = "北岸", album = "白昼")
    )

    @Test fun collectionsRetainAllMembersInsteadOfGroupingOnlyMatchingSongs() {
        val catalog = searchLibraryCatalog(tracks, "夜航")
        assertEquals(listOf("1", "2"), catalog.albums.single().trackIds)
        assertTrue(catalog.artists.isEmpty())
        val artists = searchLibraryCatalog(tracks, " 北岸 ").artists
        assertEquals(listOf("1", "2", "3"), artists.single().trackIds)
        assertEquals(2, artists.single().albumCount)
        assertEquals(listOf("2"), searchLibraryCatalog(tracks, "南山").artists.single().trackIds)
    }

    @Test fun blankListsCollectionsAndCaseInsensitiveQueriesMatchTheirOwnFields() {
        assertEquals(2, searchLibraryCatalog(tracks, " ").albums.size)
        assertEquals(2, searchLibraryCatalog(tracks, "").artists.size)
        val english = listOf(CoreTrackView("en", title = "Song", artist = "Aimer", album = "Moonlight"))
        assertEquals("Moonlight", searchLibraryCatalog(english, "MOON").albums.single().title)
        assertEquals("Aimer", searchLibraryCatalog(english, "aimer").artists.single().name)
        assertTrue(searchLibraryCatalog(english, "Song").artists.isEmpty())
        assertTrue(searchLibraryCatalog(english, "Song").albums.isEmpty())
    }

    @Test fun onlyEnabledSearchableProvidersAppearAndSearchOnlySourcesNeedNoHome() {
        fun provider(id: String, methods: List<String> = listOf("searchSongs")) =
            MobileProvider(id, id, listOf("search"), methods, pluginId = id, apiVersion = 2)
        val repository = PluginRepositoryState(
            installed = listOf("qq", "kg", "search-only", NCM_PLUGIN_ID).map {
                InstalledPlugin(it, "1", enabled = it != "kg", checksumSha256 = "")
            }, providers = listOf(provider("qq"), provider("kg"), provider("search-only"), provider("uninstalled"), provider(NCM_PLUGIN_ID)))
        assertEquals(listOf("qq", "search-only"), searchProviderOptions(repository).map { it.pluginId })
        assertTrue(searchProviderOptions(repository.copy(providers = listOf(provider("qq", emptyList())))).isEmpty())
    }

    @Test fun providerCollectionsKeepEntityIdsArtworkAndCounts() {
        val value = Json.parseToJsonElement("""{"items":[{"id":"a/b","name":"北岸","picUrl":"https://img.example/artist.jpg",
            "musicSize":8,"albumSize":2},{"name":"缺少 ID"}],"total":31}""")
        val page = providerSearchPage(value, "qqmusic", SearchCategory.ARTISTS, 0)
        val item = page.collections.single()
        assertEquals("a/b", item.id); assertEquals("artist", item.kind)
        assertEquals("https://img.example/artist.jpg", item.cover)
        assertEquals("2 张专辑", item.subtitle); assertEquals(8, item.count)
        assertEquals(2, page.nextOffset)
        val album = providerSearchCollections(Json.parseToJsonElement("""[{"id":9,"title":"夜航","artist":{"name":"北岸"},"trackCount":12}]"""), "album").single()
        assertEquals("9", album.id); assertEquals("夜航", album.title); assertEquals("北岸", album.subtitle)
    }

    @Test fun pagingUsesRawOccurrencesAndTheProvidersExplicitContinuation() {
        val value = Json.parseToJsonElement("""{"items":[{"id":"1","title":"夜航"},{"id":"1","title":"夜航"}],
            "total":80,"hasMore":true,"nextOffset":40}""")
        val page = providerSearchPage(value, "kugou", SearchCategory.SONGS, 20)
        assertEquals(40, page.nextOffset); assertEquals(2, page.size)
        assertEquals(listOf("provider://kugou/1", "provider://kugou/1"), page.tracks.map { it.id })
        assertNotEquals(page.tracks[0].queueEntryId, page.tracks[1].queueEntryId)
        val last = providerSearchPage(Json.parseToJsonElement("""{"albums":[{"id":"9","name":"夜航"}],"total":100,"hasMore":false}"""),
            "navidrome", SearchCategory.ALBUMS, 0)
        assertNull(last.nextOffset)
    }
}
