package com.twilightecho.android.app

import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.components.rememberMotionEnabled
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Work must stay proportional to visible rows, while settings and actions remain live. */
@RunWith(AndroidJUnit4::class)
class ScrollingWorkloadTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun cardsAndNestedThemesShareOneMotionSettingsSubscription() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val key = "animator_duration_scale"
        val original = device.executeShellCommand("settings get global $key").trim()
        val count = mutableIntStateOf(1)
        val observed = mutableListOf<Boolean>()
        fun observers(): Int = device.executeShellCommand("dumpsys content").lineSequence().count {
            it.contains("settings/global/$key:") && it.contains("pid=${Process.myPid()} ")
        }
        try {
            device.executeShellCommand("settings put global $key 1")
            rule.setContent {
                TwilightEchoTheme {
                    Column {
                        repeat(count.intValue) {
                            // Detail pages add their own palette; they inherit the same motion preference.
                            TwilightEchoTheme {
                                val enabled = rememberMotionEnabled()
                                SideEffect { observed += enabled }
                                Text(if (enabled) "动效开启" else "动效关闭")
                            }
                        }
                    }
                }
            }
            rule.waitForIdle()
            val before = observers()
            assertTrue("The fixture must register the system setting observer", before > 0)
            rule.runOnIdle { count.intValue = 40 }
            rule.waitForIdle()
            val after = observers()
            Log.i("ScrollingWorkload", "motion observers: $before -> $after for 1 -> 40 cards")
            assertEquals("Composing more cards must not register more settings observers", before, after)
            rule.runOnIdle { observed.clear() }
            device.executeShellCommand("settings put global $key 0")
            rule.waitUntil(5_000) { observed.size >= 40 && observed.all { !it } }
            rule.runOnIdle { observed.clear() }
            device.executeShellCommand("settings put global $key 1")
            rule.waitUntil(5_000) { observed.size >= 40 && observed.all { it } }
        } finally {
            if (original == "null") device.executeShellCommand("settings delete global $key")
            else device.executeShellCommand("settings put global $key $original")
        }
    }

    @Test fun openingSongsDoesNotTraverseTheLibraryAndRowsKeepFreshActions() {
        val reads = AtomicInteger()
        val source = List(20_000) { CoreTrackView("workload-$it", title = "歌曲 $it", artist = "北岸", duration = 180.0) }
        val tracks = object : AbstractList<CoreTrackView>() {
            override val size = source.size
            override fun get(index: Int): CoreTrackView = source[index].also { reads.incrementAndGet() }
        }
        val ui = mutableStateOf(PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.LIBRARY,
            isLoadingLibrary = false, libraryTracks = tracks, allTracks = tracks, libraryTrackCount = tracks.size))
        var played: String? = null
        var playedTracks: List<CoreTrackView>? = null
        val actions = PlayerActions(playTracks = { list, id -> playedTracks = list; played = id })
        rule.setContent { PlayerAppContent(ui.value, actions) }
        rule.onNodeWithTag("track-workload-0").assertIsDisplayed()
        rule.waitForIdle()
        Log.i("ScrollingWorkload", "20,000-song page opening reads: ${reads.get()}")
        assertTrue("Opening Songs must only read visible/prefetched rows, read ${reads.get()}", reads.get() < 1_000)
        rule.runOnIdle {
            ui.value = ui.value.copy(favoriteTrackIds = listOf("workload-1"),
                diagnostics = ui.value.diagnostics.copy(latencyMs = 32.0))
        }
        rule.onNode(hasContentDescription("已收藏") and hasAnyAncestor(hasTestTag("track-workload-1")), useUnmergedTree = true)
            .assertIsDisplayed()
        rule.onNodeWithTag("track-workload-1").performClick()
        rule.runOnIdle { assertEquals("workload-1", played); assertSame(tracks, playedTracks) }
        // Replacing the queue source must update the remembered click callback as well as the title.
        val replacement = source.take(100).map { it.copy(title = "新歌 ${it.id.substringAfterLast('-')}") }
        rule.runOnIdle { ui.value = ui.value.copy(libraryTracks = replacement, allTracks = replacement,
            libraryTrackCount = replacement.size, favoriteTrackIds = emptyList(), currentTrack = replacement[1], isPlaying = true) }
        rule.onNode(hasTestTag("track-workload-1") and hasText("新歌 1")).assertIsDisplayed()
        rule.onNodeWithTag("track-workload-1").performClick()
        rule.runOnIdle { assertSame(replacement, playedTracks) }
        rule.onNode(hasContentDescription("正在播放") and hasAnyAncestor(hasTestTag("track-workload-1")), useUnmergedTree = true)
            .assertIsDisplayed()
        rule.onNodeWithTag("librarySongs").performScrollToIndex(50)
        rule.onNodeWithTag("track-workload-50").performClick()
        rule.runOnIdle { assertEquals("workload-50", played) }
    }

    @Test fun openingSongSearchDoesNotBuildAnUnusedAlbumAndArtistCatalog() {
        val reads = AtomicInteger()
        val tracks = object : AbstractList<CoreTrackView>() {
            override val size = 20_000
            override fun get(index: Int): CoreTrackView {
                reads.incrementAndGet()
                return CoreTrackView("search-$index", title = "歌曲 $index", artist = "歌手 $index", album = "专辑 $index")
            }
        }
        val state = PlayerUiState(activeMainTab = MainDestinationTab.SEARCH, isLoadingLibrary = false,
            allTracks = tracks, libraryTracks = tracks, searchResults = tracks)
        rule.setContent { PlayerAppContent(state, PlayerActions()) }
        rule.onNodeWithTag("track-search-0").assertIsDisplayed()
        rule.waitForIdle()
        Log.i("ScrollingWorkload", "20,000-song search opening reads: ${reads.get()}")
        assertTrue("Song search must not build unused catalogs, read ${reads.get()}", reads.get() < 1_000)
    }
}
