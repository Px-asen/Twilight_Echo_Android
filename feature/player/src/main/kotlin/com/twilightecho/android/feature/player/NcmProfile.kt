package com.twilightecho.android.feature.player

import kotlinx.serialization.json.*

data class NcmProfile(val userId: String, val nickname: String, val avatarUrl: String? = null,
    val signature: String = "", val follows: Int = 0, val followeds: Int = 0)

internal fun JsonObject.ncmProfile() = NcmProfile(text("userId"), text("nickname"),
    text("avatarUrl").ifBlank { null }, text("signature"),
    get("follows")?.jsonPrimitive?.intOrNull ?: 0, get("followeds")?.jsonPrimitive?.intOrNull ?: 0)
