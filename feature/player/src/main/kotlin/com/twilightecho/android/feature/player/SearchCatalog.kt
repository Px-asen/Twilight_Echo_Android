package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreAlbumCollectionView
import com.twilightecho.android.coreclient.CoreArtistCollectionView
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.plugins.PluginRepositoryState
import kotlinx.serialization.json.*

enum class SearchCategory(val label: String, internal val method: String, internal val kind: String) {
    SONGS("歌曲", "searchSongs", "song"),
    ARTISTS("歌手", "searchArtists", "artist"),
    ALBUMS("专辑", "searchAlbums", "album")
}

data class SearchProviderOption(val pluginId: String, val providerId: String, val name: String,
    val methods: List<String> = emptyList())

/** Only enabled, described providers may receive a search request. */
internal fun searchProviderOptions(repository: PluginRepositoryState): List<SearchProviderOption> {
    val enabled = repository.installed.filter { it.enabled }.mapTo(HashSet()) { it.id }
    return repository.providers.filter { it.pluginId in enabled && it.pluginId != NCM_PLUGIN_ID && it.searchable() }
        .map { SearchProviderOption(it.pluginId, it.id, it.name, it.methods) }.distinctBy { it.pluginId }
}

internal data class LibrarySearchCatalog(val albums: List<CoreAlbumCollectionView>, val artists: List<CoreArtistCollectionView>)

/** Match collection names against the full library, never against a partial song-search result. */
internal fun searchLibraryCatalog(tracks: List<CoreTrackView>, query: String): LibrarySearchCatalog {
    return filterLibrarySearchCatalog(LibrarySearchCatalog(libraryAlbums(tracks), libraryArtists(tracks)), query)
}

internal fun filterLibrarySearchCatalog(catalog: LibrarySearchCatalog, query: String): LibrarySearchCatalog {
    val keyword = query.trim()
    return LibrarySearchCatalog(
        catalog.albums.filter { it.title.contains(keyword, ignoreCase = true) || it.artist.contains(keyword, ignoreCase = true) },
        catalog.artists.filter { it.name.contains(keyword, ignoreCase = true) }
    )
}

internal data class ProviderSearchPage(val tracks: List<CoreTrackView>, val collections: List<StreamingCollection>,
    val total: Int, val size: Int, val nextOffset: Int?)

internal fun providerCollectionArray(value: JsonElement?): JsonArray = value as? JsonArray
    ?: (value as? JsonObject)?.let { page -> listOf("items", "albums", "artists").firstNotNullOfOrNull { page[it] as? JsonArray } }
    ?: JsonArray(emptyList())

/** The shared provider contract uses the same artist/album entities on every audio source. */
internal fun providerSearchCollections(value: JsonElement?, kind: String): List<StreamingCollection> =
    providerCollectionArray(value).mapNotNull { item ->
        val raw = item as? JsonObject ?: return@mapNotNull null
        val id = raw.text("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val artist = (raw["artist"] as? JsonObject)?.text("name") ?: raw.text("artist")
        val count = (raw["trackCount"] as? JsonPrimitive)?.intOrNull
            ?: (raw["musicSize"] as? JsonPrimitive)?.intOrNull ?: 0
        val subtitle = if (kind == "artist") (raw["albumSize"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it > 0 }?.let { "$it 张专辑" }.orEmpty() else artist
        StreamingCollection(id, kind, raw.text("name").ifBlank { raw.text("title", "未命名") }, subtitle,
            listOf("cover", "coverSmall", "picUrl", "picUrlSmall").firstNotNullOfOrNull { key ->
                raw.text(key).takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            }, count)
    }

internal fun providerSearchPage(value: JsonElement, providerId: String, category: SearchCategory, offset: Int): ProviderSearchPage {
    val raw = if (category == SearchCategory.SONGS) providerTrackArray(value) else providerCollectionArray(value)
    val page = value as? JsonObject
    val total = (page?.get("total") as? JsonPrimitive)?.intOrNull ?: offset + raw.size
    val more = (page?.get("hasMore") as? JsonPrimitive)?.booleanOrNull ?: (offset + raw.size < total)
    val next = ((page?.get("nextOffset") as? JsonPrimitive)?.intOrNull ?: offset + raw.size)
        .takeIf { more && raw.isNotEmpty() && it > offset }
    return ProviderSearchPage(
        if (category == SearchCategory.SONGS) raw.providerTracks(providerId, "search-$providerId-$offset") else emptyList(),
        if (category != SearchCategory.SONGS) providerSearchCollections(value, category.kind) else emptyList(),
        total, raw.size, next
    )
}
