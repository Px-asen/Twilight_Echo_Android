package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.feature.player.MainDestinationTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The 声区 tab over a fake Core: every state and flow of the page, with screenshots for visual review.
 * The compose rule pauses infinite animations, so bars and the wave are frozen here; ZonesMotionTest covers them.
 */
@RunWith(AndroidJUnit4::class)
class ZonesUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var world: ZoneWorld

    private fun start(world: ZoneWorld, densityMultiplier: Float = 1f) {
        this.world = world
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * densityMultiplier, density.fontScale)) {
                ZoneWorldContent(world)
            }
        }
        rule.waitForIdle()
    }

    /** Gives decodes on IO time to land, lets the frames they cause run, then takes the picture. */
    private fun shot(name: String, settleMs: Long = 900) {
        repeat(3) { rule.waitForIdle(); Thread.sleep(settleMs / 3) }
        rule.waitForIdle()
        Thread.sleep(120)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "zones-shots").apply { mkdirs() }
        val file = File(directory, "$name.png")
        assertTrue("screenshot $name", device.takeScreenshot(file))
        device.executeShellCommand("mkdir -p /sdcard/Pictures/TwilightZones")
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Pictures/TwilightZones/$name.png")
    }

    private fun tag(tag: String): SemanticsNodeInteraction = rule.onNodeWithTag(tag)
    /** Status chips and error notes do not merge: their words are a child of the tagged node. */
    private fun textIn(tag: String, text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag))).assertExists()
    private fun scrollWall(tag: String) = rule.onNodeWithTag("zonesScreen").performScrollToNode(hasTestTag(tag))
    private fun scrollDetail(tag: String) = rule.onNodeWithTag("zoneDetail").performScrollToNode(hasTestTag(tag))
    private fun scrollGroup(tag: String) = rule.onNodeWithTag("groupDetail").performScrollToNode(hasTestTag(tag))
    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    private fun exists(tag: String) = count(tag) > 0
    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        runCatching { rule.waitUntil(timeoutMs, condition) }.onFailure { throw AssertionError("timed out waiting for $what; log=${world.log}", it) }
    }

    private fun openZone(id: String) {
        scrollWall("zoneCard-$id")
        tag("zoneCard-$id").performClick()
        waitFor("detail of $id") { exists("zoneDetail") }
        rule.waitForIdle()
        waitFor("snapshot of $id") { world.ui.value.desktopAudio?.zone?.zoneId == id }
        rule.waitForIdle()
    }

    @Test fun wallShowsEveryWindowStateInCoreOrder() {
        start(ZoneWorld(windowStateZones()))
        rule.onNodeWithText("声区").assertIsDisplayed()
        rule.onNodeWithText("4 个声区 · 1 处在播").assertIsDisplayed()
        tag("zonesCreateGroup").assertIsEnabled()
        tag("nav-ZONES").assertIsDisplayed()
        tag("zoneCard-$LOCAL").assert(hasText("本机")).assert(hasText("本机输出")).assert(hasText("播放中")).assert(hasText("Golden Hour"))
        tag("zoneCard-$STUDY").assert(hasText("书房")).assert(hasText("输出桥 · 书房音箱")).assert(hasText("已暂停")).assert(hasText("A Quiet Return"))
        shot("01-wall")
        scrollWall("zoneCard-$KITCHEN")
        tag("zoneCard-$BEDROOM").assert(hasText("输出桥离线")).assert(hasText("输出桥 · 卧室小音箱")).assert(hasText("请在电脑上连接输出桥"))
        tag("zoneCard-$KITCHEN").assert(hasText("队列为空")).assert(hasText("可从其他声区接力到这里"))
        tag("zonePlayPause-$BEDROOM").assertDoesNotExist()
        shot("01b-wall-lower")
        assertTrue(world.log.toString(), "zonesActive:true" in world.log)
    }

    @Test fun groupAutoMixSettingsPauseAndReconcileWithoutReplayingOrChangingMemberDsp() {
        start(ZoneWorld(groupedZones(), listOf(playingGroup())))
        scrollWall("zoneGroupCard-$GROUP"); tag("zoneGroupCard-$GROUP").performClick()
        waitFor("group detail") { exists("groupDetail") }
        waitFor("member snapshot") { world.ui.value.desktopAudio?.group?.id == GROUP }
        scrollGroup("groupAutoMix-natural")
        tag("groupAutoMix-off").assertIsSelected()
        tag("groupAutoMix-natural").performClick()
        waitFor("mode saved") { world.groups().single().autoMixMode == "natural" && !world.ui.value.desktopAudioBusy }
        tag("groupAutoMix-natural").assertIsSelected()
        assertEquals("paused", world.groups().single().playback?.state)
        assertEquals(1, world.log.count { it.startsWith("groupAutoMix:") })
        assertFalse(world.log.any { it.startsWith("dsp:") || it.startsWith("groupTransport:") })
        scrollGroup("groupAutoMix"); shot("25-group-automix-natural")
        rule.runOnIdle { world.failNext = "同步组已变化，请刷新" }
        scrollGroup("groupAutoMix-dj"); tag("groupAutoMix-dj").performClick()
        waitFor("mode rejected") { world.ui.value.desktopAudioError != null && !world.ui.value.desktopAudioBusy }
        tag("groupAutoMix-natural").assertIsSelected()
        assertEquals(2, world.log.count { it.startsWith("groupAutoMix:") })
        scrollGroup("zoneVolume"); rule.onNodeWithText("同步组已变化，请刷新").assertExists()
    }

    @Test fun oldCoreGroupAutoMixCanBeDisabledButNotEnabled() {
        start(ZoneWorld(groupedZones(), listOf(playingGroup().copy(autoMixAvailable = false, autoMixMode = "natural"))))
        scrollWall("zoneGroupCard-$GROUP"); tag("zoneGroupCard-$GROUP").performClick()
        waitFor("group detail") { exists("groupDetail") }
        waitFor("member snapshot") { world.ui.value.desktopAudio?.group?.id == GROUP }
        scrollGroup("groupAutoMix-dj")
        tag("groupAutoMix-natural").assertIsNotEnabled(); tag("groupAutoMix-dj").assertIsNotEnabled()
        tag("groupAutoMix-off").assertIsEnabled().performClick()
        waitFor("mode off") { world.groups().single().autoMixMode == "off" && !world.ui.value.desktopAudioBusy }
        scrollGroup("groupAutoMix"); shot("26-group-automix-unavailable")
    }

    @Test fun wideScreenPlacesCardsTwoUp() {
        start(ZoneWorld(windowStateZones()), densityMultiplier = 0.62f)
        val first = tag("zoneCard-$LOCAL").getUnclippedBoundsInRoot()
        val second = tag("zoneCard-$STUDY").getUnclippedBoundsInRoot()
        assertEquals(first.top, second.top)
        assertTrue(second.left > first.right)
        shot("02-wall-wide")
    }

    @Test fun cardTransportActsOnItsOwnZone() {
        start(ZoneWorld(windowStateZones()))
        tag("zonePlayPause-$STUDY").assertHasClickAction().performClick()
        rule.waitForIdle()
        assertTrue(world.log.toString(), "transport:$STUDY:play" in world.log)
        rule.onNodeWithText("4 个声区 · 2 处在播").assertIsDisplayed()
        tag("zoneCard-$STUDY").assert(hasText("播放中"))
        shot("01c-wall-two-playing")
    }

    @Test fun zoneDetailPlaysAndPausesItsOwnZone() {
        start(ZoneWorld(windowStateZones()))
        openZone(LOCAL)
        textIn("zoneDetailStatus", "播放中")
        rule.onNodeWithText("FLAC · 96 kHz / 24 bit → WASAPI 独占 · 96 kHz / 24 bit").assertIsDisplayed()
        shot("03-zone-detail")
        scrollDetail("zoneOutputCard")
        rule.onNodeWithText("输出格式来自驱动回报；DAC 内部处理未知。").assertIsDisplayed()
        shot("04-zone-detail-output")
        scrollDetail("zoneDetailPlayPause")
        tag("zoneDetailPlayPause").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertTrue(world.log.toString(), "transport:$LOCAL:pause" in world.log)
        scrollDetail("zoneDetailWindow")
        textIn("zoneDetailStatus", "已暂停")
        shot("05-zone-detail-paused")
        scrollDetail("zoneBack")
        tag("zoneBack").performClick()
        waitFor("wall") { exists("zonesScreen") }
        rule.onNodeWithText("4 个声区 · 0 处在播").assertIsDisplayed()
        shot("05b-wall-selected-card")
    }

    @Test fun offlineZoneExplainsItselfAndCannotStart() {
        start(ZoneWorld(windowStateZones()))
        openZone(BEDROOM)
        textIn("zoneDetailStatus", "输出桥离线")
        tag("zoneDetailWindow").assertIsDisplayed()
        scrollDetail("zoneDetailPlayPause")
        tag("zoneDetailPlayPause").assertIsNotEnabled()
        tag("zonePrevious").assertIsNotEnabled()
        shot("06-zone-offline")
        scrollDetail("zoneOutputCard")
        rule.onNodeWithText("连接超时，正在重试").assertIsDisplayed()
        shot("06b-zone-offline-output")
    }

    @Test fun emptyLocalZoneOffersMusicAndABridgeZoneDoesNot() {
        val empty = windowStateZones().map { if (it.id == LOCAL) it.copy(track = null, playback = null, queue = null) else it }
        start(ZoneWorld(empty))
        tag("zoneCard-$LOCAL").assert(hasText("队列为空"))
        tag("zonePickMusic-$LOCAL").assertIsDisplayed()
        shot("06c-wall-empty-local")
        tag("zonePickMusic-$LOCAL").performClick()
        rule.waitForIdle()
        assertEquals(MainDestinationTab.LIBRARY, world.ui.value.activeMainTab)
    }

    @Test fun handoffAsksBeforeReplacingAndHoldsWhenTheTargetIsDown() {
        start(ZoneWorld(windowStateZones()))
        openZone(LOCAL)
        scrollDetail("zoneHandoff")
        tag("zoneHandoff").performClick()
        waitFor("handoff sheet") { exists("handoffSheet") }
        tag("handoffConfirm").assertIsNotEnabled()
        tag("handoffConfirm-reason").assertDoesNotExist()
        // A target with a queue needs its replacement confirmed.
        tag("handoffTarget-$STUDY").performClick()
        rule.waitForIdle()
        tag("handoffTarget-$STUDY").assertIsSelected()
        tag("handoffReplace").assertIsOff()
        tag("handoffConfirm").assertIsNotEnabled()
        rule.onNodeWithTag("handoffConfirm-reason").assert(hasText("请先确认替换目标队列"))
        tag("handoffResume").assertIsOn()
        shot("07-handoff")
        // A target whose bridge is down can receive the queue but cannot resume.
        tag("handoffTarget-$BEDROOM").performClick()
        rule.waitForIdle()
        tag("handoffResume").assertIsNotEnabled()
        tag("handoffResume").assertIsOff()
        rule.onNodeWithText("目标输出桥未连接，接力后保持暂停").assertIsDisplayed()
        tag("handoffReplace").performScrollTo().performClick()
        rule.waitForIdle()
        tag("handoffConfirm").performScrollTo().assertIsEnabled().assert(hasText("接力"))
        shot("08-handoff-blocked")
        tag("handoffConfirm").performClick()
        waitFor("sheet to close after success") { !exists("handoffSheet") }
        assertTrue(world.log.toString(), "handoff:$LOCAL->$BEDROOM:replace=true:resume=false" in world.log)
        rule.onNodeWithTag("zoneNotice", useUnmergedTree = true).assertExists()
        shot("08b-handoff-notice")
    }

    @Test fun handoffToAnEmptyPlaceNeedsNoConfirmationAndKeepsCoreErrorsInline() {
        start(ZoneWorld(windowStateZones()))
        openZone(LOCAL)
        scrollDetail("zoneHandoff")
        tag("zoneHandoff").performClick()
        waitFor("handoff sheet") { exists("handoffSheet") }
        tag("handoffTarget-$KITCHEN").performClick()
        rule.waitForIdle()
        assertEquals(0, count("handoffReplace"))
        tag("handoffConfirm").performScrollTo().assertIsEnabled().assert(hasText("接力并播放"))
        world.failNext = "目标声区的队列已变化，请刷新后重试"
        tag("handoffConfirm").performClick()
        waitFor("Core's message in the sheet") { exists("zoneSheetError") }
        textIn("zoneSheetError", "目标声区的队列已变化，请刷新后重试")
        tag("handoffSheet").assertExists()
        shot("08c-handoff-error")
        assertTrue(world.log.toString(), "handoff:$LOCAL->$KITCHEN:replace=false:resume=true" in world.log)
    }

    @Test fun processingSheetFollowsTheZoneAndSendsPatches() {
        start(ZoneWorld(windowStateZones()))
        openZone(STUDY)
        scrollDetail("zoneDsp")
        tag("zoneDsp").performClick()
        waitFor("DSP sheet") { exists("dspSheet") }
        tag("dspSwitch-dspEnabled").assertIsOn()
        tag("dspSwitch-directMode").assertIsOff()
        tag("dspSwitch-eqEnabled").assertIsOn()
        shot("09-dsp")
        tag("dspSwitch-eqEnabled").performClick()
        rule.waitForIdle()
        assertTrue(world.log.toString(), world.log.any { it == """dsp:{"eqEnabled":false}""" })
        tag("dspScene-room").performScrollTo().performClick()
        waitFor("scene dialog") { exists("dspSceneDialog") }
        rule.onNodeWithText("应用 房间校正？").assertIsDisplayed()
        shot("09b-dsp-scene-dialog")
        rule.onNodeWithText("应用并允许 PCM").performClick()
        rule.waitForIdle()
        assertTrue(world.log.toString(), "scene:room" in world.log)
        tag("dspScene-dsd").performScrollTo().assertIsNotEnabled()
        shot("09c-dsp-scenes")
    }

    @Test fun signalPathShowsLocalAndBridgeStages() {
        start(ZoneWorld(windowStateZones()))
        openZone(STUDY)
        scrollDetail("zoneSignalPath")
        tag("zoneSignalPath").performClick()
        waitFor("signal path") { exists("signalPathSheet") }
        textIn("signalPathVerdict", "已转换")
        rule.onNodeWithText("网络传输").assertExists()
        rule.onNodeWithText("书房音箱 · 输出桥").assertExists()
        tag("pathNode-transport").assert(hasText("Float32 PCM · 48 kHz"))
        shot("10-signal-path-bridge")
        tag("pathNode-device").performScrollTo().assert(hasText("Topping E50 · 48 kHz / 24 bit"))
        tag("pathNode-clock").assert(hasText("修正 +1.1 ppm"))
        shot("10b-signal-path-bridge-lower")
    }

    @Test fun signalPathForTheLocalZoneIsOneStage() {
        start(ZoneWorld(windowStateZones()))
        openZone(LOCAL)
        scrollDetail("zoneSignalPath")
        tag("zoneSignalPath").performClick()
        waitFor("signal path") { exists("signalPathSheet") }
        textIn("signalPathVerdict", "已处理")
        assertEquals(0, count("pathNode-transport"))
        shot("10c-signal-path-local")
    }

    @Test fun createGroupNeedsTwoMembersAndAQueueThenKeepsCoreOrder() {
        start(ZoneWorld(windowStateZones()))
        tag("zonesCreateGroup").performClick()
        waitFor("create sheet") { exists("groupCreateSheet") }
        tag("groupCreateConfirm").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("groupCreateConfirm-reason").assert(hasText("至少选择 2 个声区"))
        tag("groupName").performScrollTo().performTextReplacement("书房与厨房")
        // Selected out of Core's order on purpose: the command still follows the zone list.
        tag("groupMember-$KITCHEN").performScrollTo().performClick()
        rule.waitForIdle()
        tag("groupMember-$STUDY").performScrollTo().performClick()
        rule.waitForIdle()
        shot("11-group-create")
        rule.onNodeWithTag("groupCreateConfirm-reason").assert(hasText("请选择采用哪份队列"))
        tag("groupSource-$STUDY").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(0, count("groupSource-$KITCHEN"))
        tag("groupCreateConfirm").performScrollTo().assertIsEnabled().assert(hasText("建立并暂停"))
        shot("11b-group-create-ready")
        tag("groupCreateConfirm").performClick()
        waitFor("sheet to close") { !exists("groupCreateSheet") }
        assertTrue(world.log.toString(), "createGroup:书房与厨房:$STUDY@0.0,$KITCHEN@0.0:source=$STUDY" in world.log)
        rule.waitForIdle()
        waitFor("group card") { exists("zoneGroupCard-group-created") }
        scrollWall("zoneGroupCard-group-created")
        shot("11c-wall-after-create")
    }

    @Test fun createGroupIsUnavailableWithoutTwoFreeZones() {
        start(ZoneWorld(windowStateZones().take(1)))
        tag("zonesCreateGroup").assertIsNotEnabled()
        rule.onNodeWithText("在电脑上新建声区并连接输出桥后，可以在这里接力或编组。").assertIsDisplayed()
        shot("11d-wall-single-zone")
    }

    @Test fun groupShowsPhaseSkewAndMembersThenUngroupsBackToTheWall() {
        start(ZoneWorld(groupedZones(), listOf(playingGroup())))
        rule.onNodeWithText("4 个声区 · 2 处在播").assertIsDisplayed()
        tag("zoneGroupCard-$GROUP").assert(hasText("客厅与书房")).assert(hasText("同步播放中")).assert(hasText("2 个声区 · 偏差 0.42 ms"))
            .assert(hasText("Blue Hours")).assert(hasText("书房")).assert(hasText("客厅"))
        assertEquals(0, count("zoneCard-$STUDY"))
        shot("12-wall-group")
        scrollWall("zoneCard-$KITCHEN")
        tag("zoneCard-$KITCHEN").assert(hasText("需要处理")).assert(hasText("输出桥配置已失效，请在电脑上重新配对"))
        shot("12b-wall-error")
        scrollWall("zoneGroupCard-$GROUP")
        tag("zoneGroupCard-$GROUP").performClick()
        waitFor("group detail") { exists("groupDetail") }
        rule.waitForIdle()
        waitFor("a member's snapshot") { world.ui.value.desktopAudio?.group?.id == GROUP }
        rule.waitForIdle()
        textIn("groupDetailPhase", "同步播放中")
        rule.onNodeWithText("同步组 · 2 个声区").assertIsDisplayed()
        shot("13-group-detail")
        scrollGroup("groupAlignment")
        rule.onNodeWithText("偏差 0.42 ms · 观测范围 ±0.18 ms").assertExists()
        shot("14-group-alignment")
        scrollGroup("groupMemberRow-$BEDROOM")
        tag("groupMemberRow-$STUDY").assert(hasText("下游延迟 12 ms · DSP 延迟 2.5 ms"))
        tag("groupMemberRow-$BEDROOM").assert(hasText("下游延迟 0 ms")).assert(hasText("可同步"))
        shot("15-group-members")
        scrollGroup("zoneUngroup")
        tag("zoneUngroup").performClick()
        waitFor("ungroup dialog") { exists("ungroupDialog") }
        rule.onNodeWithText("解除 客厅与书房？").assertIsDisplayed()
        rule.onNodeWithText("所有成员停止，回到各自原来的队列并保持暂停，不保留组队列。").assertIsDisplayed()
        shot("16-ungroup")
        tag("ungroupConfirm").performClick()
        waitFor("the page to return to the wall") { exists("zonesScreen") }
        assertTrue(world.log.toString(), "removeGroup:$GROUP" in world.log)
        rule.waitForIdle()
        assertEquals(0, count("zoneGroupCard-$GROUP"))
        scrollWall("zoneCard-$STUDY")
        tag("zoneCard-$STUDY").assertIsDisplayed()
        shot("16b-wall-after-ungroup")
    }

    @Test fun groupTransportGoesThroughTheFollowedMember() {
        start(ZoneWorld(groupedZones(), listOf(playingGroup())))
        tag("zoneGroupCard-$GROUP").performClick()
        waitFor("group detail") { exists("groupDetail") }
        rule.waitForIdle()
        waitFor("a member's snapshot") { world.ui.value.desktopAudio?.group?.id == GROUP }
        rule.waitForIdle()
        scrollGroup("zoneDetailPlayPause")
        tag("zoneDetailPlayPause").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertTrue(world.log.toString(), "groupTransport:pause" in world.log)
        scrollGroup("groupDetailWindows")
        shot("13b-group-paused")
        // Choosing another member moves the snapshot that 处理 and 信号链 show.
        scrollGroup("groupMemberRow-$BEDROOM")
        tag("groupMemberRow-$BEDROOM").performClick()
        rule.waitForIdle()
        waitFor("the chosen member's snapshot") { world.ui.value.desktopAudio?.zone?.zoneId == BEDROOM }
    }

    @Test fun groupWithAnUnreadyMemberCannotStart() {
        val group = playingGroup().copy(playback = playingGroup().playback?.copy(state = "paused"), coordination = null)
        val zones = groupedZones().map { if (it.id == BEDROOM) it.copy(output = it.output?.copy(connected = false, error = "连接已断开")) else it }
        start(ZoneWorld(zones, listOf(group)))
        tag("zoneGroupCard-$GROUP").assert(hasText("2 个声区 · 有成员未就绪"))
        shot("13c-wall-group-unready")
        tag("zoneGroupCard-$GROUP").performClick()
        waitFor("group detail") { exists("groupDetail") }
        rule.waitForIdle()
        waitFor("a member's snapshot") { world.ui.value.desktopAudio?.group?.id == GROUP }
        rule.waitForIdle()
        scrollGroup("groupReadinessHint")
        tag("zoneDetailPlayPause").assertIsNotEnabled()
        shot("13d-group-unready")
    }

    @Test fun tabNeedsADesktopSessionAndADisconnectedOneSaysSo() {
        start(ZoneWorld(windowStateZones(), connected = false))
        rule.onNodeWithText("电脑未连接").assertIsDisplayed()
        assertEquals(0, count("zonesCreateGroup"))
        assertEquals(0, count("zoneCard-$LOCAL"))
        tag("nav-ZONES").assertIsDisplayed()
        shot("17-disconnected")
    }

    @Test fun tabIsHiddenWithoutADesktopSession() {
        start(ZoneWorld(windowStateZones(), standalone = true))
        rule.waitForIdle()
        assertEquals(0, count("nav-ZONES"))
        assertEquals(0, count("zonesScreen"))
        assertNotEquals(0, count("nav-LIBRARY"))
        assertFalse(world.log.toString(), "zonesActive:true" in world.log)
    }
}
