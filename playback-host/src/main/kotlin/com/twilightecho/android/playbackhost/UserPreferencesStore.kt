package com.twilightecho.android.playbackhost

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import java.io.IOException
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "请选择可读取的图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = app.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("图片读取失败，请重新选择")
        var upright: Bitmap? = null
        try {
            // The bitmap decoder also accepts images without an EXIF container, including older-device PNGs.
            val orientation = try {
                app.contentResolver.openInputStream(source)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            upright = oriented
            val directory = File(app.filesDir, "backgrounds").apply { mkdirs() }
            val target = File(directory, "${UUID.randomUUID()}.png")
            try {
                target.outputStream().use { check(oriented.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片保存失败，请重试" } }
                val previous = state.value.appearance.backgroundImagePath
                update { it.copy(appearance = it.appearance.copy(backgroundStyle = BackgroundStyle.IMAGE, backgroundImagePath = target.absolutePath)) }
                // Only remove the image this store previously owned, after the replacement is ready.
                previous?.let(::File)?.takeIf { it.parentFile == directory }?.delete()
            } catch (error: Exception) { target.delete(); throw error }
        }
        finally { upright?.takeIf { it !== bitmap }?.recycle(); bitmap.recycle() }
    }
}

/** Shared by initial stream loads and URL renewal. The quality is pinned for that reader's lifetime. */
fun ncmPlaybackArguments(songId: String, quality: NcmPlaybackQuality, force: Boolean) = buildJsonArray {
    add(buildJsonObject { put("ncmSongId", songId) })
    add(buildJsonObject { put("force", force); put("quality", quality.wireValue) })
}
