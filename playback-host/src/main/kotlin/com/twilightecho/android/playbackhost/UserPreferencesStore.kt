package com.twilightecho.android.playbackhost

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.twilightecho.android.coreclient.BackgroundStyle
import com.twilightecho.android.coreclient.NcmPlaybackQuality
import com.twilightecho.android.coreclient.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Phone preferences are separate from the Core session and any connected computer's settings. */
class UserPreferencesStore(context: Context) {
    private val app = context.applicationContext
    private val disk = app.getSharedPreferences("user-customization", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()

    private fun read(): UserPreferences = disk.getString("preferences", null)?.let {
        try { json.decodeFromString<UserPreferences>(it).normalized() }
        catch (_: IllegalArgumentException) { UserPreferences() }
    } ?: UserPreferences()

    @Synchronized
    fun update(edit: (UserPreferences) -> UserPreferences) {
        val value = edit(mutableState.value).normalized()
        disk.edit().putString("preferences", json.encodeToString(value)).apply()
        mutableState.value = value
    }

    suspend fun importBackground(uri: String) = withContext(Dispatchers.IO) {
        val source = Uri.parse(uri)
        val bitmap = app.contentResolver.readOrientedImage(source, 2560)
        try {
            val directory = File(app.filesDir, "backgrounds").apply { mkdirs() }
            val target = File(directory, "${UUID.randomUUID()}.png")
            try {
                target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片保存失败，请重试" } }
                val previous = state.value.appearance.backgroundImagePath
                update { it.copy(appearance = it.appearance.copy(backgroundStyle = BackgroundStyle.IMAGE, backgroundImagePath = target.absolutePath)) }
                // Only remove the image this store previously owned, after the replacement is ready.
                previous?.let(::File)?.takeIf { it.parentFile == directory }?.delete()
            } catch (error: Exception) { target.delete(); throw error }
        }
        finally { bitmap.recycle() }
    }
}

/** Shared by initial stream loads and URL renewal. The quality is pinned for that reader's lifetime. */
fun ncmPlaybackArguments(songId: String, quality: NcmPlaybackQuality, force: Boolean) = buildJsonArray {
    add(buildJsonObject { put("ncmSongId", songId) })
    add(buildJsonObject { put("force", force); put("quality", quality.wireValue) })
}
