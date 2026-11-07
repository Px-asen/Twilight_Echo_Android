package com.twilightecho.android.nativeintegration.usb

import java.io.IOException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UsbImplicitFeedbackTest {
    private fun desc(vararg fields: Int) = UsbAudioFixtures.bytes(fields.size + 1, *fields)
    private fun le(value: Long, bytes: Int) = UsbAudioFixtures.le(value, bytes)
    private fun function(uac1: Boolean = false): UsbAudioFunction {
        val control = if (uac1) 0 else 4
        val stream = control + 1
        val source = stream + 1
        val parts = (if (uac1) UsbAudioFixtures.uac1Parts() else UsbAudioFixtures.uac2Parts()).mapNotNull { bytes ->
            when {
                bytes[1].toInt() == 5 && bytes[2].toInt() and 0x80 != 0 -> null // Remove explicit feedback.
                bytes[1].toInt() == 11 -> bytes.copyOf().also { it[3] = 3 }
                uac1 && bytes[1].toInt() == 0x24 && bytes[2].toInt() == 1 && bytes.size == 9 ->
                    desc(0x24, 1, 0, 1, 40, 0, 2, stream, source)
                bytes[1].toInt() == 4 && bytes[2].toInt() == stream && bytes[3].toInt() == 1 ->
                    bytes.copyOf().also { it[4] = 1 }
                uac1 && bytes[1].toInt() == 5 && bytes[2].toInt() == 1 -> bytes.copyOf().also { it[8] = 0x82.toByte() }
                else -> bytes
            }
        }.toMutableList()
        val firstStream = parts.indexOfFirst { it[1].toInt() == 4 && it[2].toInt() == stream }
        parts.addAll(firstStream, if (uac1) listOf(
            desc(0x24, 2, 4, 1, 2, 0, 1, 0, 0, 0, 0), desc(0x24, 3, 5, 1, 1, 0, 4, 0))
        else listOf(desc(0x24, 2, 4, 1, 2, 0, 30, 1, 0, 0, 0, 0, 0, 0, 0, 0),
            desc(0x24, 3, 5, 1, 1, 0, 4, 30, 0, 0, 0)))
        val protocol = if (uac1) 0 else 0x20
        parts += UsbAudioFixtures.iface(source, 0, 0, 2, protocol)
        parts += UsbAudioFixtures.iface(source, 1, 1, 2, protocol)
        if (uac1) {
            parts += desc(0x24, 1, 5, 1, 1, 0)
            parts += UsbAudioFixtures.bytes(14, 0x24, 2, 1, 1, 2, 16, 2) + le(44100, 3) + le(48000, 3)
            parts += desc(5, 0x82, 0x25, 0, 1, 1, 0, 0)
            parts += desc(0x25, 1, 1, 0, 0, 0)
        } else {
            parts += UsbAudioFixtures.bytes(16, 0x24, 1, 5, 0, 1) + le(1, 4) + UsbAudioFixtures.bytes(1, 0, 0, 0, 0, 0)
            parts += desc(0x24, 2, 1, 2, 16)
            parts += desc(5, 0x83, 0x25, 0, 1, 1)
            parts += desc(0x25, 1, 0, 0, 0, 0, 0)
        }
        return UsbAudioDescriptors.parse(UsbAudioFixtures.config(parts = parts)).configurations.single().functions.single()
    }
    private fun profiles(f: UsbAudioFunction, rate: Int = 48000, format: String = "int24-in32") =
        usbDirectProfiles(UsbAudioConfiguration(1, listOf(f)), rate, 2, format, 24)
    private fun withSource(f: UsbAudioFunction, edit: (UsbAudioStreamingAlternate) -> UsbAudioStreamingAlternate) =
        f.copy(captureAlternates = f.captureAlternates.map(edit))

    @Test fun captureFormatKeepsItsOwnChannelsStrideTerminalAndEndpointWithoutBecomingPlayback() {
        for (uac1 in listOf(true, false)) {
            val f = function(uac1)
            val capture = f.captureAlternates.single()
            assertEquals(1, capture.channels); assertEquals(2, capture.frameBytes)
            assertEquals(5, capture.terminalLink); assertTrue(capture.dataEndpoint.isInput)
            assertEquals(2, capture.dataEndpoint.usage); assertNull(capture.feedbackEndpoint)
            assertEquals(2, f.playbackAlternates.single().channels)
            assertNull(f.playbackAlternates.single().feedbackEndpoint)
            if (!uac1) assertEquals(30, f.outputTerminals.getValue(5).clockId)
        }
    }

    @Test fun uac2ProfilesPairTheAdjacentSameAlternateUsage2SourceWithItsOwnFrameBytes() {
        val f = function()
        val p = profiles(f).single()
        assertEquals(f.captureAlternates.single(), p.implicitFeedback)
        val wire = p.json(0).getValue("implicitFeedback").jsonObject
        assertEquals(6, wire.getValue("interface").jsonPrimitive.int)
        assertEquals(1, wire.getValue("alternate").jsonPrimitive.int)
        assertEquals(0x83, wire.getValue("endpoint").jsonPrimitive.int)
        assertEquals(2, wire.getValue("frameBytes").jsonPrimitive.int)
        assertEquals(2, p.json(0).getValue("channels").jsonPrimitive.int)
    }

    @Test fun uac2DoesNotGuessAnUnmarkedNonAdjacentDifferentClockOrDifferentIntervalCapture() {
        val f = function()
        val variants = listOf(withSource(f) { it.copy(interfaceNumber = 7) },
            withSource(f) { it.copy(alternateSetting = 2) },
            withSource(f) { it.copy(dataEndpoint = it.dataEndpoint.copy(attributes = 5)) },
            withSource(f) { it.copy(dataEndpoint = it.dataEndpoint.copy(interval = 2)) },
            f.copy(outputTerminals = f.outputTerminals + (5 to f.outputTerminals.getValue(5).copy(clockId = 10))))
        variants.forEach { assertTrue(profiles(it).isEmpty()) }
    }

    @Test fun uac1RequiresTheExplicitSyncAddressAndAWholeCaptureFormatSupportingTheRate() {
        val f = function(true)
        assertNotNull(profiles(f, 44100).single().implicitFeedback)
        assertTrue(profiles(withSource(f) { it.copy(dataEndpoint = it.dataEndpoint.copy(address = 0x83)) }).isEmpty())
        assertTrue(profiles(withSource(f) { it.copy(descriptorRates = listOf(UsbAudioRateRange(44100, 44100, 0))) }).isEmpty())
        assertTrue(profiles(withSource(f) { it.copy(dataEndpoint = it.dataEndpoint.copy(interval = 2)) }).isEmpty())
    }

    @Test fun existingExplicitFeedbackDoesNotStartAnExtraCaptureInterfaceOrChangeLeaseFields() {
        val original = UsbAudioFixtures.uac2()
        val f = original.copy(captureAlternates = function().captureAlternates)
        val p = profiles(f).single()
        assertNull(p.implicitFeedback)
        assertFalse(p.json(0).containsKey("implicitFeedback"))
        assertEquals(0x82, p.json(0).getValue("feedback").jsonPrimitive.int)
    }

    private data class Command(val type: Int, val request: Int, val value: Int, val index: Int,
        val bytes: ByteArray, val result: Int = bytes.size)
    private class Wire : UsbAudioControlTransfer {
        val commands = ArrayDeque<Command>()
        fun add(type: Int, request: Int, value: Int, index: Int, bytes: ByteArray, result: Int = bytes.size) = apply {
            commands += Command(type, request, value, index, bytes, result)
        }
        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray): Int {
            val expected = commands.removeFirst()
            assertEquals(expected.type, requestType); assertEquals(expected.request, request)
            assertEquals(expected.value, value); assertEquals(expected.index, index); assertEquals(expected.bytes.size, data.size)
            if (requestType and 0x80 == 0) assertArrayEquals(expected.bytes, data) else expected.bytes.copyInto(data)
            return expected.result
        }
        fun done() = assertTrue(commands.isEmpty())
    }
    private fun Wire.endpointRead(endpoint: Int, rate: Long) = add(0xa2, 0x81, 0x100, endpoint, le(rate, 3))
    private fun Wire.endpointWrite(endpoint: Int, rate: Long) = add(0x22, 1, 0x100, endpoint, le(rate, 3)).endpointRead(endpoint, rate)
    private fun Wire.clock(rate: Long, valid: Boolean = true) = apply {
        add(0xa1, 1, 0x100, 0x0a04, le(rate, 4))
        add(0xa1, 2, 0x100, 0x0a04, le(1, 2))
        add(0xa1, 2, 0x100, 0x0a04, le(1, 2) + le(rate, 4) + le(rate, 4) + le(0, 4))
        add(0xa1, 1, 0x200, 0x0a04, byteArrayOf(if (valid) 1 else 0))
    }

    @Test fun uac1ConfiguresAndRestoresBothEndpointsWithoutChangingSelectorsOrVolumes() {
        val p = profiles(function(true)).single()
        val wire = Wire().endpointRead(1, 44100).endpointWrite(1, 48000)
            .endpointRead(0x82, 44100).endpointWrite(0x82, 48000)
            .endpointWrite(0x82, 44100).endpointWrite(1, 44100)
        val session = UsbAudioPlaybackSession(wire, listOf(p))
        session.configure(0, 48000); session.restore(); wire.done()
    }

    @Test fun failedSourceWriteStillRestoresBothEndpointsAndDoesNotExposeAnActiveProfile() {
        val p = profiles(function(true)).single()
        val wire = Wire().endpointRead(1, 44100).endpointWrite(1, 48000).endpointRead(0x82, 44100)
            .add(0x22, 1, 0x100, 0x82, le(48000, 3), 2)
            .endpointWrite(0x82, 44100).endpointWrite(1, 44100)
        val session = UsbAudioPlaybackSession(wire, listOf(p))
        assertThrows(IOException::class.java) { session.configure(0, 48000) }
        assertThrows(IllegalStateException::class.java) { session.readHardwareVolume() }
        session.restore(); wire.done()
    }

    @Test fun oneRestoreFailureDoesNotPreventReturningTheOtherEndpointToItsOriginalRate() {
        val p = profiles(function(true)).single()
        val wire = Wire().endpointRead(1, 44100).endpointWrite(1, 48000).endpointRead(0x82, 44100).endpointWrite(0x82, 48000)
            .add(0x22, 1, 0x100, 0x82, le(44100, 3), -1).endpointWrite(1, 44100)
        val session = UsbAudioPlaybackSession(wire, listOf(p))
        session.configure(0, 48000)
        assertThrows(IOException::class.java) { session.restore() }
        session.restore(); wire.done()
    }

    private fun rootClock(f: UsbAudioFunction) = f.copy(
        inputTerminals = f.inputTerminals.mapValues { (_, t) -> t.copy(clockId = 10) },
        outputTerminals = f.outputTerminals.mapValues { (_, t) -> t.copy(clockId = 10) })

    @Test fun uac2ReadsBackTheCaptureClockBeforeAcceptingTheImplicitProfile() {
        val p = profiles(rootClock(function())).single()
        val wire = Wire().clock(48000).clock(48000).clock(48000)
        val session = UsbAudioPlaybackSession(wire, listOf(p))
        session.configure(0, 48000); session.restore(); wire.done()
        val invalid = Wire().clock(48000).clock(48000).clock(48000, false)
        assertThrows(IllegalStateException::class.java) { UsbAudioPlaybackSession(invalid, listOf(p)).configure(0, 48000) }
        invalid.done()
    }

    @Test fun nativeDsdFeedbackUsesUsbWordClockAndDopUsesItsPcmCarrierClock() {
        val f = rootClock(function())
        val stream = f.playbackAlternates.single()
        val source = f.captureAlternates.single()
        val native = UsbDirectProfile(f, stream, "dsd-u32-be", 32, source)
        val wire = Wire().clock(88200).clock(88200).clock(88200)
        UsbAudioPlaybackSession(wire, listOf(native)).let { it.configure(0, 2822400); it.restore() }; wire.done()
        val dop = profiles(f, 176400, "dop-int24").single()
        val carrier = Wire().clock(176400).clock(176400).clock(176400)
        UsbAudioPlaybackSession(carrier, listOf(dop)).let { it.configure(0, 176400); it.restore() }; carrier.done()
    }
}
