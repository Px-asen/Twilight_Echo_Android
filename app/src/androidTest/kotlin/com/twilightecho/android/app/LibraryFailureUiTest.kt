package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryFailureUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun aFailedDesktopReadIsNotAnEmptyLibraryAndRetryKeepsExistingTracks() {
        var retries = 0
        val state = mutableStateOf(PlayerUiState(activeMainTab = MainDestinationTab.LIBRARY,
            librarySource = LibrarySource.Desktop("desktop", "library", "http://192.168.1.2:18492", "电脑"),
            isLoadingLibrary = false, libraryError = "电脑曲库暂时无法读取"))
        rule.setContent { TwilightEchoTheme(darkTheme = false) {
            PlayerAppContent(state.value, PlayerActions(refreshLibrary = { retries++ }))
        } }
        rule.onNodeWithText("音乐库未载入").assertIsDisplayed()
        rule.onNodeWithText("电脑曲库暂时无法读取").assertIsDisplayed()
        rule.onNodeWithText("电脑曲库还是空的").assertDoesNotExist()
        rule.onNodeWithText("0 首歌曲 · 电脑曲库").assertDoesNotExist()
        rule.onNodeWithText("重试").assertIsDisplayed()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(directory, "desktop-library-read-error.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        rule.onNodeWithText("重试").performClick()
        assertEquals(1, retries)
        rule.runOnIdle { state.value = state.value.copy(
            libraryTracks = listOf(CoreTrackView("remote-track:1", title = "已经载入的歌曲")), libraryTrackCount = 1) }
        rule.onNodeWithText("已经载入的歌曲").assertIsDisplayed()
        rule.onNodeWithText("加载失败，点击重试").performClick()
        assertEquals(2, retries)
        rule.onNodeWithText("电脑曲库还是空的").assertDoesNotExist()
    }
}
