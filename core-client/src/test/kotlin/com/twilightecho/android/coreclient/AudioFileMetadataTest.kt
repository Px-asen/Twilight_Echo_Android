package com.twilightecho.android.coreclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioFileMetadataTest {
    @Test
    fun `SACD selectors preserve opaque document IDs provider queries and original fragments`() {
        val original = "content://provider/tree/Music%2FDisc/document/42?grant=keep#provider-fragment"
        for (area in listOf("stereo", "multichannel")) {
            val selected = SacdTrackSource(original, area, 2)
            assertEquals(selected, SacdTrackSource.decode(selected.encode()))
        }
        assertNull(SacdTrackSource.decode(original))
        assertNull(SacdTrackSource.decode("$original#twilight-sacd=stereo:0"))
        assertNull(SacdTrackSource.decode("$original#twilight-sacd=unknown:1"))
    }

    @Test
    fun `native metadata keeps DSD bit clock separate from carriers and retains ISO tracks`() {
        val data = """{"isDsd":true,"container":"SACD ISO","isoTracks":[
            {"source":"content://disc#twilight-sacd=stereo:1","sampleRate":2822400,"bitDepth":1,
             "channelCount":2,"dsdRate":64,"duration":120.25,"playable":true,"outputModes":["native","dop","pcm"]}
        ],"error":""}"""
        val metadata = DefaultCoreClient.DefaultJson.decodeFromString<AudioFileMetadata>(data)
        val track = metadata.isoTracks.single()
        assertEquals(2_822_400, track.sampleRate)
        assertEquals(64, track.dsdRate)
        assertEquals(1, track.bitDepth)
        assertEquals(120.25, track.duration, 0.0)
    }
}
