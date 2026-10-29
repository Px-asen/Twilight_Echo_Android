package com.twilightecho.android.plugins

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.ceil

/**
 * QR Code Model 2 in byte mode at error correction level M. It produces the same symbol as the `qrcode`
 * npm package that renders QR sign-in on Desktop: minimum version, same mask selection.
 */
internal class QrMatrix(val size: Int) {
    private val dark = BooleanArray(size * size)
    private val reserved = BooleanArray(size * size)
    operator fun get(row: Int, col: Int) = dark[row * size + col]
    fun isReserved(row: Int, col: Int) = reserved[row * size + col]
    fun set(row: Int, col: Int, value: Boolean, reserve: Boolean = false) {
        dark[row * size + col] = value
        if (reserve) reserved[row * size + col] = true
    }
    fun xor(row: Int, col: Int, value: Boolean) { if (value) dark[row * size + col] = !dark[row * size + col] }
    fun darkCount() = dark.count { it }
}

private val QR_TOTAL_CODEWORDS = intArrayOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655, 733, 815,
    901, 991, 1085, 1156, 1258, 1364, 1474, 1588, 1706, 1828, 1921, 2051, 2185, 2323, 2465, 2611, 2761, 2876, 3034, 3196, 3362, 3532, 3706)
private val QR_M_EC_CODEWORDS = intArrayOf(10, 16, 26, 36, 48, 64, 72, 88, 110, 130, 150, 176, 198, 216, 240, 280, 308, 338, 364,
    416, 442, 476, 504, 560, 588, 644, 700, 728, 784, 812, 868, 924, 980, 1036, 1064, 1120, 1204, 1260, 1316, 1372)
private val QR_M_BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26,
    28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49)

/** [mask] forces a mask pattern (0..7); by default the lowest-penalty one is chosen. */
internal fun qrEncode(text: String, mask: Int? = null): QrMatrix {
    val bytes = text.toByteArray(Charsets.UTF_8)
    fun countBits(version: Int) = if (version < 10) 8 else 16
    fun dataBits(version: Int) = (QR_TOTAL_CODEWORDS[version - 1] - QR_M_EC_CODEWORDS[version - 1]) * 8
    val version = (1..40).firstOrNull { 4 + countBits(it) + bytes.size * 8 <= dataBits(it) }
        ?: throw IllegalArgumentException("二维码内容过长")
    val codewords = qrCodewords(bytes, version, countBits(version), dataBits(version))
    val matrix = QrMatrix(version * 4 + 17)
    qrFunctionPatterns(matrix, version)
    qrFormatInfo(matrix, 0) // reserves the format area before the data is placed
    if (version >= 7) qrVersionInfo(matrix, version)
    qrPlaceData(matrix, codewords)
    val chosen = mask ?: (0..7).minBy { candidate ->
        qrFormatInfo(matrix, candidate); qrApplyMask(matrix, candidate)
        qrPenalty(matrix).also { qrApplyMask(matrix, candidate) }
    }
    require(chosen in 0..7)
    qrApplyMask(matrix, chosen)
    qrFormatInfo(matrix, chosen)
    return matrix
}

private fun qrCodewords(bytes: ByteArray, version: Int, countBits: Int, dataBits: Int): ByteArray {
    val bits = ArrayList<Boolean>(dataBits)
    fun put(value: Int, length: Int) { for (i in length - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
    put(4, 4); put(bytes.size, countBits); bytes.forEach { put(it.toInt() and 255, 8) }
    if (bits.size + 4 <= dataBits) put(0, 4)
    while (bits.size % 8 != 0) bits += false
    for (i in 0 until (dataBits - bits.size) / 8) put(if (i % 2 == 1) 0x11 else 0xEC, 8)
    val data = ByteArray(bits.size / 8) { index -> (0 until 8).fold(0) { acc, bit -> (acc shl 1) or (if (bits[index * 8 + bit]) 1 else 0) }.toByte() }

    val total = QR_TOTAL_CODEWORDS[version - 1]; val blocks = QR_M_BLOCKS[version - 1]
    val longBlocks = total % blocks; val shortBlocks = blocks - longBlocks
    val shortData = data.size / blocks; val ecCount = total / blocks - shortData
    val divisor = rsDivisor(ecCount)
    val dataBlocks = ArrayList<ByteArray>(); val ecBlocks = ArrayList<ByteArray>()
    var offset = 0
    for (b in 0 until blocks) {
        val length = if (b < shortBlocks) shortData else shortData + 1
        val block = data.copyOfRange(offset, offset + length); offset += length
        dataBlocks += block; ecBlocks += rsRemainder(block, divisor)
    }
    val out = ByteArrayOutputStream(total)
    for (i in 0..shortData) for (block in dataBlocks) if (i < block.size) out.write(block[i].toInt())
    for (i in 0 until ecCount) for (block in ecBlocks) out.write(block[i].toInt())
    return out.toByteArray()
}

private fun gfMultiply(x: Int, y: Int): Int {
    var z = 0
    for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }
    return z
}

private fun rsDivisor(degree: Int): IntArray {
    val result = IntArray(degree).also { it[degree - 1] = 1 }
    var root = 1
    repeat(degree) {
        for (j in 0 until degree) {
            result[j] = gfMultiply(result[j], root)
            if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
        }
        root = gfMultiply(root, 0x02)
    }
    return result
}

private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
    val result = IntArray(divisor.size)
    for (byte in data) {
        val factor = (byte.toInt() and 255) xor result[0]
        System.arraycopy(result, 1, result, 0, result.size - 1); result[result.size - 1] = 0
        for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
    }
    return ByteArray(result.size) { result[it].toByte() }
}

private fun qrFunctionPatterns(matrix: QrMatrix, version: Int) {
    val size = matrix.size
    for ((row, col) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
        for (r in -1..7) {
            if (row + r !in 0 until size) continue
            for (c in -1..7) {
                if (col + c !in 0 until size) continue
                val dark = (r in 0..6 && (c == 0 || c == 6)) || (c in 0..6 && (r == 0 || r == 6)) || (r in 2..4 && c in 2..4)
                matrix.set(row + r, col + c, dark, reserve = true)
            }
        }
    }
    for (r in 8 until size - 8) { matrix.set(r, 6, r % 2 == 0, reserve = true); matrix.set(6, r, r % 2 == 0, reserve = true) }
    if (version > 1) {
        val count = version / 7 + 2
        val interval = if (size == 145) 26 else ceil((size - 13).toDouble() / (2 * count - 2)).toInt() * 2
        val coords = IntArray(count).also { it[0] = 6; for (i in 1 until count) it[count - i] = size - 7 - (i - 1) * interval }
        for (i in coords.indices) for (j in coords.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == count - 1) || (i == count - 1 && j == 0)) continue
            for (r in -2..2) for (c in -2..2) {
                val dark = r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0)
                matrix.set(coords[i] + r, coords[j] + c, dark, reserve = true)
            }
        }
    }
}

private fun bchDigit(value: Int) = 32 - Integer.numberOfLeadingZeros(value)

private fun qrFormatInfo(matrix: QrMatrix, mask: Int) {
    val size = matrix.size
    val generator = 0b10100110111
    val data = (0 shl 3) or mask // level M
    var d = data shl 10
    while (bchDigit(d) - bchDigit(generator) >= 0) d = d xor (generator shl (bchDigit(d) - bchDigit(generator)))
    val bits = ((data shl 10) or d) xor 0b101010000010010
    for (i in 0 until 15) {
        val dark = (bits ushr i) and 1 == 1
        when { i < 6 -> matrix.set(i, 8, dark, true); i < 8 -> matrix.set(i + 1, 8, dark, true); else -> matrix.set(size - 15 + i, 8, dark, true) }
        when { i < 8 -> matrix.set(8, size - i - 1, dark, true); i < 9 -> matrix.set(8, 15 - i, dark, true); else -> matrix.set(8, 15 - i - 1, dark, true) }
    }
    matrix.set(size - 8, 8, true, true)
}

private fun qrVersionInfo(matrix: QrMatrix, version: Int) {
    val generator = 0b1111100100101
    var d = version shl 12
    while (bchDigit(d) - bchDigit(generator) >= 0) d = d xor (generator shl (bchDigit(d) - bchDigit(generator)))
    val bits = (version shl 12) or d
    for (i in 0 until 18) {
        val row = i / 3; val col = i % 3 + matrix.size - 11; val dark = (bits ushr i) and 1 == 1
        matrix.set(row, col, dark, true); matrix.set(col, row, dark, true)
    }
}

private fun qrPlaceData(matrix: QrMatrix, data: ByteArray) {
    val size = matrix.size
    var increment = -1; var row = size - 1; var bit = 7; var index = 0
    var col = size - 1
    while (col > 0) {
        if (col == 6) col--
        while (true) {
            for (c in 0..1) {
                if (matrix.isReserved(row, col - c)) continue
                matrix.set(row, col - c, index < data.size && ((data[index].toInt() ushr bit) and 1) == 1)
                if (--bit == -1) { index++; bit = 7 }
            }
            row += increment
            if (row !in 0 until size) { row -= increment; increment = -increment; break }
        }
        col -= 2
    }
}

private fun qrApplyMask(matrix: QrMatrix, mask: Int) {
    for (col in 0 until matrix.size) for (row in 0 until matrix.size) {
        if (matrix.isReserved(row, col)) continue
        matrix.xor(row, col, when (mask) {
            0 -> (row + col) % 2 == 0
            1 -> row % 2 == 0
            2 -> col % 3 == 0
            3 -> (row + col) % 3 == 0
            4 -> (row / 2 + col / 3) % 2 == 0
            5 -> (row * col) % 2 + (row * col) % 3 == 0
            6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0
            else -> ((row * col) % 3 + (row + col) % 2) % 2 == 0
        })
    }
}

private fun qrPenalty(matrix: QrMatrix): Int {
    val size = matrix.size
    var points = 0
    for (row in 0 until size) {
        var runRow = 0; var runCol = 0; var lastRow: Boolean? = null; var lastCol: Boolean? = null
        var bitsRow = 0; var bitsCol = 0
        for (col in 0 until size) {
            val horizontal = matrix[row, col]
            if (horizontal == lastRow) runRow++ else { if (runRow >= 5) points += 3 + runRow - 5; lastRow = horizontal; runRow = 1 }
            val vertical = matrix[col, row]
            if (vertical == lastCol) runCol++ else { if (runCol >= 5) points += 3 + runCol - 5; lastCol = vertical; runCol = 1 }
            bitsRow = ((bitsRow shl 1) and 0x7FF) or (if (horizontal) 1 else 0)
            bitsCol = ((bitsCol shl 1) and 0x7FF) or (if (vertical) 1 else 0)
            if (col >= 10 && (bitsRow == 0x5D0 || bitsRow == 0x05D)) points += 40
            if (col >= 10 && (bitsCol == 0x5D0 || bitsCol == 0x05D)) points += 40
        }
        if (runRow >= 5) points += 3 + runRow - 5
        if (runCol >= 5) points += 3 + runCol - 5
    }
    for (row in 0 until size - 1) for (col in 0 until size - 1) {
        val dark = matrix[row, col]
        if (dark == matrix[row, col + 1] && dark == matrix[row + 1, col] && dark == matrix[row + 1, col + 1]) points += 3
    }
    val percent = matrix.darkCount() * 100.0 / (size * size)
    return points + abs(ceil(percent / 5).toInt() - 10) * 10
}

/** A black-on-white 1-bit PNG with the standard four-module quiet zone, about 500 px square. */
internal fun qrPngDataUrl(text: String): String {
    val matrix = qrEncode(text)
    val modules = matrix.size + 8
    val scale = maxOf(4, 512 / modules)
    val width = modules * scale
    val stride = (width + 7) / 8 + 1
    val raw = ByteArray(stride * width)
    for (y in 0 until width) {
        val row = y / scale - 4
        for (x in 0 until width) {
            val col = x / scale - 4
            val dark = row in 0 until matrix.size && col in 0 until matrix.size && matrix[row, col]
            if (!dark) raw[y * stride + 1 + x / 8] = (raw[y * stride + 1 + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
        }
    }
    val png = ByteArrayOutputStream()
    fun chunk(type: String, body: ByteArray) {
        val header = type.toByteArray(Charsets.US_ASCII)
        png.write(intBytes(body.size)); png.write(header); png.write(body)
        png.write(intBytes(CRC32().apply { update(header); update(body) }.value.toInt()))
    }
    png.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    chunk("IHDR", intBytes(width) + intBytes(width) + byteArrayOf(1, 0, 0, 0, 0))
    chunk("IDAT", Deflater(Deflater.BEST_COMPRESSION).run {
        setInput(raw); finish()
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (!finished()) out.write(buffer, 0, deflate(buffer))
        end(); out.toByteArray()
    })
    chunk("IEND", ByteArray(0))
    return "data:image/png;base64," + b64(png.toByteArray())
}

private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
