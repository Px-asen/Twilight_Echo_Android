package com.twilightecho.android.coreclient

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/** Containers shared with the desktop asset library and the native convolver. */
enum class DspImpulseFormat(val extension: String, val mediaType: String) {
    WAV("wav", "audio/wav"),
    FLAC("flac", "audio/flac"),
    AIFF("aiff", "audio/aiff")
}

/** Content identity stays tied to the original bytes; compressed IRs are not converted for transfer. */
data class DspImpulseFile(
    val sha256: String,
    val byteSize: Long,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val format: DspImpulseFormat = DspImpulseFormat.WAV
) {
    val assetId: String get() = "impulseResponse:$sha256"
    val archivePath: String get() = "assets/impulseResponse/impulseResponse_$sha256.${format.extension}"
}

fun impulseResponseFormat(head: ByteArray, size: Int = head.size): DspImpulseFormat? = when {
    isWave(head, size) -> DspImpulseFormat.WAV
    size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "fLaC" -> DspImpulseFormat.FLAC
    size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "FORM" &&
        String(head, 8, 4, Charsets.US_ASCII) in setOf("AIFF", "AIFC") -> DspImpulseFormat.AIFF
    else -> null
}

fun isWave(head: ByteArray, size: Int = head.size): Boolean =
    size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE"

/** Copies a document into app-owned storage, using its container rather than its provider's MIME/name. */
fun copyImpulseResponse(input: InputStream, directory: File): File {
    val head = ByteArray(12)
    var size = 0
    while (size < head.size) {
        val read = input.read(head, size, head.size - size)
        if (read < 0) break
        size += read
    }
    val format = impulseResponseFormat(head, size) ?: error("请选择 WAV、FLAC 或 AIFF 脉冲响应")
    directory.mkdirs()
    val file = File(directory, "${UUID.randomUUID()}.${format.extension}")
    try {
        file.outputStream().use { output ->
            output.write(head, 0, size)
            input.copyTo(output)
        }
        return file
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}

/** Package identity plus optional header metadata; actual audio decoding remains with the convolver. */
fun describeImpulseFile(file: File): DspImpulseFile? {
    if (!file.isFile) return null
    val digest = MessageDigest.getInstance("SHA-256")
    val head = ByteArray(64 * 1024)
    var headSize = 0
    var size = 0L
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (headSize < head.size) {
                val copied = minOf(read, head.size - headSize)
                System.arraycopy(buffer, 0, head, headSize, copied)
                headSize += copied
            }
            digest.update(buffer, 0, read)
            size += read
        }
    }
    val format = impulseResponseFormat(head, headSize) ?: return null
    var channels: Int? = null
    var sampleRate: Int? = null
    if (format == DspImpulseFormat.FLAC) {
        // FLAC's first metadata block is STREAMINFO; its 20-bit rate precedes the channel count.
        if (headSize >= 42 && head[4].toInt() and 0x7f == 0 && bigEndian(head, 5, 3) == 34L) {
            sampleRate = ((head[18].toInt() and 0xff) shl 12) or
                ((head[19].toInt() and 0xff) shl 4) or ((head[20].toInt() and 0xff) shr 4)
            channels = ((head[20].toInt() shr 1) and 7) + 1
        }
    } else {
        var offset = 12L
        while (offset + 8 <= headSize) {
            val at = offset.toInt()
            val chunkSize = if (format == DspImpulseFormat.WAV) littleEndian(head, at + 4, 4) else bigEndian(head, at + 4, 4)
            val chunk = String(head, at, 4, Charsets.US_ASCII)
            if (format == DspImpulseFormat.WAV && chunk == "fmt " && at + 16 <= headSize) {
                channels = littleEndian(head, at + 10, 2).toInt()
                sampleRate = littleEndian(head, at + 12, 4).toInt()
                break
            }
            if (format == DspImpulseFormat.AIFF && chunk == "COMM" && chunkSize >= 18 && at + 26 <= headSize) {
                channels = bigEndian(head, at + 8, 2).toInt()
                val exponent = bigEndian(head, at + 16, 2).toInt()
                val mantissa = (0 until 8).fold(0.0) { value, index -> value * 256 + (head[at + 18 + index].toInt() and 0xff) }
                val rate = Math.scalb(mantissa, (exponent and 0x7fff) - 16383 - 63)
                sampleRate = rate.takeIf { exponent and 0x8000 == 0 && it.isFinite() && it > 0 && it <= Int.MAX_VALUE }?.roundToInt()
                break
            }
            offset += 8 + chunkSize + (chunkSize and 1L)
        }
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    return DspImpulseFile(sha256, size, sampleRate?.takeIf { it > 0 }, channels?.takeIf { it > 0 }, format)
}

private fun littleEndian(bytes: ByteArray, offset: Int, length: Int): Long =
    (0 until length).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 0xff) shl (8 * index)) }

private fun bigEndian(bytes: ByteArray, offset: Int, length: Int): Long =
    (0 until length).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 0xff) }
