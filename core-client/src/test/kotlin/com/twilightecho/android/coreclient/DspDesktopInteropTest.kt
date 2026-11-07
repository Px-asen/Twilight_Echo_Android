package com.twilightecho.android.coreclient

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/** Explicit cross-repository JVM/Node check; no Android installation or audio device is used. */
class DspDesktopInteropTest {
    private val json = DefaultCoreClient.DefaultJson

    @Test fun `real phone packages survive desktop import edit settings reload and reexport`() {
        val directory = File(System.getProperty("twilight.interop.output")).apply { mkdirs() }
        val wave = File(directory, "phone-ir.wav").apply { writeBytes(impulseWave()) }
        val described = describeImpulseFile(wave)!!
        val bands = listOf(
            CoreParametricEqBand(10.0, 3.5, 0.7, "lowShelf", channelMask = 1),
            CoreParametricEqBand(96000.0, -2.5, 0.8, "highShelf", enabled = false, channelMask = 0x80000003),
            CoreParametricEqBand(18000.0, 0.0, 1.2, "lowPass"),
            CoreParametricEqBand(15.0, 0.0, 0.5, "highPass"),
            CoreParametricEqBand(105.0, 4.5, 1.1),
            CoreParametricEqBand(1000.0, 0.0, 2.5, "bandPass"),
            CoreParametricEqBand(8000.0, 0.0, 4.0, "notch"),
            CoreParametricEqBand(100.0, 0.0, 1.0, "allPass")
        )
        val graphic = CoreEqConfigView(enabled = true, preset = "Rock", preampDb = -3.5,
            bandsDb = CoreEqConfigView.PRESETS.getValue("Rock"), dsp = CoreDspConfig(
                parametricBands = listOf(bands[4]), crossfadeSeconds = 2.5, playbackRate = 1.25,
                outputStage = CoreDspOutputStage(targetSampleRate = 96000, resamplerQuality = "ultra", dither = "tpdf")))
        val parametric = CoreEqConfigView(enabled = true, preampDb = -6.2, gapless = false,
            bandsDb = List(10) { index -> (index - 5) * 0.5 }, dsp = CoreDspConfig(
                enabled = false, eqMode = "parametric", parametricBands = bands,
                nodes = listOf(CoreDspNode("phone-ir", "convolver", params = buildJsonObject {
                    put("impulseResponsePath", wave.absolutePath); put("wetDb", -1.0)
                })),
                outputDeviceId = "usb-direct:/dev/bus/usb/001/002", exclusive = true,
                dopConfirmedDeviceKey = "test-device-consent", bufferFrames = 1024,
                crossfadeSeconds = 3.0, crossfadeContent = "all", continuitySampleRate = 44100,
                sampleRateFamilySync = true, allowedSampleRates = listOf(44100, 48000, 96000),
                dsdOutputMode = "native", dsdRatePolicy = "exact"))
        fun scene(id: String, name: String, config: CoreEqConfigView) = CoreDspScene(id, name,
            JsonObject(json.encodeToJsonElement(CoreEqConfigView.serializer(), config).jsonObject - "scenes"))
        val originals = listOf(scene("phone-graphic", "图示场景", graphic), scene("phone-parametric", "参数场景", parametric))
        val current = CoreEqConfigView(scenes = originals).withScene(originals[1])
        val exported = DspScenePackage.export(current, { path -> described.takeIf { path == wave.absolutePath } },
            Instant.parse("2026-11-01T00:00:00Z"), "phone-interop")
        File(directory, "phone.tedsp").outputStream().use { DspPackageArchive.write(it, exported.manifest, exported.files) }
        File(directory, "phone-eq.txt").writeText(EqualizerApoText.format(parametric))
        File(directory, "phone-eq.json").writeText(buildJsonObject {
            put("preampDb", parametric.preampDb)
            put("bands", JsonArray(bands.map { json.encodeToJsonElement(CoreParametricEqBand.serializer(), it) }))
        }.toString())
        desktopRoundtrip(directory)

        fun imported(name: String): DspPackageImport {
            val unpacked = File(directory, "$name-assets").apply { mkdirs() }
            val contents = File(directory, "$name.tedsp").inputStream().use { DspPackageArchive.read(it, unpacked) }
            val assets = DspScenePackage.assets(contents.root)
            val asset = assets.single()
            val actualWave = contents.files.getValue(asset.archivePath)
            assertArrayEquals(wave.readBytes(), actualWave.readBytes())
            // describeImpulseFile verifies the content-addressed identity used by both importers.
            assertEquals(described.assetId, describeImpulseFile(actualWave)!!.assetId)
            val result = DspScenePackage.import(contents.root, CoreEqConfigView(), mapOf(asset.id to wave.absolutePath))
            assertTrue(result.rejected.toString(), result.rejected.isEmpty())
            return result
        }
        val returned = imported("desktop-roundtrip")
        assertEquals(originals.map { it.id }, returned.scenes.map { it.scene.id })
        for ((original, actual) in originals.zip(returned.scenes)) {
            assertTrue(original.name, CoreEqConfigView().withScene(actual.scene).matchesScene(original))
            assertTrue(actual.notes.toString(), actual.notes.isEmpty())
        }
        val restored = CoreEqConfigView().withScene(returned.scenes[1].scene)
        assertEquals("auto", restored.dsp.outputDeviceId)
        assertEquals("", restored.dsp.dopConfirmedDeviceKey)
        assertEquals("native", restored.dsp.dsdOutputMode)
        assertEquals(parametric.dsp.allowedSampleRates, restored.dsp.allowedSampleRates)
        assertFalse(restored.dsp.enabled)

        val edited = imported("desktop-edited").scenes.associateBy { it.scene.id }
        val changed = CoreEqConfigView().withScene(edited.getValue("phone-parametric").scene)
        assertEquals(-4.75, changed.preampDb, 0.0)
        assertEquals(4.75, changed.dsp.parametricBands[0].gain, 0.0)
        assertEquals(parametric.dsp.parametricBands.drop(1), changed.dsp.parametricBands.drop(1))
        val desktopGraphic = CoreEqConfigView().withScene(edited.getValue("phone-graphic").scene)
        assertEquals("parametric", desktopGraphic.dsp.eqMode)
        assertTrue(desktopGraphic.dsp.parametricBands.all { it.q == 1.0 })
        assertEquals(graphic.bandsDb, desktopGraphic.dsp.parametricBands.map { it.gain })
        val parsedText = Json.parseToJsonElement(File(directory, "desktop-eq.json").readText()).jsonObject
        assertEquals(parametric.preampDb, parsedText["preampDb"]!!.jsonPrimitive.double, 0.0)
        assertEquals(bands, parsedText["bands"]!!.jsonArray.map { json.decodeFromJsonElement<CoreParametricEqBand>(it) })
    }

    @Test fun `WAV FLAC AIFF and AIFC files survive real desktop Core and phone storage imports`() {
        val directory = File(System.getProperty("twilight.interop.output"), "impulses").apply { mkdirs() }
        val files = ImpulseResponseFixtures.all().mapValues { (format, bytes) ->
            File(directory, "phone-$format.bin").apply { writeBytes(bytes) }
        }
        val scenes = files.map { (format, file) ->
            ImpulseResponseFixtures.scene(format, ImpulseResponseFixtures.config(file.absolutePath))
        }
        val exported = DspScenePackage.export(CoreEqConfigView(scenes = scenes), { describeImpulseFile(File(it)) })
        File(directory, "phone.tedsp").outputStream().use { DspPackageArchive.write(it, exported.manifest, exported.files) }
        desktopRoundtrip(directory, "impulses")
        val contents = File(directory, "desktop-roundtrip.tedsp").inputStream().use {
            DspPackageArchive.read(it, File(directory, "extracted").apply { mkdirs() })
        }
        val imported = DspPackageArchive.importScenes(contents, CoreEqConfigView(), File(directory, "owned"))
        assertTrue(imported.rejected.toString(), imported.rejected.isEmpty())
        assertEquals(files.keys.toList(), imported.scenes.map { it.scene.id })
        for (scene in imported.scenes) {
            assertTrue(scene.notes.toString(), scene.notes.isEmpty())
            val node = CoreEqConfigView().withScene(scene.scene).dsp.nodes.single()
            assertTrue(node.enabled)
            val actual = File(node.params["impulseResponsePath"]!!.jsonPrimitive.content)
            val expected = files.getValue(scene.scene.id)
            assertArrayEquals(expected.readBytes(), actual.readBytes())
            assertEquals(describeImpulseFile(expected), describeImpulseFile(actual))
            assertEquals(if (scene.scene.id == "aifc") "aiff" else scene.scene.id, actual.extension)
        }
        val phoneAgain = DspScenePackage.export(CoreEqConfigView(scenes = imported.scenes.map { it.scene }), { describeImpulseFile(File(it)) })
        assertEquals(exported.files.keys, phoneAgain.files.keys)
    }

    private fun desktopRoundtrip(directory: File, kind: String = "eq") {
        val desktopLog = File(directory, "desktop.log")
        val process = ProcessBuilder(System.getProperty("twilight.node"), "--experimental-strip-types",
            System.getProperty("twilight.interop.bridge"), System.getProperty("twilight.desktopRoot"), directory.absolutePath, kind)
            .redirectErrorStream(true).redirectOutput(desktopLog).start()
        val exit = process.waitFor()
        assertEquals(desktopLog.readText(), 0, exit)
    }

    private fun impulseWave() = ByteArrayOutputStream().also { out ->
        fun int(value: Int, bytes: Int) = repeat(bytes) { out.write((value shr (8 * it)) and 0xff) }
        out.write("RIFF".toByteArray()); int(36 + 16, 4); out.write("WAVEfmt ".toByteArray())
        int(16, 4); int(1, 2); int(2, 2); int(48000, 4); int(192000, 4); int(4, 2); int(16, 2)
        out.write("data".toByteArray()); int(16, 4); repeat(8) { int(if (it < 2) 16384 else 0, 2) }
    }.toByteArray()
}
