package com.twilightecho.android.nativeintegration.usb

import java.io.IOException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UsbDirectSessionTest {
    private data class Command(val type: Int, val request: Int, val value: Int, val index: Int,
        val data: ByteArray, val result: Int = data.size)
    private class Wire : UsbAudioControlTransfer {
        val commands = ArrayDeque<Command>()
        fun add(type: Int, request: Int, value: Int, index: Int, data: ByteArray, result: Int = data.size) = apply {
            commands += Command(type, request, value, index, data, result)
        }
        fun source(rate: Long, valid: Boolean = true, controls: Int = 7, resolution: Long = 24000) = apply {
            add(0xa1, 1, 0x100, 0x0a04, le(rate, 4))
            add(0xa1, 2, 0x100, 0x0a04, le(1, 2))
            add(0xa1, 2, 0x100, 0x0a04, le(1, 2) + le(24000, 4) + le(192000, 4) + le(resolution, 4))
            if (controls and 4 != 0) add(0xa1, 1, 0x200, 0x0a04, byteArrayOf(if (valid) 1 else 0))
        }
        fun sourceWrite(rate: Long) = add(0x21, 1, 0x100, 0x0a04, le(rate, 4))
            .add(0xa1, 1, 0x100, 0x0a04, le(rate, 4))
        fun endpointRead(rate: Long) = add(0xa2, 0x81, 0x100, 1, le(rate, 3))
        fun endpointWrite(rate: Long) = add(0x22, 1, 0x100, 1, le(rate, 3)).endpointRead(rate)
        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray): Int {
            assertTrue("Unexpected control operation", commands.isNotEmpty())
            val expected = commands.removeFirst()
            assertEquals(expected.type, requestType); assertEquals(expected.request, request)
            assertEquals(expected.value, value); assertEquals(expected.index, index)
            assertEquals(expected.data.size, data.size)
            if (requestType and 0x80 == 0) assertArrayEquals(expected.data, data) else expected.data.copyInto(data)
            return expected.result
        }
        fun done() = assertTrue("Unconsumed class controls", commands.isEmpty())
    }
    private fun profile(function: UsbAudioFunction = root()) = UsbDirectProfile(function, function.playbackAlternates.single(),
        if (function.version == UsbAudioVersion.UAC2) "int24-in32" else "int24")

    @Test fun candidatesKeepWholeFormatsAndPreferExactThenIntegerWidening() {
        val original = UsbAudioFixtures.uac1()
        val stream = original.playbackAlternates.single()
        val formats = listOf(2 to 16, 4 to 32, 3 to 24, 4 to 24).mapIndexed { index, (bytes, bits) ->
            stream.copy(alternateSetting = index + 1, subslotBytes = bytes, bitResolution = bits)
        }
        val config = UsbAudioConfiguration(1, listOf(original.copy(playbackAlternates = formats)))
        val selected = usbDirectProfiles(config, 44100, 2, "int24", 24)
        assertEquals(listOf("int24", "int32", "int24-in32", "int16"), selected.map { it.format })
        assertTrue(usbDirectProfiles(config, 96000, 2, "int24", 24).isEmpty())
        assertTrue(usbDirectProfiles(config, 44100, 1, "int24", 24).isEmpty())
        val json = Json.parseToJsonElement(usbDirectLeaseJson(17, "DAC", selected)).jsonObject
        assertEquals(17, json.getValue("fd").jsonPrimitive.int)
        val wire = json.getValue("profiles").jsonArray[2].jsonObject
        assertEquals("int24-in32", wire.getValue("format").jsonPrimitive.content)
        assertEquals(4, wire.getValue("alternate").jsonPrimitive.int)
        assertEquals(1, wire.getValue("uac").jsonPrimitive.int)
    }

    @Test fun floatRequiresDeclaredIeeeFormatAndImplicitFeedbackIsNotInvented() {
        val f = root()
        val s = f.playbackAlternates.single().copy(bitResolution = 32,
            encodings = setOf(UsbPcmEncoding.IEEE_FLOAT, UsbPcmEncoding.SIGNED_INTEGER))
        fun candidates(stream: UsbAudioStreamingAlternate) = usbDirectProfiles(
            UsbAudioConfiguration(1, listOf(f.copy(playbackAlternates = listOf(stream)))), 48000, 2, "float32", 32)
        assertEquals(listOf("float32", "int32"), candidates(s).map { it.format })
        assertTrue(candidates(s.copy(feedbackEndpoint = null)).isEmpty())
        assertEquals(2, candidates(s.copy(feedbackEndpoint = null, dataEndpoint = s.dataEndpoint.copy(attributes = 9))).size)
        assertTrue(candidates(s.copy(subslotBytes = 3)).isEmpty())
    }

    @Test fun uac1WritesAndReadsBackEndpointRateThenRestoresIt() {
        val p = profile(UsbAudioFixtures.uac1())
        val wire = Wire().endpointRead(44100).endpointWrite(48000).endpointWrite(44100)
        val session = UsbAudioPlaybackSession(wire, listOf(p))
        session.configure(0, 48000); session.restore(); session.restore(); wire.done()
    }

    @Test fun uac1FixedRateNeedsNoWritableControlAndDoesNotGuessAMultipleRateClock() {
        val p = profile(UsbAudioFixtures.uac1())
        val fixed = p.stream.copy(descriptorRates = listOf(UsbAudioRateRange(48000, 48000, 0)),
            dataEndpoint = p.stream.dataEndpoint.copy(samplingFrequencyControl = false))
        fun session(stream: UsbAudioStreamingAlternate) = UsbAudioPlaybackSession(Wire(),
            listOf(p.copy(function = p.function.copy(playbackAlternates = listOf(stream)), stream = stream)))
        session(fixed).configure(0, 48000)
        assertThrows(IllegalArgumentException::class.java) { session(fixed).configure(0, 44100) }
        assertThrows(IllegalArgumentException::class.java) { session(fixed.copy(descriptorRates = p.stream.descriptorRates)).configure(0, 48000) }
    }

    @Test fun failedWriteStillLeavesOriginalRateAvailableForNativeCleanup() {
        val wire = Wire().endpointRead(44100).add(0x22, 1, 0x100, 1, le(48000, 3), 2).endpointWrite(44100)
        val session = UsbAudioPlaybackSession(wire, listOf(profile(UsbAudioFixtures.uac1())))
        assertThrows(IOException::class.java) { session.configure(0, 48000) }
        session.restore(); wire.done()
    }

    @Test fun uac2ChangesOnlySourceFrequencyAndVerifiesTheActivePath() {
        val wire = Wire().source(48000).sourceWrite(96000).source(96000).sourceWrite(48000)
        val session = UsbAudioPlaybackSession(wire, listOf(profile()))
        session.configure(0, 96000); session.restore(); wire.done()
    }

    @Test fun multiplierUsesExactSourceRateWithoutChangingSelector() {
        val function = UsbAudioFixtures.uac2()
        val wire = Wire()
        fun path(rate: Long) {
            wire.add(0xa1, 1, 0x100, 0x1e04, le(2, 2)).add(0xa1, 1, 0x200, 0x1e04, le(1, 2))
                .add(0xa1, 1, 0x100, 0x1404, byteArrayOf(1)).source(rate)
        }
        path(24000); wire.sourceWrite(48000); path(48000); wire.sourceWrite(24000)
        val session = UsbAudioPlaybackSession(wire, listOf(profile(function)))
        session.configure(0, 96000); session.restore(); wire.done()
    }

    @Test fun readOnlyClockWorksAtItsCurrentRateButCannotBeReprogrammed() {
        val f = root().let { it.copy(clocks = it.clocks + (10 to (it.clocks.getValue(10) as UsbAudioClock.Source).copy(controls = 1))) }
        val wire = Wire().source(48000, controls = 1).source(48000, controls = 1)
        UsbAudioPlaybackSession(wire, listOf(profile(f))).let { it.configure(0, 48000); it.restore() }; wire.done()
        val mismatch = Wire().source(48000, controls = 1)
        assertThrows(IllegalArgumentException::class.java) {
            UsbAudioPlaybackSession(mismatch, listOf(profile(f))).configure(0, 96000)
        }
        mismatch.done()
    }

    @Test fun unsupportedResolutionIsRejectedBeforeAnyWrite() {
        val wire = Wire().source(48000)
        assertThrows(IllegalArgumentException::class.java) { UsbAudioPlaybackSession(wire, listOf(profile())).configure(0, 44100) }
        wire.done()
    }

    @Test fun unlockedClockAfterWriteIsRejectedAndOriginalRateIsRestored() {
        val wire = Wire().source(48000).sourceWrite(96000).source(96000, valid = false).sourceWrite(48000)
        val session = UsbAudioPlaybackSession(wire, listOf(profile()))
        assertThrows(IllegalStateException::class.java) { session.configure(0, 96000) }
        session.restore(); wire.done()
    }

    @Test fun rateRangeUsesItsResolutionWithoutRounding() {
        val range = UsbAudioRateRange(44100, 176400, 44100)
        assertTrue(range.contains(88200)); assertFalse(range.contains(48000)); assertFalse(range.contains(0))
        assertTrue(UsbAudioRateRange(44100, 96000, 0).contains(48000))
    }

    @Test fun rawDataIsRetainedWithoutPretendingItIsPcmOrAnUnknownDsdDevice() {
        val raw = UsbAudioDescriptors.parse(UsbAudioFixtures.config(parts = UsbAudioFixtures.uac2Parts(formats = 0x80000000L)))
        val config = raw.configurations.single()
        val stream = config.functions.single().playbackAlternates.single()
        assertTrue(stream.rawData)
        assertTrue(stream.encodings.isEmpty())
        assertTrue(usbDirectProfiles(config, 88200, 2, "int32", 32).isEmpty())
        assertTrue(usbDirectProfiles(config, 2822400, 2, "dsd-int8-lsb1", 1, UsbDsdDeviceIdentity(1, 2, 0)).isEmpty())
        val selected = usbDirectProfiles(config, 2822400, 2, "dsd-int8-lsb1", 1, UsbDsdDeviceIdentity(0x20b1, 1, 0))
        assertEquals("dsd-u32-be", selected.single().format)
        assertEquals(32, selected.single().json(0).getValue("clockDivisor").jsonPrimitive.int)
    }

    @Test fun xmosNativeRequiresRawDataNotMerelyAHighRatePcmAlternate() {
        val config = UsbAudioConfiguration(1, listOf(root()))
        assertTrue(usbDirectProfiles(config, 2822400, 2, "dsd-int8-msb1", 1, UsbDsdDeviceIdentity(0x20b1, 1, 0)).isEmpty())
    }

    @Test fun amaneroWordEndianDependsOnTheExactFirmwareRevisionAndAlternate() {
        val f = root().let { it.copy(playbackAlternates = listOf(it.playbackAlternates.single().copy(alternateSetting = 2))) }
        val config = UsbAudioConfiguration(1, listOf(f))
        fun profiles(revision: Int) = usbDirectProfiles(config, 2822400, 2, "dsd-int8-msb1", 1,
            UsbDsdDeviceIdentity(0x16d0, 0x071a, revision))
        assertEquals("dsd-u32-le", profiles(0x0199).single().format)
        assertEquals("dsd-u32-be", profiles(0x019b).single().format)
        assertEquals("dsd-u32-be", profiles(0x0203).single().format)
        assertTrue(profiles(0x0204).isEmpty())
        assertNull(UsbDsdDeviceIdentity(0x16d0, 0x071a, 0x0199).nativeFormat(root(), root().playbackAlternates.single()))
    }

    @Test fun deviceDescriptorKeepsTheBcdFirmwareRevisionWithoutParsingTheProductName() {
        val device = UsbAudioFixtures.bytes(18, 1, 0, 2, 0, 0, 0, 64, 0xd0, 0x16, 0x1a, 7, 0x9b, 1, 1, 2, 3, 1)
        val parsed = UsbAudioDescriptors.parse(device + UsbAudioFixtures.config(parts = UsbAudioFixtures.uac2Parts()))
        assertEquals(0x019b, parsed.deviceRevision)
        assertEquals(1, parsed.configurations.size)
    }

    @Test fun dopOnlyOffersAnInteger24BitCarrierAndNeverAFloatOr16BitFallback() {
        val f = UsbAudioFixtures.uac1Parts(rates = listOf(176400)).let {
            UsbAudioDescriptors.parse(UsbAudioFixtures.config(parts = it)).configurations.single().functions.single()
        }
        val stream = f.playbackAlternates.single()
        val variants = listOf(stream.copy(subslotBytes = 2, bitResolution = 16),
            stream.copy(alternateSetting = 2, subslotBytes = 4, bitResolution = 32, encodings = setOf(UsbPcmEncoding.IEEE_FLOAT)),
            stream.copy(alternateSetting = 3), stream.copy(alternateSetting = 4, subslotBytes = 4, bitResolution = 32))
        val profiles = usbDirectProfiles(UsbAudioConfiguration(1, listOf(f.copy(playbackAlternates = variants))),
            176400, 2, "dop-int24", 24)
        assertEquals(setOf("int24", "int24-in32"), profiles.map { it.format }.toSet())
        assertTrue(profiles.all { it.clockDivisor == 1 })
    }

    @Test fun nativeSessionProgramsTheWordClockAndRestoresItNotTheDsdBitClock() {
        val f = root()
        val p = UsbDirectProfile(f, f.playbackAlternates.single(), "dsd-u32-be", 32)
        val wire = Wire().source(48000, resolution = 0).sourceWrite(88200).source(88200, resolution = 0).sourceWrite(48000)
        val session = UsbAudioPlaybackSession(wire, listOf(p))
        session.configure(0, 2822400)
        session.restore()
        wire.done()
    }

    @Test fun nativeRateThatCannotFormWholeU32WordsDoesNotTouchControls() {
        val f = root()
        val wire = Wire()
        assertThrows(IllegalArgumentException::class.java) {
            UsbAudioPlaybackSession(wire, listOf(UsbDirectProfile(f, f.playbackAlternates.single(), "dsd-u32-be", 32)))
                .configure(0, 2822401)
        }
        wire.done()
    }

    @Test fun aKnownNativeAlternateIsNeverSelectedForPcmOrDop() {
        val f = root().let { it.copy(playbackAlternates = listOf(it.playbackAlternates.single().copy(alternateSetting = 2))) }
        val device = UsbDsdDeviceIdentity(0x16d0, 0x071a, 0x0199)
        val config = UsbAudioConfiguration(1, listOf(f))
        assertTrue(usbDirectProfiles(config, 88200, 2, "int24", 24, device).isEmpty())
        assertTrue(usbDirectProfiles(config, 176400, 2, "dop-int24", 24, device).isEmpty())
        assertEquals(1, usbDirectProfiles(config, 2822400, 2, "dsd-int8-msb1", 1, device).size)
    }

    companion object {
        private fun le(value: Long, bytes: Int) = UsbAudioFixtures.le(value, bytes)
        private fun root(): UsbAudioFunction = UsbAudioFixtures.uac2().let { f ->
            f.copy(inputTerminals = f.inputTerminals.mapValues { (_, t) -> t.copy(clockId = 10) })
        }
    }
}
