package com.twilightecho.android.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.AudioSourceDescriptor
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit hardware run: one connected DAC, headphones removed or hardware volume turned down. */
@RunWith(AndroidJUnit4::class)
class P3UsbDirectDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun connectedDacPlaysSeeksReopensChangesRateAndReturnsToSystemOutput() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val directory = File(context.cacheDir, "p3-usb-device-${System.nanoTime()}").apply { mkdirs() }
        val reports = File(context.getExternalFilesDir(null), "p3-usb-device").apply { mkdirs() }
        val trace = File(reports, "pcm-lifecycle.txt").apply { writeText("") }
        val audio = NativeAudioEngineAdapter(appContext = context)
        val json = DefaultCoreClient.DefaultJson
        fun record(label: String, value: Any?) { trace.appendText("$label=$value\n") }
        val errors = audio.subscribe { record("event", it) }
        suspend fun snapshot(label: String) = audio.getSnapshot().also { record(label, json.encodeToString(it)) }
        suspend fun await(condition: suspend () -> Boolean) = withTimeout(15000) { while (!condition()) delay(50) }
        try {
            val inventory = audio.usbDirectOutputs()
            record("inventory", inventory)
            assertEquals("Select a single connected DAC for this explicit hardware test", 1, inventory.size)
            val dac = inventory.single()
            val authorization = async(Dispatchers.Default) { audio.authorizeUsbDirectOutput(dac.id) }
            if (!dac.permissionGranted) {
                assertTrue("The USB authorization dialog must name the selected DAC",
                    device.wait(Until.hasObject(By.pkg("com.android.systemui").textContains(dac.name)), 15000))
                checkNotNull(device.findObject(By.pkg("com.android.systemui").res("android:id/button1"))).click()
            }
            assertTrue("The selected DAC must be authorized", withTimeout(15000) { authorization.await() })
            // The source itself is below -50 dBFS; software gain is an additional precaution.
            audio.setVolume(0.05)
            val direct = CoreEqConfigView(bitPerfect = true, dsp = CoreDspConfig(outputDeviceId = dac.id))
            audio.setDspConfig(json.encodeToString(direct))
            var lastFile: File? = null
            for (rate in listOf(44100, 48000)) {
                val wave = ByteBuffer.wrap(toneWave(listOf(20.0 to 997.0), rate)).order(ByteOrder.LITTLE_ENDIAN)
                for (offset in 44 until wave.capacity() step 2) wave.putShort(offset, (wave.getShort(offset) / 64).toShort())
                val file = File(directory, "quiet-$rate.wav").apply { writeBytes(wave.array()) }
                lastFile = file
                audio.loadQueue(listOf(CoreTrackView("usb-$rate", title = "Quiet USB $rate",
                    source = file.absolutePath, duration = 20.0)), 0)
                audio.load(AudioSourceDescriptor(file.absolutePath, file.absolutePath))
                audio.play(null)
                await { audio.getSnapshot().let { it.state == "playing" && it.positionMs > 300 } }
                val playing = snapshot("direct-$rate")
                assertEquals("usb-direct", playing.outputBackend)
                assertTrue("An opened direct stream must have claimed its USB interfaces", playing.exclusive)
                assertEquals(dac.id, playing.actualDeviceId)
                assertEquals(rate, playing.actualSampleRate)
                assertEquals(2, playing.actualChannels)
                assertFalse(playing.outputPerfect)
                assertEquals("backend_not_output_perfect", playing.perfectReasonCode)
                assertEquals("USB 直连独占；数字采样保持待验收", playing.perfectReason)
                assertNull(playing.errorMessage)

                val hardware = audio.readUsbHardwareVolume(dac.id)
                record("hardware-$rate", hardware)
                if (rate == 44100) {
                    val writable = hardware.channels.firstOrNull { channel ->
                        channel.volumeWritable && channel.volume?.let { volume ->
                            volume.ranges.any { it.contains(volume.currentDb256) && volume.currentDb256 > it.minimumDb256 }
                        } == true
                    }
                    if (writable != null) {
                        val original = checkNotNull(writable.volume)
                        val range = original.ranges.first { it.contains(original.currentDb256) && original.currentDb256 > it.minimumDb256 }
                        val step = range.resolutionDb256.takeIf { it > 0 } ?: 256
                        val lower = (original.currentDb256 - step).coerceAtLeast(range.minimumDb256)
                        try {
                            val actual = audio.setUsbHardwareVolume(writable.id, lower)
                            record("hardware-volume-lowered", actual)
                            assertEquals(lower, actual.currentDb256)
                        } finally {
                            val restored = audio.setUsbHardwareVolume(writable.id, original.currentDb256)
                            record("hardware-volume-restored", restored)
                            assertEquals(original.currentDb256, restored.currentDb256)
                        }
                    } else record("hardware-volume-write", "no writable channel with a lower valid value")
                    hardware.channels.firstOrNull { it.muteWritable && it.muted != null }?.let { channel ->
                        val original = checkNotNull(channel.muted)
                        try {
                            assertTrue(audio.setUsbHardwareMute(channel.id, true))
                            record("hardware-mute", true)
                        } finally {
                            assertEquals(original, audio.setUsbHardwareMute(channel.id, original))
                            record("hardware-mute-restored", original)
                        }
                    }
                }

                audio.pause()
                val paused = snapshot("paused-$rate")
                assertEquals("paused", paused.state)
                assertFalse("Pause must release the direct interface", paused.exclusive)
                assertNull(paused.actualDeviceId)
                audio.seek(1500)
                assertEquals(1500L, audio.getSnapshot().positionMs)
                audio.play(null)
                await { audio.getSnapshot().positionMs > 1800 }
                assertTrue(snapshot("resumed-$rate").exclusive)
                audio.stop()
                val stopped = snapshot("stopped-$rate")
                assertEquals("idle", stopped.state)
                assertFalse(stopped.exclusive)
            }
            audio.setDspConfig(json.encodeToString(direct.copy(dsp = direct.dsp.copy(outputDeviceId = "auto"))))
            val file = checkNotNull(lastFile)
            audio.load(AudioSourceDescriptor(file.absolutePath, file.absolutePath))
            audio.play(null)
            await { audio.getSnapshot().let { it.state == "playing" && it.positionMs > 300 } }
            val system = snapshot("system-after-release")
            assertEquals("oboe", system.outputBackend)
            assertFalse(system.exclusive)
            assertTrue(system.actualSampleRate!! > 0)
            assertNull(system.errorMessage)
            audio.stop()
            record("result", "passed")
        } catch (failure: Throwable) {
            record("failure", failure.stackTraceToString())
            throw failure
        } finally {
            try { audio.close() } finally { errors.cancel(); directory.deleteRecursively() }
        }
    }
}
