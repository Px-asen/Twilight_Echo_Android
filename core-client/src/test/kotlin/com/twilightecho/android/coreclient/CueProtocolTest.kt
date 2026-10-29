package com.twilightecho.android.coreclient

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class CueProtocolTest {
    private val json = DefaultCoreClient.DefaultJson

    @Test fun `track and upcoming projections preserve the shared segment fields`() {
        val range = CueRange(20.0, 40.0, virtualPregapSeconds = 2.0, sourcePregapSeconds = 1.0)
        val track = CoreTrackView("cue", title = "Second", source = "content://doc/disc.flac", cueRange = range)
        assertEquals(track, json.decodeFromString<CoreTrackView>(json.encodeToString(track)))
        val next = CoreUpcomingTrack("cue", title = "Second", cueRange = range)
        assertEquals(next, json.decodeFromString<CoreUpcomingTrack>(json.encodeToString(next)))
        assertFalse(json.encodeToString(range).contains("durationSeconds"))
    }

    @Test fun `a legacy presentation pregap never becomes synthetic silence`() {
        val range = json.decodeFromString<CueRange>("""{"startSeconds":20,"endSeconds":40,"pregapSeconds":2}""")
        assertEquals(2.0, range.pregapSeconds, 0.0)
        assertEquals(0.0, range.virtualPregapSeconds, 0.0)
        assertEquals(20.0, range.durationSeconds, 0.0)
        assertEquals(range, json.decodeFromString<CueRange>(json.encodeToString(range)))
    }

    @Test fun `ordinary tracks still decode without a segment`() {
        assertNull(json.decodeFromString<CoreTrackView>("""{"id":"plain","title":"Plain"}""").cueRange)
        assertNull(json.decodeFromString<CoreUpcomingTrack>("""{"id":"plain","title":"Plain"}""").cueRange)
    }
}
