package com.twilightecho.android.adapters

/** What an audio file's own header states. [bitDepth] is null where the header does not state it exactly. */
data class AudioHeader(val durationSeconds: Double, val sampleRate: Int, val bitDepth: Int?)

/**
 * The exact length of FLAC, WAV, Monkey's Audio and WavPack files, read from their headers. CUE sheets mostly
 * split such images; MediaStore and MediaMetadataRetriever skip APE and WavPack and round lengths to the
 * millisecond, while a sheet's last song ends where the file does.
 */
object AudioHeaderProbe {
    private val WAVPACK_RATES = intArrayOf(6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000,
        64000, 88200, 96000, 192000)

    /** [read] returns up to `length` bytes at `offset`, fewer at the end of the file. */
    fun probe(read: (offset: Long, length: Int) -> ByteArray): AudioHeader? = runCatching {
        var start = 0L
        // Tags written before the audio, sometimes more than one.
        for (attempt in 0 until 4) {
            val tag = read(start, 10)
            if (tag.size < 10 || tag.ascii(0, 3) != "ID3") break
            val size = (tag.u8(6) and 0x7F shl 21) or (tag.u8(7) and 0x7F shl 14) or (tag.u8(8) and 0x7F shl 7) or
                (tag.u8(9) and 0x7F)
            start += 10L + size + if (tag.u8(5) and 0x10 != 0) 10 else 0
        }
        val head = read(start, 64)
        when {
            head.ascii(0, 4) == "fLaC" -> flac(head)
            head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WAVE" -> wav(read, start)
            head.ascii(0, 4) == "MAC " -> ape(read, start)
            head.ascii(0, 4) == "wvpk" -> wavPack(read(start, 32 + 1024))
            else -> null
        }
    }.getOrNull()

    private fun flac(head: ByteArray): AudioHeader? {
        // STREAMINFO comes first: rate (20 bits), channels (3), bits (5) and samples (36) from its 11th byte.
        if (head.size < 42 || head.u8(4) and 0x7F != 0) return null
        var packed = 0L
        for (index in 18 until 26) packed = packed shl 8 or head.u8(index).toLong()
        return header(packed and 0xFFFFFFFFFL, (packed ushr 44).toInt(), ((packed ushr 36) and 0x1F).toInt() + 1)
    }

    private fun wav(read: (Long, Int) -> ByteArray, start: Long): AudioHeader? {
        var offset = start + 12
        var rate = 0
        var blockAlign = 0
        var bits = 0
        repeat(64) {
            val chunk = read(offset, 24)
            if (chunk.size < 8) return null
            val size = chunk.u32(4)
            when (chunk.ascii(0, 4)) {
                "fmt " -> {
                    if (chunk.size < 24) return null
                    rate = chunk.u32(12).toInt()
                    blockAlign = chunk.u16(20)
                    bits = chunk.u16(22)
                }
                "data" -> return if (blockAlign > 0) header(size / blockAlign, rate, bits.takeIf { it > 0 }) else null
            }
            offset += 8 + size + (size and 1)
        }
        return null
    }

    private fun ape(read: (Long, Int) -> ByteArray, start: Long): AudioHeader? {
        val head = read(start, 32)
        if (head.size < 32) return null
        val version = head.u16(4)
        if (version >= 3980) {
            // A descriptor, then the header it measures.
            val descriptorBytes = head.u32(8)
            if (descriptorBytes < 52 || descriptorBytes > 4096) return null
            val fields = read(start + descriptorBytes, 24)
            if (fields.size < 24) return null
            return header(blocks(fields.u32(12), fields.u32(4), fields.u32(8)), fields.u32(20).toInt(), fields.u16(16))
        }
        val compression = head.u16(6)
        val flags = head.u16(8)
        val blocksPerFrame = when {
            version >= 3950 -> 73728L * 4
            version >= 3900 || (version >= 3800 && compression >= 4000) -> 73728L
            else -> 9216L
        }
        val bits = when {
            flags and 1 != 0 -> 8
            flags and 8 != 0 -> 24
            else -> 16
        }
        return header(blocks(head.u32(24), blocksPerFrame, head.u32(28)), head.u32(12).toInt(), bits)
    }

    private fun blocks(frames: Long, blocksPerFrame: Long, finalFrameBlocks: Long) =
        if (frames == 0L) 0L else (frames - 1) * blocksPerFrame + finalFrameBlocks

    private fun wavPack(block: ByteArray): AudioHeader? {
        if (block.size < 32) return null
        val lower = block.u32(12)
        val flags = block.u32(24)
        // An unknown length, or DSD, whose counts are not PCM samples.
        if (lower == 0xFFFFFFFFL || flags and 0x80000000L != 0L) return null
        val upper = block.u8(11).toLong()
        val samples = lower + (upper shl 32) - upper
        val index = ((flags ushr 23) and 0xF).toInt()
        val rate = if (index < WAVPACK_RATES.size) WAVPACK_RATES[index] else wavPackCustomRate(block) ?: return null
        return header(samples, rate, null)
    }

    /** A rate outside the table is stored in the first block's sample-rate sub-block. */
    private fun wavPackCustomRate(block: ByteArray): Int? {
        val end = minOf(block.size.toLong(), 8 + block.u32(4)).toInt()
        var at = 32
        while (at + 2 <= end) {
            val id = block.u8(at)
            val large = id and 0x80 != 0
            if (large && at + 4 > end) return null
            val words = if (large) block.u8(at + 1) or (block.u8(at + 2) shl 8) or (block.u8(at + 3) shl 16) else block.u8(at + 1)
            val data = at + if (large) 4 else 2
            val length = words * 2 - if (id and 0x40 != 0) 1 else 0
            if (id and 0x3F == 0x27 && length >= 3 && data + length <= block.size) {
                return block.u8(data) or (block.u8(data + 1) shl 8) or (block.u8(data + 2) shl 16) or
                    (if (length >= 4) block.u8(data + 3) shl 24 else 0)
            }
            at = data + words * 2
        }
        return null
    }

    private fun header(samples: Long, rate: Int, bits: Int?): AudioHeader? =
        if (samples <= 0 || rate <= 0) null else AudioHeader(samples.toDouble() / rate, rate, bits)

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF
    private fun ByteArray.u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
    private fun ByteArray.u32(index: Int) = (u16(index).toLong() or (u16(index + 2).toLong() shl 16))
    private fun ByteArray.ascii(index: Int, length: Int) =
        if (size < index + length) "" else String(this, index, length, Charsets.ISO_8859_1)
}
