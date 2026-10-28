package com.twilightecho.android.plugins

import org.junit.Assert.*
import org.junit.Test

class ProviderArtworkTest {
    @Test fun replacesThumbnailSizingWithoutDroppingOtherQueryParameters() {
        assertEquals("https://p1.music.126.net/cover.jpg?quality=95&param=1024y1024",
            providerArtworkUrl("http://p1.music.126.net/cover.jpg?param=140y140&quality=95&param=256y256#preview", 1024))
    }

    @Test fun thumbnailAndPlayerRequestsHaveSeparateCacheKeys() {
        val url = "https://p2.music.126.net/cover.jpg"
        assertTrue(providerArtworkUrl(url, 256).endsWith("param=256y256"))
        assertTrue(providerArtworkUrl(url, 1024).endsWith("param=1024y1024"))
        assertNotEquals(providerArtworkUrl(url, 256), providerArtworkUrl(url, 1024))
    }

    @Test fun imageSizingDoesNotBroadenProviderNetworkAccess() {
        listOf("https://music.126.net.evil.example/a.jpg", "https://example.com/a.jpg",
            "https://p1.music.126.net:8443/a.jpg", "https://user:pass@p1.music.126.net/a.jpg",
            "file:///cover.jpg").forEach { url ->
            assertTrue(url, runCatching { providerArtworkUrl(url, 1024) }.isFailure)
        }
    }
}
