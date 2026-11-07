package com.twilightecho.android.nativeintegration.usb

import org.junit.Assert.*
import org.junit.Test

class UsbAudioDescriptorsTest {
    private val fixture = UsbAudioFixtures

    @Test fun uac1RetainsOneAlternatesCompleteFormatAndDiscreteRates() {
        val function = fixture.uac1()
        assertEquals(UsbAudioVersion.UAC1, function.version)
        assertEquals(0, function.controlInterface)
        val stream = function.playbackAlternates.single()
        assertEquals(1, stream.interfaceNumber); assertEquals(1, stream.alternateSetting)
        assertEquals(setOf(UsbPcmEncoding.SIGNED_INTEGER), stream.encodings)
        assertEquals(2, stream.channels); assertEquals(3, stream.subslotBytes)
        assertEquals(24, stream.bitResolution); assertEquals(6, stream.frameBytes)
        assertEquals(3L, stream.channelConfig)
        assertEquals(listOf(UsbAudioRateRange(44100, 44100, 0), UsbAudioRateRange(48000, 48000, 0)), stream.descriptorRates)
        assertTrue(function.clocks.isEmpty())
    }

    @Test fun uac1ContinuousRangeIsNotExpandedOrRoundedToCommonRates() {
        val raw = fixture.config(parts = fixture.uac1Parts(rates = listOf(12345, 192000), continuous = true))
        assertEquals(listOf(UsbAudioRateRange(12345, 192000, 1)),
            UsbAudioDescriptors.parse(raw).configurations.single().functions.single().playbackAlternates.single().descriptorRates)
    }

    @Test fun uac1FeatureControlsAndInputTerminalUseTheirActualByteOffsets() {
        val f = fixture.uac1()
        assertEquals(2, f.inputTerminals.getValue(1).channels)
        assertEquals(0x0101, f.inputTerminals.getValue(1).type)
        assertEquals(3L, f.inputTerminals.getValue(1).channelConfig)
        val unit = f.featureUnits.single()
        assertEquals(2, unit.id); assertEquals(1, unit.sourceId)
        assertEquals(3, unit.channels.size)
        assertTrue(unit.channels[0].muteWritable); assertTrue(unit.channels[0].volumeWritable)
        assertFalse(unit.channels[1].muteReadable); assertTrue(unit.channels[1].volumeReadable)
    }

    @Test fun alternatesDoNotFormACartesianProductOfBitDepthAndRate() {
        val parts = fixture.uac1Parts().toMutableList()
        val other = fixture.uac1Parts(rates = listOf(96000), subslot = 4, bits = 32).drop(6).mapIndexed { index, bytes ->
            if (index == 0) bytes.copyOf().also { it[3] = 2 } else bytes
        }
        parts.addAll(other)
        val streams = UsbAudioDescriptors.parse(fixture.config(parts = parts)).configurations.single().functions.single().playbackAlternates
        assertEquals(2, streams.size)
        assertEquals(listOf(44100L, 48000L), streams[0].descriptorRates.map { it.minimum })
        assertEquals(24, streams[0].bitResolution)
        assertEquals(listOf(96000L), streams[1].descriptorRates.map { it.minimum })
        assertEquals(32, streams[1].bitResolution)
    }

    @Test fun endpointFeedbackAddressRefreshAndFrequencyControlStayOnTheirEndpoint() {
        val s = fixture.uac1().playbackAlternates.single()
        assertEquals(1, s.dataEndpoint.address); assertEquals(0x126, s.dataEndpoint.rawMaxPacketSize)
        assertEquals(UsbIsoSync.ASYNCHRONOUS, s.dataEndpoint.sync)
        assertEquals(0x81, s.dataEndpoint.syncAddress); assertTrue(s.dataEndpoint.samplingFrequencyControl)
        assertEquals(0x81, s.feedbackEndpoint!!.address); assertEquals(4, s.feedbackEndpoint.refresh)
        assertFalse(s.feedbackEndpoint.samplingFrequencyControl)
    }

    @Test fun uac2RetainsClockGraphButDoesNotInventDescriptorSampleRates() {
        val f = fixture.uac2()
        assertEquals(4, f.controlInterface); assertEquals(UsbAudioVersion.UAC2, f.version)
        val s = f.playbackAlternates.single()
        assertEquals(5, s.interfaceNumber); assertEquals(4, s.subslotBytes)
        assertEquals(24, s.bitResolution); assertEquals(8, s.frameBytes)
        assertTrue(s.descriptorRates.isEmpty())
        assertEquals(30, f.inputTerminals.getValue(1).clockId)
        assertEquals(UsbAudioClock.Selector(20, listOf(10, 11), 3), f.clocks[20])
        assertEquals(UsbAudioClock.Multiplier(30, 20, 5), f.clocks[30])
        assertEquals(0x82, s.feedbackEndpoint!!.address); assertEquals(4, s.feedbackEndpoint.interval)
        assertFalse(s.dataEndpoint.samplingFrequencyControl)
    }

    @Test fun uac2ReadOnlyAndWritableControlsAreNotConfused() {
        val unit = fixture.uac2().featureUnits.single()
        assertTrue(unit.channels[0].muteWritable); assertTrue(unit.channels[0].volumeWritable)
        assertTrue(unit.channels[1].volumeReadable); assertFalse(unit.channels[1].volumeWritable)
        assertFalse(unit.channels[1].muteReadable)
    }

    @Test fun ieeeFloatAndIntegerAreOnlyReportedWhenTheirWireFormatIsDeclared() {
        for ((bitmap, expected) in listOf(1L to setOf(UsbPcmEncoding.SIGNED_INTEGER),
            4L to setOf(UsbPcmEncoding.IEEE_FLOAT), 5L to UsbPcmEncoding.entries.toSet())) {
            val parts = fixture.uac2Parts(formats = bitmap).map { bytes ->
                if (bytes.size == 6 && bytes[1].toInt() == 0x24 && bytes[2].toInt() == 2)
                    bytes.copyOf().also { it[5] = 32 } else bytes
            }
            assertEquals(expected, UsbAudioDescriptors.parse(fixture.config(parts = parts))
                .configurations.single().functions.single().playbackAlternates.single().encodings)
        }
    }

    @Test fun repeatedEntityIdsAreScopedToTheirIadAndControlInterface() {
        val parts = fixture.uac2Parts() + fixture.uac2Parts(8, 9)
        val functions = UsbAudioDescriptors.parse(fixture.config(parts = parts)).configurations.single().functions
        assertEquals(listOf(4, 8), functions.map { it.controlInterface })
        assertEquals(listOf(listOf(5), listOf(9)), functions.map { it.playbackAlternates.map { s -> s.interfaceNumber } })
        assertTrue(functions.all { it.clocks.keys == setOf(10, 11, 20, 30) })
    }

    @Test fun separateConfigurationsKeepTheirOwnFormatsAndValues() {
        val raw = fixture.config(1, fixture.uac1Parts()) + fixture.config(7, fixture.uac1Parts(rates = listOf(96000)))
        val configs = UsbAudioDescriptors.parse(raw).configurations
        assertEquals(listOf(1, 7), configs.map { it.value })
        assertEquals(listOf(96000L), configs[1].functions.single().playbackAlternates.single().descriptorRates.map { it.minimum })
    }

    @Test fun captureMidiCompressedAndUac3InterfacesAreNotOfferedAsPcmPlayback() {
        val originals = fixture.uac1Parts()
        val capture = originals.map { bytes -> if (bytes.size == 7 && bytes[1].toInt() == 0x24 && bytes[2].toInt() == 1)
            bytes.copyOf().also { it[3] = 3 } else bytes }
        val compressed = originals.map { bytes -> if (bytes.size == 7 && bytes[1].toInt() == 0x24 && bytes[2].toInt() == 1)
            bytes.copyOf().also { it[5] = 6 } else bytes }
        for (parts in listOf(capture, compressed)) assertTrue(UsbAudioDescriptors.parse(fixture.config(parts = parts))
            .configurations.single().functions.single().playbackAlternates.isEmpty())
        val uac3 = fixture.uac2Parts().map { bytes -> if (bytes.size == 9 && bytes[1].toInt() == 4)
            bytes.copyOf().also { it[7] = 0x30 } else bytes }
        assertTrue(UsbAudioDescriptors.parse(fixture.config(parts = uac3)).configurations.single().functions.isEmpty())
        val withMidi = originals + fixture.iface(9, 0, 0, 3, 0)
        assertEquals(1, UsbAudioDescriptors.parse(fixture.config(parts = withMidi)).configurations.single().functions.single().playbackAlternates.size)
    }

    @Test fun uac2WithoutFunctionAssociationDoesNotBorrowAnotherFunctionsClock() {
        val parts = fixture.uac2Parts().drop(1)
        assertTrue(UsbAudioDescriptors.parse(fixture.config(parts = parts)).configurations.single().functions.isEmpty())
    }

    @Test fun superSpeedCompanionIsPreservedWithoutGuessingActiveBusSpeed() {
        val parts = fixture.uac2Parts().flatMap { bytes ->
            if (bytes.size == 7 && bytes[1].toInt() == 5 && bytes[2].toInt() == 2)
                listOf(bytes.copyOf().also { it[5] = 0x14 }, fixture.bytes(6, 0x30, 1, 2, 0, 0x18))
            else listOf(bytes)
        }
        val s = UsbAudioDescriptors.parse(fixture.config(parts = parts)).configurations.single().functions.single().playbackAlternates.single()
        assertEquals(0x1400, s.dataEndpoint.rawMaxPacketSize)
        assertEquals(UsbSuperSpeedCompanion(1, 2, 0x1800), s.dataEndpoint.superSpeed)
        assertNull(s.feedbackEndpoint!!.superSpeed)
    }

    @Test fun truncatedZeroLengthAndConfigBoundaryViolationsAreRejected() {
        val good = fixture.config(parts = fixture.uac1Parts())
        val malformed = listOf(byteArrayOf(1), fixture.bytes(0, 4), fixture.bytes(9, 4, 0), good.dropLast(1).toByteArray(),
            good.copyOf().also { it[2] = 9; it[3] = 0 },
            good.copyOf().also { it[9] = 0 })
        malformed.forEach { raw -> assertThrows(IllegalArgumentException::class.java) { UsbAudioDescriptors.parse(raw) } }
    }

    @Test fun deviceAndVendorDescriptorsDoNotChangeAudioCapabilityMeaning() {
        val device = fixture.bytes(18, 1, 0, 2, 0, 0, 0, 64, 0x34, 0x12, 0x78, 0x56, 0, 1, 1, 2, 3, 1)
        val parts = fixture.uac1Parts().toMutableList().also { it.add(1, fixture.bytes(4, 0xff, 23, 42)) }
        assertEquals(fixture.uac1(), UsbAudioDescriptors.parse(device + fixture.config(parts = parts)).configurations.single().functions.single())
    }

    @Test fun classSpecificEndpointControlsAndLockDelayRetainTheirVersionSpecificLayout() {
        for ((version, parts) in listOf(UsbAudioVersion.UAC1 to fixture.uac1Parts(), UsbAudioVersion.UAC2 to fixture.uac2Parts())) {
            val changed = parts.map { data -> if (data[1].toInt() == 0x25) {
                if (version == UsbAudioVersion.UAC1) fixture.bytes(7, 0x25, 1, 1, 2, 0x34, 0x12)
                else fixture.bytes(8, 0x25, 1, 0, 0x15, 2, 0x34, 0x12)
            } else data }
            val endpoint = UsbAudioDescriptors.parse(fixture.config(parts = changed)).configurations.single()
                .functions.single().playbackAlternates.single().dataEndpoint
            assertEquals(2, endpoint.lockDelayUnits); assertEquals(0x1234, endpoint.lockDelay)
            assertEquals(if (version == UsbAudioVersion.UAC1) 1 else 0, endpoint.classAttributes)
            assertEquals(if (version == UsbAudioVersion.UAC1) 0 else 0x15, endpoint.classControls)
        }
    }
}
