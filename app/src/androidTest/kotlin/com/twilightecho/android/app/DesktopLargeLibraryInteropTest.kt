package com.twilightecho.android.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production TLS/RPC + 1410-song index -> real QuickJS queue -> FFmpeg/AAudio playback. */
@RunWith(AndroidJUnit4::class)
class DesktopLargeLibraryInteropTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun selectingPastTheFirstBatchPlaysTheWholeLibraryAndKeepsNextWorking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val address = InstrumentationRegistry.getArguments().getString("desktopHost")
        assumeTrue("Requires fixture with TWILIGHT_FIXTURE_TRACK_COUNT=1410", !address.isNullOrBlank())
        val host = AndroidPlaybackHost.getInstance(instrumentation.targetContext)
        rule.waitUntil(15_000) { host.primaryCoreClient.snapshot.value?.phase == CorePhase.READY }
        lateinit var vm: PlayerViewModel
        rule.runOnUiThread { vm = ViewModelProvider(rule.activity, PlayerViewModel.Factory(host))[PlayerViewModel::class.java] }
        try {
            runBlocking {
                host.switchToStandaloneLocalCore().getOrThrow()
                host.primaryCoreClient.executeCommand(CoreTypedCommand.Stop)
                host.connectDesktopLibrary(address!!, "123456").getOrThrow()
            }
            val desktop = host.librarySource.value as LibrarySource.Desktop
            rule.waitUntil(60_000) { host.desktopLibraries.value.any { it.serverId == desktop.serverId && it.complete } }
            rule.runOnUiThread {
                vm.onSelectLibraryDevice(desktop.serverId)
                vm.onSelectMainTab(MainDestinationTab.LIBRARY)
                vm.onSelectLibraryTab(LibrarySectionTab.SONGS)
            }
            rule.waitUntil(10_000) { vm.uiState.value.libraryTracks.size == 1410 }
            val tracks = vm.uiState.value.libraryTracks
            val selected = tracks[1007]
            val next = tracks[1008]
            rule.runOnUiThread { vm.onPlayTracks(tracks, selected.id) }
            rule.waitUntil(30_000) {
                assertNull(vm.uiState.value.errorBanner)
                val snapshot = host.primaryCoreClient.snapshot.value
                snapshot?.queue?.total == 1410 && snapshot.queue?.index == 1007 && snapshot.playback?.state == "playing"
            }
            assertEquals(selected.catalog!!.sourceId, DesktopMediaReference.parse(host.primaryCoreClient.snapshot.value!!.track!!.id)!!.sourceId)
            runBlocking { assertTrue(host.primaryCoreClient.executeCommand(CoreTypedCommand.Next).isSuccess) }
            rule.waitUntil(20_000) {
                DesktopMediaReference.parse(host.primaryCoreClient.snapshot.value?.track?.id.orEmpty())?.sourceId == next.catalog!!.sourceId &&
                    host.primaryCoreClient.snapshot.value?.playback?.state == "playing"
            }
            assertEquals(1410, host.primaryCoreClient.snapshot.value!!.queue!!.total)
            assertEquals("oboe", runBlocking { host.nativeAudioEngine.getSnapshot().outputBackend })
            runBlocking { assertTrue(host.primaryCoreClient.executeCommand(CoreTypedCommand.Pause).isSuccess) }
            rule.waitUntil(10_000) { !vm.uiState.value.isPlaying }
            val image = File(instrumentation.targetContext.getExternalFilesDir(null), "desktop-1410-song-playback.png")
            assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(image))
        } finally { runBlocking { host.primaryCoreClient.executeCommand(CoreTypedCommand.Stop); host.switchToStandaloneLocalCore() } }
    }
}
