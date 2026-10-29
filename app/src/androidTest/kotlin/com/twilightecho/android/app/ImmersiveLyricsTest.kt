package com.twilightecho.android.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Actual Compose rendering, with deterministic playback inputs and the same embedded cover loader. */
@RunWith(AndroidJUnit4::class)
class ImmersiveLyricsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>
    private var fontScale by mutableFloatStateOf(1f)
    private var densityMultiplier by mutableFloatStateOf(1f)
    private var lastSeek: Double? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

    private val text = listOf("把暮色留在窗边", "听一阵缓慢的风", "唱片转过安静的房间",
        "你说日落还很远", "让一首歌慢慢走远", "在回声里遇见昨天", "灯火从街角亮起来",
        "我们暂时忘记时间", "让所有喧闹轻轻落下", "等月光走过山间", "再听一遍最初的旋律", "直到下一次日落")

    private fun sample(): PlayerUiState {
        val name = "01-Golden-Hour.flac"
        val file = File(instrumentation.targetContext.cacheDir, "lyrics-qa/$name")
        file.parentFile!!.mkdirs()
        instrumentation.context.assets.open("music/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        val track = CoreTrackView("lyrics-qa", title = "Golden Hour", artist = "Paper Lantern", source = file.toURI().toString(), duration = 72.0)
        return PlayerUiState(currentTrack = track, durationSeconds = 72.0, displayedPositionSeconds = 24.0,
            activeLyricIndex = 4, isImmersivePlayerOpen = true, isLyricsMode = true, isLoadingLibrary = false,
            libraryTracks = listOf(track), libraryTrackCount = 1,
            currentTrackBookmarks = listOf(CoreBookmarkView("qa-bookmark", track.id, 12.0, "测试书签", 0L)),
            currentLyrics = CoreLyricsView(track.id, lines = text.mapIndexed { index, line ->
                CoreLyricLine(index * 6000L, line, if (index % 2 == 0) "Let the evening stay a little longer" else null)
            }))
    }

    private fun desktopArtworkState(state: PlayerUiState, image: String? = null): PlayerUiState {
        val track = state.currentTrack!!.copy(id = "remote-track:lyrics-qa", source = null)
        return state.copy(currentTrack = track, libraryTracks = listOf(track),
            currentLyrics = state.currentLyrics?.copy(trackId = track.id),
            currentTrackBookmarks = state.currentTrackBookmarks.map { it.copy(trackId = track.id) },
            operatingMode = CoreOperatingMode.RemoteDesktopCore("http://artwork-qa", "封面测试"),
            librarySource = LibrarySource.Desktop("qa", "qa-library", "http://artwork-qa", "封面测试"),
            desktopLibrary = state.desktopLibrary.copy(artwork = if (image == null) emptyMap() else mapOf(track.id to image)))
    }

    private fun start(restoration: StateRestorationTester? = null, transform: (PlayerUiState) -> PlayerUiState = { it }) {
        ui = mutableStateOf(transform(sample()))
        val actions = PlayerActions(
            setLyricsMode = { ui.value = ui.value.copy(isLyricsMode = it) },
            setPlayerOpen = { ui.value = ui.value.copy(isImmersivePlayerOpen = it) },
            setQueueOpen = { ui.value = ui.value.copy(isQueueDrawerOpen = it) },
            togglePlayback = { ui.value = ui.value.copy(isPlaying = !ui.value.isPlaying) },
            toggleFavorite = { ui.value = ui.value.copy(isCurrentTrackFavorite = !ui.value.isCurrentTrackFavorite) },
            seek = { position ->
                lastSeek = position
                ui.value = ui.value.copy(displayedPositionSeconds = position,
                    activeLyricIndex = ui.value.currentLyrics!!.lines.indexOfLast { it.timeMs <= position * 1000 })
            }
        )
        contentActions = actions
        if (restoration == null) rule.setContent { TestContent() }
        else restoration.setContent { TestContent() }
        rule.waitForIdle()
    }

    private lateinit var contentActions: PlayerActions

    @Composable private fun TestContent() {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * densityMultiplier, fontScale)) {
            TwilightEchoTheme { PlayerAppContent(ui.value, contentActions) }
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(450) // async artwork load and finite transitions
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "lyrics-screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(folder, "$name.png")))
    }

    private fun assertAnchor(index: Int) {
        rule.waitForIdle()
        val stage = rule.onNodeWithTag("lyrics").fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag("lyric-line-$index").assertIsDisplayed().assertIsSelected().fetchSemanticsNode().boundsInRoot
        assertTrue("当前行应位于歌词区域的三分之一处：row=$row stage=$stage",
            abs(row.top - (stage.top + stage.height / 3)) < 12f)
    }

    private fun scrollPosition(): Float = rule.onNodeWithTag("lyrics").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun browse() {
        rule.onNodeWithTag("lyrics").performTouchInput { swipeUp(durationMillis = 800) }
        rule.onNodeWithTag("resumeLyrics").assertIsDisplayed()
    }

    private fun assertEmptyTitleContrast() {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("暂无歌词").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("透明播放器表面上的空状态标题也必须使用浅色前景", layouts.single().layoutInput.style.color.luminance() > 0.8f)
    }

    @After fun restoreDevice() {
        rule.mainClock.autoAdvance = true
        device.setOrientationNatural()
        device.unfreezeRotation()
    }

    @Test fun focusAlignmentClickAndInterruptedLineChanges() {
        start()
        rule.onNodeWithTag("lyricsBlurredBackdrop").assertIsDisplayed()
        assertAnchor(4)
        shot("01-lyrics-cover")
        rule.onNodeWithTag("lyric-line-5").performClick()
        assertEquals(30.0, lastSeek!!, 0.001)
        assertAnchor(5)
        rule.mainClock.autoAdvance = false
        for (index in 6..8) {
            rule.runOnUiThread { ui.value = ui.value.copy(activeLyricIndex = index, displayedPositionSeconds = index * 6.0) }
            rule.mainClock.advanceTimeBy(64)
        }
        rule.mainClock.autoAdvance = true
        assertAnchor(8)
        shot("02-lyrics-retarget")
    }

    @Test fun progressOnlyTicksReachTheSeekBarAndLyricsWithoutResettingBrowsing() {
        start()
        rule.onNodeWithText("0:24").assertIsDisplayed()
        rule.runOnIdle { ui.value = ui.value.copy(sequence = ui.value.sequence + 1,
            displayedPositionSeconds = 25.0) }
        rule.onNodeWithText("0:25").assertIsDisplayed()
        assertAnchor(4)
        browse()
        val position = scrollPosition()
        rule.runOnIdle { ui.value = ui.value.copy(sequence = ui.value.sequence + 1,
            displayedPositionSeconds = 36.0, activeLyricIndex = 6) }
        rule.onNodeWithText("0:36").assertIsDisplayed()
        assertEquals(position, scrollPosition(), 0.01f)
        rule.onNodeWithTag("resumeLyrics").performClick()
        assertAnchor(6)
        rule.onNodeWithContentDescription("更多播放操作").performClick()
        rule.onNode(hasText("添加当前位置书签") and hasText("0:36")).assertIsDisplayed()
    }

    @Test fun libraryScrollSurvivesTheOpaquePlayerAndPlaybackTicks() {
        start { state ->
            val tracks = listOf(state.currentTrack!!) + List(500) { CoreTrackView("scroll-qa-$it", title = "歌曲 $it") }
            state.copy(activeMainTab = MainDestinationTab.LIBRARY, isImmersivePlayerOpen = false,
                libraryTracks = tracks, allTracks = tracks, libraryTrackCount = tracks.size)
        }
        rule.onNodeWithTag("librarySongs").performScrollToIndex(80)
        fun position() = rule.onNodeWithTag("librarySongs").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = position()
        rule.onNodeWithTag("miniPlayer").performClick()
        assertAnchor(4)
        rule.runOnIdle { ui.value = ui.value.copy(sequence = ui.value.sequence + 1,
            displayedPositionSeconds = 30.0, activeLyricIndex = 5) }
        assertAnchor(5)
        rule.onNodeWithContentDescription("收起播放器").performClick()
        assertEquals(before, position(), 0.01f)
        rule.onNodeWithTag("track-scroll-qa-80").assertIsDisplayed()
    }

    @Test fun manualBrowsingSurvivesModeAndPlayerRoundTripsAndExplicitSeeksResume() {
        start()
        browse()
        val position = scrollPosition()
        rule.runOnIdle { ui.value = ui.value.copy(activeLyricIndex = 6, displayedPositionSeconds = 36.0) }
        rule.waitForIdle()
        assertEquals(position, scrollPosition(), 0.01f)
        shot("03-manual-browsing")
        rule.onNodeWithText("封面", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("immersiveCoverBackdrop").assertIsDisplayed()
        assertCompleteCoverLayout()
        shot("04-complete-cover")
        rule.onNodeWithText("歌词", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("resumeLyrics").assertIsDisplayed()
        assertEquals(position, scrollPosition(), 0.01f)
        rule.onNodeWithContentDescription("收起播放器").performClick()
        rule.onNodeWithTag("miniPlayer").performClick()
        rule.onNodeWithTag("resumeLyrics").assertIsDisplayed()
        assertEquals(position, scrollPosition(), 0.01f)
        rule.onNodeWithTag("resumeLyrics").performClick()
        assertAnchor(6)
        browse()
        rule.onNodeWithTag("seek").performSemanticsAction(SemanticsActions.SetProgress) { it(48f) }
        rule.onNodeWithTag("resumeLyrics").assertDoesNotExist()
        assertAnchor(8)
        rule.onNodeWithContentDescription("更多播放操作").performClick()
        rule.onNodeWithText("测试书签").performClick()
        assertAnchor(2)
        rule.onNodeWithContentDescription("收藏歌曲").performClick()
        rule.onNodeWithContentDescription("取消收藏").assertIsDisplayed()
        rule.onNodeWithContentDescription("播放队列").performClick()
        rule.onNodeWithTag("queueSheet").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭队列").performClick()
        assertAnchor(2)
    }

    @Test fun lyricReplacementTrackAndCoreChangesResetManualOwnership() {
        start()
        browse()
        rule.runOnIdle { ui.value = ui.value.copy(currentLyrics = ui.value.currentLyrics!!.copy(
            lines = ui.value.currentLyrics!!.lines.map { it.copy(text = "新歌词 · ${it.text}") })) }
        rule.onNodeWithTag("resumeLyrics").assertDoesNotExist()
        assertAnchor(4)
        browse()
        rule.runOnIdle { ui.value = ui.value.copy(operatingMode = CoreOperatingMode.RemoteDesktopCore("http://qa-core")) }
        rule.onNodeWithTag("resumeLyrics").assertDoesNotExist()
        assertAnchor(4)
        browse()
        rule.runOnIdle { ui.value = ui.value.copy(currentTrack = ui.value.currentTrack!!.copy(id = "new-track"),
            currentLyrics = ui.value.currentLyrics!!.copy(trackId = "new-track")) }
        rule.onNodeWithTag("resumeLyrics").assertDoesNotExist()
        assertAnchor(4)
    }

    @Test fun savedViewportRestoresBrowsingPositionAndOwnership() {
        val restoration = StateRestorationTester(rule)
        start(restoration)
        browse()
        val position = scrollPosition()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("resumeLyrics").assertIsDisplayed()
        assertEquals(position, scrollPosition(), 0.01f)
        rule.onNodeWithTag("resumeLyrics").performClick()
        assertAnchor(4)
    }

    @Test fun untimedEmptyAndMissingArtworkStayUsable() {
        start { it.copy(currentTrack = it.currentTrack!!.copy(source = null),
            currentLyrics = it.currentLyrics!!.copy(synced = false)) }
        rule.onNodeWithTag("lyric-line-0").assertIsNotSelected().assertHasNoClickAction()
        shot("05-untimed-missing-art")
        rule.onNodeWithTag("lyrics").performTouchInput { swipeUp() }
        rule.onNodeWithTag("resumeLyrics").assertDoesNotExist()
        assertNull(lastSeek)
        rule.runOnIdle { ui.value = ui.value.copy(currentLyrics = null) }
        rule.onNodeWithText("暂无歌词").assertIsDisplayed()
        assertEmptyTitleContrast()
        rule.onNodeWithText("导入歌词").assertIsDisplayed()
        shot("06-empty-lyrics")
    }

    /** Run on both the default emulator and its isolated 1080x1680 short-screen override. */
    @Test fun smallLargeTextAndLandscapeKeepLyricsAndTransportUsable() {
        fontScale = 1.5f
        densityMultiplier = 1.14f
        start { it.copy(currentTrack = it.currentTrack!!.copy(title = "这是一首很长的歌曲名称 — The Evening Never Ends"),
            currentLyrics = it.currentLyrics!!.copy(lines = it.currentLyrics!!.lines.mapIndexed { index, line ->
                if (index == 4) line.copy(text = "把漫长的句子交给夜晚，让每一个字都能在屏幕上自然地舒展开来") else line })) }
        rule.onNodeWithTag("lyrics").assertIsDisplayed()
        val stage = rule.onNodeWithTag("lyrics").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("短竖屏不应把歌词挤到窄列", stage.width > root.width * 0.8f)
        rule.onNodeWithContentDescription("上一首").assertIsDisplayed()
        rule.onNodeWithContentDescription("开始播放").assertIsDisplayed()
        rule.onNodeWithContentDescription("下一首").assertIsDisplayed()
        shot("07-short-large-text")
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { ui.value = ui.value.copy(activeLyricIndex = 5, displayedPositionSeconds = 30.0) }
        rule.mainClock.advanceTimeBy(64)
        val next = rule.onAllNodesWithTag("lyric-line-5").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        assertTrue("超长行的下一行应经过滚动，而不是直接跳到锚点",
            next == null || abs(next.top - (stage.top + stage.height / 3)) > 12f)
        rule.mainClock.autoAdvance = true
        assertAnchor(5)
        rule.runOnIdle { ui.value = ui.value.copy(activeLyricIndex = 4, displayedPositionSeconds = 24.0) }
        rule.runOnIdle { fontScale = 1f; densityMultiplier = 1f }
        device.setOrientationLeft()
        rule.waitUntil(10000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        // MainActivity reinstalls content in onCreate; the plain ComponentActivity test host does not.
        rule.activityRule.scenario.onActivity { it.setContent { TestContent() } }
        rule.waitForIdle()
        rule.onNodeWithTag("lyrics").assertIsDisplayed()
        rule.onNodeWithContentDescription("下一首").assertIsDisplayed()
        shot("08-landscape")
        device.setOrientationNatural()
        rule.waitUntil(10000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        rule.activityRule.scenario.onActivity { it.setContent { TestContent() } }
        rule.runOnIdle { fontScale = 1.5f; densityMultiplier = 1.14f; ui.value = ui.value.copy(currentLyrics = null) }
        rule.onNodeWithText("导入歌词").performScrollTo().assertIsDisplayed()
        assertEmptyTitleContrast()
        rule.onNodeWithContentDescription("下一首").assertIsDisplayed()
        shot("13-short-empty-lyrics")
    }

    @Test fun completeCoverAndBlurredLyricsHaveSeparateLayers() {
        val image = artworkData(2)
        start { desktopArtworkState(it, image).copy(isLyricsMode = false) }
        rule.waitUntil(10000) { edgeEnergy(rule.onNodeWithTag("fullCover").captureToImage()) > 0.002f }
        shot("14-complete-detailed-cover")
        val background = rule.onNodeWithTag("immersiveCoverBackdrop")
        val bounds = background.fetchSemanticsNode().boundsInRoot
        val player = rule.onNodeWithTag("immersivePlayer").fetchSemanticsNode().boundsInRoot
        assertEquals(player.width, bounds.width, 1f)
        assertEquals(player.height, bounds.height, 1f)
        assertCompleteCoverLayout()
        rule.onNodeWithTag("lyrics").assertDoesNotExist()
        val clear = rule.onNodeWithTag("fullCover").captureToImage()
        val clearDetail = edgeEnergy(clear)
        val coverBackdropDetail = edgeEnergy(background.captureToImage())
        assertTrue("封面主体清晰，背景只能柔焦延展，不能替代完整封面：clear=$clearDetail backdrop=$coverBackdropDetail",
            coverBackdropDetail < clearDetail * 0.35f)
        rule.onNodeWithText("歌词", useUnmergedTree = true).performClick()
        shot("15-blurred-detailed-lyrics")
        rule.onNodeWithTag("immersiveCoverBackdrop").assertDoesNotExist()
        rule.onNodeWithTag("fullCover").assertDoesNotExist()
        val blurred = rule.onNodeWithTag("lyricsBlurredBackdrop").captureToImage()
        val blurredDetail = edgeEnergy(blurred)
        assertTrue("歌词背景必须真正模糊封面细节，而不只是取色或压暗：clear=$clearDetail blurred=$blurredDetail",
            clearDetail > 0.002f && blurredDetail < clearDetail * 0.35f)
        assertAnchor(4)
        rule.onNodeWithText("封面", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("immersiveCoverBackdrop").assertIsDisplayed()
        rule.onNodeWithContentDescription("开始播放").performClick()
        rule.onNodeWithContentDescription("暂停播放").assertIsDisplayed()
    }

    /** Sample the left margin, outside the header, lyric text and controls. */
    private fun edgeEnergy(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        var total = 0f
        var count = 0
        for (x in (image.width * 0.01f).toInt()..(image.width * 0.035f).toInt()) {
            for (y in (image.height * 0.17f).toInt()..(image.height * 0.39f).toInt()) {
                val a = pixels[x, y]; val b = pixels[x, y + 1]
                total += abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
                count++
            }
        }
        return total / count
    }

    @Test fun completeCoverKeepsTransportOnSmallLargeTextAndLandscape() {
        fontScale = 1.5f
        densityMultiplier = 1.14f
        start { it.copy(isLyricsMode = false, currentTrack = it.currentTrack!!.copy(source = null,
            title = "把一首很长的歌留到日落以后 — The Evening Never Ends")) }
        rule.onNodeWithTag("immersiveCoverBackdrop").assertIsDisplayed()
        assertCompleteCoverLayout()
        rule.onNodeWithContentDescription("收藏歌曲").assertIsDisplayed()
        assertCoverTransport()
        shot("16-complete-cover-small-large-text")
        rule.runOnIdle { fontScale = 1f; densityMultiplier = 1f }
        device.setOrientationLeft()
        rule.waitUntil(10000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        rule.activityRule.scenario.onActivity { it.setContent { TestContent() } }
        assertCoverTransport()
        assertCompleteCoverLayout()
        rule.onNodeWithContentDescription("播放队列").assertIsDisplayed()
        rule.onNodeWithContentDescription("播放模式：顺序播放").assertIsDisplayed()
        shot("17-complete-cover-landscape")
    }

    private fun assertCompleteCoverLayout(ratio: Float = 1f) {
        val cover = rule.onNodeWithTag("fullCover").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val player = rule.onNodeWithTag("immersivePlayer").fetchSemanticsNode().boundsInRoot
        assertEquals("封面必须保持原始宽高比，不能拉伸", ratio, cover.width / cover.height, 0.015f)
        assertTrue("封面四边必须留在可见区域", cover.left >= player.left && cover.top >= player.top &&
            cover.right <= player.right && cover.bottom <= player.bottom)
        for (tag in listOf("coverMetadata", "seek", "coverTransport")) {
            val controls = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag 不应盖在清晰封面上：cover=$cover controls=$controls",
                cover.bottom <= controls.top + 1f || cover.right <= controls.left + 1f)
        }
    }

    @Test fun squareWideAndTallArtworkKeepEveryEdgeInBothOrientations() {
        start { desktopArtworkState(it).copy(isLyricsMode = false) }
        val markers = listOf(0xFFE13E42.toInt(), 0xFF319569.toInt(), 0xFF346FDB.toInt(), 0xFFE6B537.toInt())
        for (landscape in listOf(false, true)) {
            if (landscape) {
                device.setOrientationLeft()
                rule.waitUntil(10000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                rule.activityRule.scenario.onActivity { it.setContent { TestContent() } }
            }
            for ((width, height) in listOf(480 to 480, 640 to 400, 400 to 640)) {
                val image = edgeMarkedArtwork(width, height, markers)
                rule.runOnIdle { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(
                    artwork = mapOf(ui.value.currentTrack!!.id to image))) }
                // IO can outlive a Compose idle frame. Wait for this cover, not its square placeholder.
                rule.waitUntil(10000) {
                    val cover = rule.onNodeWithTag("fullCover")
                    val bounds = cover.fetchSemanticsNode().boundsInRoot
                    if (abs(bounds.width / bounds.height - width.toFloat() / height) >= 0.015f) false
                    else {
                        val pixels = cover.captureToImage().toPixelMap()
                        val corner = pixels[(pixels.width * 0.05f).toInt(), (pixels.height * 0.05f).toInt()]
                        val marker = androidx.compose.ui.graphics.Color(markers.first())
                        abs(corner.red - marker.red) < 0.04f && abs(corner.green - marker.green) < 0.04f &&
                            abs(corner.blue - marker.blue) < 0.04f
                    }
                }
                shot("18-edges-${width}x$height-${if (landscape) "landscape" else "portrait"}")
                assertCompleteCoverLayout(width.toFloat() / height)
                val pixels = rule.onNodeWithTag("fullCover").captureToImage().toPixelMap()
                for ((index, point) in listOf(0.05f to 0.05f, 0.95f to 0.05f, 0.05f to 0.95f, 0.95f to 0.95f).withIndex()) {
                    val actual = pixels[(pixels.width * point.first).toInt(), (pixels.height * point.second).toInt()]
                    val expected = androidx.compose.ui.graphics.Color(markers[index])
                    assertEquals("第 $index 个角标必须完整可见且未被遮罩压暗", expected.red, actual.red, 0.04f)
                    assertEquals(expected.green, actual.green, 0.04f)
                    assertEquals(expected.blue, actual.blue, 0.04f)
                }
            }
        }
    }

    private fun edgeMarkedArtwork(width: Int, height: Int, markers: List<Int>): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFFECE5D7.toInt())
        paint.color = 0xFF836C5B.toInt()
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) * 0.28f, paint)
        for ((index, point) in listOf(0f to 0f, 0.88f to 0f, 0f to 0.88f, 0.88f to 0.88f).withIndex()) {
            paint.color = markers[index]
            canvas.drawRect(point.first * width, point.second * height,
                (point.first + 0.12f) * width, (point.second + 0.12f) * height, paint)
        }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun assertCoverTransport() {
        val controls = listOf("上一首", "开始播放", "下一首")
            .map { rule.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertTrue(controls[0].right <= controls[1].left)
        assertTrue(controls[1].right <= controls[2].left)
    }

    /** Original generated test art, deliberately exercising low/high luminance and dense detail. */
    private fun artworkData(tone: Int): String {
        val bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(if (tone == 1) 0xFF081A24.toInt() else 0xFFF5EBDD.toInt())
        paint.color = if (tone == 1) 0xFFB88353.toInt() else 0xFFE39679.toInt()
        canvas.drawCircle(410f, 330f, 240f, paint)
        paint.color = if (tone == 1) 0xFF456C6B.toInt() else 0xFFADC3A8.toInt()
        for (i in 0..17) {
            canvas.drawRoundRect((i * 51 - 40).toFloat(), (450 - i * 9).toFloat(), (i * 51 + 2).toFloat(), 800f, 20f, 20f, paint)
        }
        if (tone == 2) for (row in 0..35) for (column in 0..35) {
            paint.color = android.graphics.Color.rgb((row * 19 + column * 7) % 220 + 35,
                (row * 13 + column * 17) % 220 + 35, (row * 11 + column * 9) % 220 + 35)
            canvas.drawCircle(column * 21f, row * 21f, 9f, paint)
        }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() }
        bitmap.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @Test fun brightDarkAndDetailedCoversKeepBlurAndReadableForeground() {
        start { desktopArtworkState(it) }
        for ((tone, name) in listOf(0 to "09-bright-cover", 1 to "10-dark-cover", 2 to "11-detailed-cover")) {
            val image = artworkData(tone)
            rule.runOnIdle { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(
                artwork = mapOf(ui.value.currentTrack!!.id to image))) }
            rule.onNodeWithText("封面", useUnmergedTree = true).performClick()
            shot("$name-clear-player")
            rule.onNodeWithText("歌词", useUnmergedTree = true).performClick()
            shot("$name-blurred-lyrics")
            assertAnchor(4)
            rule.onNodeWithContentDescription("开始播放").assertIsDisplayed()
        }
    }

    @Test fun disabledSystemAnimationScaleKeepsFocusAndSeekUsable() {
        val previous = device.executeShellCommand("settings get global animator_duration_scale").trim().toFloatOrNull()
        try {
            device.executeShellCommand("settings put global animator_duration_scale 0")
            start()
            rule.onNodeWithTag("lyric-line-5").performClick()
            assertAnchor(5)
            shot("12-reduced-motion")
        } finally {
            if (previous == null) device.executeShellCommand("settings delete global animator_duration_scale")
            else device.executeShellCommand("settings put global animator_duration_scale $previous")
        }
    }

    @Test fun motionCapture() {
        start { it.copy(activeLyricIndex = 0, displayedPositionSeconds = 0.0, isPlaying = true) }
        Thread.sleep(2000) // let the embedded artwork replace its initial placeholder
        // Step real rendered frames: waitForIdle's normal auto-advance would skip motion on video.
        // This deterministic clock belongs only to the test; production uses playback timestamps.
        rule.mainClock.autoAdvance = false
        fun frames(count: Int) {
            val started = System.nanoTime()
            val duration = count * 16L
            var advanced = 0L
            while (advanced < duration) {
                val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtMost(duration)
                if (elapsed > advanced) {
                    rule.mainClock.advanceTimeBy(elapsed - advanced, ignoreFrameDuration = true)
                    rule.waitForIdle()
                    advanced = elapsed
                }
                Thread.sleep(4)
            }
        }
        frames(12)
        for (index in 1..6) {
            rule.runOnUiThread { ui.value = ui.value.copy(activeLyricIndex = index, displayedPositionSeconds = index * 6.0) }
            frames(72)
        }
        rule.onNodeWithTag("lyrics").performTouchInput { swipeUp(durationMillis = 800) }
        frames(60)
        rule.onNodeWithTag("resumeLyrics").assertIsDisplayed().performClick()
        frames(72)
        rule.mainClock.autoAdvance = true
        assertAnchor(6)
    }

    @Test fun stageTapOrStillnessEntersFullImmersionAndTapOrBackLeaves() {
        start()
        rule.onNodeWithTag("lyricsImmersive").assertDoesNotExist()
        // Open stage beside a short line: no seek, only lyrics remain.
        val stage = rule.onNodeWithTag("lyrics").fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag("lyric-line-5").fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { click(Offset(stage.right - 8f, row.center.y)) }
        rule.onNodeWithTag("lyricsImmersive").assertIsDisplayed()
        rule.onNodeWithText("歌词", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("收起播放器").assertDoesNotExist()
        rule.onNodeWithTag("playerPlayPause").assertDoesNotExist()
        assertNull(lastSeek)
        shot("13-lyrics-immersive")
        // Any tap brings the controls back; a tapped line still seeks.
        rule.onNodeWithTag("lyric-line-6").performClick()
        assertEquals(36.0, lastSeek!!, 0.001)
        rule.onNodeWithTag("lyricsImmersive").assertDoesNotExist()
        rule.onNodeWithTag("playerPlayPause").assertIsDisplayed()
        // With controls showing, a line tap only seeks.
        rule.onNodeWithTag("lyric-line-7").performClick()
        rule.onNodeWithTag("playerPlayPause").assertIsDisplayed()
        // Stillness while playing hands the page to the lyrics; Back returns the controls, not the library.
        rule.runOnUiThread { ui.value = ui.value.copy(isPlaying = true) }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("lyricsImmersive").fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("lyricsImmersive").assertDoesNotExist()
        rule.onNodeWithTag("playerPlayPause").assertIsDisplayed()
        assertTrue(ui.value.isImmersivePlayerOpen)
    }

    /** Every row rides the wave: a row whose only change is depth of field must not wait at its old place. */
    @Test fun immersiveAdvanceMovesEveryRowTogether() {
        start()
        val stage = rule.onNodeWithTag("lyrics").fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag("lyric-line-5").fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { click(Offset(stage.right - 8f, row.center.y)) }
        rule.onNodeWithTag("lyricsImmersive").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnUiThread { ui.value = ui.value.copy(activeLyricIndex = 5, displayedPositionSeconds = 30.0) }
            rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
            val rows = (2..8).map { rule.onNodeWithTag("lyric-line-$it").fetchSemanticsNode().boundsInRoot }
            rows.zipWithNext().forEachIndexed { i, (above, below) ->
                assertTrue("第 ${i + 3} 行歌词不应停在旧位置盖住相邻行：$above / $below", below.top >= above.bottom)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }
}
