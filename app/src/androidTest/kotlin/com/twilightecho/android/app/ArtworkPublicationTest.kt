package com.twilightecho.android.app

import android.Manifest
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import com.twilightecho.android.feature.player.PlayerActions
import com.twilightecho.android.feature.player.PlayerAppContent
import com.twilightecho.android.feature.player.PlayerUiState
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import com.twilightecho.android.plugins.providerArtworkData
import com.twilightecho.android.plugins.providerArtworkPreview
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArtworkPublicationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val host get() = AndroidPlaybackHost.getInstance(context)
    private val device get() = UiDevice.getInstance(instrumentation)
    private val directory get() = File(context.getExternalFilesDir(null), "cover-notifications").apply { mkdirs() }

    @Test fun notificationSettingsEntryOpensTheSystemNotificationPage() {
        rule.onNodeWithTag("nav-SETTINGS").performClick()
        rule.onNodeWithTag("openNotificationSettings").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 10_000))
        assertTrue(device.takeScreenshot(File(directory, "notification-settings.png")))
        device.pressBack()
    }

    @Test fun publishesRealArtworkToNotificationAndLockScreenAndClearsOldCovers() = runBlocking {
        val client = host.phoneLibraryClient
        await { client.snapshot.value != null }
        val original = client.snapshot.value!!
        assumeTrue("Run while phone playback is idle", original.playback?.state != "playing")
        val oldMode = host.operatingMode.value
        val queue = mutableListOf<CoreTrackView>()
        for (offset in 0 until (original.queue?.total ?: 0) step 50) {
            val page = client.queryQueueWindow(offset, 50) as CoreResult.Success
            queue += page.value.items.mapNotNull { it.track }
        }
        val local = File.createTempFile("system-cover-", ".flac", context.cacheDir)
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input -> local.outputStream().use { input.copyTo(it) } }
        val lockDisabled = device.executeShellCommand("locksettings get-disabled").trim() == "true"
        try {
            host.switchToStandaloneLocalCore().getOrThrow()
            val track = CoreTrackView("system-cover-local", title = "Golden Hour", artist = "Paper Lantern",
                album = "Afterglow", source = local.absolutePath, duration = 42.0)
            check(client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = listOf(track), play = false)).isSuccess)
            val play = host.requestFocusAndPlay(0.0)
            check(play.isSuccess) { "Playback could not start: $play" }
            await { platformSession()?.metadata?.description?.mediaId == track.id && platformArtwork() != null }
            assertTrue(platformArtwork()!!.width in 1..512)
            val notifications = context.getSystemService(NotificationManager::class.java)
            await { notifications.activeNotifications.any { it.notification.getLargeIcon() != null } }
            val notification = notifications.activeNotifications.first { it.notification.getLargeIcon() != null }.notification
            assertEquals(com.twilightecho.android.mediasession.R.drawable.ic_twilight_notification, notification.smallIcon.resId)
            assertTrue(device.openNotification())
            assertTrue(device.wait(Until.hasObject(By.text("Golden Hour")), 10_000))
            delay(1500) // SystemUI's artwork crossfade finishes after its accessibility title updates.
            assertTrue(device.takeScreenshot(File(directory, "local-media-notification.png")))
            device.pressBack()
            if (lockDisabled) device.executeShellCommand("locksettings set-disabled false")
            device.sleep(); device.wakeUp()
            assertTrue(device.wait(Until.hasObject(By.text("Golden Hour")), 10_000))
            delay(1500)
            assertTrue(device.takeScreenshot(File(directory, "local-lock-screen.png")))
            platformSession()!!.transportControls.pause()
            await { host.nativeAudioEngine.getSnapshot().state == "paused" }
            device.executeShellCommand("wm dismiss-keyguard")

            // Public catalogue reads only: never provide or replace account credentials.
            val cloud = publicArtworkTrack(host)
            val url = cloud.cover!!
            val thumbnail = providerArtworkData(url, 256)!!
            val highResolution = providerArtworkData(url, 1024)!!
            val small = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val large = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size, small)
            BitmapFactory.decodeByteArray(highResolution, 0, highResolution.size, large)
            assertTrue("Player cover must retain detail beyond the old 256px thumbnail", large.outWidth > 256 && large.outHeight > 256)
            assertTrue(large.outWidth > small.outWidth)
            android.util.Log.i("ArtworkPublicationTest", "NCM cover: thumbnail=${small.outWidth}x${small.outHeight}, player=${large.outWidth}x${large.outHeight}")
            assertTrue(providerArtworkPreview(url)!!.length <= 6 * 1024 * 1024)

            check(client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = listOf(cloud), play = false)).isSuccess)
            await { platformSession()?.metadata?.description?.mediaId == cloud.id && platformArtwork() != null }
            // No account playback is started here. Inspect the published notification rather than
            // SystemUI's resumption carousel, which can retain the last actively played song.
            await { notifications.activeNotifications.any { item ->
                item.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == cloud.title &&
                    item.notification.getLargeIcon() != null
            } }

            val noCover = CoreTrackView("system-no-cover", title = "No artwork")
            check(client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = listOf(noCover), play = false)).isSuccess)
            await { platformSession()?.metadata?.description?.mediaId == noCover.id && platformArtwork() == null }
        } finally {
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            if (lockDisabled) device.executeShellCommand("locksettings set-disabled true")
            client.executeCommand(CoreTypedCommand.Stop)
            client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = queue,
                startIndex = original.queue?.index?.coerceAtLeast(0) ?: 0, play = false))
            original.playback?.let {
                client.executeCommand(CoreTypedCommand.SetVolume(it.volume))
                client.executeCommand(CoreTypedCommand.SetPlayMode(PlayMode.fromWireValue(it.playMode)))
                client.executeCommand(CoreTypedCommand.Seek(it.position))
            }
            if (oldMode is CoreOperatingMode.RemoteDesktopCore) host.selectPlaybackTarget(false)
            local.delete()
        }
    }

    private fun platformSession(): MediaController? {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
        return try {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
                .firstOrNull { it.packageName == context.packageName }
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    private fun platformArtwork(): Bitmap? = platformSession()?.metadata?.let { metadata ->
        listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { metadata.getBitmap(it) }
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(30_000) { while (!condition()) delay(50) }
    }
}

/** The same production player UI, independent of the lock-screen test's activity lifecycle. */
@RunWith(AndroidJUnit4::class)
class NcmArtworkUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun realNeteaseCoverRendersInTheImmersivePlayer() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val track = publicArtworkTrack(AndroidPlaybackHost.getInstance(context))
        // Warm the image to separate network availability from the render check.
        assertNotNull(providerArtworkData(track.cover!!, 1024))
        rule.setContent {
            TwilightEchoTheme {
                PlayerAppContent(PlayerUiState(currentTrack = track, isImmersivePlayerOpen = true,
                    isLoadingLibrary = false), PlayerActions())
            }
        }
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("fullCover").fetchSemanticsNodes().isNotEmpty() }
        delay(1200)
        val directory = File(context.getExternalFilesDir(null), "cover-notifications").apply { mkdirs() }
        File(directory, "netease-hd-player.png").outputStream().use {
            assertTrue(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}

private suspend fun publicArtworkTrack(host: AndroidPlaybackHost): CoreTrackView {
    val repository = host.plugins
    repository.initialize()
    val id = "com.twilightecho.provider.ncm"
    assumeTrue("Enable the bundled NCM provider for this public catalogue/CDN check",
        repository.state.value.installed.any { it.id == id && it.enabled })
    val raw = repository.call(id, "searchSongs", buildJsonArray { add("晴天"); add(1); add(0) })
        .jsonObject.getValue("items").jsonArray.first().jsonObject
    val song = raw.getValue("ncmSongId").jsonPrimitive.content
    return CoreTrackView("provider://ncm/$song", title = raw.getValue("title").jsonPrimitive.content,
        artist = raw["artist"]?.jsonPrimitive?.content.orEmpty(), album = raw["album"]?.jsonPrimitive?.content.orEmpty(),
        source = "provider://ncm/$song", ncmSongId = song, cover = raw.getValue("cover").jsonPrimitive.content)
}
