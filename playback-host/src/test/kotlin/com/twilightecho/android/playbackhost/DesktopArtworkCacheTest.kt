package com.twilightecho.android.playbackhost

import com.twilightecho.android.coreclient.CatalogTrackReferences
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DesktopMediaReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopArtworkCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun durableSourceKeyPersistsAcrossRestartsAndChangedRemoteTrackIds() {
        val cover = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/"
        val epoch1Track = CoreTrackView(
            id = "remote-track:epoch-1-uuid",
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            catalog = CatalogTrackReferences(revision = 1L, sourceId = "src-qingtian")
        )
        val firstCache = DesktopArtworkCache(folder.root)
        firstCache.loadIndexes()
        firstCache.putBatch(
            serverId = "desk-main",
            updates = mapOf(desktopTrackArtworkKey(epoch1Track) to cover),
            revisionKey = "e1:1"
        )
        assertEquals(cover, firstCache.getMemoryOnly("desk-main", desktopTrackArtworkKey(epoch1Track)))

        // Simulate app restart and desktop epoch restart (new remote-track UUID, same catalog.sourceId).
        val restartedCache = DesktopArtworkCache(folder.root)
        restartedCache.loadIndexes()
        val epoch2Track = epoch1Track.copy(
            id = "remote-track:epoch-2-new-uuid",
            catalog = CatalogTrackReferences(revision = 4L, sourceId = "src-qingtian")
        )
        val streamTrack = CoreTrackView(
            id = DesktopMediaReference("desk-main", "lib-1", "src-qingtian").resourceId(),
            title = "晴天"
        )
        assertEquals(desktopTrackArtworkKey(epoch1Track), desktopTrackArtworkKey(epoch2Track))
        assertEquals(desktopTrackArtworkKey(epoch1Track), desktopTrackArtworkKey(streamTrack))
        assertEquals(cover, restartedCache.get("desk-main", desktopTrackArtworkKey(epoch2Track)))
        assertEquals(cover, restartedCache.getMemoryOnly("desk-main", desktopTrackArtworkKey(streamTrack)))
    }

    private fun blobFiles(): List<File> =
        File(folder.root, "desktop-artwork/blobs").listFiles()?.filter { it.extension == "dat" }.orEmpty()

    @Test fun identicalAlbumCoversDeduplicateToSingleBlobOnDisk() {
        val sharedAlbumCover = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
        val cache = DesktopArtworkCache(folder.root)
        cache.loadIndexes()
        val updates = (1..12).associate { trackNo -> "source:album-track-$trackNo" to (sharedAlbumCover as String?) }
        cache.putBatch("desk-main", updates, revisionKey = "e1:3")

        assertEquals(1, blobFiles().size)
        for (trackNo in 1..12) {
            assertEquals(sharedAlbumCover, cache.get("desk-main", "source:album-track-$trackNo"))
        }
    }

    @Test fun explicitNullClearsCachedCoverAndMarksRevisionKnown() {
        val cover = "data:image/jpeg;base64,coverBytesA"
        val cache = DesktopArtworkCache(folder.root)
        cache.loadIndexes()
        cache.putBatch("desk-main", mapOf("source:s1" to cover), revisionKey = "e1:1")
        assertTrue(cache.isKnown("desk-main", "source:s1", "e1:1"))
        assertEquals(cover, cache.get("desk-main", "source:s1"))

        cache.putBatch("desk-main", mapOf("source:s1" to null), revisionKey = "e1:2")
        assertNull(cache.get("desk-main", "source:s1"))
        assertNull(cache.getMemoryOnly("desk-main", "source:s1"))
        assertTrue(cache.isKnown("desk-main", "source:s1", "e1:2"))
        assertFalse(cache.isKnown("desk-main", "source:s1", "e1:3"))
        assertTrue(blobFiles().isEmpty())
    }

    @Test fun retainServerKeysAndDeleteServerPruneUnreferencedBlobs() {
        val cache = DesktopArtworkCache(folder.root)
        cache.loadIndexes()
        cache.putBatch(
            "desk-a",
            mapOf(
                "source:keep" to "data:image/jpeg;base64,keepCover",
                "source:drop" to "data:image/jpeg;base64,dropCover"
            ),
            revisionKey = "ea:1"
        )
        cache.putBatch(
            "desk-b",
            mapOf("source:other" to "data:image/jpeg;base64,otherServerCover"),
            revisionKey = "eb:1"
        )

        cache.retainServerKeys("desk-a", setOf("source:keep"))
        assertEquals("data:image/jpeg;base64,keepCover", cache.get("desk-a", "source:keep"))
        assertNull(cache.get("desk-a", "source:drop"))
        assertEquals("data:image/jpeg;base64,otherServerCover", cache.get("desk-b", "source:other"))

        cache.deleteServer("desk-b")
        assertNull(cache.get("desk-b", "source:other"))

        val reloaded = DesktopArtworkCache(folder.root).also { it.loadIndexes() }
        assertEquals("data:image/jpeg;base64,keepCover", reloaded.get("desk-a", "source:keep"))
        assertNull(reloaded.get("desk-b", "source:other"))
        assertEquals(1, blobFiles().size)
    }
}
