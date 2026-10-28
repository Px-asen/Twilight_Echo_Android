package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DspModuleCatalogTest {
    @Test fun builtInModulesHaveUniqueNativeTypesAndConvolverWaitsForAnImpulse() {
        assertEquals(14, dspModules.size)
        assertEquals(14, dspModules.map { it.type }.distinct().size)
        assertFalse(dspModules.single { it.type == "convolver" }.newNode().enabled)
        assertTrue(dspModules.filterNot { it.type == "convolver" }.all { it.newNode().enabled })
    }
    @Test fun reorderingPreservesNodeParametersAndIds() {
        val nodes = dspModules.take(3).map { it.newNode() }
        assertEquals(listOf(nodes[1], nodes[0], nodes[2]), moveDspNode(nodes, 0, 1))
        assertEquals(nodes, moveDspNode(nodes, 0, -1))
    }
    @Test fun savedSceneRoundTripsFullAudioSettingsWithoutRecursiveSceneCopies() {
        val json = DefaultCoreClient.DefaultJson
        val original = CoreEqConfigView(enabled = true, dsp = CoreDspConfig(eqMode = "parametric", nodes = dspModules.take(2).map { it.newNode() },
            playbackRate = 1.25, outputStage = CoreDspOutputStage(headroomEnabled = true)))
        val scene = CoreDspScene("night", "夜间耳机", JsonObject(json.encodeToJsonElement(original).jsonObject - "scenes"))
        val saved = json.decodeFromString<CoreEqConfigView>(json.encodeToString(CoreEqConfigView.serializer(), original.copy(scenes = listOf(scene))))
        assertEquals(original, json.decodeFromJsonElement<CoreEqConfigView>(saved.scenes.single().config))
        assertFalse("scenes" in scene.config)
    }
}
