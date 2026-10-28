package com.twilightecho.android.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.playbackhost.UserPreferencesStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CustomizationSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var fontScale by mutableFloatStateOf(1f)
    private var imagePath: String? = null

    private fun start(preferences: UserPreferences = UserPreferences()) {
        val source = File(instrumentation.targetContext.cacheDir, "customization-qa.flac")
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input -> source.outputStream().use { input.copyTo(it) } }
        val track = CoreTrackView("customization-qa", title = "暮色留声", artist = "回响乐队", source = source.toURI().toString(), duration = 42.0)
        ui = mutableStateOf(PlayerUiState(activeMainTab = MainDestinationTab.SETTINGS, homeAvailable = false,
            currentTrack = track, durationSeconds = 42.0, preferences = preferences, isLoadingLibrary = false,
            currentLyrics = CoreLyricsView(track.id, lines = listOf("暮色落在窗边", "风吹过唱片", "把回声留给明天", "等月光走过山间").mapIndexed { index, text ->
                CoreLyricLine(index * 6000L, text, "译文第${index + 1}句")
            }), activeLyricIndex = 1))
        val actions = PlayerActions(editPreferences = { change -> ui.value = ui.value.copy(preferences = change(ui.value.preferences).normalized()) },
            pickBackground = { ui.value = ui.value.copy(preferences = ui.value.preferences.copy(appearance = ui.value.preferences.appearance.copy(
                backgroundStyle = BackgroundStyle.IMAGE, backgroundImagePath = imagePath))) },
            setPlayerOpen = { ui.value = ui.value.copy(isImmersivePlayerOpen = it) },
            setLyricsMode = { ui.value = ui.value.copy(isLyricsMode = it) },
            selectTab = { ui.value = ui.value.copy(activeMainTab = it) })
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { PlayerAppContent(ui.value, actions) }
        }
    }

    private fun slider(page: String, tag: String, value: Float) {
        rule.onNodeWithTag("settings-$page").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        rule.waitForIdle()
    }

    private fun color(tag: String, value: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.onNodeWithTag("colorHex").performTextReplacement(value)
        if (tag == "accentColor") shot("00-color-picker")
        rule.onNodeWithTag("applyColor").performClick()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1000) // finite palette transition and off-thread image decode
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "customization-screenshots").apply { mkdirs() }
        val canvas = if (name == "00-color-picker") rule.onNodeWithTag("colorPicker") else rule.onRoot()
        File(directory, "$name.png").outputStream().use {
            assertTrue(canvas.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun appearanceControlsApplyCustomColorsDensityOpacityAndReset() {
        start()
        rule.onNodeWithTag("openAppearance").performClick()
        rule.onNodeWithTag("accent-CUSTOM").performClick()
        color("accentColor", "#3178C6")
        rule.onNodeWithTag("background-SOLID").performClick()
        color("backgroundColor", "#F4F0E8")
        assertEquals(0xFF3178C6.toInt(), ui.value.preferences.appearance.accentArgb)
        assertEquals(0xFFF4F0E8.toInt(), ui.value.preferences.appearance.backgroundArgb)
        shot("01-appearance-light")
        rule.runOnIdle { ui.value = ui.value.copy(preferences = ui.value.preferences.copy(appearance =
            ui.value.preferences.appearance.copy(backgroundArgb = 0xFF888888.toInt()))) }
        val heading = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("外观").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(heading) }
        assertTrue("中灰背景使用深色文字", heading.single().layoutInput.style.color.luminance() < 0.01f)
        shot("10-mid-grey-contrast")
        rule.runOnIdle { ui.value = ui.value.copy(preferences = ui.value.preferences.copy(appearance =
            ui.value.preferences.appearance.copy(backgroundArgb = 0xFFF4F0E8.toInt()))) }
        slider("APPEARANCE", "densityScale", 125f)
        slider("APPEARANCE", "contentOpacity", 35f)
        assertEquals(1.25f, ui.value.preferences.appearance.densityScale)
        assertEquals(0.35f, ui.value.preferences.appearance.contentOpacity)
        shot("02-appearance-scaled")
        rule.onNodeWithTag("settings-APPEARANCE").performScrollToNode(hasTestTag("resetAppearance"))
        rule.onNodeWithTag("resetAppearance").performClick()
        assertEquals(AppearancePreferences(), ui.value.preferences.appearance)
    }

    @Test fun lyricsChoicesReachActualTextAndRemainUsableAtLargeScale() {
        start()
        rule.onNodeWithTag("openLyricsSettings").performClick()
        slider("LYRICS", "lyricFontSize", 42f)
        rule.onNodeWithTag("lyricWeight-500").performClick()
        slider("LYRICS", "lyricSpacing", 34f)
        slider("LYRICS", "lyricBlur", 0f)
        rule.onNodeWithTag("settings-LYRICS").performScrollToNode(hasText("显示译文"))
        rule.onNode(isToggleable()).performClick()
        assertFalse(ui.value.preferences.lyrics.showTranslation)
        rule.onNodeWithTag("settings-LYRICS").performScrollToIndex(0)
        shot("03-lyrics-settings")
        rule.runOnIdle { ui.value = ui.value.copy(isImmersivePlayerOpen = true, isLyricsMode = true) }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNode(hasText("风吹过唱片") and hasAnyAncestor(hasTestTag("lyric-line-1")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(42.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(FontWeight.Medium, layouts.single().layoutInput.style.fontWeight)
        rule.onNodeWithText("译文第2句", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("lyric-line-1").assertIsDisplayed().assertIsSelected()
        shot("04-lyrics-custom")
        rule.runOnIdle {
            fontScale = 1.35f
            ui.value = ui.value.copy(preferences = ui.value.preferences.copy(appearance = AppearancePreferences(densityScale = 1.25f)))
        }
        rule.onNodeWithTag("lyric-line-1").assertIsDisplayed()
        rule.onNodeWithTag("playerPlayPause").assertIsDisplayed()
        shot("05-lyrics-large-scale")
    }

    @Test fun imageAndSolidBackgroundsAlsoApplyInsideThePlayer() {
        imagePath = File(instrumentation.targetContext.cacheDir, "customization-background.jpg").also { file ->
            instrumentation.context.assets.open("music/03.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        }.absolutePath
        start()
        rule.onNodeWithTag("openAppearance").performClick()
        rule.onNodeWithTag("background-IMAGE").performClick()
        rule.onNodeWithTag("customImageBackground").assertIsDisplayed()
        shot("06-image-settings")
        rule.runOnIdle { ui.value = ui.value.copy(isImmersivePlayerOpen = true) }
        rule.onAllNodesWithTag("customImageBackground").assertCountEquals(2)
        rule.onNodeWithTag("immersiveCoverBackdrop").assertDoesNotExist()
        shot("07-image-player")
        rule.runOnIdle { ui.value = ui.value.copy(preferences = ui.value.preferences.copy(appearance = ui.value.preferences.appearance.copy(
            backgroundStyle = BackgroundStyle.SOLID, backgroundArgb = 0xFF17302A.toInt()))) }
        rule.onAllNodesWithTag("solidBackground").assertCountEquals(2)
        rule.onNodeWithTag("customImageBackground").assertDoesNotExist()
        shot("08-solid-player")
    }

    @Test fun qualitySelectionUsesAllProviderValues() {
        start()
        rule.onNodeWithTag("openStreamingSettings").performClick()
        NcmPlaybackQuality.entries.forEach { quality ->
            rule.onNodeWithTag("quality-${quality.name}").performClick().assertIsSelected()
            assertEquals(quality, ui.value.preferences.ncmQuality)
        }
        shot("09-streaming-quality")
    }

    @Test fun preferencesAndImportedImageSurviveStoreRecreationAndInvalidSelection() = runBlocking {
        val id = UUID.randomUUID().toString()
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "customization-store-$id").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$name-$id", mode)
        }
        val store = UserPreferencesStore(context)
        store.update { it.copy(appearance = AppearancePreferences(AccentSource.CUSTOM, densityScale = 1.2f, contentOpacity = 0.5f),
            lyrics = LyricsPreferences(38f, 500, 0f, 34f, false, 22f), ncmQuality = NcmPlaybackQuality.LOSSLESS) }
        val image = File(directory, "source.jpg")
        instrumentation.context.assets.open("music/01.jpg").use { input -> image.outputStream().use { input.copyTo(it) } }
        store.importBackground(image.toURI().toString())
        image.delete()
        val saved = store.state.value
        assertTrue(File(saved.appearance.backgroundImagePath!!).isFile)
        assertEquals(saved, UserPreferencesStore(context).state.value)
        val invalid = File(directory, "text.txt").apply { writeText("a lyric, not an image") }
        assertTrue(runCatching { store.importBackground(invalid.toURI().toString()) }.isFailure)
        assertEquals(saved, store.state.value)
        assertTrue(File(saved.appearance.backgroundImagePath!!).isFile)
        val replacement = File(directory, "replacement.png")
        val bitmap = android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF17302A.toInt()) }
        replacement.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        store.importBackground(replacement.toURI().toString())
        assertFalse(File(saved.appearance.backgroundImagePath!!).exists())
        assertTrue(File(store.state.value.appearance.backgroundImagePath!!).isFile)
        assertEquals(saved.lyrics, store.state.value.lyrics)
        assertEquals(saved.ncmQuality, store.state.value.ncmQuality)
    }
}
