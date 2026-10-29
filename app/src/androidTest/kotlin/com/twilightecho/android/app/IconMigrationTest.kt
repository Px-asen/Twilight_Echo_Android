package com.twilightecho.android.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class IconMigrationTest {
    @Test fun installedApplicationAndLauncherMatchDesktopArtwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val desktop = instrumentation.context.assets.open("icons/desktop-reference.png").use {
            BitmapFactory.decodeStream(it)!!
        }
        val packageManager = context.packageManager
        assertMatchesDesktop("Application", packageManager.getApplicationIcon(context.packageName), desktop)
        val launchIntent = packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("An installed launcher entry must exist", launchIntent)
        assertMatchesDesktop("Launcher activity", packageManager.getActivityIcon(launchIntent!!), desktop)
    }

    @Test fun launcherThemedAndNotificationUseTheDesktopRibbon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(launcher is AdaptiveIconDrawable)
        val sheet = Bitmap.createBitmap(900, 340, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(28, 28, 30))
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f }
        launcher.setBounds(40, 32, 272, 264)
        launcher.draw(canvas)
        canvas.drawText("Launcher / system mask", 40f, 310f, label)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val monochrome = (launcher as AdaptiveIconDrawable).monochrome
            assertNotNull("Android 13+ themed launcher icon", monochrome)
            monochrome!!.setTint(Color.rgb(30, 55, 105))
            val themed = AdaptiveIconDrawable(ColorDrawable(Color.rgb(190, 210, 255)), monochrome)
            themed.setBounds(335, 32, 567, 264)
            themed.draw(canvas)
            canvas.drawText("Themed / system mask", 335f, 310f, label)
        }
        val notificationId = context.resources.getIdentifier("ic_twilight_notification", "drawable", context.packageName)
        assertTrue(notificationId != 0)
        val notification = context.getDrawable(notificationId)!!
        notification.setBounds(685, 100, 813, 228)
        notification.draw(canvas)
        canvas.drawText("Notification", 685f, 310f, label)
        val directory = File(context.getExternalFilesDir(null), "performance-ui").apply { mkdirs() }
        File(directory, "icons.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertMatchesDesktop(name: String, icon: Drawable, desktop: Bitmap) {
        val actual = Bitmap.createBitmap(desktop.width, desktop.height, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, actual.width, actual.height)
        icon.draw(Canvas(actual))
        val centerX = (actual.width - 1) / 2f
        val centerY = (actual.height - 1) / 2f
        val radius = minOf(actual.width, actual.height) * 0.4f
        var error = 0L
        var samples = 0
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                // Ignore the outer system mask: launchers may use circles or squircles.
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy > radius * radius) continue
                val expected = desktop.getPixel(x, y)
                val pixel = actual.getPixel(x, y)
                error += abs(Color.red(expected) - Color.red(pixel))
                error += abs(Color.green(expected) - Color.green(pixel))
                error += abs(Color.blue(expected) - Color.blue(pixel))
                samples += 3
            }
        }
        val meanError = error.toDouble() / samples
        assertTrue("$name icon differs from desktop artwork: mean RGB error $meanError", meanError < 8.0)
        actual.recycle()
    }
}
