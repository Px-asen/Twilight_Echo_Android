package com.twilightecho.android.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.feature.player.*
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** P1 audio settings: EQ text import with headroom and bypass, device presets, and `.tedsp` scene packages. */
@RunWith(AndroidJUnit4::class)
class P1AudioSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: MutableState<PlayerUiState>
    private var reapplied = 0
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

    private fun scene(id: String, name: String, config: CoreEqConfigView) = CoreDspScene(id, name,
        JsonObject(DefaultCoreClient.DefaultJson.encodeToJsonElement(CoreEqConfigView.serializer(), config).jsonObject - "scenes"))

    private fun start(state: PlayerUiState) {
        ui = mutableStateOf(state.copy(homeAvailable = false, activeMainTab = MainDestinationTab.SETTINGS))
        fun audio(edit: (CoreEqConfigView) -> CoreEqConfigView) { ui.value = ui.value.copy(eqConfig = edit(ui.value.eqConfig)) }
        val actions = PlayerActions(
            toggleEq = { audio { it.copy(enabled = !it.enabled) } },
            preset = { name -> audio { it.copy(preset = name, bandsDb = CoreEqConfigView.PRESETS[name] ?: it.bandsDb) } },
            band = { index, value -> audio { it.copy(bandsDb = it.bandsDb.mapIndexed { i, old -> if (i == index) value else old }) } },
            preamp = { value -> audio { it.copy(preampDb = value) } },
            editDsp = { edit -> audio { it.copy(dsp = edit(it.dsp)) } },
            editAudio = ::audio,
            saveDspScene = { name -> audio { it.copy(scenes = it.scenes + scene("saved-${it.scenes.size}", name, it)) } },
            applyDspScene = { id -> audio { config -> config.scenes.find { it.id == id }?.let(config::withScene) ?: config } },
            deleteDspScene = { id -> audio { it.copy(scenes = it.scenes.filterNot { scene -> scene.id == id }) } },
            reapplyOutputPreset = { reapplied++ },
            editPreferences = { edit -> ui.value = ui.value.copy(preferences = edit(ui.value.preferences).normalized()) }
        )
        rule.setContent { TwilightEchoTheme { PlayerAppContent(ui.value, actions) } }
        rule.waitForIdle()
        rule.onNodeWithText("音频与 DSP", substring = false).performScrollTo().performClick()
    }

    private fun switchIn(tag: String) = rule.onNode(hasAnyAncestor(hasTestTag(tag)) and isToggleable())

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
        device.executeShellCommand("mkdir -p /sdcard/Pictures/TwilightUiQA")
        device.executeShellCommand("cp ${File(directory, "$name.png").absolutePath} /sdcard/Pictures/TwilightUiQA/$name.png")
    }

    @Test fun eqTextImportShowsHeadroomAndBypassStopsProcessing() {
        start(PlayerUiState(eqConfig = CoreEqConfigView(enabled = true)))
        rule.onNodeWithTag("audio-page-eq").performScrollTo().performClick()
        val text = "Preamp: -6.2 dB\nFilter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.71\nFilter 2: ON PK Fc 2400 Hz Gain 9 dB Q 2.4\n"
        rule.runOnUiThread {
            // Emulators and debuggable builds skip the system clipboard preview for clips marked this way.
            val clip = ClipData.newPlainText("eq", text).apply {
                description.extras = PersistableBundle().apply { putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true) }
            }
            (rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        }
        rule.onNodeWithTag("eq-import-paste").performScrollTo().performClick()
        rule.onNodeWithTag("eq-import-summary").performScrollTo()
            .assertTextEquals("已导入 EqualizerAPO：2 个滤波器，前级 -6.2 dB")
        val imported = ui.value.eqConfig
        assertEquals("parametric", imported.dsp.eqMode)
        assertEquals(-6.2, imported.preampDb, 0.0)
        assertEquals(listOf(105.0, 2400.0), imported.dsp.parametricBands.map { it.frequency })
        assertEquals(listOf("lowShelf", "peak"), imported.dsp.parametricBands.map { it.filterType })

        // +9 dB at 2.4 kHz over a -6.2 dB preamp would clip; one tap lowers the preamp until it does not.
        assertTrue(EqResponse.headroom(imported).clippingRisk)
        rule.onNodeWithText("频率响应").performScrollTo()
        rule.onNodeWithTag("eq-auto-preamp").assertIsDisplayed()
        shot("p1-eq-import-headroom")
        rule.onNodeWithTag("eq-auto-preamp").performClick()
        rule.waitForIdle()
        assertFalse(EqResponse.headroom(ui.value.eqConfig).clippingRisk)
        assertTrue(ui.value.eqConfig.preampDb < -6.2)
        rule.onNodeWithTag("eq-auto-preamp").assertDoesNotExist()
        rule.onNodeWithTag("eq-headroom").assertExists()

        switchIn("dsp-bypass").performScrollTo().performClick()
        assertFalse(ui.value.eqConfig.dsp.enabled)
        rule.onNodeWithText("已旁路均衡器与处理链").assertIsDisplayed()
        rule.onNodeWithText("声音处理已旁路，均衡器暂不生效。").assertExists()
        rule.onNodeWithTag("eq-headroom").assertDoesNotExist()
        shot("p1-eq-bypass")
        switchIn("dsp-bypass").performClick()
        assertTrue(ui.value.eqConfig.dsp.enabled)

        rule.onNodeWithTag("eq-import-undo").performScrollTo().performClick()
        assertEquals("graphic", ui.value.eqConfig.dsp.eqMode)
        assertEquals(0.0, ui.value.eqConfig.preampDb, 0.0)
    }

    @Test fun devicePresetsShowTheOutputAndBindScenes() {
        val hd650 = scene("a", "HD 650 校正", CoreEqConfigView(enabled = true, preampDb = -6.0))
        val commute = scene("b", "通勤", CoreEqConfigView(enabled = true, preampDb = -2.0))
        val output = OutputDeviceIdentity("wired_headset", "HD 650")
        start(PlayerUiState(eqConfig = CoreEqConfigView(scenes = listOf(hd650, commute)), outputPreset = OutputPresetStatus(output)))
        rule.onNodeWithTag("audio-page-scenes").performScrollTo().performClick()
        rule.onNodeWithTag("output-preset-status").performScrollTo()
        rule.onNodeWithText("当前输出：HD 650").assertIsDisplayed()
        rule.onNodeWithText("未设置预设，保持当前声音").assertIsDisplayed()

        rule.onNodeWithTag("output-preset-add").performScrollTo().performClick()
        rule.onNodeWithTag("output-preset-scene-a").performClick()
        shot("p1-output-preset-dialog")
        rule.onNodeWithTag("output-preset-save").performClick()
        val binding = ui.value.preferences.outputPresets.bindings.single()
        assertEquals(OutputPresetBinding(OutputPresetScope.DEVICE, "a", "HD 650 校正", "wired_headset", "HD 650"), binding)
        rule.onNodeWithText("有线耳机 · HD 650 校正").performScrollTo().assertIsDisplayed()

        // What the coordinator reports: applied, then changed by hand, then a failed apply.
        rule.runOnIdle {
            ui.value = ui.value.copy(eqConfig = ui.value.eqConfig.withScene(hd650), outputPreset = OutputPresetStatus(output, binding, "a"))
        }
        rule.onNodeWithTag("output-preset-status").performScrollTo()
        rule.onNodeWithText("正在使用“HD 650 校正”").assertIsDisplayed()
        rule.onNodeWithText("匹配：本设备").assertIsDisplayed()
        shot("p1-output-presets")
        rule.runOnIdle { ui.value = ui.value.copy(eqConfig = ui.value.eqConfig.copy(preampDb = -3.0)) }
        rule.onNodeWithTag("output-preset-status").performScrollTo()
        rule.onNodeWithText("已手动调整，预设为“HD 650 校正”").assertIsDisplayed()
        rule.onNodeWithTag("output-preset-reapply").performClick()
        assertEquals(1, reapplied)
        rule.runOnIdle {
            ui.value = ui.value.copy(outputPreset = OutputPresetStatus(output, binding, failure = "未能应用场景“HD 650 校正”，声音保持不变。"))
        }
        rule.onNodeWithTag("output-preset-status").performScrollTo()
        rule.onNodeWithText("未能应用场景“HD 650 校正”，声音保持不变。").assertIsDisplayed()
        rule.onNodeWithText("重试").assertIsDisplayed()

        switchIn("output-presets").performScrollTo().performClick()
        assertFalse(ui.value.preferences.outputPresets.autoSwitch)
        rule.onNodeWithText("换设备时保持当前声音").assertIsDisplayed()
        rule.onNodeWithTag("output-preset-remove-${binding.target}").performScrollTo().performClick()
        assertTrue(ui.value.preferences.outputPresets.bindings.isEmpty())
        rule.runOnIdle { ui.value = ui.value.copy(outputPreset = OutputPresetStatus(output)) }
        rule.onNodeWithText("未设置预设，保持当前声音").assertExists()

        rule.onNodeWithText("保存你的聆听方式").performScrollTo()
        rule.onNodeWithTag("scene-package-import").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("scene-package-export").assertIsEnabled()
        rule.onNodeWithText("配置包与电脑版通用。").assertIsDisplayed()
        shot("p1-scenes-page")
        rule.runOnIdle { ui.value = ui.value.copy(eqConfig = ui.value.eqConfig.copy(scenes = emptyList())) }
        rule.onNodeWithTag("scene-package-export").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("output-preset-add").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("先保存一个场景。").assertIsDisplayed()
    }

    @Test fun scenePackageFilesCarryImpulseResponsesBothWays() {
        val context = instrumentation.targetContext
        val impulses = File(context.filesDir, "dsp-impulses").apply { mkdirs() }
        val source = File(impulses, "p1-test-source.wav").apply { writeBytes(wave()) }
        val sha = describeImpulseFile(source)!!.sha256
        val target = File(impulses, "$sha.wav").apply { delete() }
        val file = File(context.cacheDir, "p1-scenes.tedsp").apply { delete() }
        try {
            val convolver = CoreDspNode("phone-convolver", "convolver",
                params = buildJsonObject { put("impulseResponsePath", source.absolutePath); put("wetDb", 0.0) })
            val room = scene("room", "房间校正", CoreEqConfigView(enabled = true, preampDb = -3.0, dsp = CoreDspConfig(nodes = listOf(convolver))))
            val export = ScenePackageFiles.export(context, CoreEqConfigView(scenes = listOf(room)), Uri.fromFile(file))
            assertEquals(1, export.sceneCount)
            val entries = ZipInputStream(file.inputStream()).use { zip -> generateSequence { zip.nextEntry?.name }.toList() }
            assertEquals(listOf("manifest.json", "assets/impulseResponse/impulseResponse_$sha.wav"), entries)

            val imported = ScenePackageFiles.import(context, Uri.fromFile(file), CoreEqConfigView())
            assertEquals(emptyList<DspSceneRejection>(), imported.rejected)
            val scene = imported.scenes.single()
            assertEquals(emptyList<String>(), scene.notes)
            val node = CoreEqConfigView().withScene(scene.scene).dsp.nodes.single()
            assertTrue(node.enabled)
            assertEquals(target.absolutePath, node.params["impulseResponsePath"]!!.jsonPrimitive.content)
            assertArrayEquals(source.readBytes(), target.readBytes())
            assertEquals(-3.0, CoreEqConfigView().withScene(scene.scene).preampDb, 0.0)
        } finally {
            source.delete()
            target.delete()
            file.delete()
        }
    }

    /** A short 48 kHz stereo float WAV. */
    private fun wave(): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(value: Int, bytes: Int) = repeat(bytes) { out.write((value shr (8 * it)) and 0xFF) }
        val frames = 64
        out.write("RIFF".toByteArray()); int(36 + frames * 8, 4); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16, 4); int(3, 2); int(2, 2); int(48000, 4); int(48000 * 8, 4); int(8, 2); int(32, 2)
        out.write("data".toByteArray()); int(frames * 8, 4)
        repeat(frames * 2) { index -> int(if (index < 2) java.lang.Float.floatToIntBits(1f) else 0, 4) }
        return out.toByteArray()
    }
}
