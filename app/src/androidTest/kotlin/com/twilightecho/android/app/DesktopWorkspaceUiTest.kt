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
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Pure UI fixtures: no server credentials, media queue mutations or DAC access. */
@RunWith(AndroidJUnit4::class)
class DesktopWorkspaceUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val primary = DesktopQueueDestination(DesktopQueueTarget("zone", "local", 2), "书房", DesktopZoneQueue("q-a", 4, "one"))
    private val bedroom = DesktopQueueDestination(DesktopQueueTarget("zone", "zone-b", 3), "卧室", null)
    private fun shot(name: String) {
        rule.waitForIdle(); Thread.sleep(250)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(File(context.getExternalFilesDir(null), "workspace-shots").apply { mkdirs() }, "$name.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
    }
    @Test fun catalogPickerSelectsSourcesAndConfirmsTheChosenDestinationBeforePlayback() {
        val source = CatalogSource("source-a", "local", "recording-a", track = CatalogSourceTrack("夜航", "北岸", "灯塔", format = "FLAC"))
        val entity = CatalogEntity("recording-a", "recording", "夜航", "北岸", 1)
        val state = mutableStateOf(DesktopQueueState(open = true, destinations = listOf(primary, bedroom), destination = primary))
        var confirmed: Pair<String, Boolean>? = null
        val actions = DesktopQueueActions(
            destination = { state.value = state.value.copy(destination = it) },
            search = { state.value = state.value.copy(search = it) },
            submit = { state.value = state.value.copy(catalog = CatalogPage(5, 1, items = listOf(entity))) },
            entity = { state.value = state.value.copy(entity = it, sources = CatalogSourcePage(5, 1, items = listOf(source))) },
            toggle = { state.value = state.value.copy(selected = listOf(it.id), selectionRevision = 5) },
            prepare = { state.value = state.value.copy(pending = catalogQueueSelection(5, state.value.selected)) },
            confirm = { confirmed = state.value.destination!!.name to it; state.value = state.value.copy(pending = null, notice = "已更新「卧室」") }
        )
        rule.setContent { TwilightEchoTheme { PlayerAppContent(PlayerUiState(desktopQueue = state.value), PlayerActions(desktopQueue = actions)) } }
        rule.onNodeWithTag("sessionDestination").performClick()
        rule.onNodeWithText("卧室").performClick()
        rule.onNodeWithTag("sessionMusicSearch").performTextInput("夜航")
        rule.onAllNodesWithText("搜索", useUnmergedTree = false).filterToOne(hasAnyAncestor(hasTestTag("desktopSessionQueue"))).performClick()
        rule.onAllNodesWithText("夜航").onLast().performClick()
        rule.onAllNodesWithText("夜航").onLast().performClick()
        rule.onNodeWithTag("sessionPrepareQueue").assertIsEnabled()
        shot("01-catalog-picker")
        rule.onNodeWithTag("sessionPrepareQueue").performClick()
        rule.onNodeWithText("载入到「卧室」").assertIsDisplayed()
        assertNull(confirmed)
        shot("02-destination-confirmation")
        rule.onNodeWithText("仅载入，保持暂停").performClick()
        rule.runOnIdle { assertEquals("卧室" to false, confirmed) }
    }
    @Test fun serverDspAssetsProtectReferencesAndSceneApplicationRequiresConfirmation() {
        val ir = DesktopDspAsset("ir-one", "impulseResponse", "客厅校正.wav", "客厅校正.wav", 123456, 1)
        val scene = DesktopManagedScene("room", "客厅校正", nodes = listOf(DesktopDspNode("ir", "convolver", true, ir.id)))
        val plugin = DesktopVst3Plugin("plugin", "Room EQ", "Studio", "1.0", "available", 12)
        val library = DesktopDspLibrary(3, DesktopDspAssets(1, items = listOf(ir)), DesktopVst3Plugins(1, items = listOf(plugin)),
            listOf(scene), true, searchRootCount = 1, helpers = DesktopVst3Helpers(true, true, true))
        val state = mutableStateOf(DesktopDspLibraryState(open = true, zoneId = "zone-room", subject = "客厅", library = library))
        var edited: JsonObject? = null
        val actions = DesktopDspLibraryActions(edit = { edited = it })
        rule.setContent { TwilightEchoTheme { PlayerAppContent(PlayerUiState(desktopDspLibrary = state.value), PlayerActions(desktopDspLibrary = actions)) } }
        rule.onNodeWithText("删除", useUnmergedTree = false).assertIsNotEnabled()
        rule.onNodeWithText("绑定").performClick()
        rule.onNodeWithText("客厅校正").performClick()
        rule.onNodeWithText("保存到场景").performClick()
        assertNull(edited)
        rule.onNodeWithText("确认").performClick()
        rule.runOnIdle { assertEquals("bindImpulse", edited!!["kind"]!!.jsonPrimitive.content) }
        shot("07-dsp-assets")
        rule.onNodeWithText("场景", useUnmergedTree = false).performClick()
        rule.onNodeWithText("应用").performClick()
        shot("08-dsp-apply-confirmation")
        rule.onNodeWithText("确认").performClick()
        rule.runOnIdle { assertEquals("applyScene", edited!!["kind"]!!.jsonPrimitive.content) }
        rule.onNodeWithText("插件", useUnmergedTree = false).performClick()
        rule.onNodeWithTag("dspScan").assertIsEnabled()
        shot("09-dsp-plugins")
        rule.runOnIdle { state.value = state.value.copy(needsRefresh = true, error = "连接中断，请刷新核对") }
        rule.onNodeWithTag("dspScan").assertIsNotEnabled()
    }
    @Test fun profilePreviewRequiresConfirmationAndParameterOverridesStayExplicit() {
        val library = DesktopDspLibrary(4, scenes = listOf(DesktopManagedScene("room", "客厅校正",
            nodes = listOf(DesktopDspNode("vst", "vst3Plugin", true, catalogId = "plugin")))))
        val preview = DesktopProfilePreview("preview", 4, "客厅配置包", listOf(DesktopProfileScene("夜间", 2), DesktopProfileScene("日间", 3)), 1, 800000)
        val state = mutableStateOf(DesktopDspLibraryState(open = true, zoneId = "zone-room", subject = "客厅", library = library, preview = preview))
        var imports = 0; var written: Pair<Long, Double?>? = null
        val actions = DesktopDspLibraryActions(importProfile = { imports++; state.value = state.value.copy(preview = null) },
            parameters = { scene, node -> state.value = state.value.copy(parameters = DesktopParameterPage(4, scene, node, "Room EQ", 2, 0,
                listOf(DesktopVst3Parameter(0, "Mix", defaultNormalizedValue = 0.5), DesktopVst3Parameter(1, "Latency", defaultNormalizedValue = 0.0, flags = 2)))) },
            parameter = { id, value -> written = id to value }, closeParameters = { state.value = state.value.copy(parameters = null) })
        rule.setContent { TwilightEchoTheme { PlayerAppContent(PlayerUiState(desktopDspLibrary = state.value), PlayerActions(desktopDspLibrary = actions)) } }
        rule.onNodeWithTag("dspProfileConfirm").assertIsEnabled()
        assertEquals(0, imports); shot("10-profile-preview")
        rule.onNodeWithTag("dspProfileConfirm").performClick()
        rule.runOnIdle { assertEquals(1, imports) }
        rule.onNodeWithText("场景", useUnmergedTree = false).performClick()
        rule.onNodeWithText("参数").performClick()
        rule.onAllNodesWithText("预设值（未读取）").onFirst().assertExists()
        rule.onNodeWithText("只读").assertIsDisplayed()
        assertNull(written)
        rule.onNodeWithText("设置覆盖值").performClick()
        shot("11-vst3-parameters")
        rule.onNodeWithText("保存 50.0%").performClick()
        rule.runOnIdle { assertEquals(0L to 0.5, written) }
    }
    @Test fun repeatedQueueTracksKeepSeparateOccurrenceActions() {
        val rows = listOf("one", "two").mapIndexed { index, id -> DesktopSessionQueueItem(index, id,
            CoreTrackView(id = "same-song", title = "夜航", artist = "北岸")) }
        var moved: JsonObject? = null
        rule.setContent { TwilightEchoTheme { PlayerAppContent(PlayerUiState(desktopQueue = DesktopQueueState(open = true,
            destinations = listOf(primary), destination = primary, items = rows, total = 2)),
            PlayerActions(desktopQueue = DesktopQueueActions(edit = { moved = it }))) } }
        rule.onNodeWithText("队列 · 2").performClick()
        shot("03-occurrence-queue")
        rule.onAllNodesWithText("下移").onFirst().performClick()
        rule.runOnIdle { assertEquals("one", moved!!["entryId"]!!.jsonPrimitive.content); assertEquals(JsonNull, moved!!["beforeEntryId"]) }
    }
    @Test fun focusBuilderAndSavedConditionsAreUsableWithoutReturningToTheComputer() {
        val empty = buildJsonObject { put("tagsRevision", 1) }
        val state = mutableStateOf(DesktopLibraryWorkspaceState(open = true, host = "书房电脑",
            saved = DesktopSavedFocusPage(1, 0, 0), focus = DesktopFocusPage(empty, 0, 0)))
        var queried: JsonObject? = null
        val actions = DesktopLibraryWorkspaceActions(query = { state.value = state.value.copy(query = it) }, focus = { queried = state.value.query })
        rule.setContent { TwilightEchoTheme { PlayerAppContent(PlayerUiState(desktopWorkspace = state.value), PlayerActions(desktopWorkspace = actions)) } }
        rule.onNodeWithText("添加条件").performClick()
        rule.onNodeWithText("个人评分下限").performClick()
        rule.onNodeWithTag("desktopWorkspaceItems").performScrollToNode(hasTestTag("desktopFocusQuery"))
        rule.onNodeWithTag("desktopFocusQuery").performClick()
        rule.runOnIdle { assertTrue(queried!!.toString().contains("gteStars")) }
        shot("04-focus-builder")
    }
    @Test fun tagMembersAndServerPlaylistRowsExposeTheirOwnManagementActions() {
        val token = buildJsonObject { put("tagsRevision", 2) }
        val tag = DesktopTag("tag-a", "深夜", 1, 2, 1, 0)
        val entity = CatalogEntity("recording-a", "recording", "夜航", "北岸")
        val playlist = DesktopServerPlaylist("playlist-a", "夜间航线", 2)
        val state = mutableStateOf(DesktopLibraryWorkspaceState(open = true, host = "书房电脑", tab = DesktopWorkspaceTab.TAGS,
            tags = DesktopTagsPage(token, 0, 1, listOf(tag)), playlists = DesktopServerPlaylists(3, 0, 1, listOf(playlist))))
        var removed: String? = null
        val actions = DesktopLibraryWorkspaceActions(
            tab = { state.value = state.value.copy(tab = it) },
            tag = { state.value = state.value.copy(tag = it?.let { DesktopTagMembers(token, it, 0, 1, listOf(entity)) }) },
            removeTagMember = { removed = it },
            playlist = { state.value = state.value.copy(playlist = it, members = DesktopServerPlaylistMembers(3, 0, 2,
                listOf(DesktopServerPlaylistMember("member-a", 0, CoreTrackView(id = "track-a", title = "夜航", artist = "北岸")),
                    DesktopServerPlaylistMember("member-b", 1, CoreTrackView(id = "track-b", title = "归途", artist = "北岸"))))) }
        )
        rule.setContent { TwilightEchoTheme { PlayerAppContent(PlayerUiState(desktopWorkspace = state.value), PlayerActions(desktopWorkspace = actions)) } }
        rule.onNodeWithText("深夜").performClick()
        shot("05-tag-members")
        rule.onNodeWithText("移除标签").performClick()
        rule.runOnIdle { assertEquals("recording-a", removed) }
        rule.onNodeWithText("Server 歌单").performClick()
        rule.onNodeWithText("夜间航线").performClick()
        rule.onNodeWithText("添加歌曲").assertIsEnabled()
        rule.onNodeWithText("播放到…").assertIsEnabled()
        shot("06-server-playlist")
    }
}
