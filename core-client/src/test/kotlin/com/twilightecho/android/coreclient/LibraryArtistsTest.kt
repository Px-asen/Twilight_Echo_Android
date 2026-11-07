package com.twilightecho.android.coreclient

import org.junit.Assert.*
import org.junit.Test

class LibraryArtistsTest {
    @Test fun explicitMembersAreDeduplicatedButTheirPunctuationStaysLiteral() {
        val track = CoreTrackView("1", title = "合唱", artist = "错误的显示文本",
            artists = listOf(" 北岸 ", "北岸", "Simon & Garfunkel", "AC/DC"))
        assertEquals(listOf("北岸", "Simon & Garfunkel", "AC/DC"), libraryArtistNames(track))
    }

    @Test fun legacyJoinedCreditsMatchDesktopSeparatorsAndPreserveBandNames() {
        assertEquals(listOf("北岸", "南山", "AC/DC", "Guest"), libraryArtistNames(
            CoreTrackView("1", title = "合唱", artist = "北岸／南山 & AC/DC/Guest")))
        assertEquals(listOf("Earth, Wind & Fire"), libraryArtistNames(
            CoreTrackView("1", title = "乐队", artists = listOf("Earth, Wind & Fire"))))
        assertEquals(listOf("未知艺术家"), libraryArtistNames(CoreTrackView("1", title = "无名", artist = " / ")))
    }

    @Test fun completeDesktopMembershipReplacesTheLegacyCopiedJoinedTagWithoutDroppingTruncatedCredits() {
        val catalog = CatalogTrackReferences(1, "source", listOf(
            CatalogEntity("north", "artist", "北岸"), CatalogEntity("south", "artist", "南山")), total = 2)
        val track = CoreTrackView("1", title = "合唱", artist = "北岸 / 南山", artists = listOf("北岸 / 南山"), catalog = catalog)
        assertEquals(listOf("北岸", "南山"), libraryArtistNames(track))
        val many = track.copy(artists = listOf("北岸", "南山", "第三位歌手"), catalog = catalog.copy(items = catalog.items.take(1), total = 3))
        assertEquals(listOf("北岸", "南山", "第三位歌手"), libraryArtistNames(many))
    }

    @Test fun nameComparisonHandlesUnicodeAndWhitespaceWithoutMergingDifferentPunctuation() {
        assertEquals(artistNameKey(" ＡＩＭＥＲ\u00a0 "), artistNameKey("aimer"))
        assertEquals(artistNameKey("宇多田\u3000光"), artistNameKey("宇多田 光"))
        assertNotEquals(artistNameKey("AC/DC"), artistNameKey("ACDC"))
    }

    @Test fun portraitSelectionHonorsManualClearPermissionsAndExpiry() {
        val data = EntityMetadataDetail("artist", 1, "北岸", artwork = listOf(
            MetadataArtwork("denied", usage = MetadataUsage(display = false)),
            MetadataArtwork("expired", usage = MetadataUsage(expiresAt = 99)),
            MetadataArtwork("valid", usage = MetadataUsage(expiresAt = 101))))
        assertEquals("valid", data.artworkPreview(100))
        assertNull(data.artworkPreview(101))
        val manual = data.copy(manualArtwork = PersonalArtworkPreview("manual"))
        assertEquals("manual", manual.artworkPreview(100))
        assertNull(manual.copy(artworkOverride = ArtworkOverride("clear")).artworkPreview(100))
        assertNull(data.copy(artworkOverride = ArtworkOverride("value")).artworkPreview(100))
    }
}
