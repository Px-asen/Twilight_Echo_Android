package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.playbackhost.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineDownloadsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun externalAddressIsStoredPerPairedComputerAndConnectionKeepsItsIdentity() {
        val device = DesktopCoreDevice("server-id", "书房电脑", "https://192.168.1.10:18492", trusted = true)
        val state = mutableStateOf(PlayerUiState(activeMainTab = MainDestinationTab.SETTINGS, desktopDiscovery = DesktopDiscoveryState(devices = listOf(device))))
        var target: DesktopCoreDevice? = null
        val actions = PlayerActions(desktopExternalUrl = { id, address -> state.value = state.value.copy(preferences = state.value.preferences.copy(desktopExternalUrls = mapOf(id to address))) },
            connectExternalDesktop = { target = it })
        rule.setContent { TwilightEchoTheme { PlayerAppContent(state.value, actions) } }
        rule.onNodeWithText("连接电脑").performClick()
        rule.onNodeWithText("外网地址").performScrollTo().performClick()
        rule.onNodeWithTag("externalAddress").performTextInput("https://music.example.com:18492")
        rule.onNodeWithText("保存").performClick()
        rule.onNodeWithTag("externalConnect-server-id").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("server-id", target!!.id) }
        rule.waitForIdle(); Thread.sleep(300)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(File(context.getExternalFilesDir(null), "mobile-shots").apply { mkdirs() }, "external-address.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
    }
    @Test fun networkDefaultsAndRemovalAreVisibleAndExplicit() {
        val item = OfflineDownload("fixture", DesktopMediaReference("server", "library", "source", "v1"),
            CoreTrackView("track", title = "夜航", artist = "北岸"), "书房", OfflineDownloadStatus.READY, 24_000_000, 24_000_000)
        val state = mutableStateOf(PlayerUiState(offlineDownloadsOpen = true, offlineDownloads = OfflineDownloadState(listOf(item))))
        var action: String? = null
        val actions = PlayerActions(offlineDownloadAction = { _, value -> action = value },
            mobileNetworkPreferences = { state.value = state.value.copy(preferences = state.value.preferences.copy(mobileNetwork = it)) })
        rule.setContent { TwilightEchoTheme { PlayerAppContent(state.value, actions) } }
        rule.onNodeWithText("网络与存储").performClick()
        rule.onNodeWithTag("meteredDownloads").assertIsOff()
        rule.onNodeWithTag("meteredDownloads").performClick()
        rule.onNodeWithTag("meteredDownloads").assertIsOn()
        rule.waitForIdle(); Thread.sleep(300)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(File(context.getExternalFilesDir(null), "mobile-shots").apply { mkdirs() }, "offline-downloads.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
        rule.onNodeWithText("移除").performClick()
        assertNull(action)
        rule.onNodeWithText("取消").performClick()
        assertNull(action)
        rule.onNodeWithText("播放").performClick()
        assertEquals("play", action)
    }
}
