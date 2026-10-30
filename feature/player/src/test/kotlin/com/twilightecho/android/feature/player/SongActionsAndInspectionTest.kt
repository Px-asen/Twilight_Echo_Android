package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CorePlaybackView
import com.twilightecho.android.coreclient.CoreQueueEntryView
import com.twilightecho.android.coreclient.CoreQueueReference
import com.twilightecho.android.coreclient.CoreSnapshot
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CueRange
import com.twilightecho.android.coreclient.TrackInspectionDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongActionsAndInspectionTest {

    @Test
    fun `resolveInsertAfterOccurrenceId prefers snapshot occurrenceId and falls back to queue index`() {
        val items = listOf(
            CoreQueueEntryView("occ-0", 0, CoreTrackView(id = "t0", title = "Song 0", artist = "Artist")),
            CoreQueueEntryView("occ-1", 1, CoreTrackView(id = "t1", title = "Song 1", artist = "Artist"))
        )
        val snapWithOcc = CoreSnapshot(
            epoch = "epoch-1",
            sequence = 1,
            queue = CoreQueueReference(id = "default", revision = 2, total = 2, index = 1, occurrenceId = "occ-explicit")
        )
        assertEquals("occ-explicit", resolveInsertAfterOccurrenceId(snapWithOcc, items))

        val snapByIndex = CoreSnapshot(
            epoch = "epoch-1",
            sequence = 2,
            queue = CoreQueueReference(id = "default", revision = 2, total = 2, index = 1, occurrenceId = null)
        )
        assertEquals("occ-1", resolveInsertAfterOccurrenceId(snapByIndex, items))

        val emptyQueueSnap = CoreSnapshot(
            epoch = "epoch-1",
            sequence = 3,
            queue = CoreQueueReference(id = "default", revision = 1, total = 0, index = -1),
            playback = CorePlaybackView(state = "stopped")
        )
        assertNull(resolveInsertAfterOccurrenceId(emptyQueueSnap, emptyList()))
    }

    @Test
    fun `describeTrackOrigin distinguishes phone, folder, CUE, NetEase, and desktop tracks`() {
        assertEquals(
            "电脑曲库 · 书房台式机",
            describeTrackOrigin(CoreTrackView(id = "remote-track:1", title = "A", artist = "B"), "书房台式机")
        )
        assertEquals(
            "网易云音乐",
            describeTrackOrigin(
                CoreTrackView(
                    id = "provider://ncm/12345",
                    title = "A",
                    artist = "B",
                    source = "provider://ncm/12345",
                    ncmSongId = "12345"
                ),
                null
            )
        )
        assertEquals(
            "CUE 分轨 · 本机音乐文件夹",
            describeTrackOrigin(
                CoreTrackView(
                    id = "folder:abc:cue:1",
                    title = "Track 01",
                    artist = "Artist",
                    source = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fcd.flac",
                    cueRange = CueRange(0.0, 180.0)
                ),
                null
            )
        )
        assertEquals(
            "本机媒体库",
            describeTrackOrigin(
                CoreTrackView("media:88", title = "Local Song", artist = "Artist", source = "content://media/external/audio/media/88"),
                null
            )
        )
    }

    @Test
    fun `audio spec and metadata sections format probed tags and audio specs`() {
        assertEquals("96 kHz (96000 Hz)", formatSampleRateHz(96000))
        assertEquals("44.1 kHz (44100 Hz)", formatSampleRateHz(44100))
        assertEquals("立体声 (2 ch)", formatChannelCount(2))
        assertEquals("5.1 环绕声 (6 ch)", formatChannelCount(6))
        assertEquals("01:05.500 → 04:20", formatCueRange(CueRange(65.5, 260.0)))
        assertTrue(formatFileSizeBytes(36_700_160L).startsWith("35.00 MB"))

        val track = CoreTrackView(
            id = "media:77",
            title = "Catalog Title",
            artist = "Catalog Artist",
            album = "Catalog Album",
            duration = 215.0,
            source = "content://media/external/audio/media/77",
            format = "flac",
            sampleRate = 44100,
            bitDepth = 16,
            trackNumber = 4,
            discNumber = 1,
            bpm = 128.0,
            isrc = "CN-A01-26-00001"
        )
        val probed = TrackInspectionDetails(
            title = "Embedded Tag Title",
            artist = "Embedded Artist",
            album = "Embedded Album",
            albumArtist = "Embedded Album Artist",
            composer = "Composer Name",
            releaseDate = "2026-05-01",
            genre = "Post-Rock",
            format = "FLAC",
            mimeType = "audio/flac",
            sampleRate = 96000,
            bitDepth = 24,
            channels = 2,
            bitrateKbps = 2850.0,
            fileSize = 76_543_210L,
            fileName = "04 - Embedded Tag Title.flac",
            displayPath = "内部存储/Music/04 - Embedded Tag Title.flac"
        )
        val sections = buildTrackMetadataSections(
            TrackInspectionUiState(
                track = track,
                sourceLabel = "本机媒体库",
                details = probed,
                loading = false
            )
        )
        assertEquals(listOf("标签信息", "音频规格", "来源与文件"), sections.map { it.title })

        val tags = sections[0].fields.associate { it.label to it.value }
        assertEquals("Embedded Tag Title", tags["标题"])
        assertEquals("Composer Name", tags["作曲"])
        assertEquals("4", tags["曲目号"])
        assertEquals("128", tags["BPM"])
        assertEquals("CN-A01-26-00001", tags["ISRC"])

        val specs = sections[1].fields.associate { it.label to it.value }
        assertEquals("FLAC", specs["音频格式"])
        assertEquals("audio/flac", specs["MIME 类型"])
        assertEquals("96 kHz (96000 Hz)", specs["采样率"])
        assertEquals("24 bit", specs["位深"])
        assertEquals("立体声 (2 ch)", specs["声道"])
        assertEquals("2850 kbps", specs["码率"])

        val source = sections[2].fields.associate { it.label to it.value }
        assertEquals("04 - Embedded Tag Title.flac", source["文件名"])
        assertEquals("内部存储/Music/04 - Embedded Tag Title.flac", source["路径"])
    }
}
