package com.twilightecho.android.app

import android.content.ContentValues
import android.content.ContentUris
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.twilightecho.android.coreclient.CoreTypedCommand
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the shipped activity, permissions, JS Core and native engine, not UI stand-ins. */
@RunWith(AndroidJUnit4::class)
class NativePlayerSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val host get() = AndroidPlaybackHost.getInstance(instrumentation.targetContext)
    private val device get() = UiDevice.getInstance(instrumentation)
    private val ownedFixtures = mutableListOf<Uri>()

    private fun importTestMusic(): String {
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val assets = instrumentation.context.assets
        var firstTrackId = ""
        assets.list("music")!!.filter { it.endsWith(".flac") }.sorted().forEach { name ->
            val displayName = "Twilight-UI-$name"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/TwilightUiQA")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.DURATION, 42000L)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            ownedFixtures += uri
            if (firstTrackId.isEmpty()) firstTrackId = "mediastore-${ContentUris.parseId(uri)}"
            assets.open("music/$name").use { input -> resolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        }
        return firstTrackId
    }

    private fun hideKeyboard() {
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        rule.waitForIdle()
    }

    private fun awaitPlayback(state: String) {
        try {
            rule.waitUntil(30000) { host.primaryCoreClient.snapshot.value?.playback?.state == state }
        } catch (error: Throwable) {
            Log.e("NativePlayerSmokeTest", "Expected $state; snapshot=${host.primaryCoreClient.snapshot.value}")
            shot("native-failure")
            throw error
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(500)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
        device.executeShellCommand("mkdir -p /sdcard/Pictures/TwilightUiQA")
        device.executeShellCommand("cp ${File(dir, "$name.png").absolutePath} /sdcard/Pictures/TwilightUiQA/$name.png")
    }

    @After fun finishPlayback() {
        runBlocking { host.primaryCoreClient.executeCommand(CoreTypedCommand.Pause) }
        ownedFixtures.forEach { instrumentation.targetContext.contentResolver.delete(it, null, null) }
        device.unfreezeRotation()
    }

    @Test fun scanPlaySeekLyricsFavoriteBookmarkAndRotate() {
        val trackId = importTestMusic()
        rule.waitUntil(15000) { host.primaryCoreClient.snapshot.value != null }
        assertNull(host.primaryCoreClient.snapshot.value!!.track)
        rule.onNodeWithTag("miniPlayer").assertDoesNotExist()
        shot("16-native-empty")
        rule.onNodeWithTag("nav-SETTINGS").performClick()
        rule.onNodeWithText("本地音乐", substring = false).performClick()
        rule.onNodeWithText("扫描本地音乐", substring = false).performClick()
        device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), 5000)?.click()
        rule.waitUntil(30000) { runBlocking { host.primaryCoreClient.searchTracks("Golden Hour").getOrNull()?.items?.any { it.id == trackId } == true } }
        rule.onNodeWithTag("nav-SEARCH").performClick()
        rule.onNodeWithTag("searchInput").performTextInput("Golden Hour")
        hideKeyboard()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("track-$trackId").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("track-$trackId").performScrollTo().performClick()
        awaitPlayback("playing")
        assertEquals(trackId, host.primaryCoreClient.snapshot.value?.track?.id)
        Log.i("NativePlayerSmokeTest", "Audio=${runBlocking { host.nativeAudioEngine.getSnapshot() }}; path=${host.nativeAudioEngine.currentResolvedNativePath}")
        rule.waitUntil(15000) { (host.primaryCoreClient.snapshot.value?.playback?.position ?: 0.0) >= 1.0 }
        assertNull("Successful playback must not report the inactive decoder as a failure", host.primaryCoreClient.snapshot.value?.error)
        assertNotNull("Playback must lease the selected real MediaStore resource", host.nativeAudioEngine.currentResolvedNativePath)
        val measuredRate = host.primaryCoreClient.snapshot.value?.playback?.sourceSampleRate
        assertTrue("The source is 44.1 kHz; absent decoder metadata must stay unknown", measuredRate == null || measuredRate == 0 || measuredRate == 44100)
        val firstRevision = host.primaryCoreClient.snapshot.value!!.queue!!.revision
        rule.onNodeWithTag("track-$trackId").performClick()
        rule.waitUntil(10000) { host.primaryCoreClient.snapshot.value!!.queue!!.revision > firstRevision }
        rule.onNodeWithTag("miniPlayer").performClick()
        rule.onNodeWithTag("fullCover").assertIsDisplayed()
        val cover = rule.onNodeWithTag("fullCover").fetchSemanticsNode().boundsInRoot
        val metadata = rule.onNodeWithTag("coverMetadata").fetchSemanticsNode().boundsInRoot
        assertEquals(1f, cover.width / cover.height, 0.015f)
        assertTrue("真实播放时歌名也必须位于完整封面下方", cover.bottom <= metadata.top)
        shot("13-native-player")
        rule.onNodeWithContentDescription("暂停播放").performClick()
        rule.waitUntil(10000) { host.primaryCoreClient.snapshot.value?.playback?.state == "paused" }
        rule.onNodeWithTag("seek").performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
        rule.waitUntil(10000) { (host.primaryCoreClient.snapshot.value?.playback?.position ?: 0.0) >= 11.0 }
        val track = host.primaryCoreClient.snapshot.value!!.track!!
        val resolver = instrumentation.targetContext.contentResolver
        val lyricName = "Twilight-UI-lyrics.lrc"
        val lyricUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, lyricName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        })!!
        ownedFixtures += lyricUri
        instrumentation.context.assets.open("music/01-Golden-Hour.lrc").use { input ->
            resolver.openOutputStream(lyricUri)!!.use { input.copyTo(it) }
        }
        val pickerName = resolver.query(lyricUri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        rule.onNodeWithContentDescription("更多播放操作").performClick()
        rule.onNodeWithText("导入 LRC 歌词").performClick()
        if (!device.wait(Until.hasObject(By.text(pickerName)), 3000)) {
            device.wait(Until.findObject(By.desc("Show roots")), 5000)!!.click()
            device.wait(Until.findObject(By.text("Downloads").res("android:id/title")), 5000)!!.click()
        }
        val document = device.wait(Until.findObject(By.text(pickerName)), 10000)
        assertNotNull("The system document picker must expose the imported LRC fixture", document)
        document!!.click()
        rule.waitUntil(10000) { host.primaryCoreClient.snapshot.value?.currentLyrics?.lines?.isNotEmpty() == true }
        rule.onNodeWithText("歌词", useUnmergedTree = true).performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("lyrics").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("lyrics").performScrollToNode(hasText("听见安静，也听见想念"))
        rule.onNodeWithText("听见安静，也听见想念").performClick()
        rule.waitUntil(10000) { (host.primaryCoreClient.snapshot.value?.playback?.position ?: 0.0) >= 17.0 }
        shot("14-native-lyrics")
        rule.onNodeWithContentDescription("收藏歌曲").performClick()
        rule.waitUntil(10000) { track.id in host.primaryCoreClient.snapshot.value!!.favoriteTrackIds }
        rule.onNodeWithContentDescription("更多播放操作").performClick()
        rule.onNodeWithText("添加当前位置书签").performClick()
        rule.waitUntil(10000) { host.primaryCoreClient.snapshot.value!!.bookmarks.any { it.trackId == track.id } }
        rule.onNodeWithText("封面", useUnmergedTree = true).performClick()
        device.setOrientationLeft()
        rule.waitUntil(15000) { device.displayWidth > device.displayHeight }
        shot("15-native-landscape")
        rule.onNodeWithContentDescription("下一首").assertIsDisplayed()
        rule.onNodeWithTag("seek").assertIsDisplayed()
        device.setOrientationNatural()
        rule.waitUntil(15000) { device.displayWidth < device.displayHeight }
        rule.onNodeWithContentDescription("收起播放器").performClick()
        rule.onNodeWithTag("nav-LIBRARY").assertIsDisplayed()

        rule.onNodeWithTag("nav-LIBRARY").performClick()
        rule.onNodeWithText("歌单", substring = false).performClick()
        rule.onNodeWithContentDescription("新建歌单").performClick()
        rule.onNodeWithTag("playlistName").performTextInput("夜行验收")
        rule.onNodeWithText("创建", substring = false).performClick()
        rule.waitUntil(10000) { host.primaryCoreClient.snapshot.value!!.playlists.any { it.name == "夜行验收" } }
        rule.onNodeWithText("夜行验收", substring = false).performClick()
        rule.onNodeWithText("加入当前歌曲").performScrollTo().performClick()
        rule.waitUntil(10000) { host.primaryCoreClient.snapshot.value!!.playlists
            .first { it.name == "夜行验收" }.trackIds == listOf(track.id) }
        rule.onNodeWithText("播放全部").performScrollTo().performClick()
        awaitPlayback("playing")
        assertEquals(track.id, host.primaryCoreClient.snapshot.value!!.track!!.id)
        rule.waitUntil(10000) { (host.primaryCoreClient.snapshot.value?.playback?.position ?: 0.0) >= 0.5 }
        assertNull("Playlist playback must not retain a superseded backend error", host.primaryCoreClient.snapshot.value?.error)
        shot("19-native-playlist")
        rule.onNodeWithContentDescription("删除歌单").performClick()
        rule.onNodeWithText("删除", substring = false).performClick()
        rule.waitUntil(10000) { host.primaryCoreClient.snapshot.value!!.playlists.none { it.name == "夜行验收" } }

        rule.onNodeWithTag("nav-SETTINGS").performClick()
        rule.onNodeWithContentDescription("返回设置").performClick()
        rule.onNodeWithText("音频与 DSP", substring = false).performClick()
        rule.onNodeWithTag("audio-page-eq").performScrollTo().performClick()
        rule.onNodeWithText("人声", substring = false).performScrollTo().performClick()
        rule.waitUntil(10000) { host.primaryCoreClient.snapshot.value!!.eqConfig.preset == "Vocal" }
        rule.onNodeWithTag("settings-AUDIO").performScrollToNode(hasTestTag("eqBand-9"))
        rule.onNodeWithTag("eqBand-9").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        rule.waitUntil(10000) { host.primaryCoreClient.snapshot.value!!.eqConfig.bandsDb[9] == 3.0 }
        assertTrue(host.primaryCoreClient.snapshot.value!!.eqConfig.enabled)
    }
}
