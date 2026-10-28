package com.twilightecho.android.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run with POST_NOTIFICATIONS ungranted; no audio/library permission is needed. */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionTest {
    @Test fun requestsNotificationsOnceAndKeepsDenialRecoverableInSettings() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        context.getSharedPreferences("runtime_permissions", Context.MODE_PRIVATE).edit()
            .remove("notifications_requested").commit()
        val permissionPackage = "com.android.permissioncontroller"
        val allow = By.res(permissionPackage, "permission_allow_button")
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(allow), 10_000))
            val directory = File(context.getExternalFilesDir(null), "cover-notifications").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(directory, "notification-permission.png")))
            device.findObject(By.res(permissionPackage, "permission_deny_button")).click()
            device.wait(Until.gone(allow), 5_000)
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertFalse(device.wait(Until.hasObject(allow), 1_500))
        }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use {
            assertFalse(device.wait(Until.hasObject(allow), 1_500))
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}
