package com.twilightecho.android.plugins

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Queued v2 sources, desktop-shaped tracks, lyrics and playback results. */
class ProviderSourceTest {
    @Test fun sourcesCarryWhatTheProviderNeedsAgain() {
        val track = buildJsonObject {
            put("id", "qq:0039MnYb0qxYhV"); put("title", "晴天"); put("artist", "周杰伦"); put("duration", 269)
            put("cover", "https://y.gtimg.cn/a.jpg"); put("filePath", "http://127.0.0.1:1234/stream"); put("lyric", JsonNull)
            put("providerSongId", "0039MnYb0qxYhV"); putJsonObject("extra") { put("mediaMid", "0039") }
            put("description", "x".repeat(600))
        }
        val source = providerSource("qq", track)
        assertTrue(source, source.startsWith("provider://qq/qq%3A0039MnYb0qxYhV?t="))
        val ref = parseProviderSource(source)!!
        assertEquals("qq", ref.providerId); assertEquals("qq:0039MnYb0qxYhV", ref.trackId)
        // Media URLs, nulls and long strings stay out of the queue.
        assertEquals(setOf("id", "title", "artist", "duration", "providerSongId", "extra"), ref.track.keys)
        assertEquals(track["extra"], ref.track["extra"])
    }

    @Test fun idsWithReservedCharactersRoundTrip() {
        for (id in listOf("jellyfin:http%3A%2F%2Fnas%3A8096:abc", "a+b c/d?e&t=1", "汽水#1")) {
            assertEquals(id, parseProviderSource(providerSource("qishui", buildJsonObject { put("id", id) }))?.trackId)
        }
        assertEquals("12345", parseProviderSource(providerSource("kugou", buildJsonObject { put("id", 12345) }))?.trackId)
        assertEquals("provider://jellyfin/a%2Bb%20c", providerTrackId("jellyfin", "a+b c"))
    }

    @Test fun ncmForeignAndDamagedSourcesAreNotProviderTracks() {
        assertEquals("音源 ID 无效", assertThrows(IllegalArgumentException::class.java) { providerSource("ncm", buildJsonObject { put("id", "1") }) }.message)
        assertThrows(IllegalArgumentException::class.java) { providerSource("QQ", buildJsonObject { put("id", "1") }) }
        assertEquals("音源返回了无效歌曲 ID", assertThrows(IllegalArgumentException::class.java) { providerSource("qq", buildJsonObject { put("id", " ") }) }.message)
        assertNull(parseProviderSource("provider://ncm/123"))
        assertNull(parseProviderSource("https://example.com/a.mp3"))
        assertNull(parseProviderSource("provider://qq/abc"))
        val source = providerSource("qq", buildJsonObject { put("id", "abc") })
        assertEquals("abc", parseProviderSource(source)?.trackId)
        assertNull(parseProviderSource(source.replace("provider://qq/abc", "provider://qq/xyz"))) // the payload names another song
        assertNull(parseProviderSource(source.substringBefore("?t=") + "?t=%%%"))
    }

    @Test fun minimalTracksStayShort() {
        val track = buildJsonObject {
            put("id", "x".repeat(512))
            repeat(20) { put("field$it", "v".repeat(300)) }
            putJsonArray("artists") { repeat(3) { index -> addJsonObject { put("id", "$index"); put("name", "歌手$index") } } }
        }
        val minimal = minimalTrack(track)
        assertTrue(minimal.toString().length <= 2048)
        assertEquals(track["id"], minimal["id"]); assertEquals(track["artists"], minimal["artists"])
    }

    @Test fun trackViewsReadDesktopShapedTracks() {
        val view = providerTrackView("qq", buildJsonObject {
            put("id", "qq:1"); put("name", "  晴天 ")
            putJsonArray("artists") { addJsonObject { put("name", "周杰伦") }; addJsonObject { put("name", "") }; add("杨瑞代") }
            putJsonObject("album") { put("name", "叶惠美") }; put("duration", 269.5); put("cover", "http://y.gtimg.cn/a.jpg")
            put("size", 0); put("bitrate", 320)
        })
        assertEquals("晴天", view.title); assertEquals("周杰伦 / 杨瑞代", view.artist); assertEquals(listOf("周杰伦", "杨瑞代"), view.artists)
        assertEquals("叶惠美", view.album); assertEquals(269.5, view.duration, 0.0); assertEquals("http://y.gtimg.cn/a.jpg", view.cover)
        assertNull(view.size); assertEquals(320.0, view.bitrate!!, 0.0)
        assertEquals("provider://qq/qq%3A1", view.id); assertTrue(view.source!!.startsWith(view.id + "?t="))
        val bare = providerTrackView("qq", buildJsonObject { put("id", "2"); put("cover", "data:image/png;base64,AA") })
        assertEquals("未知歌曲", bare.title); assertNull(bare.cover)
    }

    @Test fun lyricsBecomeSortedLrc() {
        val lyrics = "[ti:晴天]\n[00:20.5]故事的小黄花\n[01:02.345][00:10]重复\nuntimed\n[00:05.12]  从出生那年就飘着  \n[00:61.00]bad"
        assertEquals("[00:05.120]从出生那年就飘着\n[00:10.000]重复\n[00:20.500]故事的小黄花\n[01:02.345]重复",
            providerLrc(buildJsonObject { put("lyrics", lyrics) }))
        assertNull(providerLrc(buildJsonObject { put("lyrics", "纯文本歌词") }))
        assertNull(providerLrc(buildJsonObject { put("lyrics", JsonNull) }))
        assertNull(providerLrc(JsonPrimitive("[00:01.00]x")))
    }

    @Test fun playbackResultsKeepTheirHeaders() {
        val playback = providerPlayback(buildJsonObject {
            put("url", "https://ws.stream.qqmusic.qq.com/C400.m4a?vkey=1")
            putJsonArray("headers") { addJsonArray { add("Referer"); add("https://y.qq.com/") }; addJsonArray { add("bad") }; add("x") }
        })
        assertEquals(ProviderPlayback("https://ws.stream.qqmusic.qq.com/C400.m4a?vkey=1", listOf("Referer" to "https://y.qq.com/")), playback)
        assertNull(providerPlayback(JsonNull)); assertNull(providerPlayback(buildJsonObject { put("url", "") }))
        assertNull(providerPlayback(JsonPrimitive("https://a.test/x.mp3")))
    }

    @Test fun workerSlicesNeverSplitASurrogatePair() {
        val text = "a".repeat(SLICE_CHARS - 1) + "😀" + "b".repeat(10)
        assertEquals(SLICE_CHARS - 1, sliceEnd(text, 0))
        assertEquals(text.length, sliceEnd(text, SLICE_CHARS - 1))
        assertEquals(SLICE_CHARS, sliceEnd("c".repeat(SLICE_CHARS * 2), 0))
    }
}
