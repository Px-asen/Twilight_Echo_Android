package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreTrackView
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
