package com.twilightecho.android.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiDesignTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>
    private var playedIds = emptyList<String>()
    private var selectedOccurrence: String? = null
    private var fontScale by mutableFloatStateOf(1f)
    private var densityMultiplier by mutableFloatStateOf(1f)
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun sampleState(): PlayerUiState {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "ui-artwork").apply { mkdirs() }
        val files = instrumentation.context.assets.list("music")!!.filter { it.endsWith(".flac") }.sorted()
        files.forEach { name ->
            val output = File(directory, name)
            instrumentation.context.assets.open("music/$name").use { input -> output.outputStream().use { input.copyTo(it) } }
        }
        val titles = listOf("Golden Hour", "A Quiet Return", "Blue Hours", "Low Tide", "Open Windows", "Somewhere Slowly")
        val tracks = titles.mapIndexed { index, title -> CoreTrackView("qa-$index", title = title,
            artist = listOf("Paper Lantern", "North Shore", "Moss Garden")[index / 2],
            album = listOf("Afterglow", "Blue Hours", "Still Life")[index / 2],
            source = File(directory, files[index]).toURI().toString(), duration = 42.0, format = "FLAC",
            sampleRate = 44100, bitDepth = 16)
        }
        return PlayerUiState(currentTrack = tracks.first(), durationSeconds = 42.0, displayedPositionSeconds = 12.0,
            libraryTracks = tracks, searchResults = tracks, libraryTrackCount = tracks.size, isLoadingLibrary = false,
            albums = (0..2).map { i -> CoreAlbumCollectionView("album-$i", tracks[i * 2].album, tracks[i * 2].artist,
                2, 84.0, tracks.slice(i * 2..i * 2 + 1).map { it.id }) },
            artists = (0..2).map { i -> CoreArtistCollectionView("artist-$i", tracks[i * 2].artist, 1, 2,
                tracks.slice(i * 2..i * 2 + 1).map { it.id }) },
            playlists = listOf(CorePlaylistView("playlist-1", "暮色留声", tracks.take(3).map { it.id })),
            queueTotal = 125, queueItems = (0 until 50).map { CoreQueueEntryView("occ-$it", it, tracks[it % tracks.size]) },
            currentLyrics = CoreLyricsView("qa-0", lines = listOf(
                CoreLyricLine(0, "风把暮色带进窗沿", "Evening settles by the window"),
                CoreLyricLine(6000, "我们把时间交给唱片"),
                CoreLyricLine(12000, "让一首歌慢慢走远"),
                CoreLyricLine(18000, "听见安静，也听见想念"),
                CoreLyricLine(24000, "在蓝色的夜里停留"),
                CoreLyricLine(30000, "直到下一次日落")
            )), activeLyricIndex = 2,
            diagnostics = AudioDiagnosticsUiModel("FLAC", 44100, 16, "Oboe · 模拟器", 48000, 16, false))
    }

    private fun start(initial: (PlayerUiState) -> PlayerUiState = { it }) {
        ui = mutableStateOf(initial(sampleState()))
        val actions = PlayerActions(
            selectTab = { ui.value = ui.value.copy(activeMainTab = it) },
            selectLibraryTab = { ui.value = ui.value.copy(activeLibraryTab = it) },
            setPlayerOpen = { ui.value = ui.value.copy(isImmersivePlayerOpen = it) },
            setLyricsMode = { ui.value = ui.value.copy(isLyricsMode = it) },
            setQueueOpen = { ui.value = ui.value.copy(isQueueDrawerOpen = it) },
            togglePlayback = { ui.value = ui.value.copy(isPlaying = !ui.value.isPlaying) },
            toggleFavorite = { ui.value = ui.value.copy(isCurrentTrackFavorite = !ui.value.isCurrentTrackFavorite) },
            query = { query -> ui.value = ui.value.copy(searchQuery = query,
                searchResults = ui.value.libraryTracks.filter { it.title.contains(query, true) }) },
            selectFilter = { ui.value = ui.value.copy(activeSearchFilter = it) },
            seek = { ui.value = ui.value.copy(displayedPositionSeconds = it, activeLyricIndex = (it / 6).toInt()) },
            playTracks = { tracks, id ->
                playedIds = tracks.map { it.id }
                ui.value = ui.value.copy(currentTrack = tracks.first { it.id == id },
                    currentLyrics = ui.value.currentLyrics?.copy(trackId = id))
            },
            playlistName = { ui.value = ui.value.copy(newPlaylistNameDraft = it) },
            createPlaylist = { ui.value = ui.value.copy(playlists = ui.value.playlists +
                CorePlaylistView("new", ui.value.newPlaylistNameDraft), newPlaylistNameDraft = "") },
            deletePlaylist = { id -> ui.value = ui.value.copy(playlists = ui.value.playlists.filter { it.id != id }) },
            addBookmark = { ui.value = ui.value.copy(currentTrackBookmarks = listOf(
                CoreBookmarkView("bookmark", ui.value.currentTrack!!.id, 18.0, "0:18", 0L))) },
            removeBookmark = { ui.value = ui.value.copy(currentTrackBookmarks = emptyList()) },
            loadMoreQueue = {
                val state = ui.value
                ui.value = state.copy(queueItems = (0 until minOf(state.queueItems.size + 50, 125)).map {
                    CoreQueueEntryView("occ-$it", it, state.libraryTracks[it % state.libraryTracks.size])
                })
            },
            selectQueueEntry = {
                selectedOccurrence = it.occurrenceId
                ui.value = ui.value.copy(currentQueueIndex = it.index, isQueueDrawerOpen = false)
            },
            toggleEq = { ui.value = ui.value.copy(eqConfig = ui.value.eqConfig.copy(enabled = !ui.value.eqConfig.enabled)) },
            band = { index, value -> ui.value = ui.value.copy(eqConfig = ui.value.eqConfig.copy(
                bandsDb = ui.value.eqConfig.bandsDb.mapIndexed { i, old -> if (i == index) value else old })) },
            remoteHost = { ui.value = ui.value.copy(remoteHostInput = it) },
            remotePin = { ui.value = ui.value.copy(remotePinInput = it) }
        )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * densityMultiplier, fontScale)) {
                TwilightEchoTheme { PlayerAppContent(ui.value, actions) }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400) // allow the cover's IO and finite screen transition to finish
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "ui-screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
        device.executeShellCommand("mkdir -p /sdcard/Pictures/TwilightUiQA")
        device.executeShellCommand("cp ${File(directory, "$name.png").absolutePath} /sdcard/Pictures/TwilightUiQA/$name.png")
    }

    private fun hideKeyboard() {
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        rule.waitForIdle()
    }

    @Test fun p0AudioPathKeepsReadableGroupsAndExpandsOutputFacts() {
        start { it.copy(homeAvailable = false, activeMainTab = MainDestinationTab.SETTINGS,
            diagnostics = AudioDiagnosticsUiModel(codec = "FLAC", sourceSampleRateHz = 44100, sourceBitDepth = 16,
                actualSampleRateHz = 44100, actualBitDepth = 32, actualSampleFormat = "float32",
                requestedSampleRateHz = 44100, requestedSampleFormat = "float32",
                hardwareSampleRateHz = 48000, hardwareBitDepth = 16, hardwareSampleFormat = "int16",
                actualChannels = 2, hardwareChannels = 2, deviceName = "手机扬声器")) }
        rule.onNodeWithText("音频与 DSP", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("播放输出 · 共享").assertIsDisplayed()
        rule.onNodeWithText("未确认 Bit-Perfect").assertDoesNotExist()
        rule.onNodeWithText("系统硬件格式").assertDoesNotExist()
        shot("p0-audio-path")
        rule.onNodeWithTag("audioOutputDetails").performScrollTo().performClick()
        rule.onNodeWithText("48 kHz / 16 位 · 双声道").performScrollTo()
        rule.onNodeWithText("48 kHz / 16 位 · 双声道").assertIsDisplayed()
        shot("p0-audio-output-details")
        rule.runOnIdle { fontScale = 1.5f }
        rule.onNodeWithText("48 kHz / 16 位 · 双声道").performScrollTo()
        shot("p0-audio-large-text")
        rule.onNodeWithText("48 kHz / 16 位 · 双声道").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(getResults(layouts))
                val layout = layouts.single()
                // The paragraph can retain its wrapping width after density changes. Check the visible glyphs.
                assertFalse(layout.didOverflowHeight)
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line))
                    assertTrue(layout.getLineLeft(line) >= -1f)
                    assertTrue(layout.getLineRight(line) <= layout.size.width + 1f)
                    assertTrue(layout.getLineBottom(line) <= layout.size.height + 1f)
                }
            }
        rule.onNodeWithTag("audioOutputDetails").performScrollTo().performClick()
        rule.onNodeWithText("系统硬件格式").assertDoesNotExist()
    }

    @Test fun p0IdleAudioPathOffersOneClearAction() {
        start { it.copy(homeAvailable = false, activeMainTab = MainDestinationTab.SETTINGS,
            currentTrack = null, diagnostics = AudioDiagnosticsUiModel()) }
        rule.onNodeWithText("音频与 DSP", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("选择歌曲查看音频链路").assertIsDisplayed()
        rule.onNodeWithTag("audioOutputDetails").assertDoesNotExist()
        shot("p0-audio-idle")
        rule.runOnIdle { ui.value = ui.value.copy(diagnostics = AudioDiagnosticsUiModel(codec = "FLAC", sourceSampleRateHz = 44100)) }
        rule.onNodeWithText("播放歌曲查看输出").assertIsDisplayed()
        rule.onNodeWithText("标准播放").assertDoesNotExist()
    }

    @Test fun p1BluetoothOutputNamesItsLinkWithoutGuessingTheCodec() {
        start { it.copy(homeAvailable = false, activeMainTab = MainDestinationTab.SETTINGS,
            diagnostics = AudioDiagnosticsUiModel(codec = "FLAC", sourceSampleRateHz = 96000, sourceBitDepth = 24,
                actualSampleRateHz = 48000, actualBitDepth = 16, actualSampleFormat = "int16", actualChannels = 2,
                deviceName = "蓝牙耳机", bluetoothProfile = "a2dp")) }
        rule.onNodeWithText("音频与 DSP", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("蓝牙编码").assertDoesNotExist()
        rule.onNodeWithTag("audioOutputDetails").performScrollTo().performClick()
        rule.onNodeWithText("A2DP").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("仅系统应用可读取").performScrollTo().assertIsDisplayed()
        // No codec is named: neither the headset's name nor the phone's codec list says which one plays.
        listOf("SBC", "AAC", "LDAC", "aptX", "LC3").forEach {
            rule.onNode(hasAnyAncestor(hasTestTag("audioSignalSummary")) and hasText(it, substring = true)).assertDoesNotExist()
        }
        shot("p1-bluetooth-output")
        rule.runOnIdle { ui.value = ui.value.copy(diagnostics = ui.value.diagnostics.copy(bluetoothProfile = "call")) }
        rule.onNodeWithText("通话线路").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { ui.value = ui.value.copy(diagnostics = ui.value.diagnostics.copy(bluetoothProfile = "", deviceName = "手机扬声器")) }
        rule.onNodeWithText("蓝牙编码").assertDoesNotExist()
    }

    @Test fun browseCollectionPlayerLyricsAndAudio() {
        start()
        shot("01-home")
        rule.onNodeWithTag("nav-LIBRARY").performClick()
        rule.onNodeWithText("专辑").performClick()
        shot("02-albums")
        rule.onNodeWithText("Afterglow").performClick()
        shot("03-collection")
        rule.onNodeWithTag("collection").performScrollToNode(hasText("A Quiet Return"))
        rule.onNodeWithText("A Quiet Return").performClick()
        rule.runOnIdle { assertEquals(listOf("qa-0", "qa-1"), playedIds) }
        rule.onNodeWithTag("miniPlayer").performClick()
        rule.onNodeWithTag("immersiveCoverBackdrop").assertIsDisplayed()
        rule.onNodeWithTag("fullCover").assertIsDisplayed()
        shot("04-player")
        rule.onNodeWithContentDescription("收藏歌曲").performClick()
        rule.onNodeWithContentDescription("取消收藏").assertIsDisplayed()
        rule.onNodeWithTag("seek").performSemanticsAction(SemanticsActions.SetProgress) { it(18f) }
        rule.runOnIdle { assertEquals(18.0, ui.value.displayedPositionSeconds, 0.01) }
        rule.onNodeWithText("歌词", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("lyrics").assertIsDisplayed()
        shot("05-lyrics")
        rule.onNodeWithTag("lyrics").performTouchInput { swipeUp() }
        rule.onNodeWithText("回到当前歌词").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("更多播放操作").performClick()
        rule.onNodeWithText("添加当前位置书签").performClick()
        rule.onNodeWithContentDescription("更多播放操作").performClick()
        rule.onNodeWithContentDescription("删除书签 0:18").performClick()
        rule.runOnIdle { assertTrue(ui.value.currentTrackBookmarks.isEmpty()) }
        rule.onNodeWithText("音频详情与 DSP").performClick()
        rule.onNodeWithTag("settings-AUDIO").assertIsDisplayed()
        shot("06-audio")
        rule.onNodeWithTag("audio-page-eq").performScrollTo().performClick()
        rule.onNodeWithTag("settings-AUDIO").performScrollToNode(hasTestTag("eqBand-9"))
        rule.onNodeWithTag("eqBand-9").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        rule.runOnIdle { assertEquals(3.0, ui.value.eqConfig.bandsDb[9], 0.01) }
    }

    @Test fun queuePagesAndRepeatedOccurrences() {
        start { it.copy(isImmersivePlayerOpen = true, isQueueDrawerOpen = true) }
        rule.onNodeWithTag("queueList").performScrollToNode(hasText("加载更多"))
        rule.onNodeWithText("加载更多").performClick()
        rule.runOnIdle { assertEquals(100, ui.value.queueItems.size) }
        rule.onNodeWithTag("queueList").performScrollToNode(hasTestTag("queue-occ-73"))
        shot("07-queue")
        rule.onNodeWithTag("queue-occ-73").performClick()
        rule.runOnIdle { assertEquals("occ-73", selectedOccurrence); assertEquals(73, ui.value.currentQueueIndex) }
    }

    @Test fun searchAndPlaylistAndRemoteSettings() {
        start()
        rule.onNodeWithTag("nav-SEARCH").performClick()
        rule.onNodeWithTag("searchInput").performTextInput("Golden")
        hideKeyboard()
        rule.onNodeWithTag("track-qa-0").assertExists()
        rule.onNodeWithTag("track-qa-1").assertDoesNotExist()
        shot("08-search")
        rule.onNodeWithTag("track-qa-0").performClick()
        rule.runOnIdle { assertEquals(listOf("qa-0"), playedIds) }
        rule.onNodeWithContentDescription("清除搜索").performClick()
        hideKeyboard()
        rule.onNodeWithTag("nav-LIBRARY").performClick()
        rule.onNodeWithText("歌单").performClick()
        rule.onNodeWithContentDescription("新建歌单").performClick()
        rule.onNodeWithTag("playlistName").performTextInput("夜行")
        rule.onNodeWithText("创建").performClick()
        rule.onNodeWithText("夜行").assertExists().performClick()
        rule.onNodeWithContentDescription("删除歌单").performClick()
        rule.onNodeWithText("删除", substring = false).performClick()
        rule.runOnIdle { assertFalse(ui.value.playlists.any { it.name == "夜行" }) }
        rule.onNodeWithTag("nav-SETTINGS").performClick()
        shot("09-settings")
        rule.onNodeWithText("连接电脑", substring = false).performClick()
        rule.onNodeWithTag("manualDesktopConnection").performScrollTo().performClick()
        rule.onNodeWithTag("remoteHost").performScrollTo().performClick().performTextInput("192.168.1.20:18492")
        rule.runOnIdle { assertEquals("192.168.1.20:18492", ui.value.remoteHostInput) }
        rule.onNodeWithTag("remotePin").performScrollTo().performClick().performTextInput("123456")
        hideKeyboard()
        shot("10-remote")
        rule.runOnIdle { assertEquals("host=${ui.value.remoteHostInput}", "123456", ui.value.remotePinInput) }
    }

    @Test fun emptyMissingCoverLongTitleAndLargeText() {
        // Without a streaming provider there is no home page, so the app opens on the library.
        start { it.copy(homeAvailable = false, currentTrack = null, libraryTracks = emptyList(), libraryTrackCount = 0,
            albums = emptyList()) }
        rule.onNodeWithTag("nav-HOME").assertDoesNotExist()
        rule.onNodeWithTag("miniPlayer").assertDoesNotExist()
        rule.onNodeWithText("扫描本地音乐").assertExists()
        shot("11-empty")
        rule.runOnIdle {
            val sample = sampleState()
            ui.value = sample.copy(currentTrack = sample.currentTrack!!.copy(source = null,
                title = "这是一首很长很长的歌曲名称 — A Very Long Title That Must Stay Readable"),
                isImmersivePlayerOpen = true)
            fontScale = 1.5f
            densityMultiplier = 1.14f
        }
        rule.onNodeWithContentDescription("下一首").assertIsDisplayed()
        shot("12-small-large-text")
        rule.runOnIdle { ui.value = ui.value.copy(isImmersivePlayerOpen = false,
            activeMainTab = MainDestinationTab.LIBRARY, activeLibraryTab = LibrarySectionTab.ARTISTS) }
        shot("17-small-large-library")
        rule.onNodeWithText("艺术家", useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(getResults(layouts))
                assertEquals(1, layouts.single().lineCount)
                val layout = layouts.single()
                // Unwrapped paragraphs can have infinite width; check the actual line's glyphs.
                assertEquals("艺术家".length, layout.getLineEnd(0, visibleEnd = true))
                assertFalse(layout.isLineEllipsized(0))
                assertTrue(layout.getLineRight(0) <= layout.size.width + 1f)
            }
    }

    /** Also run with `adb shell wm size 1080x1680` on the isolated QA emulator. */
    @Test fun shortPlayerTransportFitsScreen() {
        start { it.copy(isImmersivePlayerOpen = true) }
        shot("18-short-player")
        val controls = listOf("上一首", "开始播放", "下一首")
            .map { rule.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertTrue("上一首与播放按钮不能重叠", controls[0].right <= controls[1].left)
        assertTrue("下一首与播放按钮不能重叠", controls[1].right <= controls[2].left)
        rule.onNodeWithContentDescription("播放队列").assertIsDisplayed()
        rule.onNodeWithContentDescription("播放模式：顺序播放").assertIsDisplayed()
    }

    @Test fun sourceSpecificationsRemainReadableInCoverLyricsAndRemoteStates() {
        start { it.copy(isImmersivePlayerOpen = true,
            currentTrack = it.currentTrack!!.copy(format = "flac", sampleRate = 96000, bitDepth = 24),
            diagnostics = it.diagnostics.copy(codec = "FLAC", sourceSampleRateHz = 96000, sourceBitDepth = 24)) }
        fun assertQuality() {
            rule.onNodeWithTag("playbackQuality").assertTextEquals("FLAC 24/96").assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(getResults(layouts))
                    assertFalse("音质规格不能被截断", layouts.single().isLineEllipsized(0))
                }
        }
        assertQuality()
        shot("19-player-source-quality")
        rule.onNodeWithText("歌词", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertTrue(ui.value.isLyricsMode) }
        rule.onNodeWithTag("lyricsBlurredBackdrop").assertIsDisplayed()
        rule.onNodeWithTag("lyrics").assertIsDisplayed()
        assertQuality()
        shot("21-lyrics-source-quality")
        rule.onNodeWithText("封面", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("coverPlayer").assertIsDisplayed()
        rule.runOnIdle {
            fontScale = 1.5f
            densityMultiplier = 1.14f
            ui.value = ui.value.copy(operatingMode = CoreOperatingMode.RemoteDesktopCore("http://192.168.1.20:18492", "书房电脑"))
        }
        assertQuality()
        rule.onNodeWithTag("playbackNote").assertTextEquals("在 书房电脑 上播放")
        rule.onNodeWithContentDescription("下一首").assertIsDisplayed()
        shot("20-player-quality-large-text")
        rule.runOnIdle { ui.value = ui.value.copy(operatingMode =
            CoreOperatingMode.RemoteDesktopCore("http://192.168.1.20:18492", "书房电脑", connected = false)) }
        rule.onNodeWithTag("playbackNote").assertTextEquals("电脑未连接").assertIsDisplayed()
        assertQuality()
        rule.runOnIdle { ui.value = ui.value.copy(currentTrack = ui.value.currentTrack!!.copy(format = null,
            sampleRate = null, bitDepth = null), diagnostics = AudioDiagnosticsUiModel()) }
        rule.onNodeWithTag("playbackQuality").assertDoesNotExist()
        rule.onNodeWithTag("playbackNote").assertIsDisplayed()
    }

    @Test fun coreBridgeAndDesktopArtistDetail() {
        val cover = InstrumentationRegistry.getInstrumentation().context.assets.open("music/02.jpg").use {
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP)
        }
        val desktop = LibrarySource.Desktop("srv", "lib", "http://192.168.1.20:18492", "XOS-STUDIO")
        val artist = CatalogEntity("artist-1", "artist", "Paper Lantern", sourceCount = 2)
        val intro = "Paper Lantern 是一支以暮色与旧唱片质感为主题的独立民谣乐队。乐队成立于海边小城，" +
            "早期作品在木吉他、手风琴与磁带噪声之间寻找安静的叙事；第二张专辑《Afterglow》加入弦乐与环境录音，" +
            "让日落前最后一小时的光线成为整张唱片的主角。乐队坚持全模拟录音，并在每次巡演中重新编排旧作。"
        start { it.copy(librarySource = desktop, libraryDevice = "srv",
            libraryDevices = listOf(LibraryDeviceOption(LIBRARY_ALL, "全部设备"), LibraryDeviceOption(LIBRARY_PHONE, "此手机"),
                LibraryDeviceOption("srv", "XOS-STUDIO")),
            operatingMode = CoreOperatingMode.RemoteDesktopCore(desktop.baseUrl, desktop.hostLabel),
            desktopDiscovery = DesktopDiscoveryState(devices = listOf(
                DesktopCoreDevice("srv", "XOS-STUDIO", desktop.baseUrl, trusted = true),
                DesktopCoreDevice("srv-2", "客厅电脑", "http://192.168.1.31:18492"))),
            desktopLibrary = DesktopBrowseState(kind = "artist", page = CatalogPage(1, 3, items = listOf(artist,
                CatalogEntity("artist-2", "artist", "North Shore", sourceCount = 2),
                CatalogEntity("artist-3", "artist", "Moss Garden", sourceCount = 2))))) }
        shot("13-home-core-pill")
        rule.onNodeWithTag("nav-SETTINGS").performClick()
        rule.onNodeWithText("连接电脑", substring = false).performClick()
        rule.onNodeWithTag("coreTopology").assertIsDisplayed()
        shot("14-core-topology")
        rule.onNodeWithTag("desktop-srv-2").performScrollTo()
        shot("15-core-devices")
        rule.runOnIdle { ui.value = ui.value.copy(activeMainTab = MainDestinationTab.LIBRARY,
            activeLibraryTab = LibrarySectionTab.ARTISTS) }
        shot("16-desktop-artists")
        rule.runOnIdle { ui.value = ui.value.copy(desktopLibrary = ui.value.desktopLibrary.copy(selected = artist,
            metadata = EntityMetadataDetail("artist-1", 1, "Paper Lantern",
                MetadataIntroduction(intro, "zh-Hans", attribution = MetadataAttribution("Wikipedia", "CC BY-SA 4.0")),
                facts = listOf(MetadataFact("description", "独立民谣乐队"), MetadataFact("primaryType", "Group"),
                    MetadataFact("firstReleaseDate", "2014-05-02"), MetadataFact("country", "JP")),
                artwork = listOf(MetadataArtwork(cover, MetadataAttribution("Fanart.tv", "CC BY-NC")))),
            sources = CatalogSourcePage(1, 2, items = listOf(
                CatalogSource("src-1", "local", "rec-1", track = CatalogSourceTrack("Golden Hour", "Paper Lantern",
                    "Afterglow", 42.0, "flac", 96000, 24)),
                CatalogSource("src-2", "local", "rec-2", track = CatalogSourceTrack("A Quiet Return", "Paper Lantern",
                    "Afterglow", 42.0, "flac", 44100, 16)))))) }
        rule.onNodeWithTag("desktopMetadataArtwork").assertIsDisplayed()
        shot("17-desktop-artist-detail")
        rule.onNodeWithText("展开全文").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("desktopEntity").performScrollToNode(hasContentDescription("在电脑播放 A Quiet Return"))
        shot("18-desktop-artist-sources")
    }
}
