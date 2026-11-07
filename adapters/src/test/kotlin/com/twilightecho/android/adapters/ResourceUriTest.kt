package com.twilightecho.android.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

class ResourceUriTest {
    @Test fun `absolute file names keep URI delimiters percent escapes and Unicode as literal characters`() {
        val path = "/storage/emulated/0/Music/中文 #1?100%+%23.wav"
        val uri = URI(resourceUriString(path))
        assertEquals("file", uri.scheme)
        assertEquals(path, uri.path)
        assertNull(uri.query)
        assertNull(uri.fragment)
        assertEquals("file:///storage/emulated/0/Music/%E4%B8%AD%E6%96%87%20%231%3F100%25+%2523.wav", uri.toASCIIString())
    }

    @Test fun `relative file paths and leading double slashes retain their full path`() {
        for (path in listOf("Music/album #1?100%+%23.flac", "./album:side#1.wav", "//storage/Music/album#1.wav")) {
            val uri = URI(resourceUriString(path))
            assertEquals(path, uri.path)
            assertNull(uri.query)
            assertNull(uri.fragment)
        }
    }

    @Test fun `encoded file and content URIs are not encoded again or stripped of their query and fragment`() {
        for (source in listOf(
            "file:///storage/emulated/0/Music/%E4%B8%AD%E6%96%87%20%231%3F100%25+%2523.wav",
            "content://documents/tree/primary%3AMusic/document/primary%3AMusic%2Falbum%231.flac?grant=keep#original",
            "https://example.com/album%231.flac?token=a%2Bb#original",
            "desktop-stream:source#part",
            "CONTENT://provider/music/42?grant=keep#original"
        )) assertEquals(source, resourceUriString(source))
    }

    @Test fun `ordinary local file paths still round trip without changing their identity`() {
        for (source in listOf("/data/user/0/com.twilightecho.android.app/cache/track.wav", "Music/track.wav", "")) {
            assertEquals(source, URI(resourceUriString(source)).path)
        }
    }
}
