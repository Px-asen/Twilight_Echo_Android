package com.twilightecho.android.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.adapters.*
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Instrumentation restarts the target app. Run only after pausing user playback. */
@RunWith(AndroidJUnit4::class)
class P2UsbPcmOutputTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val json = DefaultCoreClient.DefaultJson
    private fun source(directory: File) = File(directory, "source.flac").also { file ->
        instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
    private fun noUsb() {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assumeTrue("Negative test requires no connected USB output", manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .none { it.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY) })
    }
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(15000) { while (!condition()) delay(50) }

    @Test fun noUsbRejectsStrictOutputRetainsPauseAndRecoversOrdinaryPlayback() = runBlocking {
        noUsb()
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS))
        val directory = File(context.cacheDir, "p2-usb-pcm-${System.nanoTime()}").apply { mkdirs() }
        val file = source(directory)
        val audio = NativeAudioEngineAdapter(appContext = context)
        val errors = CopyOnWriteArrayList<AudioEvent.EngineError>()
        val subscription = audio.subscribe { if (it is AudioEvent.EngineError) errors += it }
        val strict = CoreEqConfigView(dsp = CoreDspConfig(usbPcmPassthrough = true))
        try {
            audio.setDspConfig(json.encodeToString(strict))
            try {
                audio.load(AudioSourceDescriptor(file.absolutePath, file.absolutePath, startTimeSeconds = 6.25))
                fail("No USB device must not be silently routed to a shared speaker")
            } catch (error: java.io.IOException) {
                assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("USB"))
            }
            val refused = audio.getSnapshot()
            assertEquals("paused", refused.state)
            assertEquals(6250L, refused.positionMs)
            assertFalse(refused.usbPcmPreferenceAccepted)
            assertFalse(refused.usbPcmFormatMatched)
            assertFalse(refused.outputPerfect)
            assertNull(refused.actualSampleRate)
            assertTrue(errors.isNotEmpty())
            // Disabling strict mode is preparation only; it does not start/unmute playback.
            audio.setDspConfig(json.encodeToString(strict.copy(dsp = strict.dsp.copy(usbPcmPassthrough = false))))
            assertEquals("paused", audio.getSnapshot().state)
            assertEquals(6250L, audio.getSnapshot().positionMs)
            audio.play(null)
            await { audio.getSnapshot().positionMs > 6500 }
            val ordinary = audio.getSnapshot()
            assertEquals("playing", ordinary.state)
            assertFalse(ordinary.usbPcmPreferenceAccepted)
            assertFalse(ordinary.outputPerfect)
            assertNull(ordinary.errorMessage)
            assertTrue(ordinary.actualSampleRate!! > 0)
            audio.pause(); audio.seek(6250)
            assertEquals("paused", audio.getSnapshot().state)
            assertEquals(6250L, audio.getSnapshot().positionMs)
            val reports = File(context.getExternalFilesDir(null), "p2-usb-pcm").apply { mkdirs() }
            File(reports, "no-usb-recovery.txt").writeText("refused=$refused\nordinary=$ordinary\nerrors=$errors\n")
        } finally { audio.close(); subscription.cancel(); directory.deleteRecursively() }
    }

    @Test fun realQuickJsPersistsIndependentRequestWithoutSavingSystemPreferenceFacts() = runBlocking {
        noUsb()
        val directory = File(context.cacheDir, "p2-usb-store-${System.nanoTime()}").apply { mkdirs() }
        suspend fun session(action: suspend (DefaultCoreClient) -> Unit) {
            val audio = NativeAudioEngineAdapter(appContext = context)
            val resources = AndroidResourceAccessAdapter(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val host = QuickJsRuntimeHost(context, CoreHostPorts(object : ClockPort {
                override fun nowUnixMs() = System.currentTimeMillis()
                override fun nowMonotonicMs() = android.os.SystemClock.elapsedRealtime()
            }, object : SchedulerPort {
                override fun schedule(delayMs: Long, task: () -> Unit) = CancelHandle {}
            }, AndroidSessionStoreAdapter(directory), resources, audio))
            val client = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient("p2-usb-store"), host, scope)
            try { host.start(); check(client.reconcileState().isSuccess); action(client) }
            finally { host.shutdown(); scope.cancel(); audio.close(); resources.close() }
        }
        try {
            session { client ->
                val before = client.snapshot.value!!.eqConfig!!
                assertFalse(before.dsp.usbPcmPassthrough)
                if (!before.bitPerfect) {
                    assertTrue(client.setEqConfig(before.copy(dsp = before.dsp.copy(usbPcmPassthrough = true))).isFailure)
                    assertTrue(client.reconcileState().isSuccess)
                    assertEquals(before, client.snapshot.value!!.eqConfig)
                }
                // The real core defaults to continuity-first. Choose source-first explicitly;
                // enabling USB itself must not change the existing policy or any other setting.
                val followSource = before.copy(bitPerfect = true)
                client.setEqConfig(followSource).getOrThrow()
                val requested = followSource.copy(dsp = followSource.dsp.copy(usbPcmPassthrough = true))
                client.setEqConfig(requested).getOrThrow()
                assertTrue(client.reconcileState().isSuccess)
                assertEquals(requested, client.snapshot.value!!.eqConfig)
            }
            session { client ->
                val snapshot = client.snapshot.value!!
                assertTrue(snapshot.eqConfig!!.dsp.usbPcmPassthrough)
                assertTrue(snapshot.eqConfig!!.bitPerfect)
                assertFalse(snapshot.playback!!.usbPcmPreferenceAccepted)
                assertFalse(snapshot.playback!!.usbPcmFormatMatched)
                assertFalse(snapshot.playback!!.outputPerfect)
            }
        } finally { directory.deleteRecursively() }
    }
}
