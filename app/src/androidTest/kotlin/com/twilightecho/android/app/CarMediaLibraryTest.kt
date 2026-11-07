package com.twilightecho.android.app

import android.content.ComponentName
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CoreTypedCommand
import com.twilightecho.android.mediasession.TwilightMediaSessionService
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@UnstableApi
@RunWith(AndroidJUnit4::class)
class CarMediaLibraryTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun browserSearchAndSelectionUsePhoneCoreAndRejectCallerUris() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = AndroidPlaybackHost.getInstance(context)
        host.awaitStarted()
        val client = host.phoneLibraryClient
        val original = client.reconcileState().getOrNull()!!
        assumeTrue("Run with idle phone playback", original.playback?.state != "playing")
        val library = client.searchTracks("").getOrThrow().items
        val queue = mutableListOf<CoreTrackView>()
        for (offset in 0 until (original.queue?.total ?: 0) step 50)
            queue += client.queryQueueWindow(offset, 50).getOrNull()!!.items.mapNotNull { it.track }
        val file = File.createTempFile("car-library-", ".flac", context.cacheDir)
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        val track = CoreTrackView("car-fixture", title = "Car Fixture Horizon", artist = "Car Fixture Artist", album = "Car Fixture Album", source = file.absolutePath, duration = 42.0)
        var browser: MediaBrowser? = null
        try {
            host.selectPlaybackTarget(true).getOrThrow()
            client.syncLocalTracks(library + track).getOrThrow()
            browser = withContext(Dispatchers.Main) {
                MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, TwilightMediaSessionService::class.java))).buildAsync().await()
            }
            val remote = browser
            val item = withContext(Dispatchers.Main) {
                val root = remote.getLibraryRoot(null).await()
                assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
                assertEquals(true, root.value!!.mediaMetadata.isBrowsable)
                val children = remote.getChildren(root.value!!.mediaId, 0, 50, null).await()
                assertTrue(children.value!!.any { it.mediaMetadata.title.toString() == "此手机的音乐" })
                assertEquals(LibraryResult.RESULT_SUCCESS, remote.search("Car Fixture", null).await().resultCode)
                val result = remote.getSearchResult("Car Fixture", 0, 50, null).await()
                assertEquals(1, result.value!!.size)
                result.value!!.single().also {
                    assertNull(it.localConfiguration)
                    assertTrue(it.mediaId.startsWith("car:song:"))
                    assertEquals("Car Fixture Artist", it.mediaMetadata.artist.toString())
                    assertEquals(LibraryResult.RESULT_SUCCESS, remote.getItem(it.mediaId).await().resultCode)
                }
            }
            withContext(Dispatchers.Main) { remote.setMediaItem(item); remote.prepare(); remote.play() }
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (client.snapshot.value?.track?.id != track.id || host.nativeAudioEngine.getSnapshot().state != "playing") {
                check(SystemClock.elapsedRealtime() < deadline) { "Car selection did not play: ${client.snapshot.value?.playback}" }
                delay(50)
            }
            assertFalse(host.systemMediaTransport.isRemote)
            withContext(Dispatchers.Main) { remote.pause() }
            delay(250)
            val revision = client.snapshot.value!!.queue!!.revision
            withContext(Dispatchers.Main) {
                val invalid = MediaItem.Builder().setMediaId("unknown-id").setUri("file:///private/unlisted.flac").build()
                assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, remote.getItem(invalid.mediaId).await().resultCode)
                remote.setMediaItem(invalid)
            }
            delay(500)
            assertEquals(revision, client.snapshot.value!!.queue!!.revision)
            assertEquals(track.id, client.snapshot.value!!.track!!.id)
        } finally {
            withContext(Dispatchers.Main) { browser?.release() }
            client.executeCommand(CoreTypedCommand.Stop)
            if (queue.isEmpty()) clearTestPlaybackQueue(client)
            else client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = queue, startIndex = original.queue?.index?.coerceAtLeast(0) ?: 0, play = false))
            client.syncLocalTracks(library).getOrThrow()
            file.delete()
        }
    }
}
