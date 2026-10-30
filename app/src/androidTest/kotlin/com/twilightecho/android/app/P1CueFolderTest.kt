package com.twilightecho.android.app

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.twilightecho.android.adapters.MusicFolders
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.concurrent.thread

/**
 * P1 CUE through the shipped app: a music folder picked in the system picker, its CUE image split into library
 * songs with and without Android's music permission, the album played through the native engine, and the folder
 * removed again. Start without the music permission to cover the folder-only scan (see the P1 README).
 */
@RunWith(AndroidJUnit4::class)
class P1CueFolderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val host get() = AndroidPlaybackHost.getInstance(context)
    private val client get() = host.primaryCoreClient
    private val device get() = UiDevice.getInstance(instrumentation)
    private val staging get() = File(context.getExternalFilesDir(null), "cue-fixture")
    private val cueIds = (1..3).map { cueTrackId("primary:Music/CueTest/Album.wav", "primary:Music/CueTest/Album.cue", it) }
    private val soloFolderId = MusicFolders.folderTrackId("primary:Music/CueTest/Solo.wav")
    private val savedQueue = mutableListOf<CoreTrackView>()
    private var original: CoreSnapshot? = null
    private var savedAudio: CoreEqConfigView? = null

    private fun shell(command: String): String = device.executeShellCommand(command)

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(500)
        val directory = File(context.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
        shell("mkdir -p /sdcard/Pictures/TwilightUiQA")
        shell("cp ${File(directory, "$name.png").absolutePath} /sdcard/Pictures/TwilightUiQA/$name.png")
    }

    private fun placeFixtures() {
        staging.deleteRecursively()
        staging.mkdirs()
        File(staging, "Album.wav").writeBytes(toneWave(CUE_ALBUM_PARTS))
        File(staging, "Album.cue").writeBytes(CUE_ALBUM_SHEET.toByteArray(Charset.forName("GBK")))
        File(staging, "Broken.cue").writeText("FILE \"Gone.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n")
        File(staging, "Solo.wav").writeBytes(toneWave(listOf(6.0 to 550.0)))
        instrumentation.context.assets.open("music/01.jpg").use { input -> File(staging, "cover.jpg").outputStream().use { input.copyTo(it) } }
        shell("rm -rf $FOLDER")
        shell("mkdir -p $FOLDER")
        staging.listFiles()!!.forEach { shell("cp ${it.absolutePath} $FOLDER/${it.name}") }
        scanFixtureAudio()
    }

    private fun scanFixtureAudio() {
        val paths = arrayOf("$FOLDER/Album.wav", "$FOLDER/Solo.wav")
        val scanned = CountDownLatch(paths.size)
        // Shell copies do not consistently trigger MediaStore indexing on every emulator.
        MediaScannerConnection.scanFile(context, paths, arrayOf("audio/wav", "audio/wav")) { _, _ -> scanned.countDown() }
        assertTrue("MediaStore must finish indexing the owned fixture paths", scanned.await(15, TimeUnit.SECONDS))
    }

    private fun releaseTestFolder() = host.musicFolders.value.filter { it.label == "Music/CueTest" }.forEach { host.removeMusicFolder(it.uri) }

    private fun scanStatus(): String? = rule.onAllNodesWithTag("scanStatus")
        .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun awaitStatus(expected: (String) -> Boolean): String {
        try {
            rule.waitUntil(60000) { scanStatus()?.let(expected) == true }
        } catch (error: Throwable) {
            shot("p1-cue-failure")
            throw AssertionError("Scan status stayed “${scanStatus()}”", error)
        }
        return scanStatus()!!
    }

    /** MediaStore's row for a fixture file, once Android has indexed its length. */
    private fun mediaStoreId(name: String): Long? = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ? " +
            "AND ${MediaStore.Audio.Media.DURATION} >= 5000", arrayOf("Music/CueTest/", name), null)
        ?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun library(query: String) = runBlocking { client.searchTracks(query).getOrThrow().items }
    private fun album() = runBlocking { client.queryCollections().getOrThrow().albums.singleOrNull { it.title == "CUE 验收" } }

    private fun pickCueFolder() {
        rule.onNodeWithTag("addMusicFolder").performScrollTo().performClick()
        // The picker opens in Music; inside CueTest it lists the folder's files.
        val entry = device.wait(Until.findObject(By.text("CueTest")), 15000)
        assertNotNull("The folder picker must open in Music and list CueTest", entry)
        entry!!.click()
        assertTrue(device.wait(Until.hasObject(By.text("Album.cue")), 10000))
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder|使用此文件夹"))), 10000)!!.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow|允许"))), 10000)!!.click()
    }

    @After fun cleanUp() {
        runBlocking {
            client.executeCommand(CoreTypedCommand.Pause)
            original?.let { snapshot ->
                client.executeCommand(CoreTypedCommand.ReplaceQueue(tracks = savedQueue,
                    startIndex = snapshot.queue?.index?.coerceAtLeast(0) ?: 0, play = false))
                snapshot.playback?.let { client.executeCommand(CoreTypedCommand.SetVolume(it.volume)) }
            }
            savedAudio?.let { client.setEqConfig(it) }
        }
        releaseTestFolder()
        shell("rm -rf $FOLDER")
        scanFixtureAudio()
        staging.deleteRecursively()
        // The library forgets the fixture files again.
        runBlocking {
            repeat(50) { if (mediaStoreId("Album.wav") != null || mediaStoreId("Solo.wav") != null) Thread.sleep(200) }
            host.scanAndSyncDeviceMusic()
        }
    }

    @Test fun pickedFolderSplitsItsCueImageAndPlaysTheSongsSeamlessly() {
        // Startup snapshots arrive before session/audio restoration has finished. A read-only queue query
        // verifies that the embedded host is accepting requests before we save or change the user's state.
        rule.waitUntil(15000) {
            if (client.snapshot.value == null) false else {
                when (val result = runBlocking { client.queryQueueWindow(0, 1) }) {
                    is CoreResult.Success -> true
                    is CoreResult.Failure -> {
                        assertEquals(result.error.message, CoreErrorCode.NOT_READY, result.error.code)
                        false
                    }
                }
            }
        }
        check(runBlocking { client.reconcileState() }.isSuccess)
        original = client.snapshot.value!!.also { snapshot ->
            for (offset in 0 until (snapshot.queue?.total ?: 0) step 50) {
                savedQueue += runBlocking { client.queryQueueWindow(offset, 50) }.getOrNull()!!.items.mapNotNull { it.track }
            }
        }
        // Seamless playback is a setting; the test turns it on and puts the setting back afterwards.
        val audio = runBlocking { client.getEqConfig() }.getOrThrow().also { savedAudio = it }
        if (!audio.gapless || audio.dsp.crossfadeSeconds > 0) {
            runBlocking { client.setEqConfig(audio.copy(gapless = true, dsp = audio.dsp.copy(crossfadeSeconds = 0.0))) }.getOrThrow()
        }
        releaseTestFolder()
        placeFixtures()

        rule.onNodeWithTag("nav-SETTINGS").performClick()
        rule.onNodeWithText("本地音乐", substring = false).performClick()
        rule.onNodeWithTag("musicFoldersCard").performScrollTo()
        shot("p1-cue-01-no-folders")
        pickCueFolder()
        rule.waitUntil(10000) { host.musicFolders.value.any { it.label == "Music/CueTest" } }

        // Picking a folder immediately imports its songs and CUE splits without asking for storage permissions.
        val status = awaitStatus { it.startsWith("已从音乐文件夹同步") }
        assertTrue(status, Regex("已从音乐文件夹同步 \\d+ 首歌曲，其中 3 首来自 CUE 分轨，1 个 CUE 未分轨").matches(status))
        val cueAlbum = album()!!
        assertEquals(cueIds, cueAlbum.trackIds)
        assertEquals(24.0, cueAlbum.totalDurationSec, 0.01)
        val songs = listOf("晨光", "午后", "夜色").map { title -> library(title).single { it.id in cueIds } }
        assertEquals(cueIds, songs.map { it.id })
        assertEquals(listOf("暮色乐团", "客座歌手", "暮色乐团"), songs.map { it.artist })
        assertEquals(listOf(1, 2, 3), songs.map { it.trackNumber })
        CUE_ALBUM_RANGES.zip(songs).forEach { (range, song) ->
            assertEquals(range.durationSeconds, song.duration, 0.001)
            val audio = song.source!!
            val cue = song.cueRange
            assertTrue(audio, audio.endsWith("/document/primary%3AMusic%2FCueTest%2FAlbum.wav"))
            assertEquals(range.startSeconds, cue!!.startSeconds, 0.0001)
            assertEquals(range.endSeconds, cue.endSeconds, 0.0001)
            assertTrue(song.cover!!.endsWith("/document/primary%3AMusic%2FCueTest%2Fcover.jpg"))
        }
        assertEquals(songs[0].cueRange!!.endSeconds, songs[1].cueRange!!.startSeconds, 0.0)
        val solo = library("Solo").single { it.id == soloFolderId }
        assertEquals(6.0, solo.duration, 0.0)
        assertEquals("WAV", solo.format)
        assertEquals(44100, solo.sampleRate)
        assertTrue(solo.source!!.startsWith("content://com.android.externalstorage.documents/tree/"))
        assertTrue(solo.cover!!.endsWith("/document/primary%3AMusic%2FCueTest%2Fcover.jpg"))

        rule.onNodeWithTag("scanDetailsToggle").performScrollTo().assertTextEquals("详情（1）").performClick()
        rule.onNodeWithTag("scanDetail").assertTextEquals("CueTest/Broken.cue：找不到引用的音频“Gone.wav”")
        rule.onNodeWithTag("musicFoldersCard").performScrollTo()
        shot("p1-cue-03-folders")

        rule.onNodeWithTag("nav-LIBRARY").performClick()
        rule.onNodeWithText("专辑").performClick()
        rule.onNodeWithTag("albumGrid").performScrollToNode(hasText("CUE 验收"))
        rule.onNodeWithText("CUE 验收").performClick()
        rule.onNodeWithTag("collection").performScrollToNode(hasText("夜色"))
        shot("p1-cue-04-album")
        rule.onNodeWithText("播放全部").performScrollTo().performClick()
        rule.waitUntil(30000) { client.snapshot.value?.let { it.playback?.state == "playing" && it.track?.id == cueIds[0] } == true }
        assertEquals(8.0, client.snapshot.value!!.playback!!.duration, 0.05)
        assertNotNull("Playback leases the folder's file", host.nativeAudioEngine.currentResolvedNativePath)
        assertEquals(cueIds, runBlocking { client.queryQueueWindow(0, 10) }.getOrNull()!!.items.take(3).map { it.track?.id })

        // Play across the first boundary and seek past the second song's end; record what the app reports.
        val reported = CopyOnWriteArrayList<Pair<String?, CorePlaybackView>>()
        var sampling = true
        val sampler = thread {
            while (sampling) {
                client.snapshot.value?.let { snapshot -> snapshot.playback?.let { reported += snapshot.track?.id to it } }
                Thread.sleep(20)
            }
        }
        try {
            rule.onNodeWithTag("miniPlayer").performClick()
            rule.onNodeWithTag("seek").performSemanticsAction(SemanticsActions.SetProgress) { it(6.5f) }
            rule.waitUntil(15000) { client.snapshot.value?.track?.id == cueIds[1] && (client.snapshot.value?.playback?.position ?: 0.0) > 0.3 }
            rule.onNode(hasText("午后") and hasAnyAncestor(hasTestTag("immersivePlayer")),
                useUnmergedTree = true).assertIsDisplayed()
            shot("p1-cue-05-now-playing")
            runBlocking { client.executeCommand(CoreTypedCommand.Seek(30.0)) }
            rule.waitUntil(15000) { client.snapshot.value?.track?.id == cueIds[2] && (client.snapshot.value?.playback?.position ?: 0.0) > 0.3 }
        } finally {
            sampling = false
            sampler.join()
        }
        Log.i(TAG, "Reported: ${reported.map { (id, playback) ->
            "${cueIds.indexOf(id) + 1}/${playback.state}/${playback.position}" + if (playback.gaplessActive) "/gapless" else ""
        }.distinct().joinToString()}")
        val ranges = cueIds.zip(CUE_ALBUM_RANGES).toMap()
        // Each song starts from its own beginning, never from where the song before it ended.
        cueIds.drop(1).forEach { id ->
            val start = reported.first { it.first == id }.second.position
            assertTrue("$id started at $start s", start < 1.5)
        }
        reported.forEach { (id, playback) ->
            val range = ranges[id] ?: return@forEach
            assertTrue("$id reported ${playback.position} s of ${range.durationSeconds} s", playback.position <= range.durationSeconds + 0.06)
        }
        val crossing = reported.dropWhile { it.first != cueIds[0] || it.second.position < 6.5 }.takeWhile { it.first != cueIds[2] }
        assertTrue("The app stays playing across the boundary", crossing.filter { it.first == cueIds[0] || it.first == cueIds[1] }
            .map { it.second.state }.distinct().all { it == "playing" })
        assertTrue("The next song is preloaded seamlessly", crossing.any { it.first == cueIds[0] && it.second.gaplessActive })
        runBlocking { client.executeCommand(CoreTypedCommand.Pause) }
        rule.onNodeWithContentDescription("收起播放器").performClick()

        // Removing the folder drops its songs at once without keeping any MediaStore fallback songs.
        rule.onNodeWithTag("nav-SETTINGS").performClick()
        rule.onNodeWithTag("musicFoldersCard").performScrollTo()
        rule.onNodeWithContentDescription("移除 Music/CueTest").performScrollTo().performClick()
        val afterRemoval = awaitStatus {
            it == "尚未选择音乐文件夹" || (it.startsWith("已从音乐文件夹同步") && "CUE" !in it)
        }
        assertTrue(afterRemoval, afterRemoval == "尚未选择音乐文件夹" ||
            Regex("已从音乐文件夹同步 \\d+ 首歌曲").matches(afterRemoval))
        assertTrue(host.musicFolders.value.none { it.label == "Music/CueTest" })
        assertNull(album())
        assertTrue(library("Solo").none { it.id == soloFolderId })
        shot("p1-cue-06-removed")
    }

    private companion object {
        const val TAG = "P1CueFolderTest"
        const val FOLDER = "/sdcard/Music/CueTest"
    }
}
