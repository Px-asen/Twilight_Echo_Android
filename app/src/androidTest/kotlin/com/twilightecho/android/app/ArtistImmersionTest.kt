package com.twilightecho.android.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pixel checks use a deliberately colored canvas: fading to a solid grey must fail. */
@RunWith(AndroidJUnit4::class)
class ArtistImmersionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val songs = listOf(CoreTrackView("immersion-1", title = "暮色", artist = "北岸", album = "夜航"))

    private fun appearance(): UserPreferences {
        val image = File(context.cacheDir, "artist-immersion-background.png")
        val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 400f, 800f,
                intArrayOf(0xFFB84783.toInt(), 0xFF5B94AE.toInt()), null, Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, 400f, 800f, paint)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return UserPreferences(appearance = AppearancePreferences(backgroundStyle = BackgroundStyle.IMAGE,
            backgroundImagePath = image.absolutePath))
    }

    private fun assertSeamless(name: String) {
        rule.waitForIdle()
        val hero = rule.onNodeWithTag("immersiveArtistHero").fetchSemanticsNode().boundsInRoot
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "artist-immersion").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val boundary = hero.bottom.roundToInt()
        assertTrue("The transition must be visible", boundary + 6 < bitmap.height)
        // Stay away from text and buttons; sample both edges of the actual rendered boundary.
        for (x in listOf(4, bitmap.width - 5)) {
            val below = bitmap.getPixel(x, boundary + 6)
            assertTrue("The custom canvas must be loaded", maxOf(AndroidColor.red(below), AndroidColor.green(below), AndroidColor.blue(below)) -
                minOf(AndroidColor.red(below), AndroidColor.green(below), AndroidColor.blue(below)) > 8)
            for (y in boundary - 6..boundary + 6) {
                val pixel = bitmap.getPixel(x, y)
                val difference = maxOf(abs(AndroidColor.red(pixel) - AndroidColor.red(below)),
                    abs(AndroidColor.green(pixel) - AndroidColor.green(below)),
                    abs(AndroidColor.blue(pixel) - AndroidColor.blue(below)))
                assertTrue("Portrait must dissolve into the canvas without even a single-pixel seam: y=$y, delta=$difference", difference <= 4)
            }
        }
    }

    @Test fun localArtistWithoutPhotoIsCompactAndBlendsIntoThePage() {
        val ui = PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.LIBRARY,
            activeLibraryTab = LibrarySectionTab.ARTISTS, isLoadingLibrary = false,
            currentTrack = songs.first(), libraryTracks = songs, allTracks = songs, libraryTrackCount = 1,
            artists = listOf(CoreArtistCollectionView("immersion-artist", "北岸", 1, 1, songs.map { it.id })),
            albums = listOf(CoreAlbumCollectionView("immersion-album", "夜航", "北岸", 1, 0.0, songs.map { it.id })),
            preferences = appearance())
        var played = emptyList<String>()
        rule.setContent { PlayerAppContent(ui, PlayerActions(playTracks = { tracks, _ -> played = tracks.map { it.id } })) }
        rule.onNodeWithTag("libraryArtistPlaceholder-immersion-artist").performClick()
        rule.onNodeWithTag("artistPortrait", useUnmergedTree = true).assertDoesNotExist()
        assertTrue("No full-size blank portrait", rule.onNodeWithTag("immersiveArtistHero").getUnclippedBoundsInRoot().let { it.bottom - it.top } < 280.dp)
        rule.onNodeWithText("暂无歌手简介").assertIsDisplayed()
        rule.onNodeWithText("播放全部").assertIsDisplayed().performClick()
        assertEquals(songs.map { it.id }, played)
        assertSeamless("local-no-portrait")
        rule.onNodeWithTag("artistAlbumsTab").performClick().assertIsSelected()
        rule.onNodeWithText("夜航", substring = false).performClick()
        rule.onNodeWithContentDescription("返回音乐库").performClick()
        rule.onNodeWithTag("artistAlbumsTab").assertIsSelected()
        rule.onNodeWithTag("artistSongsTab").performClick().assertIsSelected()
    }

    @Test fun desktopPortraitDissolvesIntoCustomBackdropAndKeepsBiography() {
        val portrait = instrumentation.context.assets.open("music/01.jpg").use {
            "data:image/jpeg;base64," + Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
        }
        val artist = CatalogEntity("immersion-desktop-artist", "artist", "北岸")
        val ui = PlayerUiState(homeAvailable = false, activeMainTab = MainDestinationTab.LIBRARY,
            activeLibraryTab = LibrarySectionTab.ARTISTS, libraryDevice = "desktop", isLoadingLibrary = false,
            librarySource = LibrarySource.Desktop("desktop", "library", "http://127.0.0.1", "书房电脑"),
            desktopLibrary = DesktopBrowseState(selected = artist, metadata = EntityMetadataDetail(artist.id, 1, "北岸",
                MetadataIntroduction("让旋律在城市与海之间，自然流动。"), manualArtwork = PersonalArtworkPreview(portrait)),
                sources = CatalogSourcePage(1, 0)), preferences = appearance())
        rule.setContent { PlayerAppContent(ui, PlayerActions()) }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("desktopMetadataArtwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("让旋律在城市与海之间，自然流动。").assertIsDisplayed()
        rule.onNodeWithTag("targetPhone").assertDoesNotExist()
        assertSeamless("desktop-portrait")
    }

    @Test fun streamingPortraitUsesTheSameContinuousCanvas() {
        val bytes = instrumentation.context.assets.open("music/02.jpg").use { it.readBytes() }
        val artist = StreamingCollection("immersion-streaming", "artist", "北岸", "网易云音乐", "https://fixture.invalid/artist-immersion.jpg")
        val detail = StreamingDetail("artist:immersion-streaming", "北岸", artist.subtitle, collection = artist,
            artistProfile = StreamingArtistProfile("北岸", "让旋律在城市与海之间，自然流动。", artist.cover, loading = false),
            tracks = songs, total = 1, loading = false)
        val ui = PlayerUiState(activeMainTab = MainDestinationTab.LIBRARY, activeLibraryTab = LibrarySectionTab.STREAMING,
            streaming = StreamingUiState(access = StreamingAccess.READY, detail = detail), preferences = appearance())
        rule.setContent {
            CompositionLocalProvider(LocalProviderArtworkData provides { _, _ -> bytes }) { PlayerAppContent(ui, PlayerActions()) }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("artistPortrait", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertSeamless("streaming-portrait")
        rule.onNodeWithTag("artistSongsTab").performScrollTo().assertIsSelected()
    }

    @Test fun playbackSelectorIsOnlyOnHomeRootAndStillSwitchesTargets() {
        val ui = mutableStateOf(PlayerUiState(activeMainTab = MainDestinationTab.HOME,
            librarySource = LibrarySource.Desktop("desktop", "library", "http://127.0.0.1", "书房电脑"),
            isLoadingLibrary = false, streaming = StreamingUiState(access = StreamingAccess.READY,
                playlists = List(12) { StreamingCollection("p$it", "playlist", "夜航歌单 $it", owned = it < 6) },
                recommendedPlaylists = List(6) { StreamingCollection("r$it", "playlist", "推荐歌单 $it") })))
        rule.setContent { PlayerAppContent(ui.value, PlayerActions(
            selectTab = { ui.value = ui.value.copy(activeMainTab = it) },
            playbackTarget = { phone -> ui.value = ui.value.copy(operatingMode = if (phone) CoreOperatingMode.StandaloneLocal
                else CoreOperatingMode.RemoteDesktopCore("http://127.0.0.1", "书房电脑")) },
            streaming = StreamingActions(group = { group -> ui.value = ui.value.copy(streaming = ui.value.streaming.copy(
                detail = StreamingDetail("group:$group", group.title, group = group, loading = false))) },
                back = { ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail = null)) }))) }
        rule.onNodeWithTag("targetPhone").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag("targetDesktop").performClick().assertIsSelected()
        rule.onNodeWithTag("targetPhone").performClick().assertIsSelected()
        repeat(2) { rule.onNodeWithTag("home").performTouchInput { swipeUp(durationMillis = 500) } }
        rule.onNodeWithTag("playbackHeader").assertIsNotDisplayed()
        repeat(6) { rule.onNodeWithTag("home").performTouchInput { swipeDown(durationMillis = 500) } }
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()
        for (tab in listOf(MainDestinationTab.LIBRARY, MainDestinationTab.SEARCH, MainDestinationTab.SETTINGS)) {
            rule.onNodeWithTag("nav-$tab").performClick()
            rule.onNodeWithTag("playbackHeader").assertDoesNotExist()
            rule.onNodeWithTag("targetPhone").assertDoesNotExist()
        }
        rule.onNodeWithTag("nav-HOME").performClick()
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()
        rule.onNodeWithTag("home-liked").performScrollTo().performClick()
        rule.onNodeWithTag("streamingDetail").assertIsDisplayed()
        rule.onNodeWithTag("playbackHeader").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回流媒体音乐").performClick()
        rule.onNodeWithTag("targetPhone").assertIsDisplayed()

        // ABI v2 providers use a separate detail model, but follow the same Home-root rule.
        rule.runOnIdle { ui.value = ui.value.copy(activeHomePluginId = "fixture.provider",
            homeProviders = listOf(HomeProviderOption("fixture.provider", "fixture", "测试音源")),
            providerHome = ProviderHomeUiState(detail = StreamingDetail("v2-home:fixture", "精选", loading = false))) }
        rule.onNodeWithTag("streamingDetail").assertIsDisplayed()
        rule.onNodeWithTag("playbackHeader").assertDoesNotExist()
    }
}
