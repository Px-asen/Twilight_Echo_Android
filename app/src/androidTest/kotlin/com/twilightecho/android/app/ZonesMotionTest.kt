package com.twilightecho.android.app

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File

/**
 * Motion on the real frame clock. There is deliberately no compose test rule here: it would pause the infinite
 * animations this class exists to see. Pixels of consecutive frames are compared, tapped through UiAutomator.
 */
@RunWith(AndroidJUnit4::class)
class ZonesMotionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    /** A stuck gesture or scenario fails the test instead of holding the whole run. */
    @get:Rule val timeout: Timeout = Timeout.seconds(90)

    @After fun restoreAnimationScale() {
        device.executeShellCommand("settings delete global animator_duration_scale")
    }

    private fun frame(): Bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()).copy(Bitmap.Config.ARGB_8888, false)

    private fun changedPixels(first: Bitmap, second: Bitmap, region: Rect): Int {
        var changed = 0
        for (y in region.top until region.bottom) for (x in region.left until region.right) {
            if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
        }
        return changed
    }

    private fun find(tag: String, timeoutMs: Long = 8_000): UiObject2 {
        val found = device.wait(Until.findObject(By.res(tag)), timeoutMs)
        assertNotNull("no node tagged $tag on screen", found)
        return found
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "zones-shots").apply { mkdirs() }
        val file = File(directory, "$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        device.executeShellCommand("mkdir -p /sdcard/Pictures/TwilightZones")
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Pictures/TwilightZones/$name.png")
    }

    /** Swipes the page up until [tag] is fully above the dock, then returns it; never taps a clipped sliver. */
    private fun reveal(tag: String): UiObject2 {
        repeat(6) {
            val node = find(tag)
            val visible = node.visibleBounds
            if (visible.height() >= 96 && visible.bottom < device.displayHeight * 0.72) return node
            device.swipe(device.displayWidth / 2, (device.displayHeight * 0.7).toInt(), device.displayWidth / 2,
                (device.displayHeight * 0.45).toInt(), 30)
            Thread.sleep(500)
        }
        throw AssertionError("$tag never came fully into view")
    }

    /** Frames are compared after decodes and page transitions have finished. */
    private fun settle(ms: Long = 1_400) = Thread.sleep(ms)

    private fun launch(world: ZoneWorld, block: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var host: ComponentActivity? = null
            scenario.onActivity { activity -> host = activity; activity.setContent { ZoneWorldContent(world) } }
            try {
                block()
            } finally {
                // close() and onActivity wait for an idle main looper, which never comes while infinite
                // animations run; runOnMainSync does not, so the animations are dropped through it first.
                instrumentation.runOnMainSync { host?.setContent {} }
            }
        }
    }

    @Test fun onlyThePlayingCardMovesOnTheWall() {
        launch(ZoneWorld(windowStateZones())) {
            val playing = find("zoneCard-$LOCAL").visibleBounds
            val paused = find("zoneCard-$STUDY").visibleBounds
            settle()
            val first = frame()
            Thread.sleep(430)
            val second = frame()
            save(second, "20-live-wall")
            assertTrue("the playing card should animate", changedPixels(first, second, playing) > 0)
            assertEquals("a paused card should be still", 0, changedPixels(first, second, paused))
        }
    }

    @Test fun playingDetailAnimatesAndPausingStopsIt() {
        val world = ZoneWorld(windowStateZones())
        launch(world) {
            find("zoneCard-$LOCAL").click()
            val chip = find("zoneDetailStatus").visibleBounds
            settle()
            val seek = find("zoneSeek").visibleBounds
            val first = frame()
            Thread.sleep(430)
            val second = frame()
            save(second, "21-live-zone-detail")
            assertTrue("the 3-bar indicator should animate", changedPixels(first, second, chip) > 0)
            assertTrue("the wave should travel", changedPixels(first, second, seek) > 0)

            reveal("zoneDetailPlayPause").click()
            // Amplitude 240 ms, cover dim 400 ms, button spring: all of them done well before this.
            settle(1_200)
            assertTrue(world.log.toString(), "transport:$LOCAL:pause" in world.log)
            val pausedSeek = find("zoneSeek").visibleBounds
            val third = frame()
            Thread.sleep(430)
            val fourth = frame()
            save(fourth, "22-live-zone-paused")
            assertEquals("the wave rests as a line", 0, changedPixels(third, fourth, pausedSeek))
            // The chip may have scrolled away with the swipe; when it is on screen it must be still too.
            val pausedChip = device.findObject(androidx.test.uiautomator.By.res("zoneDetailStatus"))?.visibleBounds
            if (pausedChip != null && pausedChip.height() > 8) {
                assertEquals("no bars while paused", 0, changedPixels(third, fourth, pausedChip))
            }
        }
    }

    @Test fun playingGroupAnimatesOnItsDetailPage() {
        val world = ZoneWorld(groupedZones(), listOf(playingGroup()))
        launch(world) {
            find("zoneGroupCard-$GROUP").click()
            val chip = find("groupDetailPhase").visibleBounds
            settle()
            val first = frame()
            Thread.sleep(430)
            val second = frame()
            save(second, "23-live-group-detail")
            assertTrue("the group's bars should animate", changedPixels(first, second, chip) > 0)
        }
    }

    @Test fun reducedMotionHoldsTheIndicatorStill() {
        device.executeShellCommand("settings put global animator_duration_scale 0")
        launch(ZoneWorld(windowStateZones())) {
            find("zoneCard-$LOCAL").click()
            val chip = find("zoneDetailStatus").visibleBounds
            settle()
            val first = frame()
            Thread.sleep(430)
            val second = frame()
            save(second, "24-live-reduced-motion")
            assertEquals("no animated bars with animations off", 0, changedPixels(first, second, chip))
        }
    }
}
