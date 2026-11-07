package com.twilightecho.android.playbackhost

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Backgrounds and personal library artwork share decoding, EXIF orientation and bitmap ownership. */
internal fun ContentResolver.readOrientedImage(uri: Uri, decodeEdge: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "请选择可读取的图片" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > decodeEdge) sample *= 2
    val bitmap = openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("图片读取失败，请重新选择")
    try {
        val orientation = try {
            openInputStream(uri)?.use {
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
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    } catch (error: Exception) { bitmap.recycle(); throw error }
}

/** User-selected artwork only; no provider images or local source files leave the phone. */
class PersonalArtworkImporter(private val resolver: ContentResolver) {
    suspend fun dataUrl(uri: String): String = withContext(Dispatchers.IO) {
        var bitmap = resolver.readOrientedImage(Uri.parse(uri), 3072)
        try {
            var edge = minOf(1536, maxOf(bitmap.width, bitmap.height))
            var bytes: ByteArray
            do {
                if (maxOf(bitmap.width, bitmap.height) > edge) {
                    val scale = edge.toDouble() / maxOf(bitmap.width, bitmap.height)
                    val resized = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()),
                        maxOf(1, (bitmap.height * scale).toInt()), true)
                    if (resized !== bitmap) bitmap.recycle()
                    bitmap = resized
                }
                bytes = ByteArrayOutputStream().use { output ->
                    @Suppress("DEPRECATION")
                    check(bitmap.compress(Bitmap.CompressFormat.WEBP, 90, output)) { "图片压缩失败，请重新选择" }
                    output.toByteArray()
                }
                edge = maxOf(1, edge * 3 / 4)
            } while (bytes.size > 2 * 1024 * 1024)
            "data:image/webp;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
}
