package com.twilightecho.android.playbackhost

import com.twilightecho.android.coreclient.CatalogTrackReferences
import com.twilightecho.android.coreclient.CoreTrackView
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NcmOfflineFallbackTest {

    @Test fun encodeAndParseRoundTripsChineseMetadataAndSpecialCharacters() {
        val track = CoreTrackView(
            id = "remote-track:42",
            title = "夜曲 (Feat. A&B? #1 + Live=True)",
            artist = "周杰伦 / 特别嘉宾",
            album = "十一月的萧邦",
            duration = 226.4
        )
        val uri = NcmFallbackUri.encode(track)
        assertTrue(NcmFallbackUri.isFallbackMatchSource(uri))
        assertNull(NcmFallbackUri.directSongId(uri))

        val parsed = NcmFallbackUri.parse(uri)
        assertNotNull(parsed)
        assertEquals(track.title, parsed!!.title)
        assertEquals(track.artist, parsed.artist)
        assertEquals(track.album, parsed.album)
        assertEquals(226.4, parsed.duration ?: 0.0, 0.001)
        assertEquals(NcmFallbackUri.cacheKey(track), NcmFallbackUri.cacheKey(parsed))
    }

    @Test fun offlineNcmFallbackTrackPreservesDesktopTrackIdAndSelectsDirectOrLazySource() {
        val desktopTrack = CoreTrackView(
            id = "remote-track:desktop-song-1",
            title = "七里香",
            artist = "周杰伦",
            album = "七里香",
            duration = 299.0,
            catalog = CatalogTrackReferences(revision = 7L, sourceId = "src-qilixiang")
        )

        // Without a pre-resolved match, uses lazy provider://ncm/match?... URI while keeping desktop track ID.
        val lazyFallback = offlineNcmFallbackTrack(desktopTrack, cachedMatch = null)
        assertEquals("remote-track:desktop-song-1", lazyFallback.id)
        assertEquals(desktopTrack.catalog, lazyFallback.catalog)
        assertTrue(NcmFallbackUri.isFallbackMatchSource(lazyFallback.source))

        // With a resolved NCM match, binds directly to provider://ncm/<id> and adopts NCM cover if local cover is null.
        val matched = CoreTrackView(
            id = "provider://ncm/186016",
            source = "provider://ncm/186016",
            ncmSongId = "186016",
            title = "七里香",
            artist = "周杰伦",
            cover = "https://p1.music.126.net/cover.jpg"
        )
        val directFallback = offlineNcmFallbackTrack(desktopTrack, cachedMatch = matched)
        assertEquals("remote-track:desktop-song-1", directFallback.id)
        assertEquals("provider://ncm/186016", directFallback.source)
        assertEquals("186016", directFallback.ncmSongId)
        assertEquals("https://p1.music.126.net/cover.jpg", directFallback.cover)
        assertEquals("186016", NcmFallbackUri.directSongId(directFallback.source))
    }

    @Test fun bestNcmStreamingMatchPrefersPlayableExactMatchAndRejectsUnrelatedSongs() {
        val raw = Json.parseToJsonElement(
            """
            {
              "items": [
                {
                  "ncmSongId": "1001",
                  "title": "晴天",
                  "artist": "周杰伦",
                  "album": "叶惠美",
                  "duration": 269.0,
                  "playable": false,
                  "unavailableReason": "无版权"
                },
                {
                  "ncmSongId": "1002",
                  "title": "晴天",
                  "artist": "周杰伦",
                  "album": "叶惠美",
                  "duration": 269.5,
                  "cover": "https://example.com/qingtian.jpg"
                },
                {
                  "ncmSongId": "1003",
                  "title": "完全不同的歌",
                  "artist": "其他歌手",
                  "duration": 180.0
                }
              ]
            }
            """.trimIndent()
        )
        val candidates = parseNcmSearchCandidates(raw)
        assertEquals(2, candidates.size)

        val target = CoreTrackView(id = "remote-track:1", title = "晴天", artist = "周杰伦", album = "叶惠美", duration = 269.0)
        val match = bestNcmStreamingMatch(target, candidates)
        assertNotNull(match)
        assertEquals("1002", match!!.ncmSongId)
        assertEquals("provider://ncm/1002", match.source)

        val missing = CoreTrackView(id = "remote-track:2", title = "不存在的冷门曲目", artist = "未知音乐人", duration = 310.0)
        assertNull(bestNcmStreamingMatch(missing, candidates))
    }
}
