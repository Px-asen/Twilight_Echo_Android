package com.twilightecho.android.nativeintegration.usb

import org.junit.Assert.*
import org.junit.Test

class UsbPlaybackVolumeTest {
    private val f get() = UsbAudioFixtures.uac1()
    private val volumeOnly = UsbFeatureChannel(false, false, true, true)
    private fun controls(function: UsbAudioFunction, pins: List<Int> = emptyList()): Pair<UsbAudioControls, ArrayDeque<Int>> {
        val remaining = ArrayDeque(pins)
        val wire = UsbAudioControlTransfer { type, request, value, index, data ->
            assertEquals(0xa1, type)
            assertEquals(if (function.version == UsbAudioVersion.UAC1) 0x81 else 1, request)
            assertEquals(if (function.version == UsbAudioVersion.UAC1) 0 else 0x100, value)
            assertEquals((50 shl 8) or function.controlInterface, index)
            assertEquals(1, data.size); data[0] = remaining.removeFirst().toByte(); 1
        }
        return UsbAudioControls(wire, function) to remaining
    }
    private fun fixed(function: UsbAudioFunction = f): UsbDirectProfile {
        val stream = function.playbackAlternates.single().copy(descriptorRates = listOf(UsbAudioRateRange(48000, 48000, 0)),
            dataEndpoint = function.playbackAlternates.single().dataEndpoint.copy(samplingFrequencyControl = false))
        return UsbDirectProfile(function.copy(playbackAlternates = listOf(stream)), stream, "int24")
    }
    private fun volumeWire(): UsbAudioControlTransfer = UsbAudioControlTransfer { type, request, value, index, data ->
        assertEquals(0xa1, type); assertEquals(0x200, value); assertEquals(0x0200, index)
        val result = when (request) { 0x81 -> -1536; 0x82 -> -24576; 0x83 -> 0; 0x84 -> 256; else -> error("Unexpected control") }
        assertEquals(2, data.size); UsbAudioFixtures.le(result.toLong(), 2).copyInto(data); 2
    }

    @Test fun outputTerminalSourcesAreParsedForBothVersions() {
        for (function in listOf(f, UsbAudioFixtures.uac2())) {
            assertEquals(mapOf(3 to UsbAudioOutputTerminal(3, 0x0301, 2,
                if (function.version == UsbAudioVersion.UAC2) 30 else null)), function.outputTerminals)
            val (controls, pins) = controls(function)
            assertEquals(listOf(2), controls.playbackFeatureUnits(function.playbackAlternates.single()).map { it.id })
            assertTrue(pins.isEmpty())
        }
    }

    @Test fun playbackNeverSelectsAnUnrelatedStreamOrCaptureFeatureUnit() {
        val function = f.copy(inputTerminals = f.inputTerminals +
            (10 to UsbAudioInputTerminal(10, 0x0201, null, 2, 3)) +
            (20 to UsbAudioInputTerminal(20, 0x0101, null, 2, 3)),
            featureUnits = f.featureUnits + UsbAudioFeatureUnit(11, 10, listOf(volumeOnly)) +
                UsbAudioFeatureUnit(21, 20, listOf(volumeOnly)),
            outputTerminals = f.outputTerminals + (12 to UsbAudioOutputTerminal(12, 0x0101, 11)) +
                (22 to UsbAudioOutputTerminal(22, 0x0302, 21)))
        assertEquals(listOf(2), controls(function).first.playbackFeatureUnits(function.playbackAlternates.single()).map { it.id })
    }

    @Test fun mixerIncludesOnlyTheFeatureBranchesFedByTheActivePlaybackTerminal() {
        val function = f.copy(featureUnits = f.featureUnits + UsbAudioFeatureUnit(11, 51, listOf(volumeOnly)),
            signalUnits = mapOf(40 to UsbAudioSignalUnit.Junction(40, listOf(2, 11)),
                51 to UsbAudioSignalUnit.Selector(51, listOf(10, 12), false)),
            outputTerminals = mapOf(3 to UsbAudioOutputTerminal(3, 0x0301, 40)))
        assertEquals(listOf(2), controls(function).first.playbackFeatureUnits(function.playbackAlternates.single()).map { it.id })
    }

    @Test fun selectorFollowsItsCurrentPinAndNeverChangesTheSelection() {
        for (original in listOf(f, UsbAudioFixtures.uac2())) {
            val function = original.copy(signalUnits = mapOf(50 to UsbAudioSignalUnit.Selector(50, listOf(2, 10), true)),
                outputTerminals = mapOf(3 to UsbAudioOutputTerminal(3, 0x0301, 50)))
            val (controls, pins) = controls(function, listOf(1, 2))
            assertEquals(listOf(2), controls.playbackFeatureUnits(function.playbackAlternates.single()).map { it.id })
            assertTrue(controls.playbackFeatureUnits(function.playbackAlternates.single()).isEmpty())
            assertTrue(pins.isEmpty())
        }
    }

    @Test fun unreadableSignalSelectorIsReportedRatherThanGuessingTheFirstInput() {
        val function = f.copy(signalUnits = mapOf(50 to UsbAudioSignalUnit.Selector(50, listOf(2, 10), false)),
            outputTerminals = mapOf(3 to UsbAudioOutputTerminal(3, 0x0301, 50)))
        assertThrows(IllegalArgumentException::class.java) {
            controls(function).first.playbackFeatureUnits(function.playbackAlternates.single())
        }
    }

    @Test fun singlePinSelectorNeedsNoControlRequestToIdentifyItsOnlyInput() {
        val function = f.copy(signalUnits = mapOf(50 to UsbAudioSignalUnit.Selector(50, listOf(2), false)),
            outputTerminals = mapOf(3 to UsbAudioOutputTerminal(3, 0x0301, 50)))
        assertEquals(listOf(2), controls(function).first.playbackFeatureUnits(function.playbackAlternates.single()).map { it.id })
    }

    @Test fun configuredFixedRateLeaseExposesHardwareControlsAndReleaseInvalidatesThemWithoutRestoringVolume() {
        val p = fixed(f.copy(featureUnits = listOf(UsbAudioFeatureUnit(2, 1, listOf(volumeOnly)))))
        val session = UsbAudioPlaybackSession(volumeWire(), listOf(p), 7)
        assertThrows(IllegalStateException::class.java) { session.readHardwareVolume() }
        session.configure(0, 48000) // No clock change: no restore action, but the profile is active.
        val channel = session.readHardwareVolume().channels.single()
        assertEquals(UsbHardwareControlId(7, 2, 0), channel.id)
        assertEquals(-1536, channel.volume?.currentDb256); assertTrue(channel.volumeWritable); assertNull(channel.muted)
        session.restore()
        assertThrows(IllegalStateException::class.java) { session.readHardwareVolume() }
        assertThrows(IllegalStateException::class.java) { session.setHardwareVolume(channel.id, -3072) }
    }

    @Test fun previousLeaseAndNonPlaybackUnitCannotWriteTheCurrentDac() {
        val session = UsbAudioPlaybackSession(volumeWire(), listOf(fixed()), 8)
        session.configure(0, 48000)
        assertThrows(IllegalStateException::class.java) { session.setHardwareVolume(UsbHardwareControlId(7, 2, 0), -3072) }
        assertThrows(IllegalArgumentException::class.java) { session.setHardwareVolume(UsbHardwareControlId(8, 99, 0), -3072) }
    }

    @Test fun failedConfigureDoesNotExposeAPlaybackVolumeControl() {
        val session = UsbAudioPlaybackSession(volumeWire(), listOf(fixed()), 9)
        assertThrows(IllegalArgumentException::class.java) { session.configure(0, 44100) }
        assertThrows(IllegalStateException::class.java) { session.readHardwareVolume() }
    }

    @Test fun explicitHardwareWritesUseTheConfiguredLeaseAndReleaseDoesNotUndoThem() {
        val p = fixed(f.copy(featureUnits = listOf(f.featureUnits.single().copy(channels = listOf(
            UsbFeatureChannel(true, true, true, true))))))
        var current = -1536
        var muted = false
        var writes = 0
        val io = UsbAudioControlTransfer { type, request, value, index, data ->
            assertEquals(0x0200, index)
            if (type == 0x21) {
                assertEquals(1, request); writes++
                when (value) {
                    0x100 -> { assertEquals(1, data.size); muted = data[0].toInt() != 0 }
                    0x200 -> { assertEquals(2, data.size); current = ((data[0].toInt() and 0xff) or (data[1].toInt() shl 8)).toShort().toInt() }
                    else -> error("Unexpected write selector")
                }
            } else {
                assertEquals(0xa1, type)
                if (value == 0x100) { assertEquals(0x81, request); data[0] = if (muted) 1 else 0 }
                else {
                    assertEquals(0x200, value)
                    val reply = when (request) { 0x81 -> current; 0x82 -> -24576; 0x83 -> 0; 0x84 -> 256; else -> error("Unexpected read") }
                    UsbAudioFixtures.le(reply.toLong(), 2).copyInto(data)
                }
            }
            data.size
        }
        val session = UsbAudioPlaybackSession(io, listOf(p), 7)
        session.configure(0, 48000)
        assertEquals(0, writes)
        assertEquals(-3072, session.setHardwareVolume(UsbHardwareControlId(7, 2, 0), -3072).currentDb256)
        assertTrue(session.setHardwareMute(UsbHardwareControlId(7, 2, 0), true))
        session.restore()
        assertEquals(2, writes); assertEquals(-3072, current); assertTrue(muted)
    }

    @Test fun signalSelectorIsReadAgainBeforeWritingAndChangedPathCannotReceiveTheOldCommand() {
        val function = f.copy(featureUnits = listOf(UsbAudioFeatureUnit(2, 1, listOf(volumeOnly))),
            signalUnits = mapOf(50 to UsbAudioSignalUnit.Selector(50, listOf(2, 10), true)),
            outputTerminals = mapOf(3 to UsbAudioOutputTerminal(3, 0x0301, 50)))
        var selectorCalls = 0
        val volume = volumeWire()
        val wire = UsbAudioControlTransfer { type, request, value, index, data ->
            if (index == 0x3200) {
                assertEquals(0xa1, type); assertEquals(0x81, request); assertEquals(0, value)
                data[0] = if (++selectorCalls == 1) 1 else 2; 1
            } else volume.transfer(type, request, value, index, data)
        }
        val session = UsbAudioPlaybackSession(wire, listOf(fixed(function)), 7)
        session.configure(0, 48000)
        val channel = session.readHardwareVolume().channels.single()
        assertThrows(IllegalArgumentException::class.java) { session.setHardwareVolume(channel.id, -3072) }
        assertEquals(2, selectorCalls)
    }

    @Test fun descriptorSignalSourcesUseVersionSpecificProcessingEffectAndSelectorLayouts() {
        for (uac1 in listOf(true, false)) {
            fun desc(vararg values: Int) = UsbAudioFixtures.bytes(values.size + 1, *values)
            val parts = (if (uac1) UsbAudioFixtures.uac1Parts() else UsbAudioFixtures.uac2Parts()).toMutableList()
            val at = parts.indexOfFirst { it.size >= 9 && it[1].toInt() == 4 && it[6].toInt() == 2 }
            parts.addAll(at, listOf(
                desc(0x24, 4, 40, 2, 2, 10, 2, 3, 0, 0, 0, 0),
                if (uac1) desc(0x24, 5, 50, 2, 40, 10, 0) else desc(0x24, 5, 50, 2, 40, 10, 1, 0),
                desc(0x24, if (uac1) 7 else 8, 60, 1, 0, 1, 50, 0),
                desc(0x24, if (uac1) 8 else 9, 61, 0, 0, 1, 60, 0)))
            if (!uac1) parts.addAll(at, listOf(desc(0x24, 7, 62, 1, 0, 61, 0), desc(0x24, 0x0d, 63, 62, 10, 11, 0)))
            val function = UsbAudioDescriptors.parse(UsbAudioFixtures.config(parts = parts)).configurations.single().functions.single()
            assertEquals(listOf(2, 10), function.signalUnits.getValue(40).sources)
            assertEquals(UsbAudioSignalUnit.Selector(50, listOf(40, 10), true), function.signalUnits[50])
            assertEquals(listOf(50), function.signalUnits.getValue(60).sources)
            assertEquals(listOf(60), function.signalUnits.getValue(61).sources)
            if (!uac1) {
                assertEquals(listOf(61), function.signalUnits.getValue(62).sources)
                assertEquals(listOf(62), function.signalUnits.getValue(63).sources)
            }
        }
    }
}
