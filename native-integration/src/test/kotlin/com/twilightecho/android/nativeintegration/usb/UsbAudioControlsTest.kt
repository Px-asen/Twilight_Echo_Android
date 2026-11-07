package com.twilightecho.android.nativeintegration.usb

import java.io.IOException
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class UsbAudioControlsTest {
    private data class Request(val type: Int, val request: Int, val value: Int, val index: Int,
        val length: Int, val reply: ByteArray, val result: Int = reply.size)
    private class Wire : UsbAudioControlTransfer {
        val requests = ArrayDeque<Request>()
        var calls = 0
        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray): Int {
            calls++
            assertTrue("Unexpected USB control read", requests.isNotEmpty())
            val expected = requests.removeFirst()
            assertEquals(expected.type, requestType); assertEquals(expected.request, request)
            assertEquals(expected.value, value); assertEquals(expected.index, index); assertEquals(expected.length, data.size)
            if (requestType and 0x80 == 0) assertArrayEquals(expected.reply, data)
            else expected.reply.copyInto(data, endIndex = minOf(data.size, expected.reply.size))
            return expected.result
        }
        fun entity(id: Int, selector: Int, request: Int, reply: ByteArray, control: Int = 4, channel: Int = 0): Wire {
            requests += Request(0xa1, request, (selector shl 8) or channel, (id shl 8) or control, reply.size, reply)
            return this
        }
        fun entityWrite(id: Int, selector: Int, payload: ByteArray, control: Int, channel: Int = 0,
            result: Int = payload.size) = apply {
            requests += Request(0x21, 1, (selector shl 8) or channel, (id shl 8) or control, payload.size, payload, result)
        }
        fun volume(function: UsbAudioFunction, current: Int, channel: Int = 0,
            ranges: List<UsbAudioVolumeRange> = listOf(UsbAudioVolumeRange(-24576, 0, 256))) = apply {
            val c = function.controlInterface
            if (function.version == UsbAudioVersion.UAC1) {
                val range = ranges.single()
                entity(2, 2, 0x81, le(current.toLong(), 2), c, channel)
                entity(2, 2, 0x82, le(range.minimumDb256.toLong(), 2), c, channel)
                entity(2, 2, 0x83, le(range.maximumDb256.toLong(), 2), c, channel)
                entity(2, 2, 0x84, le(range.resolutionDb256.toLong(), 2), c, channel)
            } else {
                entity(2, 2, 1, le(current.toLong(), 2), c, channel)
                entity(2, 2, 2, le(ranges.size.toLong(), 2), c, channel)
                if (ranges.isNotEmpty()) entity(2, 2, 2, le(ranges.size.toLong(), 2) + ranges.fold(byteArrayOf()) { a, r ->
                    a + le(r.minimumDb256.toLong(), 2) + le(r.maximumDb256.toLong(), 2) + le(r.resolutionDb256.toLong(), 2)
                }, c, channel)
            }
        }
        fun source(id: Int = 10, current: Long = 48000, controls: Int = 7, control: Int = 4,
            ranges: List<UsbAudioRateRange> = listOf(UsbAudioRateRange(44100, 44100, 0), UsbAudioRateRange(48000, 192000, 48000))): Wire {
            entity(id, 1, 1, le(current, 4), control)
            entity(id, 1, 2, le(ranges.size.toLong(), 2), control)
            if (ranges.isNotEmpty()) entity(id, 1, 2, le(ranges.size.toLong(), 2) + ranges.fold(byteArrayOf()) { a, r ->
                a + le(r.minimum, 4) + le(r.maximum, 4) + le(r.resolution, 4)
            }, control)
            if (controls and 4 != 0) entity(id, 2, 1, byteArrayOf(1), control)
            return this
        }
        fun done() = assertTrue("Some USB control reads were not performed", requests.isEmpty())
    }
    private fun root(function: UsbAudioFunction = UsbAudioFixtures.uac2(), id: Int = 10): UsbAudioFunction =
        function.copy(inputTerminals = function.inputTerminals.mapValues { (_, t) -> t.copy(clockId = id) })

    @Test fun uac2FollowsTheSelectedClockAndExactMultiplierWithoutWritingControls() {
        val f = UsbAudioFixtures.uac2()
        val wire = Wire().entity(30, 1, 1, le(2, 2)).entity(30, 2, 1, le(1, 2))
            .entity(20, 1, 1, byteArrayOf(2)).source(11, 44100, 1)
        val observation = UsbAudioControls(wire, f).readClock(f.playbackAlternates.single())
        assertEquals(11, observation.sourceId); assertEquals(44100L, observation.sourceCurrentRate)
        assertEquals(88200L, observation.playbackRate)
        assertEquals(48000L, observation.sourceRateFor(96000))
        assertNull(observation.sourceRateFor(44101))
        assertEquals(mapOf(20 to 2), observation.selectedInputs)
        assertNull(observation.valid)
        assertEquals(UsbAudioRateRange(48000, 192000, 48000), observation.sourceRanges.last())
        wire.done()
    }

    @Test fun sourceControlUsesEntityPlusItsActualAudioControlInterface() {
        val f = root(UsbAudioFixtures.uac2().copy(controlInterface = 8))
        val wire = Wire().source(control = 8)
        val observation = UsbAudioControls(wire, f).readClock(f.playbackAlternates.single())
        assertEquals(true, observation.valid); assertEquals(48000L, observation.playbackRate)
        assertEquals(BigInteger.ONE, observation.numerator); assertTrue(observation.selectedInputs.isEmpty())
        wire.done()
    }

    @Test fun clockValuesRemainUnsigned32BitRatherThanNegativeOrClampedToPcmLimit() {
        val f = root()
        val ranges = listOf(UsbAudioRateRange(0, 0xffffffffL, 1))
        val wire = Wire().source(current = 0xffffffffL, ranges = ranges)
        val snapshot = UsbAudioControls(wire, f).readClock(f.playbackAlternates.single())
        assertEquals(0xffffffffL, snapshot.sourceCurrentRate); assertEquals(ranges, snapshot.sourceRanges)
        wire.done()
    }

    @Test fun nestedMultipliersReduceExactlyAndNonIntegralRatesAreNotRounded() {
        var f = root(id = 31)
        f = f.copy(clocks = f.clocks + (31 to UsbAudioClock.Multiplier(31, 30, 5)) +
            (30 to UsbAudioClock.Multiplier(30, 10, 5)))
        val wire = Wire().entity(31, 1, 1, le(3, 2)).entity(31, 2, 1, le(7, 2))
            .entity(30, 1, 1, le(2, 2)).entity(30, 2, 1, le(3, 2)).source(current = 48000)
        val snapshot = UsbAudioControls(wire, f).readClock(f.playbackAlternates.single())
        assertEquals(BigInteger.valueOf(2), snapshot.numerator); assertEquals(BigInteger.valueOf(7), snapshot.denominator)
        assertNull(snapshot.playbackRate); assertEquals(168000L, snapshot.sourceRateFor(48000))
        wire.done()
    }

    @Test fun invalidClockIsReportedAsAnObservationNotReplacedByANominalRate() {
        val f = root()
        val wire = Wire().source(current = 0)
        val validity = wire.requests.removeLast()
        wire.requests += validity.copy(reply = byteArrayOf(0))
        val snapshot = UsbAudioControls(wire, f).readClock(f.playbackAlternates.single())
        assertEquals(false, snapshot.valid); assertEquals(0L, snapshot.sourceCurrentRate)
        wire.done()
    }

    @Test fun emptyRangeDoesNotIssueAnInventedSecondRequest() {
        val f = root()
        val wire = Wire().source(ranges = emptyList())
        assertTrue(UsbAudioControls(wire, f).readClock(f.playbackAlternates.single()).sourceRanges.isEmpty())
        assertEquals(3, wire.calls); wire.done()
    }

    @Test fun badClockSelectorAndCycleAreExplicitFailuresWithNoGuessedClock() {
        val original = UsbAudioFixtures.uac2()
        for (pin in listOf(0, 3)) {
            val f = root(original, 20)
            val wire = Wire().entity(20, 1, 1, byteArrayOf(pin.toByte()))
            assertThrows(IllegalArgumentException::class.java) { UsbAudioControls(wire, f).readClock(f.playbackAlternates.single()) }
            wire.done()
        }
        val cycle = root(original, 20).copy(clocks = mapOf(20 to UsbAudioClock.Selector(20, listOf(20), 1)))
        val wire = Wire().entity(20, 1, 1, byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { UsbAudioControls(wire, cycle).readClock(cycle.playbackAlternates.single()) }
        wire.done()
    }

    @Test fun shortOrFailedControlReadsDoNotBecomeZeroRatesOrTriggerRetries() {
        for (returned in listOf(-1, 0, 3)) {
            val f = root()
            val wire = Wire()
            wire.requests += Request(0xa1, 1, 0x0100, 0x0a04, 4, byteArrayOf(), returned)
            assertThrows(IOException::class.java) { UsbAudioControls(wire, f).readClock(f.playbackAlternates.single()) }
            assertEquals(1, wire.calls); wire.done()
        }
    }

    @Test fun rangeCountChangingBetweenHeaderAndPayloadIsNotAccepted() {
        val f = root()
        val wire = Wire().source()
        val requests = wire.requests.toMutableList()
        requests[2] = requests[2].copy(reply = requests[2].reply.copyOf().also { it[0] = 1 })
        wire.requests.clear(); wire.requests.addAll(requests)
        assertThrows(IllegalArgumentException::class.java) { UsbAudioControls(wire, f).readClock(f.playbackAlternates.single()) }
        assertEquals(3, wire.calls) // No validity read after a failed RANGE.
    }

    @Test fun uac1RateUsesEndpointRecipientAndThreeByteFrequency() {
        val f = UsbAudioFixtures.uac1()
        val wire = Wire()
        wire.requests += Request(0xa2, 0x81, 0x0100, 1, 3, le(44100, 3))
        assertEquals(44100L, UsbAudioControls(wire, f).readUac1CurrentRate(f.playbackAlternates.single()))
        wire.done()
    }

    @Test fun uac1VolumeUsesSignedDb256AndTheRequestedLogicalChannel() {
        val f = UsbAudioFixtures.uac1()
        val wire = Wire().entity(2, 2, 0x81, le(-1536, 2), 0, 1)
            .entity(2, 2, 0x82, le(-24576, 2), 0, 1).entity(2, 2, 0x83, le(0, 2), 0, 1)
            .entity(2, 2, 0x84, le(256, 2), 0, 1)
        assertEquals(UsbAudioVolumeSnapshot(-1536, listOf(UsbAudioVolumeRange(-24576, 0, 256))),
            UsbAudioControls(wire, f).readVolume(2, 1))
        wire.done()
    }

    @Test fun uac2VolumePreservesInfinityAndDiscontinuousHardwareRanges() {
        val f = UsbAudioFixtures.uac2()
        val ranges = le(2, 2) + le(-32768, 2) + le(-32768, 2) + le(0, 2) +
            le(-24576, 2) + le(0, 2) + le(256, 2)
        val wire = Wire().entity(2, 2, 1, le(-32768, 2)).entity(2, 2, 2, le(2, 2)).entity(2, 2, 2, ranges)
        assertEquals(UsbAudioVolumeSnapshot(-32768, listOf(UsbAudioVolumeRange(-32768, -32768, 0),
            UsbAudioVolumeRange(-24576, 0, 256))), UsbAudioControls(wire, f).readVolume(2, 0))
        wire.done()
    }

    @Test fun unavailableChannelControlsNeverSendAControlRequest() {
        val f = UsbAudioFixtures.uac2()
        val wire = Wire()
        val controls = UsbAudioControls(wire, f)
        assertThrows(IllegalArgumentException::class.java) { controls.readMute(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { controls.readVolume(2, 3) }
        assertEquals(0, wire.calls)
    }

    @Test fun muteReadUsesVersionSpecificCurCodeAndDoesNotWriteOrChangeVolume() {
        for (f in listOf(UsbAudioFixtures.uac1(), UsbAudioFixtures.uac2())) {
            val wire = Wire().entity(2, 1, if (f.version == UsbAudioVersion.UAC1) 0x81 else 1,
                byteArrayOf(1), control = f.controlInterface)
            assertTrue(UsbAudioControls(wire, f).readMute(2, 0)); wire.done()
        }
    }

    @Test fun volumeWriteUsesSignedLittleEndianCurAndReturnsActualReadbackForBothVersions() {
        for (f in listOf(UsbAudioFixtures.uac1(), UsbAudioFixtures.uac2())) {
            val channel = if (f.version == UsbAudioVersion.UAC1) 1 else 0
            val wire = Wire().volume(f, -1024, channel).entityWrite(2, 2, le(-1536, 2), f.controlInterface, channel)
                .entity(2, 2, if (f.version == UsbAudioVersion.UAC1) 0x81 else 1, le(-1536, 2), f.controlInterface, channel)
            val result = UsbAudioControls(wire, f).setVolume(2, channel, -1536)
            assertEquals(-1536, result.currentDb256); assertEquals(256, result.ranges.single().resolutionDb256)
            wire.done()
        }
    }

    @Test fun volumeWritePreservesInfinityAndRejectsGapsAndWrongStepsWithoutAnOutRequest() {
        val f = UsbAudioFixtures.uac2()
        val ranges = listOf(UsbAudioVolumeRange(-32768, -32768, 0), UsbAudioVolumeRange(-4096, 0, 128))
        val wire = Wire().volume(f, -1024, ranges = ranges).entityWrite(2, 2, le(-32768, 2), f.controlInterface)
            .entity(2, 2, 1, le(-32768, 2))
        assertEquals(-32768, UsbAudioControls(wire, f).setVolume(2, 0, -32768).currentDb256)
        wire.done()
        for (target in listOf(-32767, -8192, -4095, 1, 65536)) {
            val invalid = Wire().volume(f, -1024, ranges = ranges)
            assertThrows(IllegalArgumentException::class.java) { UsbAudioControls(invalid, f).setVolume(2, 0, target) }
            assertEquals(3, invalid.calls); invalid.done()
        }
    }

    @Test fun readonlyHardwareControlsCannotBeWrittenEvenIfTheyCanBeRead() {
        val wire = Wire()
        val controls = UsbAudioControls(wire, UsbAudioFixtures.uac2())
        assertThrows(IllegalArgumentException::class.java) { controls.setVolume(2, 1, -256) }
        assertThrows(IllegalArgumentException::class.java) { controls.setMute(2, 1, true) }
        assertEquals(0, wire.calls)
    }

    @Test fun rejectedVolumeAndMuteWritesReturnTheDacsActualValueNotTheRequestedValue() {
        val f = UsbAudioFixtures.uac2()
        val wire = Wire().volume(f, -1024).entityWrite(2, 2, le(-1536, 2), f.controlInterface)
            .entity(2, 2, 1, le(-1024, 2))
            .entityWrite(2, 1, byteArrayOf(1), f.controlInterface).entity(2, 1, 1, byteArrayOf(0))
        val controls = UsbAudioControls(wire, f)
        assertEquals(-1024, controls.setVolume(2, 0, -1536).currentDb256)
        assertFalse(controls.setMute(2, 0, true)); wire.done()
    }

    @Test fun shortOrFailedVolumeWritesDoNotRetryOrPerformAReadback() {
        val f = UsbAudioFixtures.uac2()
        for (returned in listOf(-1, 0, 1)) {
            val wire = Wire().volume(f, -1024).entityWrite(2, 2, le(-1536, 2), f.controlInterface, result = returned)
            assertThrows(IOException::class.java) { UsbAudioControls(wire, f).setVolume(2, 0, -1536) }
            assertEquals(4, wire.calls); wire.done()
        }
    }

    @Test fun failedVolumeReadbackIsAnErrorRatherThanAReportedSuccessfulAdjustment() {
        val f = UsbAudioFixtures.uac2()
        val wire = Wire().volume(f, -1024).entityWrite(2, 2, le(-1536, 2), f.controlInterface)
        wire.requests += Request(0xa1, 1, 0x200, 0x0204, 2, byteArrayOf(), -1)
        assertThrows(IOException::class.java) { UsbAudioControls(wire, f).setVolume(2, 0, -1536) }
        wire.done()
    }

    @Test fun muteWriteHasItsOwnSelectorAndVersionSpecificReadbackAndNoVolumeWrite() {
        for (f in listOf(UsbAudioFixtures.uac1(), UsbAudioFixtures.uac2())) {
            val wire = Wire().entityWrite(2, 1, byteArrayOf(1), f.controlInterface)
                .entity(2, 1, if (f.version == UsbAudioVersion.UAC1) 0x81 else 1, byteArrayOf(1), f.controlInterface)
            assertTrue(UsbAudioControls(wire, f).setMute(2, 0, true)); wire.done()
        }
    }

    @Test fun aForeignAlternateCannotReadAnotherFunctionsClock() {
        val f = UsbAudioFixtures.uac2()
        val wire = Wire()
        assertThrows(IllegalArgumentException::class.java) {
            UsbAudioControls(wire, f).readClock(f.playbackAlternates.single().copy(interfaceNumber = 9))
        }
        assertEquals(0, wire.calls)
    }

    @Test fun activeConfigurationComesFromGetConfigurationRatherThanDescriptorOrder() {
        val descriptors = UsbAudioDescriptors.parse(UsbAudioFixtures.config(1, UsbAudioFixtures.uac1Parts()) +
            UsbAudioFixtures.config(7, UsbAudioFixtures.uac2Parts()))
        val wire = Wire()
        wire.requests += Request(0x80, 8, 0, 0, 1, byteArrayOf(7))
        val active = readActiveUsbAudioConfiguration(wire, descriptors)
        assertEquals(7, active.value); assertEquals(UsbAudioVersion.UAC2, active.functions.single().version)
        wire.done()
    }

    @Test fun unconfiguredAndFailedConfigurationReadsDoNotSelectTheFirstConfiguration() {
        val descriptors = UsbAudioDescriptors.parse(UsbAudioFixtures.config(parts = UsbAudioFixtures.uac1Parts()))
        for (request in listOf(Request(0x80, 8, 0, 0, 1, byteArrayOf(0)),
            Request(0x80, 8, 0, 0, 1, byteArrayOf(), -1))) {
            val wire = Wire(); wire.requests += request
            assertThrows(IOException::class.java) { readActiveUsbAudioConfiguration(wire, descriptors) }
            assertEquals(1, wire.calls); wire.done()
        }
    }

    companion object { private fun le(value: Long, length: Int) = UsbAudioFixtures.le(value, length) }
}
