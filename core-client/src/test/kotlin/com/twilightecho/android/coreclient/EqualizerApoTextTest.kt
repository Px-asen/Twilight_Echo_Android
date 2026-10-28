package com.twilightecho.android.coreclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EqualizerApoTextTest {
    private fun issuesOf(text: String): List<EqTextIssue> = try {
        EqualizerApoText.parse(text)
        fail("expected import issues")
        emptyList()
    } catch (error: EqTextImportException) {
        error.issues
    }

    @Test
    fun importsAutoEqParametricTextExactly() {
        val profile = EqualizerApoText.parse(
            """
            Preamp: -6.4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.4 dB Q 0.70
            Filter 2: ON PK Fc 225 Hz Gain -2.3 dB Q 0.83
            Filter 3: ON PK Fc 3300 Hz Gain 3.9 dB Q 1.59
            Filter 4: ON HSC Fc 10000 Hz Gain -2.0 dB Q 0.70
            """.trimIndent(),
            fileName = "Sennheiser HD 650 ParametricEQ.txt"
        )

        assertEquals(EqTextFormat.AUTOEQ, profile.format)
        assertEquals(-6.4, profile.preampDb, 0.0)
        assertEquals(
            listOf(
                CoreParametricEqBand(105.0, 5.4, 0.7, "lowShelf"),
                CoreParametricEqBand(225.0, -2.3, 0.83, "peak"),
                CoreParametricEqBand(3300.0, 3.9, 1.59, "peak"),
                CoreParametricEqBand(10000.0, -2.0, 0.7, "highShelf")
            ),
            profile.bands
        )
    }

    @Test
    fun importsRewFilterSettingsAndSkipsEmptySlots() {
        val profile = EqualizerApoText.parse(
            "﻿Filter Settings file\r\n\r\nRoom EQ V5.31.1\r\nDated: 28-Oct-2026 10:21:00\r\n\r\n" +
                "Notes: living room, seat 2\r\n\r\nEqualiser: Generic\r\nAverage 1\r\n" +
                "Filter  1: ON  PK       Fc   63.0 Hz  Gain  -6.0 dB  Q  4.00\r\n" +
                "Filter  2: OFF PK       Fc   125 Hz  Gain  -3.0 dB  Q  2.00\r\n" +
                "Filter  3: ON  HP       Fc   20.0 Hz\r\n" +
                "Filter  4: OFF None\r\n"
        )

        assertEquals(EqTextFormat.REW, profile.format)
        assertEquals(0.0, profile.preampDb, 0.0)
        assertEquals(
            listOf(
                CoreParametricEqBand(63.0, -6.0, 4.0, "peak"),
                CoreParametricEqBand(125.0, -3.0, 2.0, "peak", enabled = false),
                CoreParametricEqBand(20.0, 0.0, 0.7071067811865476, "highPass")
            ),
            profile.bands
        )
    }

    @Test
    fun appliesChannelSelectionsAndSumsPreamps() {
        val profile = EqualizerApoText.parse(
            """
            # Equalizer APO
            Device: Speakers Realtek
            Preamp: -3 dB
            Preamp: -1.5dB
            Channel: L
            Filter: ON PK Fc 1000Hz Gain 2dB Q 1
            Channel: R 3
            Filter: ON NO Fc 60 Hz Q 10
            Channel: all
            Filter: ON LPQ Fc 18000 Hz Gain 0 dB Q 0.5
            """.trimIndent()
        )

        assertEquals(EqTextFormat.EQUALIZER_APO, profile.format)
        assertEquals(-4.5, profile.preampDb, 0.0)
        assertEquals(listOf(1L, 6L, EqLimits.ALL_CHANNELS), profile.bands.map { it.channelMask })
        assertEquals(listOf("peak", "notch", "lowPass"), profile.bands.map { it.filterType })
    }

    @Test
    fun reportsEveryParameterItCannotKeepWithItsLine() {
        val issues = issuesOf(
            """
            Preamp: -14 dB
            Filter 1: ON PK Fc 1000 Hz Gain 30 dB Q 1
            Filter 2: ON PK Fc 1000 Hz Gain 3 dB Q 25
            Filter 3: ON LS Fc 100 Hz Gain 3 dB
            Filter 4: ON PK Fc 1000 Hz Gain 3 dB BW Oct 1
            GraphicEQ: 20 0; 1000 3
            Filter 5: ON LPQ Fc 5000 Hz Gain 2 dB Q 0.7
            hello
            Channel: C
            Filter 6: ON LSC 12dB Fc 100 Hz Gain 3 dB
            Filter 7: ON PK Fc 5 Hz Gain 3 dB Q 1
            """.trimIndent()
        )

        assertEquals((1..11).toList(), issues.map { it.line })
        assertEquals("第 1 行：前级 -14 dB 超出 ±12 dB", issues[0].toString())
        assertTrue(issues[1].message.contains("Gain 30 dB"))
        assertTrue(issues[2].message.contains("Q 25"))
        assertTrue(issues[3].message.contains("LSC"))
        assertTrue(issues[4].message.contains("BW"))
        assertTrue(issues[5].message.contains("GraphicEQ"))
        assertTrue(issues[6].message.contains("Gain"))
        assertTrue(issues[8].message.contains("声道 C"))
        assertTrue(issues[9].message.contains("12dB"))
        assertTrue(issues[10].message.contains("Fc 5 Hz"))
    }

    @Test
    fun reportsBandCountPreampChannelAndEmptyFiles() {
        val tooMany = (1..33).joinToString("\n") { "Filter $it: ON PK Fc ${100 * it} Hz Gain 1 dB Q 1" }
        assertEquals(listOf("共 33 个滤波器，最多支持 32 个"), issuesOf(tooMany).map { it.message })
        assertEquals(listOf(2), issuesOf("Channel: L\nPreamp: -3 dB\nFilter: ON PK Fc 1000 Hz Gain 1 dB Q 1").map { it.line })
        assertEquals(listOf("未找到参数均衡滤波器"), issuesOf("Preamp: -3 dB\n").map { it.message })
        assertEquals(listOf(3), issuesOf("Device: A\nFilter: ON PK Fc 1000 Hz Gain 1 dB Q 1\nDevice: B").map { it.line })
    }

    @Test
    fun exportsAutoEqLayoutAndReimportsTheSameBands() {
        val bands = listOf(
            CoreParametricEqBand(105.0, 5.4, 0.7, "lowShelf"),
            CoreParametricEqBand(1000.0, -2.25, 1.41, "peak", channelMask = 1L),
            CoreParametricEqBand(60.0, 0.0, 30.0 / 1.5, "notch", channelMask = 2L or 4L),
            CoreParametricEqBand(18000.0, 0.0, 0.5, "lowPass", enabled = false),
            CoreParametricEqBand(25.0, 0.0, 0.7071067811865476, "highPass"),
            CoreParametricEqBand(800.0, 0.0, 2.0, "bandPass"),
            CoreParametricEqBand(1200.0, 0.0, 0.9, "allPass"),
            CoreParametricEqBand(9000.0, -3.0, 0.7, "highShelf")
        )
        val config = CoreEqConfigView(
            enabled = true,
            preampDb = -6.4,
            dsp = CoreDspConfig(eqMode = "parametric", parametricBands = bands)
        )

        val text = EqualizerApoText.format(config)

        assertEquals(
            """
            Preamp: -6.4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.4 dB Q 0.7
            Channel: L
            Filter 2: ON PK Fc 1000 Hz Gain -2.25 dB Q 1.41
            Channel: R 3
            Filter 3: ON NO Fc 60 Hz Q 20
            Channel: all
            Filter 4: OFF LPQ Fc 18000 Hz Q 0.5
            Filter 5: ON HPQ Fc 25 Hz Q 0.707107
            Filter 6: ON BP Fc 800 Hz Q 2
            Filter 7: ON AP Fc 1200 Hz Q 0.9
            Filter 8: ON HSC Fc 9000 Hz Gain -3 dB Q 0.7
            """.trimIndent() + "\n",
            text
        )
        val reimported = EqualizerApoText.parse(text)
        assertEquals(-6.4, reimported.preampDb, 0.0)
        assertEquals(bands.size, reimported.bands.size)
        reimported.bands.zip(bands).forEach { (actual, expected) ->
            assertEquals(expected.copy(q = 0.0), actual.copy(q = 0.0))
            assertEquals(expected.q, actual.q, 1.0e-6)
        }
    }

    @Test
    fun exportsGraphicSlidersAsTheEnginePeaksAndSilentBandsAsOff() {
        val graphic = EqualizerApoText.format(
            CoreEqConfigView(preampDb = -3.0, bandsDb = listOf(3.0, 2.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0, -2.5))
        ).lines()
        assertEquals("Preamp: -3 dB", graphic[0])
        assertEquals("Filter 1: ON PK Fc 31 Hz Gain 3 dB Q 1.4", graphic[1])
        assertEquals("Filter 10: ON PK Fc 16000 Hz Gain -2.5 dB Q 1.4", graphic[10])

        val silent = EqualizerApoText.format(
            CoreEqConfigView(dsp = CoreDspConfig(eqMode = "parametric", parametricBands = listOf(
                CoreParametricEqBand(1000.0, 3.0, 1.0, channelMask = 0L)
            )))
        )
        assertEquals("Preamp: 0 dB\nFilter 1: OFF PK Fc 1000 Hz Gain 3 dB Q 1\n", silent)
    }

    @Test
    fun decodesUtf16AndUtf8Files() {
        val text = "Preamp: -1 dB\r\nFilter 1: ON PK Fc 1000 Hz Gain 1 dB Q 1\r\n"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)

        assertEquals(text, EqualizerApoText.decode(utf16))
        assertEquals(-1.0, EqualizerApoText.parse(EqualizerApoText.decode(utf8)).preampDb, 0.0)
    }

    @Test
    fun importReplacesTheParametricBandsAndTurnsTheEqualizerOn() {
        val profile = EqualizerApoText.parse("Preamp: -2 dB\nFilter 1: ON PK Fc 1000 Hz Gain 2 dB Q 1")
        val imported = CoreEqConfigView(dsp = CoreDspConfig(enabled = false)).withImportedEq(profile)
        assertTrue(imported.enabled)
        assertFalse(imported.dsp.enabled)
        assertEquals("parametric", imported.dsp.eqMode)
        assertEquals(-2.0, imported.preampDb, 0.0)
        assertEquals(profile.bands, imported.dsp.parametricBands)
    }
}
