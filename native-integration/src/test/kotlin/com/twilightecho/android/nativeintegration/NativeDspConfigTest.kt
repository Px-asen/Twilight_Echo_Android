package com.twilightecho.android.nativeintegration

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import com.twilightecho.android.coreclient.CoreEqConfigView
import com.twilightecho.android.coreclient.DefaultCoreClient

class NativeDspConfigTest {
    @Test fun dsdModesAndPoliciesUseTheSharedProcessingContractWithoutChangingOtherControls() {
        val initial = CoreEqConfigView()
        val json = DefaultCoreClient.DefaultJson
        assertEquals("pcm", initial.dsp.dsdOutputMode)
        for (mode in listOf("pcm", "auto", "native", "dop")) {
            val config = initial.copy(dsp = initial.dsp.copy(outputDeviceId = direct.device,
                dsdOutputMode = mode, dsdRatePolicy = "downrate", dopConfirmedDeviceKey = "8369:1:1.00"))
            val state = Json.parseToJsonElement(nativeDspState(config, json, 1)).jsonObject
            val processing = state.getValue("processing").jsonObject
            assertEquals(mode, processing.getValue("dsdOutputMode").jsonPrimitive.content)
            assertEquals("downrate", processing.getValue("dsdRatePolicy").jsonPrimitive.content)
            assertEquals(initial.bandsDb, config.bandsDb)
            assertEquals(1.0, config.dsp.playbackRate, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            nativeDspState(initial.copy(dsp = initial.dsp.copy(outputDeviceId = direct.device, dsdOutputMode = "dop")), json, 1)
        }
        for (dsp in listOf(initial.dsp.copy(dsdOutputMode = "guess"), initial.dsp.copy(dsdRatePolicy = "guess")))
            assertThrows(IllegalArgumentException::class.java) { nativeDspState(initial.copy(dsp = dsp), json, 1) }
    }

    @Test fun mapsPhoneEqAndGaplessToSharedDspContract() {
        val obj = Json.parseToJsonElement(nativeDspConfig(
            """{"enabled":true,"preampDb":-6,"bandsDb":[1,2,3,4,5,6,7,8,9,10],"gapless":false,"bitPerfect":false}""", Json)).jsonObject
        assertTrue(obj.getValue("dspEnabled").jsonPrimitive.boolean)
        assertTrue(obj.getValue("eqEnabled").jsonPrimitive.boolean)
        assertFalse(obj.getValue("gapless").jsonPrimitive.boolean)
        assertEquals(-6.0, obj.getValue("eqPreamp").jsonPrimitive.double, 0.0)
        val bands = obj.getValue("eqBands").jsonArray
        assertEquals(10, bands.size)
        assertEquals(31.0, bands.first().jsonObject.getValue("frequency").jsonPrimitive.double, 0.0)
        assertEquals(16000.0, bands.last().jsonObject.getValue("frequency").jsonPrimitive.double, 0.0)
        assertEquals(10.0, bands.last().jsonObject.getValue("gain").jsonPrimitive.double, 0.0)
    }
    @Test fun bypassDoesNotInventBitPerfectAndGraphPassesThrough() {
        val obj = Json.parseToJsonElement(nativeDspConfig("""{"enabled":false,"bandsDb":[],"bitPerfect":true}""", Json)).jsonObject
        assertFalse(obj.getValue("dspEnabled").jsonPrimitive.boolean)
        assertFalse("outputPerfect" in obj)
        val graph = """{"dspEnabled":true,"graph":{"nodes":[]}}"""
        assertEquals(graph, nativeDspConfig(graph, Json))
    }

    @Test fun usbDirectSelectsItsOwnBackendAndDoesNotAppendAnAAudioFlagToTheHostPath() {
        val config = CoreEqConfigView()
        val direct = config.copy(dsp = config.dsp.copy(outputDeviceId = "usb-direct:/dev/bus/usb/001/002", exclusive = true))
        assertEquals(NativeOutputRoute("usb-direct", direct.dsp.outputDeviceId), nativeOutputRoute(direct))
        assertEquals(NativeOutputRoute("oboe", "auto"), nativeOutputRoute(config))
        assertEquals(NativeOutputRoute("oboe", "17:exclusive"), nativeOutputRoute(config.copy(dsp = config.dsp.copy(outputDeviceId = "17", exclusive = true))))
        assertThrows(IllegalArgumentException::class.java) { nativeOutputConfig(direct.copy(dsp = direct.dsp.copy(usbPcmPassthrough = true))) }
    }

    private val system = NativeOutputRoute("oboe", "auto")
    private val direct = NativeOutputRoute("usb-direct", "usb-direct:/dev/bus/usb/001/002")

    @Test fun directRouteIsPausedBeforeSelectionAndCommitsWithoutRestoringOrPlaying() {
        val calls = mutableListOf<String>()
        applyNativeOutputRoute(system, direct, { calls += "pause" }, { calls += it.backend },
            { calls += "apply" }, { fail("Successful route must not restore") })
        assertEquals(listOf("pause", "usb-direct", "apply"), calls)
    }

    @Test fun rejectedGraphRestoresTheAcceptedRouteAndSettingsWhileRemainingPaused() {
        var selected = system
        var paused = false
        var restored = false
        val rejection = java.io.IOException("IR preparation failed")
        val error = assertThrows(java.io.IOException::class.java) {
            applyNativeOutputRoute(system, direct, { paused = true }, { selected = it },
                { assertEquals(direct, selected); throw rejection }, { restored = true })
        }
        assertSame(rejection, error)
        assertEquals(system, selected)
        assertTrue(paused && restored)
    }

    @Test fun partialDeviceSelectionFailureAlsoRestoresThePreviousBackend() {
        var backend = system.backend
        var restored = false
        assertThrows(java.io.IOException::class.java) {
            applyNativeOutputRoute(system, direct, {}, {
                backend = it.backend
                if (it == direct) throw java.io.IOException("Device selection failed")
            }, { fail("Must not apply after device rejection") }, { restored = true })
        }
        assertEquals(system.backend, backend)
        assertTrue(restored)
    }

    @Test fun failedCommitRestoresSettingsAlreadyChangedByTheCandidate() {
        var selected = direct
        var revision = "accepted"
        val calls = mutableListOf<String>()
        assertThrows(java.io.IOException::class.java) {
            applyNativeOutputRoute(direct, system, { calls += "pause" }, { selected = it }, {
                revision = "candidate"
                throw java.io.IOException("Route commit failed")
            }, { revision = "accepted"; calls += "restore" })
        }
        assertEquals(direct, selected)
        assertEquals("accepted", revision)
        assertEquals(listOf("pause", "pause", "restore"), calls)
    }

    @Test fun recoveryFailureIsReportedAlongsideTheOriginalRejection() {
        val rejection = java.io.IOException("Graph rejected")
        val recovery = java.io.IOException("Previous route unavailable")
        val error = assertThrows(java.io.IOException::class.java) {
            applyNativeOutputRoute(system, direct, {}, {}, { throw rejection }, { throw recovery })
        }
        assertSame(rejection, error.cause)
        assertEquals(listOf(recovery), error.suppressed.toList())
        assertTrue(error.message!!.contains(recovery.message!!))
    }

    @Test fun unchangedRouteKeepsTheExistingDspFailureContract() {
        val rejection = java.io.IOException("Graph rejected")
        val error = assertThrows(java.io.IOException::class.java) {
            applyNativeOutputRoute(system, system, { fail("No route change") }, { fail("No route change") },
                { throw rejection }, { fail("No route transaction to restore") })
        }
        assertSame(rejection, error)
    }
}
