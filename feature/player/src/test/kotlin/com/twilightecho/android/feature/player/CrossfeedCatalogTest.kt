package com.twilightecho.android.feature.player

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfeedCatalogTest {
    private val spec = dspModules.single { it.type == "crossfeed" }

    @Test fun crossfeedKeepsSavedAlgorithmValuesAndDefaults() {
        val choice = spec.controls.filterIsInstance<DspControl.Choice>().single()
        assertEquals(listOf("custom", "bauer", "bs2b", "meier"), choice.options.map { it.first })
        assertEquals("bs2b", spec.newNode().params["algorithm"]?.jsonPrimitive?.content)
        assertEquals(0.3, spec.newNode().params["strength"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.35, spec.newNode().params["delayMs"]!!.jsonPrimitive.double, 0.0)
        assertEquals(700.0, spec.newNode().params["cutoffHz"]!!.jsonPrimitive.double, 0.0)
    }

    @Test fun labelsIdentifyTheBs2bProfileAndCustomOnlyParameters() {
        val options = spec.controls.filterIsInstance<DspControl.Choice>().single().options.toMap()
        assertEquals("Meier（BS2B 预设）", options["meier"])
        assertEquals("Bauer（BS2B）", options["bauer"])
        spec.controls.filter { it.key == "delayMs" || it.key == "cutoffHz" }.forEach {
            assertTrue(it.label.startsWith("自定义"))
        }
    }
}
