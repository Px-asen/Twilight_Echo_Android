package com.twilightecho.android.app

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.playbackhost.*
import com.twilightecho.android.feature.player.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflinePlaybackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun actualTlsDownloadPlaysAfterComputerIsForgottenAndRetainsFileDuringRescan() = runBlocking {
        val address = InstrumentationRegistry.getArguments().getString("desktopHost")
        assumeTrue("Requires the desktop TLS fixture", !address.isNullOrBlank())
        val host = AndroidPlaybackHost.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        host.awaitStarted()
        val oldPolicy = host.userPreferences.state.value.mobileNetwork
        var id: String? = null
        var playlistId: String? = null
        try {
            host.selectPlaybackTarget(true).getOrThrow()
            host.phoneLibraryClient.executeCommand(CoreTypedCommand.Stop)
            host.userPreferences.update { it.copy(mobileNetwork = oldPolicy.copy(allowMeteredDownloads = true)) }
            host.connectDesktopLibrary(address!!, "123456").getOrThrow()
            val source = host.librarySource.value as LibrarySource.Desktop
            val alternative = address.replace("127.0.0.1", "localhost")
            host.connectDesktopLibrary(alternative, expectedServerId = source.serverId).getOrThrow()
            assertEquals(source.serverId, (host.librarySource.value as LibrarySource.Desktop).serverId)
            assertTrue(host.connectDesktopLibrary(address, expectedServerId = "different-server").isFailure)
            val page = host.libraryCoreClient.searchTracksPage("", 0, 10).getOrThrow()
            val track = page.items.first()
            host.downloadDesktopTrack(source.serverId, track)
            val deadline = SystemClock.elapsedRealtime() + 60_000
            var ready: OfflineDownload? = null
            while (ready == null) {
                val row = host.offlineDownloads.state.value.items.firstOrNull { it.reference.serverId == source.serverId }
                id = row?.id
                check(row?.status != OfflineDownloadStatus.FAILED) { row?.error ?: "Download failed" }
                if (row?.status == OfflineDownloadStatus.READY) ready = row
                check(SystemClock.elapsedRealtime() < deadline) { "Download timed out" }
                delay(50)
            }
            val local = host.offlineDownloads.track(ready)!!
            assertTrue(File(local.source!!).length() > 1000)
            assertNotNull(ready.reference.sourceVersion)
            assertEquals(local.id, host.offlineTrack(source.serverId, track)?.id)
            lateinit var viewModel: PlayerViewModel
            rule.runOnUiThread { viewModel = ViewModelProvider(rule.activity, PlayerViewModel.Factory(host))[PlayerViewModel::class.java]; viewModel.onOfflineDownloadAction(ready.id, "play") }
            val routedDeadline = SystemClock.elapsedRealtime() + 15_000
            while (host.phoneLibraryClient.snapshot.value?.track?.id != local.id || host.nativeAudioEngine.getSnapshot().state != "playing") {
                check(SystemClock.elapsedRealtime() < routedDeadline) { "Offline page play did not route to local download" }; delay(50)
            }
            host.phoneLibraryClient.executeCommand(CoreTypedCommand.Stop)
            host.forgetDesktop(source.serverId).getOrThrow()
            assertFalse(source.serverId in host.connectedDesktops.value)
            host.scanAndSyncDeviceMusic().getOrThrow()
            assertTrue(host.phoneLibraryClient.searchTracks(local.title).getOrThrow().items.any { it.id == local.id })
            host.phoneLibraryClient.createPlaylist("Offline cleanup fixture", listOf(local.id)).getOrThrow()
            playlistId = host.phoneLibraryClient.listPlaylists().getOrThrow().first { it.name == "Offline cleanup fixture" }.id
            check(host.phoneLibraryClient.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = listOf(local), play = false)).isSuccess)
            check(host.requestFocusAndPlay(0.0).isSuccess)
            val playbackDeadline = SystemClock.elapsedRealtime() + 20_000
            while (host.nativeAudioEngine.getSnapshot().state != "playing") {
                check(SystemClock.elapsedRealtime() < playbackDeadline) { "Offline native playback failed" }; delay(50)
            }
            assertTrue(runCatching { host.removeOfflineDownload(ready.id) }.isFailure)
            check(host.phoneLibraryClient.executeCommand(CoreTypedCommand.Seek(20.0)).isSuccess)
            delay(300)
            assertTrue(host.nativeAudioEngine.getSnapshot().positionMs >= 19500)
            check(host.phoneLibraryClient.executeCommand(CoreTypedCommand.Stop).isSuccess)
            host.removeOfflineDownload(ready.id)
            assertFalse(File(local.source!!).exists())
            val cleanupDeadline = SystemClock.elapsedRealtime() + 10_000
            while (host.phoneLibraryClient.listPlaylists().getOrThrow().first { it.id == playlistId }.trackIds.contains(local.id)) {
                check(SystemClock.elapsedRealtime() < cleanupDeadline) { "Removed download retained by playlist" }; delay(50)
            }
        } finally {
            host.phoneLibraryClient.executeCommand(CoreTypedCommand.Stop)
            clearTestPlaybackQueue(host.phoneLibraryClient)
            id?.let { host.offlineDownloads.remove(it) }
            playlistId?.let { host.phoneLibraryClient.deletePlaylist(it) }
            host.userPreferences.update { it.copy(mobileNetwork = oldPolicy) }
        }
    }
}
