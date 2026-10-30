package com.twilightecho.android.nativeintegration

import com.twilightecho.android.coreclient.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UsbPcmLeaseTest {
    private val tuple = UsbMixerTuple(48000, "int32", 12)
    private inner class Platform : UsbMixerPlatform {
        var connected = true
        var usb = true
        var route = listOf(7)
        var formats = listOf(tuple)
        var preference: UsbMixerTuple? = null
        var accepts = true
        var clears = true
        var failRead = false
        var hideRead = false
        var writes = 0
        var clearCount = 0
        override fun outputs() = if (connected) listOf(UsbOutput(7, usb)) else emptyList()
        override fun mediaRoute() = route
        override fun supported(id: Int) = formats
        override fun preferred(id: Int): UsbMixerTuple? { check(!failRead); return if (hideRead) null else preference }
        override fun set(id: Int, tuple: UsbMixerTuple): Boolean { writes++; if (accepts) preference = tuple; return accepts }
        override fun clear(id: Int): Boolean { clearCount++; if (clears) preference = null; return clears }
    }
    private fun UsbPcmLease.open() = acquire("auto", 48000, 2, "int32")

    @Test fun exactTupleIsAcquiredReleasedAndReacquired() {
        val p = Platform(); val lease = UsbPcmLease(p, UsbPcmArbiter())
        assertEquals("OK:7", lease.open()); assertTrue(lease.valid()); assertEquals(tuple, p.preference)
        lease.release(); assertNull(p.preference); assertFalse(lease.valid())
        assertEquals("OK:7", lease.open()); lease.release(); assertEquals(2, p.clearCount)
    }
    @Test fun independentCapabilityListsCannotBeCombined() {
        val p = Platform(); val lease = UsbPcmLease(p, UsbPcmArbiter())
        for (format in listOf(tuple.copy(rate = 44100), tuple.copy(encoding = "int24"),
            tuple.copy(mask = 4), tuple.copy(indexMask = 3), tuple.copy(bitPerfect = false))) {
            p.formats = listOf(format); assertFalse(lease.open().startsWith("OK:"))
        }
        p.formats = listOf(tuple.copy(rate = 44100), tuple.copy(encoding = "int24"))
        assertFalse(lease.open().startsWith("OK:")); assertEquals(0, p.writes)
    }
    @Test fun nonUsbMissingAndAmbiguousRoutesNeverWrite() {
        val p = Platform(); val lease = UsbPcmLease(p, UsbPcmArbiter())
        p.usb = false; assertFalse(lease.open().startsWith("OK:"))
        p.usb = true; p.route = listOf(7, 8); assertFalse(lease.open().startsWith("OK:"))
        p.route = listOf(7); p.connected = false; assertFalse(lease.open().startsWith("OK:"))
        assertEquals(0, p.writes)
    }
    @Test fun refusesExistingPreferenceAndAnotherEngineOwner() {
        val p = Platform(); val arbiter = UsbPcmArbiter()
        val first = UsbPcmLease(p, arbiter); val second = UsbPcmLease(p, arbiter)
        p.preference = tuple; assertFalse(first.open().startsWith("OK:")); assertEquals(0, p.clearCount)
        p.preference = null; assertEquals("OK:7", first.open())
        assertFalse(second.open().startsWith("OK:")); second.release(); assertEquals(tuple, p.preference)
        first.release(); assertEquals("OK:7", second.open()); second.release()
    }
    @Test fun failedSetCleansOnlyItsAttemptAndAllowsRetry() {
        val p = Platform(); val lease = UsbPcmLease(p, UsbPcmArbiter())
        p.accepts = false; p.clears = false
        assertFalse(lease.open().startsWith("OK:")); assertEquals(0, p.clearCount)
        p.clears = true
        p.accepts = true; assertEquals("OK:7", lease.open()); lease.release()
    }
    @Test fun nullReadbackCannotAuthorizeFallbackWhenClearFails() {
        val p = Platform(); val a = UsbPcmArbiter(); val first = UsbPcmLease(p, a); val second = UsbPcmLease(p, a)
        first.open(); p.hideRead = true; p.clears = false
        assertFalse(first.valid()); assertFalse(first.release()); assertEquals(tuple, p.preference)
        assertFalse(second.open().startsWith("OK:")); assertEquals(1, p.writes)
        p.hideRead = false; p.clears = true; assertTrue(first.release())
        assertEquals("OK:7", second.open()); second.release()
    }
    @Test fun preferenceLossIsStickyAndDoesNotReclaimOrClearReplacement() {
        val p = Platform(); val lease = UsbPcmLease(p, UsbPcmArbiter())
        lease.open(); p.preference = tuple.copy(rate = 44100); assertFalse(lease.valid())
        p.preference = tuple; assertFalse(lease.valid())
        p.preference = tuple.copy(rate = 44100); lease.release()
        assertEquals(0, p.clearCount); assertEquals(1, p.writes)
    }
    @Test fun unplugAndQueryFailureInvalidate() {
        val p = Platform(); val lease = UsbPcmLease(p, UsbPcmArbiter())
        lease.open(); p.connected = false; assertFalse(lease.valid()); lease.release()
        p.connected = true; p.preference = null; lease.open(); p.failRead = true
        assertFalse(lease.valid()); p.failRead = false; lease.release()
    }
    @Test fun failedCleanupBlocksOtherOwnersAndExplicitRetryClearsFirst() {
        val p = Platform(); val a = UsbPcmArbiter(); val first = UsbPcmLease(p, a); val second = UsbPcmLease(p, a)
        first.open(); p.clears = false; first.release(); assertFalse(second.open().startsWith("OK:"))
        assertEquals(1, p.writes); p.clears = true; assertEquals("OK:7", first.open())
        assertEquals(2, p.writes); first.release()
    }
    @Test fun supportedEncodingsAndMonoUseTheirOwnCompleteTuple() {
        val p = Platform(); val lease = UsbPcmLease(p, UsbPcmArbiter())
        for (encoding in listOf("int16", "int24", "int32", "float32")) {
            p.formats = listOf(UsbMixerTuple(44100, encoding, 4))
            assertEquals("OK:7", lease.acquire("7", 44100, 1, encoding)); lease.release()
        }
    }
    @Test fun retiredOwnerCleanupCanBeRetriedByTheNextSession() {
        val p = Platform(); val arbiter = UsbPcmArbiter()
        val old = UsbPcmLease(p, arbiter); val next = UsbPcmLease(p, arbiter)
        old.open(); p.clears = false; assertFalse(old.retire())
        assertFalse(next.release()); assertEquals(1, p.writes)
        p.clears = true; assertTrue(next.release()); assertNull(p.preference)
        assertEquals("OK:7", next.open()); next.release()
    }
    @Test fun defaultsAndDspConflictsAreExplicit() {
        val json = DefaultCoreClient.DefaultJson
        assertFalse(json.decodeFromString<CoreEqConfigView>("{}").dsp.usbPcmPassthrough)
        val c = CoreEqConfigView(dsp = CoreDspConfig(usbPcmPassthrough = true))
        assertTrue(json.parseToJsonElement(nativeOutputConfig(c)).jsonObject["usbPcmPassthrough"]!!.jsonPrimitive.boolean)
        for (bad in listOf(c.copy(bitPerfect = false), c.copy(enabled = true, preampDb = -1.0),
            c.copy(dsp = c.dsp.copy(exclusive = true)), c.copy(dsp = c.dsp.copy(playbackRate = 0.5)),
            c.copy(dsp = c.dsp.copy(sampleRateFamilySync = true)), c.copy(dsp = c.dsp.copy(crossfadeSeconds = 1.0)),
            c.copy(dsp = c.dsp.copy(outputStage = CoreDspOutputStage(targetSampleRate = 96000))))) {
            assertThrows(IllegalArgumentException::class.java) { nativeOutputConfig(bad) }
        }
    }
}
