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
    private val cached = MutableStateFlow<Map<String, LibraryArtistProfile>>(emptyMap())
    val profiles = cached.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val requests = mutableMapOf<String, Any>()

    fun reset() {
        requests.clear(); jobs.values.forEach { it.cancel() }; jobs.clear()
        cached.value = emptyMap(); mutable.value = LibraryArtistProfile()
    }

    fun load(artist: CoreArtistCollectionView, force: Boolean = false) {
        mutable.value = cached.value[artist.id] ?: LibraryArtistProfile(artist.id, loading = true)
        request(artist, force)
    }

    /** Visible tiles and the detail page share one session-scoped read, not song/album artwork. */
    fun request(artist: CoreArtistCollectionView, force: Boolean = false) {
        if (!force && (artist.id in cached.value || jobs[artist.id]?.isActive == true)) return
        jobs.remove(artist.id)?.cancel()
        val origin = Any()
        requests[artist.id] = origin
        val previous = cached.value[artist.id]
        publish((previous ?: LibraryArtistProfile(artist.id)).copy(loading = true, error = null))
        jobs[artist.id] = scope.launch {
            val result = read(artist)
            if (requests[artist.id] === origin) {
                publish(LibraryArtistProfile(artist.id, if (result.isSuccess) result.getOrNull() else previous?.metadata,
                    error = result.exceptionOrNull()?.message))
                jobs.remove(artist.id)
                requests.remove(artist.id)
            }
        }
    }

    private fun publish(profile: LibraryArtistProfile) {
        // Bitmap metadata stays in memory for nearby artists, never in the persistent track index.
        cached.update { current ->
            val updated = current - profile.collectionId + (profile.collectionId to profile)
            val keep = updated.filterValues { it.loading }.keys + mutable.value.collectionId +
                updated.filterValues { !it.loading }.keys.toList().takeLast(48)
            updated.filterKeys { it in keep }
        }
        if (mutable.value.collectionId == profile.collectionId) mutable.value = profile
    }
}
