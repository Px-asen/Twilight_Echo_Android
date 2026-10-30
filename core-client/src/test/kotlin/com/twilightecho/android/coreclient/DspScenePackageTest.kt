package com.twilightecho.android.coreclient

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DspScenePackageTest {
    @get:Rule val folder = TemporaryFolder()

    private val json = DefaultCoreClient.DefaultJson
    private val sha = "a".repeat(64)
    private val impulsePath = "/data/user/0/app/files/dsp-impulses/ir.wav"

    private fun scene(id: String, name: String, config: CoreEqConfigView) =
        CoreDspScene(id, name, JsonObject(json.encodeToJsonElement(CoreEqConfigView.serializer(), config).jsonObject - "scenes"))

    private fun saved(scene: CoreDspScene) = CoreEqConfigView().withScene(scene)

    private val graphicScene = scene("g", "摇滚", CoreEqConfigView(
        enabled = true, preset = "Rock", preampDb = -3.5, bandsDb = CoreEqConfigView.PRESETS.getValue("Rock"),
        dsp = CoreDspConfig(
            parametricBands = listOf(CoreParametricEqBand(80.0, 2.0, 0.7, "lowShelf")),
            nodes = listOf(CoreDspNode("phone-crossfeed", "crossfeed", params = buildJsonObject { put("strength", 0.3) })),
            outputStage = CoreDspOutputStage(targetSampleRate = 96000, resamplerQuality = "ultra", dither = "tpdf"),
            crossfadeSeconds = 2.0, outputDeviceId = "17", playbackRate = 1.25
        )))

    private val parametricScene = scene("p", "HD 650", CoreEqConfigView(
        enabled = true, preampDb = -6.2, bandsDb = listOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0),
        dsp = CoreDspConfig(
            enabled = false, eqMode = "parametric",
            parametricBands = listOf(
                CoreParametricEqBand(105.0, 5.5, 0.71, "lowShelf"),
                CoreParametricEqBand(2400.0, -3.1, 2.4),
                CoreParametricEqBand(15.0, 0.0, 0.5, "highPass", enabled = false, channelMask = 1)
            ),
            nodes = listOf(CoreDspNode("phone-convolver", "convolver",
                params = buildJsonObject { put("impulseResponsePath", impulsePath); put("wetDb", -1.0) }))
        )))

    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test fun `PCM policy round trips without saving a device capability snapshot`() {
        val config = CoreEqConfigView(bitPerfect = true, dsp = CoreDspConfig(sampleRateFamilySync = true,
            allowedSampleRates = listOf(44100, 48000, 96000)))
        val original = scene("pcm", "家族同步", config)
        val exported = DspScenePackage.export(config.copy(scenes = listOf(original)), { null }).manifest
        assertFalse("deviceSampleRates" in exported.toString())
        val imported = DspScenePackage.import(exported, CoreEqConfigView())
        assertTrue(imported.rejected.isEmpty())
        val restored = saved(imported.scenes.single().scene)
        assertTrue(restored.bitPerfect)
        assertTrue(restored.dsp.sampleRateFamilySync)
        assertEquals(config.dsp.allowedSampleRates, restored.dsp.allowedSampleRates)
    }

    @Test fun `malformed PCM extension rejects only its scene and old graphs retain defaults`() {
        val valid = parse("""{"id":"old","graph":{"nodes":[]}}""").jsonObject
        listOf("\"bad\"", "[48000,48000]", "[7999]", "[768001]", "[48000.5]", "null").forEach { bad ->
            val invalid = parse("""{"id":"bad","graph":{"nodes":[]},"android":{"allowedSampleRates":$bad}}""")
            val result = DspScenePackage.import(JsonArray(listOf(invalid, valid)), CoreEqConfigView())
            assertEquals(1, result.rejected.size)
            assertEquals("old", result.scenes.single().scene.id)
            assertFalse(saved(result.scenes.single().scene).dsp.sampleRateFamilySync)
            assertTrue(saved(result.scenes.single().scene).dsp.allowedSampleRates.isEmpty())
        }
    }

    @Test fun `USB request round trips separately from route and mixer facts`() {
        val config = CoreEqConfigView(dsp = CoreDspConfig(usbPcmPassthrough = true))
        val exported = DspScenePackage.export(config.copy(scenes = listOf(scene("usb", "USB", config))), { null }).manifest
        val imported = DspScenePackage.import(exported, CoreEqConfigView())
        assertTrue(imported.rejected.isEmpty())
        assertTrue(saved(imported.scenes.single().scene).dsp.usbPcmPassthrough)
        assertFalse("usbPcmPreferenceAccepted" in exported.toString())
        val bad = parse("""{"id":"bad","graph":{"nodes":[]},"android":{"usbPcmPassthrough":"true"}}""")
        val old = parse("""{"id":"old","graph":{"nodes":[]}}""")
        val result = DspScenePackage.import(JsonArray(listOf(bad, old)), CoreEqConfigView())
        assertEquals(1, result.rejected.size)
        assertFalse(saved(result.scenes.single().scene).dsp.usbPcmPassthrough)
    }

    @Test fun `high PCM targets and allowed sets round trip without truncation`() {
        for (rate in listOf(705600, 768000)) {
            val config = CoreEqConfigView(bitPerfect = true, dsp = CoreDspConfig(
                allowedSampleRates = listOf(705600, 768000), outputStage = CoreDspOutputStage(targetSampleRate = rate)))
            val exported = DspScenePackage.export(config.copy(scenes = listOf(scene("high", "高采样率", config))), { null }).manifest
            val imported = DspScenePackage.import(exported, CoreEqConfigView())
            assertTrue(imported.rejected.isEmpty())
            val restored = saved(imported.scenes.single().scene)
            assertEquals(rate, restored.dsp.outputStage.targetSampleRate)
            assertEquals(config.dsp.allowedSampleRates, restored.dsp.allowedSampleRates)
        }
    }

    @Test
    fun `phone scenes export as desktop graphs and import back unchanged`() {
        val current = CoreEqConfigView(scenes = listOf(graphicScene, parametricScene)).withScene(parametricScene)
        val export = DspScenePackage.export(current, { path -> DspImpulseFile(sha, 4096, 48000, 2).takeIf { path == impulsePath } },
            Instant.parse("2026-10-28T08:00:00Z"), "dsp-profile-test")
        val manifest = export.manifest
        assertEquals(DspScenePackage.KIND, manifest["kind"]!!.jsonPrimitive.content)
        val profile = manifest["profile"]!!.jsonObject
        assertEquals("p", profile["pinnedSceneId"]!!.jsonPrimitive.content)
        assertEquals(listOf("impulseResponse:$sha"), profile["assetIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, export.sceneCount)
        assertEquals(mapOf("assets/impulseResponse/impulseResponse_$sha.wav" to File(impulsePath)), export.files)
        val asset = manifest["assets"]!!.jsonArray.single().jsonObject
        assertEquals("assets/impulseResponse/impulseResponse_$sha.wav", asset["archivePath"]!!.jsonPrimitive.content)
        assertEquals(48000, asset["asset"]!!.jsonObject["sourceSampleRate"]!!.jsonPrimitive.int)

        val graphicGraph = profile["scenes"]!!.jsonArray[0].jsonObject["graph"]!!.jsonObject
        val eq = graphicGraph["nodes"]!!.jsonArray[0].jsonObject
        assertEquals("equalizer", eq["type"]!!.jsonPrimitive.content)
        assertEquals("graphic", eq["params"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        val bands = eq["params"]!!.jsonObject["bands"]!!.jsonArray.map { it.jsonObject }
        assertEquals(EqLimits.GRAPHIC_FREQUENCIES_HZ, bands.map { it["frequency"]!!.jsonPrimitive.double })
        assertTrue(bands.all { it["q"]!!.jsonPrimitive.double == 1.4 })
        assertEquals(96000, graphicGraph["outputStage"]!!.jsonObject["targetSampleRate"]!!.jsonPrimitive.int)
        assertEquals("fixed", graphicGraph["outputStage"]!!.jsonObject["targetSampleRateMode"]!!.jsonPrimitive.content)
        val convolver = profile["scenes"]!!.jsonArray[1].jsonObject["graph"]!!.jsonObject["nodes"]!!.jsonArray[1].jsonObject
        assertEquals("impulseResponse:$sha", convolver["params"]!!.jsonObject["impulseResponseAssetId"]!!.jsonPrimitive.content)
        val text = manifest.toString()
        assertFalse("phone paths stay on the phone", impulsePath in text)
        assertFalse("output choices stay on the phone", "outputDeviceId" in text)
        assertTrue(export.notes.single().contains("旁路"))

        val base = CoreEqConfigView(gapless = false, dsp = CoreDspConfig(crossfadeSeconds = 5.0, playbackRate = 0.75))
        val imported = DspScenePackage.import(manifest, base, mapOf("impulseResponse:$sha" to impulsePath))
        assertEquals(emptyList<DspSceneRejection>(), imported.rejected)
        val (graphic, parametric) = imported.scenes
        assertEquals(listOf("g", "p"), imported.scenes.map { it.scene.id })
        assertEquals(listOf("摇滚", "HD 650"), imported.scenes.map { it.scene.name })
        assertTrue(saved(graphic.scene).matchesScene(graphicScene))
        assertTrue(saved(parametric.scene).matchesScene(parametricScene))
        assertEquals(emptyList<String>(), graphic.notes + parametric.notes)
        assertEquals(setOf("impulseResponse:$sha"), parametric.impulseAssetIds)
        // The layout the scene was not using comes back too, and no output choice is carried.
        assertEquals(graphicScene.config["dsp"]!!.jsonObject["parametricBands"], graphic.scene.config["dsp"]!!.jsonObject["parametricBands"])
        assertEquals(parametricScene.config["bandsDb"], parametric.scene.config["bandsDb"])
        assertEquals("auto", saved(graphic.scene).dsp.outputDeviceId)
        assertEquals("Rock", saved(graphic.scene).preset)
    }

    @Test
    fun `a desktop scene keeps its EQ exactly and notes what the phone leaves out`() {
        val desktop = parse("""
            {"id":"default","name":"Default","enabled":true,"priority":0,"rules":{"deviceIds":["wasapi:{abc}"]},
             "graph":{"version":2,"nodes":[
               {"id":"replay-gain","type":"replayGain","enabled":true,"params":{"mode":"album","preampDb":0}},
               {"id":"equalizer","type":"equalizer","enabled":true,"params":{"mode":"graphic","preampDb":-4,
                 "bands":[{"frequency":31,"gain":3,"q":1,"filterType":"lowShelf"},{"frequency":1000,"gain":-2,"q":1,"filterType":"peak"}]}},
               {"id":"crossfeed","type":"crossfeed","enabled":false,"params":{"strength":0.4}},
               {"id":"convolver","type":"convolver","enabled":true,"params":{"impulseResponsePath":"C:\\ir.wav"}},
               {"id":"vst","type":"vst3Plugin","enabled":true,"params":{}},
               {"id":"eq2","type":"equalizer","enabled":true,"params":{"bands":[]}}],
              "outputStage":{"targetSampleRate":"device","targetSampleRateMode":"max-pcm","resamplerQuality":"soxrVhq","dither":"tpdf"}}}
        """)
        val result = DspScenePackage.import(desktop, CoreEqConfigView(), moduleName = { if (it == "convolver") "卷积校正" else it })
        val scene = result.scenes.single()
        val config = saved(scene.scene)
        assertEquals("parametric", config.dsp.eqMode)
        assertEquals(-4.0, config.preampDb, 0.0)
        // Graphic bands run as peaks at the desktop's Q 1, so they import that way.
        assertEquals(listOf(CoreParametricEqBand(31.0, 3.0, 1.0), CoreParametricEqBand(1000.0, -2.0, 1.0)), config.dsp.parametricBands)
        assertEquals(listOf("replay-gain", "crossfeed", "convolver"), config.dsp.nodes.map { it.id })
        assertFalse(config.dsp.nodes.last().enabled)
        assertNull(config.dsp.nodes.last().params["impulseResponsePath"])
        assertEquals(0, config.dsp.outputStage.targetSampleRate)
        assertEquals("ultra", config.dsp.outputStage.resamplerQuality)
        assertEquals("tpdf", config.dsp.outputStage.dither)
        assertTrue(config.dsp.enabled)
        assertEquals(listOf(
            "卷积校正没有随文件提供的 WAV 脉冲响应，已关闭",
            "手机不支持，未导入：VST3 插件",
            "同类模块只保留第一个，未导入：均衡器",
            "电脑上的自动匹配条件未导入，可在“设备预设”中绑定输出",
            "输出采样率规则在手机上改为跟随设备",
            "手机没有 SoX 重采样，改用内置最高质量"
        ), scene.notes)
    }

    @Test
    fun `the phone's own ten bands open as sliders and dynamics before the EQ are noted`() {
        val bands = EqLimits.GRAPHIC_FREQUENCIES_HZ.mapIndexed { index, frequency ->
            """{"frequency":$frequency,"gain":${index - 5},"q":1.4,"filterType":"peak"}""" }
        val desktop = parse("""
            [{"id":"a","name":"A","graph":{"nodes":[
               {"id":"c","type":"compressor","enabled":true,"params":{}},
               {"id":"e","type":"equalizer","params":{"mode":"parametric","preampDb":-5,"bands":[${bands.joinToString()}]}}]}},
             {"id":"b","name":"B","graph":{"nodes":[{"id":"m","type":"meter","params":{}}]}}]
        """)
        val (a, b) = DspScenePackage.import(desktop, CoreEqConfigView()).scenes
        val sliders = saved(a.scene)
        assertEquals("graphic", sliders.dsp.eqMode)
        assertEquals((-5..4).map { it.toDouble() }, sliders.bandsDb)
        assertEquals("Custom", sliders.preset)
        assertEquals(listOf("手机上均衡器排在最前，处理顺序与原场景不同"), a.notes)
        // No equalizer: the phone EQ is off and flat.
        val plain = saved(b.scene)
        assertFalse(plain.enabled)
        assertEquals("Flat", plain.preset)
        assertEquals(emptyList<String>(), b.notes)
    }

    @Test
    fun `scenes the phone cannot hold are rejected with the parameters while the rest import`() {
        val tooMany = (1..33).joinToString { """{"frequency":${it * 100},"gain":1,"q":1}""" }
        val desktop = parse("""
            {"kind":"twilight-echo-dsp-profile","schemaVersion":1,"profile":{"scenes":[
              {"id":"loud","name":"Loud","graph":{"nodes":[{"type":"equalizer","params":{"preampDb":-15,"bands":[]}}]}},
              {"id":"wide","name":"Wide","graph":{"nodes":[{"type":"equalizer","params":{"bands":[$tooMany]}}]}},
              {"id":"odd","name":"Odd","graph":{"nodes":[{"type":"equalizer","params":{"bands":[
                {"frequency":5,"gain":30,"q":40,"filterType":"lowShelfQ"},{"gain":1},{"frequency":"1k"}]}}]}},
              {"id":"ok","name":"OK","graph":{"nodes":[]}},
              {"id":"ok","name":"Again","graph":{"nodes":[]}},
              {"id":"bare","name":"Bare"}]},"assets":[]}
        """)
        val result = DspScenePackage.import(desktop, CoreEqConfigView())
        assertEquals(listOf("ok"), result.scenes.map { it.scene.id })
        assertEquals(listOf(
            DspSceneRejection("Loud", listOf("前级 -15 dB 超出手机范围（±12 dB）")),
            DspSceneRejection("Wide", listOf("均衡器有 33 段，手机最多 32 段")),
            DspSceneRejection("Odd", listOf("第 1 段频率 5 Hz 超出 10–96000 Hz", "第 1 段增益 30 dB 超出 ±24 dB",
                "第 1 段 Q 值 40 超出 0.1–30", "第 1 段滤波器“lowShelfQ”手机不支持", "另有 2 处")),
            DspSceneRejection("Again", listOf("与前面的场景 ID 重复")),
            DspSceneRejection("Bare", listOf("缺少处理图"))
        ), result.rejected)
    }

    @Test
    fun `files that are not scenes are refused as a whole`() {
        fun refused(text: String) = assertThrows(DspPackageException::class.java) {
            DspScenePackage.import(parse(text), CoreEqConfigView())
        }.message
        assertEquals("不是 Twilight Echo DSP 配置包", refused("""{"kind":"other"}"""))
        assertEquals("配置包版本不受支持", refused("""{"kind":"twilight-echo-dsp-profile","schemaVersion":2}"""))
        assertEquals("文件中没有 DSP 场景", refused("{}"))
        assertEquals("文件中没有 DSP 场景", refused("[]"))
        // A desktop settings backup carries its scenes too.
        assertEquals(1, DspScenePackage.import(parse("""{"dspScenes":[{"id":"x","graph":{}}]}"""), CoreEqConfigView()).scenes.size)
    }

    @Test
    fun `invalid phone extras fall back to the current settings`() {
        val desktop = parse("""
            {"id":"x","name":"X","graph":{"nodes":[]},"android":{"crossfadeSeconds":99,"playbackRate":1.5,"bufferFrames":12.5,
             "continuitySampleRate":22050,"bandsDb":[1,2],"parametricBands":[{"frequency":1}],"crossfadeCurve":"linear"}}
        """)
        val base = CoreEqConfigView(dsp = CoreDspConfig(crossfadeSeconds = 3.0, bufferFrames = 512, continuitySampleRate = 44100))
        val config = saved(DspScenePackage.import(desktop, base).scenes.single().scene)
        assertEquals(3.0, config.dsp.crossfadeSeconds, 0.0)
        assertEquals(1.5, config.dsp.playbackRate, 0.0)
        assertEquals(512, config.dsp.bufferFrames)
        assertEquals(44100, config.dsp.continuitySampleRate)
        assertEquals("linear", config.dsp.crossfadeCurve)
        assertEquals(List(10) { 0.0 }, config.bandsDb)
        assertEquals(CoreDspConfig().parametricBands, config.dsp.parametricBands)
    }

    @Test
    fun `merging keeps unchanged scenes, updates known ones, renames clashes and undo restores`() {
        val a = scene("a", "耳机", CoreEqConfigView(preampDb = -1.0))
        val b = scene("b", "音箱", CoreEqConfigView(preampDb = -2.0))
        val newerB = scene("b", "音箱", CoreEqConfigView(preampDb = -2.5))
        val c = scene("c", "耳机", CoreEqConfigView(preampDb = -3.0))
        val merge = DspScenePackage.merge(listOf(a, b), listOf(a, newerB, c))
        assertEquals(1, merge.unchanged)
        assertEquals(listOf(b), merge.replaced)
        assertEquals(listOf("耳机 (2)"), merge.added.map { it.name })
        assertEquals(listOf("a", "b", "c"), merge.scenes.map { it.id })
        assertEquals(newerB, merge.scenes[1])

        val later = scene("d", "后来保存", CoreEqConfigView())
        assertEquals(listOf(a, b, later), merge.undo(merge.scenes + later))

        val full = (1..32).map { scene("s$it", "场景$it", CoreEqConfigView()) }
        val overflow = DspScenePackage.merge(full, listOf(c, scene("s1", "改名", CoreEqConfigView())))
        assertEquals(listOf(c), overflow.overflow)
        assertEquals("改名", overflow.scenes.first().name)
        assertEquals(32, overflow.scenes.size)
    }

    @Test
    fun `the archive round-trips the manifest and assets and refuses unsafe paths`() {
        val wav = folder.newFile("ir.wav").apply { writeBytes(wave(sampleRate = 44100, channels = 2)) }
        val described = describeImpulseFile(wav)!!
        assertEquals(MessageDigest.getInstance("SHA-256").digest(wav.readBytes()).joinToString("") { "%02x".format(it) }, described.sha256)
        assertEquals(44100, described.sampleRate)
        assertEquals(2, described.channels)
        assertEquals(wav.length(), described.byteSize)
        assertNull(describeImpulseFile(folder.newFile("not.wav").apply { writeText("ID3 not a wave") }))
        assertNull(describeImpulseFile(File(folder.root, "missing.wav")))

        val manifest = buildJsonObject { put("kind", DspScenePackage.KIND); put("schemaVersion", 1) }
        val bytes = ByteArrayOutputStream().also { DspPackageArchive.write(it, manifest, mapOf(described.archivePath to wav)) }.toByteArray()
        val contents = DspPackageArchive.read(ByteArrayInputStream(bytes), folder.newFolder("in"))
        assertEquals(manifest, contents.root)
        assertArrayEquals(wav.readBytes(), contents.files.getValue(described.archivePath).readBytes())

        val plain = DspPackageArchive.read(ByteArrayInputStream("\uFEFF[{\"graph\":{}}]".toByteArray()), folder.newFolder("json"))
        assertEquals(0, plain.files.size)
        assertTrue(plain.root is JsonArray)

        fun zip(vararg names: String) = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> names.forEach { zip.putNextEntry(ZipEntry(it)); zip.write("{}".toByteArray()); zip.closeEntry() } }
        }.toByteArray()
        fun refused(bytes: ByteArray) = assertThrows(DspPackageException::class.java) {
            DspPackageArchive.read(ByteArrayInputStream(bytes), folder.newFolder())
        }.message
        assertEquals("配置包包含非法路径", refused(zip("manifest.json", "assets/../../evil")))
        assertEquals("配置包缺少清单", refused(zip("assets/a.wav")))
        assertEquals("文件不是有效的场景 JSON", refused("not json".toByteArray()))
    }

    private fun wave(sampleRate: Int, channels: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(value: Int, bytes: Int) = repeat(bytes) { out.write((value shr (8 * it)) and 0xFF) }
        out.write("RIFF".toByteArray()); int(36 + 8, 4); out.write("WAVE".toByteArray())
        out.write("LIST".toByteArray()); int(3, 4); out.write(byteArrayOf(1, 2, 3, 0)) // odd chunk, padded
        out.write("fmt ".toByteArray()); int(16, 4); int(3, 2); int(channels, 2); int(sampleRate, 4)
        int(sampleRate * channels * 4, 4); int(channels * 4, 2); int(32, 2)
        out.write("data".toByteArray()); int(8, 4); repeat(8) { out.write(0) }
        return out.toByteArray()
    }
}
