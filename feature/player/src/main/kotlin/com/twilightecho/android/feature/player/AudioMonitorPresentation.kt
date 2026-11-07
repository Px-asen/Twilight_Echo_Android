package com.twilightecho.android.feature.player

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun audioMonitorLevels(visualization: JsonObject): List<Float> =
    (visualization["visualizerBars"] as? JsonArray).orEmpty().map {
        (it.jsonPrimitive.doubleOrNull ?: 0.0).toFloat().coerceIn(0f, 1f)
    }
