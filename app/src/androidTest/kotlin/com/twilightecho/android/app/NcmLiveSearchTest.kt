package com.twilightecho.android.app

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Live public search, without supplying or changing any account credentials. */
@RunWith(AndroidJUnit4::class)
class NcmLiveSearchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun mainSearchUsesTheRealNeteaseSongsAlbumsAndArtists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = AndroidPlaybackHost.getInstance(context).plugins
        runBlocking {
            repository.initialize()
            val entry = repository.state.value.entries.single { it.id == "com.twilightecho.provider.ncm" }
            if (entry.installedVersion != entry.version) repository.install(entry.id)
            repository.setEnabled(entry.id, true)
        }
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("nav-SEARCH").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-SEARCH").performClick()
        rule.onNodeWithTag("search-source-NETEASE").performClick()
        rule.onNodeWithTag("searchInput").performTextReplacement("晴天")
        rule.onNodeWithTag("searchInput").performImeAction()
        rule.waitUntil(40_000) { rule.onAllNodesWithTag("ncm-search-track-0").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("loadedTrackArtwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("ncm-main-search-live-songs.png")
        rule.onNodeWithTag("search-category-ALBUMS").performClick()
        rule.waitUntil(40_000) { rule.onAllNodes(hasTestTagPrefix("ncm-search-album-")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("loadedStreamingArtwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("ncm-main-search-live-albums.png")
        rule.onNodeWithTag("search-category-ARTISTS").performClick()
        rule.waitUntil(40_000) { rule.onAllNodes(hasTestTagPrefix("ncm-search-artist-")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("loadedStreamingArtwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("ncm-main-search-live-artists.png")
    }
    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") { node ->
        node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
