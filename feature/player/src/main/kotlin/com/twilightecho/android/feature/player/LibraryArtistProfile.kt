package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Metadata supplements a merged collection; its songs and album membership remain unchanged. */
data class LibraryArtistProfile(val collectionId: String = "", val metadata: EntityMetadataDetail? = null,
    val loading: Boolean = false, val error: String? = null)

internal class LibraryArtistPresenter(private val scope: CoroutineScope,
    private val read: suspend (CoreArtistCollectionView) -> Result<EntityMetadataDetail?>) {
    private val mutable = MutableStateFlow(LibraryArtistProfile())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var request = 0L

    fun reset() { request++; job?.cancel(); mutable.value = LibraryArtistProfile() }
    fun load(artist: CoreArtistCollectionView, force: Boolean = false) {
        if (!force && mutable.value.collectionId == artist.id) return
        job?.cancel(); val origin = ++request
        mutable.value = LibraryArtistProfile(artist.id, loading = true)
        job = scope.launch {
            val result = read(artist)
            if (origin == request) mutable.value = LibraryArtistProfile(artist.id, result.getOrNull(),
                error = result.exceptionOrNull()?.message)
        }
    }
}
