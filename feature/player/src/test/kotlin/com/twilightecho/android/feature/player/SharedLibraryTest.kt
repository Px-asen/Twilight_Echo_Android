package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CatalogEntity
import com.twilightecho.android.coreclient.CatalogTrackReferences
import org.junit.Assert.*
import org.junit.Test

class SharedLibraryTest {
    private fun song(id: String, title: String, album: String = "专辑", artist: String = "歌手") =
        CoreTrackView(id, title = title, artist = artist, album = album, duration = 200.0)

    private val phone = listOf(song("p1", "晚风"), song("p2", "海边"))
    private val study = DesktopShelf("study", "书房电脑", connected = true,
        tracks = listOf(song("d1", "晚风"), song("d2", "远方", "旅途")))
    private val office = DesktopShelf("office", "办公室电脑", connected = false,
        tracks = listOf(song("o1", "远方", "旅途"), song("o2", "夜航", "旅途")))
    private val desktops = listOf(office, study)

    @Test fun loadingRefreshesAndDeviceChangesReuseTheAllDeviceCatalog() {
        val projector = SharedLibraryProjector()
        val first = projector.project(phone, desktops, LIBRARY_ALL)
        val refresh = projector.project(phone, desktops.map { it.copy() }, LIBRARY_ALL)
        assertSame(first.all, refresh.all)
        assertSame(first.visible, refresh.visible)
        assertSame(first.owners, refresh.owners)
        assertSame(first.phoneTrackIds, refresh.phoneTrackIds)
        val filtered = projector.project(phone, desktops, "study")
        assertSame(first.all, filtered.all)
        assertEquals(sharedLibrary(phone, desktops, "study"), filtered.visible)
        assertSame(filtered.visible, projector.project(phone, desktops, "study").visible)
        assertSame(first.all, projector.project(phone, desktops, LIBRARY_ALL).visible)
    }

    @Test fun catalogCacheInvalidatesMetadataMembershipAndOnlinePreference() {
        val projector = SharedLibraryProjector()
        val first = projector.project(phone, desktops, LIBRARY_ALL)
        val reconnected = listOf(office.copy(connected = true), study.copy(connected = false))
        val online = projector.project(phone, reconnected, LIBRARY_ALL)
        assertEquals(setOf("p1", "p2", "o1", "o2"), online.all.tracks.map { it.id }.toSet())
        assertNotSame(first.all, online.all)
        val edited = phone.map { if (it.id == "p1") it.copy(title = "新标题", artist = "新歌手") else it }
        val metadata = projector.project(edited, reconnected, LIBRARY_PHONE)
        assertEquals("新标题", metadata.visible.tracks.first { it.id == "p1" }.title)
        assertTrue(metadata.visible.artists.any { it.name == "新歌手" })
        val removed = projector.project(edited.take(1), emptyList(), LIBRARY_ALL)
        assertEquals(setOf("p1"), removed.phoneTrackIds)
        assertTrue(removed.owners.isEmpty())
        assertEquals(listOf("p1"), removed.all.tracks.map { it.id })
    }

    @Test fun artistEditingListsExactSourceMembershipsRatherThanAdoptingEverySameName() {
        val a = CatalogEntity("artist-a", "artist", "北岸")
        val b = a.copy(id = "artist-b")
        val other = a.copy(id = "artist-outside-collection")
        val tracks = listOf(
            song("one", "独唱", "首版").copy(catalog = CatalogTrackReferences(1, "source-one", listOf(a))),
            song("two", "现场", "现场版").copy(catalog = CatalogTrackReferences(1, "source-two", listOf(b, a))),
            song("outside", "另一个同名人").copy(catalog = CatalogTrackReferences(1, "source-other", listOf(other))),
            song("no-catalog", "只有同名文本", artist = "北岸")
        )
        assertEquals(listOf(a to "首版 · 独唱", b to "现场版 · 现场"),
            desktopArtistEditTargets(listOf("one", "two", "no-catalog"), "北岸", tracks))
    }

    @Test fun allDevicesListEachSongOnceAndPreferTheCopyThatPlaysWithoutANetwork() {
        val view = sharedLibrary(phone, desktops, LIBRARY_ALL)
        // 晚风 plays from the phone, 远方 from the connected study rather than the offline office.
        assertEquals(setOf("p1", "p2", "d2", "o2"), view.tracks.map { it.id }.toSet())
        assertEquals(4, view.tracks.size)
    }

    @Test fun chineseTitlesSortByPronunciation() {
        assertEquals(listOf("p2", "p1", "o2", "d2"), sharedLibrary(phone, desktops, LIBRARY_ALL).tracks.map { it.id })
    }

    @Test fun oneDeviceShowsExactlyItsSongsEvenWhenOffline() {
        assertEquals(setOf("p1", "p2"), sharedLibrary(phone, desktops, LIBRARY_PHONE).tracks.map { it.id }.toSet())
        assertEquals(setOf("d1", "d2"), sharedLibrary(phone, desktops, "study").tracks.map { it.id }.toSet())
        assertEquals(setOf("o1", "o2"), sharedLibrary(phone, desktops, "office").tracks.map { it.id }.toSet())
        assertTrue(sharedLibrary(phone, desktops, "forgotten").tracks.isEmpty())
    }

    @Test fun albumsAndArtistsAreBuiltFromTheVisibleSongs() {
        val view = sharedLibrary(phone, desktops, LIBRARY_ALL)
        assertEquals(mapOf("专辑" to 2, "旅途" to 2), view.albums.associate { it.title to it.trackCount })
        assertEquals(setOf("p1", "p2"), view.albums.single { it.title == "专辑" }.trackIds.toSet())
        assertEquals(400.0, view.albums.single { it.title == "旅途" }.totalDurationSec, 0.0)
        val artist = view.artists.single()
        assertEquals("歌手", artist.name)
        assertEquals(2, artist.albumCount)
        assertEquals(4, artist.trackCount)
        val untitled = libraryAlbums(listOf(song("x", "无名", album = " ")))
        assertEquals("未知专辑", untitled.single().title)
    }

    @Test fun everyComputerSongKnowsItsComputer() {
        val owners = desktopOwners(desktops)
        assertEquals(mapOf("d1" to "study", "d2" to "study", "o1" to "office", "o2" to "office"), owners)
        assertNull(owners["p1"])
    }

    @Test fun soloAndCollaborationsShareOneCollectionPerCreditedArtist() {
        val solo = song("solo", "独唱", artist = "北岸")
        val duet = song("duet", "合唱", artist = "北岸 / 南山").copy(artists = listOf("北岸", "南山", "北岸"))
        val legacy = song("legacy", "另一首合唱", artist = "南山／北岸")
        val artists = libraryArtists(listOf(solo, duet, legacy, solo)).associateBy { it.name }
        assertEquals(setOf("北岸", "南山"), artists.keys)
        assertEquals(listOf("solo", "duet", "legacy"), artists.getValue("北岸").trackIds)
        assertEquals(3, artists.getValue("北岸").trackCount)
        assertEquals(2, artists.getValue("南山").trackCount)
    }

    @Test fun syncedArtistSpellingVariantsDoNotCreateDuplicateCardsOnAnyDeviceFilter() {
        val desktop = DesktopShelf("study", "电脑", true, listOf(
            song("d1", "晚风", artist = "ＡＩＭＥＲ"), song("d2", "海边", artist = "aimer\u00a0")))
        for (device in listOf(LIBRARY_ALL, "study")) {
            val artists = sharedLibrary(emptyList(), listOf(desktop), device).artists
            assertEquals(1, artists.size)
            assertEquals(2, artists.single().trackCount)
            assertEquals("artist:aimer", artists.single().id)
        }
    }

    @Test fun phonePreferredSongsStillResolveTheirExactComputerArtistIncludingCollaborators() {
        val phoneSong = song("phone", "合唱", artist = "北岸 / 南山").copy(artists = listOf("北岸", "南山"))
        val computerSong = phoneSong.copy(id = "desktop", catalog = CatalogTrackReferences(1, "source-duet",
            listOf(CatalogEntity("artist-north", "artist", "北岸"), CatalogEntity("artist-south", "artist", "南山"))))
        val unrelated = computerSong.copy(id = "other", title = "同歌手的另一首歌", catalog = CatalogTrackReferences(1, "source-other"))
        val view = sharedLibrary(listOf(phoneSong), listOf(DesktopShelf("study", "电脑", true, listOf(computerSong))), LIBRARY_ALL)
        assertEquals(listOf("phone"), view.tracks.map { it.id })
        for (artist in view.artists) {
            assertEquals(setOf("source-duet"), desktopArtistSources(artist, view.tracks, listOf(computerSong, unrelated)))
        }
    }

    @Test fun computerArtistAlbumsKeepExactReleaseMembershipEvenWhenTitlesMatch() {
        val tracks = listOf("first", "second").map { id -> song(id, "歌 $id", album = "同名专辑", artist = "北岸")
            .copy(catalog = CatalogTrackReferences(1, "source-$id", listOf(CatalogEntity("release-$id", "release", "同名专辑")))) }
        val desktops = listOf(DesktopShelf("study", "电脑", true, tracks))
        val computer = sharedLibrary(emptyList(), desktops, "study")
        assertEquals(2, computer.albums.size)
        assertEquals(2, computer.artists.single().albumCount)
        assertEquals(mapOf("album:catalog:release-first" to listOf("first"), "album:catalog:release-second" to listOf("second")),
            computer.albums.associate { it.id to it.trackIds })
        // Existing all-device/local album grouping keeps its original contract.
        assertEquals(1, sharedLibrary(emptyList(), desktops, LIBRARY_ALL).albums.size)
    }

    @Test fun searchMatchesTitleArtistOrAlbumIgnoringCase() {
        val tracks = phone + study.tracks + song("e1", "Moonlight", artist = "Aimer")
        assertEquals(listOf("d2"), searchSharedLibrary(tracks, " 旅途 ").map { it.id })
        assertEquals(listOf("e1"), searchSharedLibrary(tracks, "aimer").map { it.id })
        assertEquals(listOf("e1"), searchSharedLibrary(tracks, "MOON").map { it.id })
        assertEquals(tracks, searchSharedLibrary(tracks, "  "))
        assertTrue(searchSharedLibrary(tracks, "不存在").isEmpty())
    }

    @Test fun streamingMatchPrefersTheSameTitleAndArtist() {
        val track = CoreTrackView("desktop", title = "晚风", artist = "歌手", duration = 200.0)
        val live = CoreTrackView("live", title = "晚风 (Live)", artist = "歌手", duration = 260.0)
        val studio = CoreTrackView("studio", title = "晚风", artist = "歌手", duration = 201.0)
        assertEquals("studio", bestStreamingMatch(track, listOf(live, studio))?.id)
        assertEquals("live", bestStreamingMatch(track, listOf(live))?.id)
        assertNull(bestStreamingMatch(track, listOf(CoreTrackView("other", title = "别的歌", artist = "歌手", duration = 200.0))))
    }

    @Test fun anotherArtistOnlyMatchesWhenTheLengthAgrees() {
        val track = CoreTrackView("desktop", title = "晚风", artist = "歌手", duration = 200.0)
        assertNull(bestStreamingMatch(track, listOf(CoreTrackView("cover", title = "晚风", artist = "别人", duration = 240.0))))
        assertEquals("same", bestStreamingMatch(track, listOf(CoreTrackView("same", title = "晚风", artist = "别人", duration = 202.0)))?.id)
        val anonymous = CoreTrackView("desktop", title = "晚风", duration = 0.0)
        assertEquals("cover", bestStreamingMatch(anonymous, listOf(CoreTrackView("cover", title = "晚风", artist = "别人")))?.id)
    }

    @Test fun anyOfSeveralArtistsCounts() {
        val track = CoreTrackView("desktop", title = "合唱", artist = "甲/乙")
        assertEquals("duet", bestStreamingMatch(track, listOf(CoreTrackView("duet", title = "合唱", artist = "乙、丙")))?.id)
        assertNull(bestStreamingMatch(track, listOf(CoreTrackView("solo", title = "合唱", artist = "丙"))))
    }

    @Test fun aTitleWithoutLettersMatchesNothing() {
        val candidates = listOf(CoreTrackView("any", title = "晚风"))
        assertNull(bestStreamingMatch(CoreTrackView("blank", title = "  "), candidates))
        assertNull(bestStreamingMatch(CoreTrackView("marks", title = "？！"), candidates))
    }
}
