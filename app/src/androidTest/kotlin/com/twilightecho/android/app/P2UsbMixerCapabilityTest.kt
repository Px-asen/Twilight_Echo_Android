package com.twilightecho.android.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only reads mixer APIs. Instrumentation restarts the target app: run while playback is paused. */
@RunWith(AndroidJUnit4::class)
class P2UsbMixerCapabilityTest {
    @Test fun recordPlatformMixerCapabilitiesReadOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("capturedAtUnixMs", System.currentTimeMillis())
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("packageName", context.packageName)
            .put("modifyAudioSettingsGranted", context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) == PackageManager.PERMISSION_GRANTED)
            .put("readOnly", true)
            .put("apiAvailable", Build.VERSION.SDK_INT >= 34)
        var failures = 0
        if (Build.VERSION.SDK_INT >= 34) {
            failures = captureApi34(context, report)
        } else {
            report.put("status", "api_unavailable").put("devices", JSONArray())
        }
        val directory = File(context.getExternalFilesDir(null), "p2-usb-assessment").apply { mkdirs() }
        val file = File(directory, "usb-mixer-capabilities.json")
        file.writeText(report.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("usbMixerReport", file.absolutePath) })
        assertTrue(file.length() > 0)
        assertEquals("Platform query exceptions are recorded in ${file.absolutePath}", 0, failures)
    }

    @RequiresApi(34)
    private fun captureApi34(context: Context, report: JSONObject): Int {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val inventory = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val devices = JSONArray()
        var failures = 0
        var usbCount = 0
        var bitPerfectUsbCount = 0
        inventory.forEach { device ->
            val usb = device.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
            if (usb) usbCount++
            val entry = JSONObject().put("id", device.id).put("type", device.type)
                .put("name", device.productName.toString()).put("usb", usb)
                .put("sampleRates", JSONArray(device.sampleRates.toList()))
                .put("encodings", JSONArray(device.encodings.toList()))
                .put("channelMasks", JSONArray(device.channelMasks.toList()))
                .put("channelIndexMasks", JSONArray(device.channelIndexMasks.toList()))
            try {
                val supported = manager.getSupportedMixerAttributes(device)
                val bitPerfectCount = supported.count { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
                if (usb && bitPerfectCount > 0) bitPerfectUsbCount++
                entry.put("queryStatus", "returned")
                    .put("supportedMixerAttributes", JSONArray(supported.map(::mixerJson)))
                    .put("bitPerfectAttributeCount", bitPerfectCount)
                    // This getter returns a preference, not the currently active output mode.
                    .put("preferredMixerAttributes", manager.getPreferredMixerAttributes(attributes, device)?.let(::mixerJson) ?: JSONObject.NULL)
            } catch (error: Exception) {
                failures++
                entry.put("queryStatus", "exception").put("errorType", error.javaClass.name)
                    .put("errorMessage", error.message ?: "")
            }
            devices.put(entry)
        }
        report.put("devices", devices).put("usbOutputCount", usbCount)
            .put("bitPerfectUsbOutputCount", bitPerfectUsbCount).put("queryExceptionCount", failures)
            .put("status", when {
                failures > 0 -> "query_failed"
                usbCount == 0 -> "no_usb_output"
                bitPerfectUsbCount > 0 -> "bit_perfect_advertised"
                else -> "bit_perfect_not_advertised"
            })
            // Android maps native query failure to an empty list/null in these public getters.
            .put("emptyResultMeaning", "not_advertised_or_native_query_failed")
        return failures
    }

    @RequiresApi(34)
    private fun mixerJson(mixer: AudioMixerAttributes): JSONObject = JSONObject()
        .put("behavior", mixer.mixerBehavior)
        .put("sampleRate", mixer.format.sampleRate)
        .put("encoding", mixer.format.encoding)
        .put("channelMask", mixer.format.channelMask)
        .put("channelIndexMask", mixer.format.channelIndexMask)
        .put("channelCount", mixer.format.channelCount)
}
