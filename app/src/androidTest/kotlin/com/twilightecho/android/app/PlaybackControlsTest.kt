package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.PlayMode
import com.twilightecho.android.feature.player.PlayerActions
import com.twilightecho.android.feature.player.PlayerAppContent
import com.twilightecho.android.feature.player.PlayerUiState
import com.twilightecho.android.ui.components.TwilightIcons
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(450) // finish the finite transition and artwork load
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null),
            "playback-icon-screenshots").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png")))
    }

    @Test fun allRoundedSymbolsRenderAtSmallAndLargeSizes() {
        val symbols = listOf(
            "播放" to TwilightIcons.Play, "暂停" to TwilightIcons.Pause,
            "上一首" to TwilightIcons.SkipPrevious, "下一首" to TwilightIcons.SkipNext,
            "随机播放" to TwilightIcons.Shuffle, "顺序播放" to TwilightIcons.RepeatSequence,
            "列表循环" to TwilightIcons.RepeatAll, "单曲循环" to TwilightIcons.RepeatOne,
            "播放队列" to TwilightIcons.Queue
        )
        rule.setContent {
            TwilightEchoTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Material Symbols Rounded", color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge)
                    Text("20dp / 40dp", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    symbols.forEachIndexed { index, (label, icon) ->
                        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                            listOf(20.dp, 40.dp).forEach { size ->
                                Box(Modifier.size(size).background(Color.Black).testTag("symbol-$index-$size")) {
                                    Icon(icon, null, Modifier.fillMaxSize(), tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
        symbols.indices.forEach { index ->
            listOf(20.dp, 40.dp).forEach { size ->
                val pixels = rule.onNodeWithTag("symbol-$index-$size").captureToImage().toPixelMap()
                var visible = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    if (pixels[x, y].red > 0.8f) visible++
                }
                assertTrue("${symbols[index].first} must render inside its $size viewport", visible > 10)
            }
        }
        shot("01-rounded-symbols")
    }

    @Test fun playbackActionsAndTouchTargetsRemainUsable() {
        val coverFile = File(instrumentation.targetContext.cacheDir, "playback-icons/01-Golden-Hour.flac")
        coverFile.parentFile!!.mkdirs()
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input ->
            coverFile.outputStream().use { input.copyTo(it) }
        }
        val track = CoreTrackView("icons-qa", title = "Golden Hour", artist = "Paper Lantern",
            album = "Afterglow", source = coverFile.toURI().toString(), duration = 42.0,
            format = "FLAC", sampleRate = 44100, bitDepth = 16)
        val ui = mutableStateOf(PlayerUiState(currentTrack = track, durationSeconds = 42.0,
            displayedPositionSeconds = 12.0, isImmersivePlayerOpen = true, isLoadingLibrary = false,
            libraryTracks = listOf(track), libraryTrackCount = 1))
        var previousClicks = 0
        var nextClicks = 0
        val modes = listOf(PlayMode.SEQUENCE, PlayMode.LOOP, PlayMode.SINGLE, PlayMode.SHUFFLE, PlayMode.HEART)
        val labels = listOf("顺序播放", "列表循环", "单曲循环", "随机播放", "心动模式")
        val actions = PlayerActions(
            togglePlayback = { ui.value = ui.value.copy(isPlaying = !ui.value.isPlaying) },
            previous = { previousClicks++ }, next = { nextClicks++ },
            cyclePlayMode = { ui.value = ui.value.copy(playMode = modes[(modes.indexOf(ui.value.playMode) + 1) % modes.size]) },
            setQueueOpen = { ui.value = ui.value.copy(isQueueDrawerOpen = it) },
            setPlayerOpen = { ui.value = ui.value.copy(isImmersivePlayerOpen = it) },
            setLyricsMode = { ui.value = ui.value.copy(isLyricsMode = it) }
        )
        rule.setContent { TwilightEchoTheme { PlayerAppContent(ui.value, actions) } }
        listOf("上一首", "开始播放", "下一首", "播放模式：顺序播放", "播放队列").forEach {
            rule.onNodeWithContentDescription(it).assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        shot("02-player-paused")
        rule.onNodeWithContentDescription("开始播放").performClick()
        rule.onNodeWithContentDescription("暂停播放").assertIsDisplayed()
        rule.runOnIdle { assertTrue(ui.value.isPlaying) }
        shot("03-player-playing")
        rule.onNodeWithContentDescription("暂停播放").performClick()
        rule.runOnIdle { assertFalse(ui.value.isPlaying) }
        rule.onNodeWithContentDescription("上一首").performClick()
        rule.onNodeWithContentDescription("下一首").performClick()
        rule.runOnIdle { assertEquals(1, previousClicks); assertEquals(1, nextClicks) }
        labels.forEachIndexed { index, label ->
            rule.onNodeWithContentDescription("播放模式：$label").performClick()
            rule.runOnIdle { assertEquals(modes[(index + 1) % modes.size], ui.value.playMode) }
            if (ui.value.playMode == PlayMode.SHUFFLE) shot("04-player-shuffle")
        }
        rule.onNodeWithContentDescription("播放队列").performClick()
        rule.onNodeWithTag("queueSheet").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭队列").performClick()
        rule.onNodeWithText("歌词", useUnmergedTree = true).performClick()
        rule.onNodeWithContentDescription("开始播放").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        shot("05-lyrics-controls")
        rule.runOnIdle { ui.value = ui.value.copy(isImmersivePlayerOpen = false) }
        rule.onNodeWithTag("miniPlayer").assertIsDisplayed()
        rule.onNodeWithContentDescription("播放").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("暂停").assertIsDisplayed()
        rule.runOnIdle { assertTrue(ui.value.isPlaying) }
        shot("06-mini-player")
    }
}
