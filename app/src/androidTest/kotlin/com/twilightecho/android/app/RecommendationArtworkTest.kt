package com.twilightecho.android.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Real cover fixtures keep recommendation artwork checks independent of accounts and network. */
@RunWith(AndroidJUnit4::class)
class RecommendationArtworkTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun headersFollowTheFirstSongAcrossLoadingRefreshAndEmptyStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val covers = (1..3).associate { index ->
            "https://recommendation-artwork.test/$index.jpg" to
                instrumentation.context.assets.open("music/0$index.jpg").use { it.readBytes() }
        }
        val songs = covers.keys.mapIndexed { index, cover ->
            CoreTrackView("provider://ncm/${700 + index}", title = listOf("Golden Hour", "A Quiet Return", "Blue Hours")[index],
                artist = "Paper Lantern", album = "Afterglow", source = "provider://ncm/${700 + index}",
                cover = cover, duration = 180.0, ncmSongId = "${700 + index}")
        }
        val ui = mutableStateOf(PlayerUiState(isLoadingLibrary = false, currentTrack = songs[2],
            streaming = StreamingUiState(access = StreamingAccess.READY,
                detail = recommendation(StreamingRecommendation.DAILY))))
        val actions = PlayerActions(streaming = StreamingActions(retry = {
            val detail = ui.value.streaming.detail!!
            ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail = detail.copy(tracks = detail.tracks.reversed())))
        }))
        rule.setContent {
            CompositionLocalProvider(LocalProviderArtworkData provides { url, _ -> covers[url] }) {
                TwilightEchoTheme(darkTheme = true) { PlayerAppContent(ui.value, actions) }
            }
        }

        StreamingRecommendation.entries.forEach { kind ->
            val empty = recommendation(kind)
            rule.runOnIdle { ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail = empty)) }
            rule.onNodeWithTag("streamingDetailArtwork").assertDoesNotExist()

            // Even an unavailable first song supplies the cover; the current player is a different song.
            val first = songs[0].copy(unavailableReason = if (kind == StreamingRecommendation.RADAR) "原平台版权受限" else null)
            rule.runOnIdle {
                ui.value = ui.value.copy(streaming = ui.value.streaming.copy(
                    detail = empty.copy(tracks = listOf(first, songs[1]), total = 2, loading = false)))
            }
            assertCover(covers.getValue(songs[0].cover!!))
            screenshot("${kind.name.lowercase()}-first-song.png")

            rule.onNodeWithText(if (kind == StreamingRecommendation.FM) "换一批" else "刷新").performClick()
            assertCover(covers.getValue(songs[1].cover!!))
            screenshot("${kind.name.lowercase()}-refreshed.png")

            rule.runOnIdle { ui.value = ui.value.copy(streaming = ui.value.streaming.copy(detail = empty.copy(loading = false))) }
            rule.onNodeWithTag("streamingDetailArtwork").assertDoesNotExist()
            rule.onNodeWithText("这里还没有音乐").assertIsDisplayed()
        }
    }

    private fun recommendation(kind: StreamingRecommendation) = StreamingDetail("rec:$kind", kind.title,
        "网易云音乐 · ${kind.caption}", recommendation = kind)

    private fun assertCover(bytes: ByteArray) {
        rule.onNodeWithTag("streamingDetailArtwork").assertIsDisplayed()
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val expected = Bitmap.createScaledBitmap(original, 24, 24, true)
        rule.waitUntil(10_000) {
            val captured = rule.onNodeWithTag("streamingDetailArtwork").captureToImage().asAndroidBitmap()
            val actual = Bitmap.createScaledBitmap(captured, 24, 24, true)
            // Ignore rounded corners and tolerate image resampling, while comparing the actual rendered cover.
            var difference = 0L
            for (y in 4 until 20) for (x in 4 until 20) {
                val a = actual.getPixel(x, y)
                val e = expected.getPixel(x, y)
                difference += abs(Color.red(a) - Color.red(e)) + abs(Color.green(a) - Color.green(e)) +
                    abs(Color.blue(a) - Color.blue(e))
            }
            difference / (16.0 * 16.0 * 3.0) < 14.0
        }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "recommendation-artwork").apply { mkdirs() }
        File(directory, name).outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
