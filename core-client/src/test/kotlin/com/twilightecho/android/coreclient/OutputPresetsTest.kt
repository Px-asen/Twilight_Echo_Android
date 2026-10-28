package com.twilightecho.android.coreclient

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutputPresetsTest {
    private val headphones = OutputDeviceIdentity("wired_headset", "HD 650")
    private val buds = OutputDeviceIdentity("bluetooth", "WF-1000XM5")
    private val otherBuds = OutputDeviceIdentity("bluetooth", "AirPods Pro")
    private val speaker = OutputDeviceIdentity("speaker", "Pixel 8")

    private fun scene(id: String, preampDb: Double, outputDeviceId: String = "99") = CoreDspScene(id, "场景$id", JsonObject(
        DefaultCoreClient.DefaultJson.encodeToJsonElement(CoreEqConfigView.serializer(),
            CoreEqConfigView(enabled = true, preampDb = preampDb, dsp = CoreDspConfig(outputDeviceId = outputDeviceId))).jsonObject - "scenes"))

    private fun device(output: OutputDeviceIdentity, sceneId: String) =
        OutputPresetBinding(OutputPresetScope.DEVICE, sceneId, "场景$sceneId", output.routeType, output.name)

    private fun type(routeType: String, sceneId: String) =
        OutputPresetBinding(OutputPresetScope.ROUTE_TYPE, sceneId, "场景$sceneId", routeType)

    private fun other(sceneId: String) = OutputPresetBinding(OutputPresetScope.OTHER, sceneId, "场景$sceneId")

    @Test
    fun `device beats route type beats other and same-name outputs share a binding`() {
        val settings = OutputPresetPreferences(bindings = listOf(other("o"), type("bluetooth", "t"), device(buds, "d")))
        assertEquals("d", settings.match(buds)?.sceneId)
        // Reconnecting gives a new connection-time ID but the same identity, so the same binding.
        assertEquals("d", settings.match(OutputDeviceIdentity("bluetooth", "WF-1000XM5"))?.sceneId)
        assertEquals("t", settings.match(otherBuds)?.sceneId)
        assertEquals("o", settings.match(headphones)?.sceneId)
        // A wired output that happens to share the name is a different output.
        assertEquals("o", settings.match(OutputDeviceIdentity("wired_headset", "WF-1000XM5"))?.sceneId)
        assertNull(OutputPresetPreferences(bindings = listOf(device(buds, "d"))).match(otherBuds))
    }

    @Test
    fun `a binding replaces the previous one for the same target`() {
        val settings = OutputPresetPreferences().bind(device(buds, "a")).bind(type("bluetooth", "t")).bind(device(buds, "b"))
        assertEquals(listOf("t", "b"), settings.bindings.map { it.sceneId })
        assertEquals(listOf("t"), settings.unbind(device(buds, "x")).bindings.map { it.sceneId })
        val duplicated = OutputPresetPreferences(bindings = listOf(device(buds, "a"), other("o"), device(buds, "b"))).normalized()
        assertEquals(listOf("o", "b"), duplicated.bindings.map { it.sceneId })
        assertEquals(64, OutputPresetPreferences(bindings = (0 until 80).map { device(OutputDeviceIdentity("usb_dac", "DAC $it"), "s") })
            .normalized().bindings.size)
    }

    @Test
    fun `a scene keeps the current output device choice and compares numbers by value`() {
        val saved = scene("a", -4.0, outputDeviceId = "17")
        val current = CoreEqConfigView(dsp = CoreDspConfig(outputDeviceId = "auto"), scenes = listOf(saved))
        val applied = current.withScene(saved)
        assertEquals(-4.0, applied.preampDb, 0.0)
        assertEquals("auto", applied.dsp.outputDeviceId)
        assertEquals(listOf(saved), applied.scenes)
        assertTrue(applied.matchesScene(saved))
        assertTrue(applied.copy(dsp = applied.dsp.copy(outputDeviceId = "23")).matchesScene(saved))
        assertFalse(applied.copy(preampDb = -3.0).matchesScene(saved))

        val gain = CoreDspNode("g", "gain", params = JsonObject(mapOf("gainDb" to JsonPrimitive(2.0))))
        val withNode = CoreDspScene("n", "n", JsonObject(DefaultCoreClient.DefaultJson.encodeToJsonElement(CoreEqConfigView.serializer(),
            CoreEqConfigView(dsp = CoreDspConfig(nodes = listOf(gain)))).jsonObject - "scenes"))
        val printedByCore = CoreEqConfigView(dsp = CoreDspConfig(nodes = listOf(gain.copy(params = JsonObject(mapOf("gainDb" to JsonPrimitive(2)))))))
        assertTrue(printedByCore.matchesScene(withNode))
        assertFalse(CoreEqConfigView().matchesScene(CoreDspScene("bad", "bad", JsonObject(mapOf("preampDb" to JsonPrimitive("loud"))))))
    }

    private class Harness(scope: TestScope, config: CoreEqConfigView, settings: OutputPresetPreferences) {
        var config = config
        var writes = 0
        var reject = false
        val preferences = MutableStateFlow(UserPreferences(outputPresets = settings))
        val coordinator = OutputPresetCoordinator(scope.backgroundScope, preferences, { edit -> preferences.value = edit(preferences.value).normalized() },
            Mutex(), readConfig = { this.config }, writeConfig = { next -> check(!reject) { "rejected" }; writes++; this.config = next })
        init { coordinator.start() }
        val settings get() = preferences.value.outputPresets
        fun output(identity: OutputDeviceIdentity?) = coordinator.outputChanged { identity }
        fun edit(change: (OutputPresetPreferences) -> OutputPresetPreferences) {
            preferences.value = preferences.value.copy(outputPresets = change(settings))
        }
    }

    private val scenes = listOf(scene("a", -4.0), scene("b", -7.0), scene("o", 0.0))
    private val base = CoreEqConfigView(preampDb = 1.0, dsp = CoreDspConfig(outputDeviceId = "auto"), scenes = scenes)

    @Test
    fun `a new output applies its preset once and manual changes stay until the output changes`() = runTest {
        val h = Harness(this, base, OutputPresetPreferences(bindings = listOf(device(headphones, "a"), type("bluetooth", "b"))))
        h.output(headphones); runCurrent()
        assertEquals(-4.0, h.config.preampDb, 0.0)
        assertEquals("auto", h.config.dsp.outputDeviceId)
        assertEquals(headphones.key, h.settings.lastOutputKey)
        assertEquals("a", h.coordinator.status.value.appliedSceneId)

        h.config = h.config.copy(preampDb = -2.0) // manual change
        h.output(null); h.output(headphones); runCurrent() // stop, then play again on the same output
        assertEquals(-2.0, h.config.preampDb, 0.0)
        assertEquals(1, h.writes)
        assertEquals("a", h.coordinator.status.value.appliedSceneId)

        h.output(buds); runCurrent()
        assertEquals(-7.0, h.config.preampDb, 0.0)
        h.output(speaker); runCurrent() // nothing bound: the current sound stays
        assertEquals(-7.0, h.config.preampDb, 0.0)
        assertEquals(2, h.writes)
        assertNull(h.coordinator.status.value.binding)
        assertEquals(speaker.key, h.settings.lastOutputKey)

        h.config = h.config.copy(preampDb = 3.0)
        h.coordinator.reapply(); runCurrent() // nothing to restore without a binding
        assertEquals(3.0, h.config.preampDb, 0.0)
    }

    @Test
    fun `a restart on the same output keeps the sound but a reconnect applies the preset again`() = runTest {
        val h = Harness(this, base.copy(preampDb = -1.5), OutputPresetPreferences(bindings = listOf(device(headphones, "a")),
            lastOutputKey = headphones.key))
        h.output(headphones); runCurrent()
        assertEquals(-1.5, h.config.preampDb, 0.0)
        assertEquals(0, h.writes)

        h.coordinator.outputsRemoved(listOf(otherBuds)); runCurrent()
        assertEquals(headphones.key, h.settings.lastOutputKey)
        h.output(null); h.coordinator.outputsRemoved(listOf(headphones)); runCurrent()
        assertNull(h.settings.lastOutputKey)
        h.output(headphones); runCurrent()
        assertEquals(-4.0, h.config.preampDb, 0.0)

        h.config = h.config.copy(preampDb = 0.5)
        h.coordinator.reapply(); runCurrent() // restore replaces the manual change
        assertEquals(-4.0, h.config.preampDb, 0.0)
    }

    @Test
    fun `a deleted scene or rejected config keeps the sound and reports until retried`() = runTest {
        val h = Harness(this, base, OutputPresetPreferences(bindings = listOf(device(headphones, "gone"), device(buds, "b"))))
        h.output(headphones); runCurrent()
        assertEquals(1.0, h.config.preampDb, 0.0)
        assertEquals("场景“场景gone”已删除，声音保持不变。", h.coordinator.status.value.failure)
        assertEquals(headphones.key, h.settings.lastOutputKey) // no retry loop on later route updates

        h.reject = true
        h.output(buds); runCurrent()
        assertEquals(1.0, h.config.preampDb, 0.0)
        assertEquals("未能应用场景“场景b”，声音保持不变。", h.coordinator.status.value.failure)
        assertNull(h.coordinator.status.value.appliedSceneId)
        h.output(buds); runCurrent()
        assertEquals(0, h.writes)

        h.reject = false
        h.coordinator.reapply(); runCurrent()
        assertEquals(-7.0, h.config.preampDb, 0.0)
        assertNull(h.coordinator.status.value.failure)
        assertEquals("b", h.coordinator.status.value.appliedSceneId)
    }

    @Test
    fun `binding the current output or turning switching on applies at once and off leaves the sound`() = runTest {
        val h = Harness(this, base, OutputPresetPreferences(autoSwitch = false, bindings = listOf(device(headphones, "a"))))
        h.output(headphones); runCurrent()
        assertEquals(1.0, h.config.preampDb, 0.0)
        assertEquals("a", h.coordinator.status.value.binding?.sceneId)
        assertNull(h.settings.lastOutputKey)

        h.edit { it.copy(autoSwitch = true) }; runCurrent()
        assertEquals(-4.0, h.config.preampDb, 0.0)
        h.edit { it.bind(device(headphones, "b")) }; runCurrent()
        assertEquals(-7.0, h.config.preampDb, 0.0)
        h.edit { it.bind(other("o")) }; runCurrent() // lower priority than the device: nothing changes
        assertEquals(-7.0, h.config.preampDb, 0.0)
        h.edit { it.unbind(device(headphones, "b")) }; runCurrent() // the catch-all now matches
        assertEquals(0.0, h.config.preampDb, 0.0)
        h.edit { it.unbind(other("o")) }; runCurrent() // nothing matches: the sound stays
        assertEquals(0.0, h.config.preampDb, 0.0)

        h.edit { it.copy(autoSwitch = false).bind(device(buds, "a")) }; runCurrent()
        h.output(buds); runCurrent()
        assertEquals(0.0, h.config.preampDb, 0.0)
        h.output(null); runCurrent()
        h.edit { it.copy(autoSwitch = true) }; runCurrent() // idle: the next output applies
        assertNull(h.settings.lastOutputKey)
        h.output(buds); runCurrent()
        assertEquals(-4.0, h.config.preampDb, 0.0)
    }
}
