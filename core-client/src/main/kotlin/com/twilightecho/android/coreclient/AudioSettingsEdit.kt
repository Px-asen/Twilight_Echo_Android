package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** A confirmed edit, including the actual Core config it replaced rather than a UI snapshot. */
data class CoreAudioEditReceipt(val before: CoreEqConfigView, val committed: CoreEqConfigView)

/** Caller holds the audio-config mutex shared with output presets for this whole read/edit/write. */
suspend fun CoreClient.commitAudioEdit(edit: (CoreEqConfigView) -> CoreEqConfigView): Result<CoreAudioEditReceipt> = try {
    val before = getEqConfig().getOrThrow()
    val response = setEqConfig(edit(before)).getOrThrow()
    val config = response.value?.jsonObject?.get("snapshot")?.jsonObject?.get("eqConfig")
        ?: error("Core 未返回已提交的音频配置")
    Result.success(CoreAudioEditReceipt(before, DefaultCoreClient.DefaultJson.decodeFromJsonElement(config)))
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

/** Undo an EQ import without reverting later changes to routing, non-EQ DSP, or saved scenes. */
fun CoreEqConfigView.withRestoredEq(before: CoreEqConfigView): CoreEqConfigView = copy(
    enabled = before.enabled,
    preampDb = before.preampDb,
    dsp = dsp.copy(eqMode = before.dsp.eqMode, parametricBands = before.dsp.parametricBands)
)

/** Saves the current sound; callers that offer a confirmed save receive capacity/name errors. */
fun CoreEqConfigView.withSavedDspScene(name: String): CoreEqConfigView {
    require(name.isNotBlank()) { "请输入场景名称" }
    require(scenes.size < DspScenePackage.MAX_SCENES) { "场景已满 ${DspScenePackage.MAX_SCENES} 个" }
    val scene = CoreDspScene(UUID.randomUUID().toString(), name.trim().take(80),
        JsonObject(DefaultCoreClient.DefaultJson.encodeToJsonElement(this).jsonObject - "scenes"))
    return copy(scenes = scenes + scene)
}
