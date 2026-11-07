package com.twilightecho.android.adapters

/** What an audio file's own header states. [bitDepth] is null where the header does not state it exactly. */
data class AudioHeader(val durationSeconds: Double, val sampleRate: Int, val bitDepth: Int?, val isDsd: Boolean = false)

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
            head.ascii(0, 4) == "wvpk" -> wavPack(read, start)
            head.ascii(0, 4) == "DSD " -> dsf(read(start, 80))
            head.ascii(0, 4) == "FRM8" && head.ascii(12, 4) == "DSD " -> dff(read, start)
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

    private fun wavPack(read: (Long, Int) -> ByteArray, start: Long): AudioHeader? {
        var offset = start
        var block = read(offset, 32)
        // Metadata-only blocks can precede the first audio block.
        while (block.size == 32 && block.ascii(0, 4) == "wvpk" && block.u32(20) == 0L) {
            if (block.u32(4) < 24) return null
            offset += 8 + block.u32(4)
            block = read(offset, 32)
        }
        if (block.size != 32 || block.ascii(0, 4) != "wvpk" || block.u32(4) < 24) return null
        val lower = block.u32(12)
        val flags = block.u32(24)
        if (lower == 0xFFFFFFFFL) return null
        val upper = block.u8(11).toLong()
        val samples = lower + (upper shl 32) - upper
        val index = ((flags ushr 23) and 0xF).toInt()
        val dsd = flags and 0x80000000L != 0L
        var rate = WAVPACK_RATES.getOrNull(index)
        var dsdShift: Int? = null
        val end = offset + 8 + block.u32(4)
        var at = offset + 32
        // Skip payloads positionally: album art or audio data need not fit a probe buffer.
        while ((rate == null || (dsd && dsdShift == null)) && at + 2 <= end) {
            val metadata = read(at, 4)
            if (metadata.size < 2) return null
            val id = metadata.u8(0)
            val large = id and 0x80 != 0
            if (large && (metadata.size < 4 || at + 4 > end)) return null
            val words = if (large) metadata.u8(1) or (metadata.u8(2) shl 8) or (metadata.u8(3) shl 16) else metadata.u8(1)
            val data = at + if (large) 4 else 2
            val length = words * 2 - if (id and 0x40 != 0) 1 else 0
            if (length < 0 || data + words * 2 > end) return null
            when (id and 0x3F) {
                0x27 -> if (length == 3 || length == 4) {
                    val value = read(data, length)
                    if (value.size != length) return null
                    rate = value.u8(0) or (value.u8(1) shl 8) or (value.u8(2) shl 16) or
                        (if (length == 4) (value.u8(3) and 0x7F) shl 24 else 0)
                }
                0x0E -> if (dsd && length >= 2) {
                    val value = read(data, 1)
                    if (value.isEmpty()) return null
                    dsdShift = value.u8(0).takeIf { it <= 30 } ?: return null
                }
            }
            at = data + words * 2
        }
        val baseRate = rate?.takeIf { it > 0 } ?: return null
        if (dsd) {
            // WavPack totals count DSD bytes per channel; its native rate counts bits.
            val byteRate = baseRate.toLong() shl (dsdShift ?: return null)
            if (samples <= 0 || byteRate > Int.MAX_VALUE / 8) return null
            val bitRate = byteRate * 8
            return AudioHeader(samples.toDouble() / byteRate, bitRate.toInt(), 1, isDsd = true)
        }
        // BYTES_STORED gives the container; SHIFT removes padding, not INT32 redundancy.
        val bits = if (flags and 0x80L != 0L) 32 else ((flags and 3).toInt() + 1) * 8 - ((flags ushr 13) and 0x1F).toInt()
        return header(samples, baseRate, bits.takeIf { it in 1..32 })
    }

    private fun dsf(head: ByteArray): AudioHeader? {
        // DSD chunk (28 bytes) followed immediately by fmt chunk (>= 52 bytes).
        if (head.size < 72 || head.u64(4) != 28L || head.ascii(28, 4) != "fmt " || head.u64(32) < 52L) return null
        val rate = head.u32(56).toInt()
        // 1/8 describes bit order within a byte; either encoding is one-bit DSD.
        val bits = head.u32(60).toInt().takeIf { it == 1 || it == 8 }?.let { 1 }
        val samples = head.u64(64)
        return header(samples, rate, bits, isDsd = true)
    }

    private fun dff(read: (Long, Int) -> ByteArray, start: Long): AudioHeader? {
        var offset = start + 16
        var rate = 0
        var channels = 0
        repeat(32) {
            val chunk = read(offset, 16)
            if (chunk.size < 12) return null
            val id = chunk.ascii(0, 4)
            val size = chunk.be64(4)
            if (size < 0) return null
            when (id) {
                "PROP" -> if (size >= 4 && chunk.ascii(12, 4) == "SND ") {
                    var sub = offset + 16
                    val propEnd = offset + 12 + size
                    repeat(32) {
                        if (sub + 12 > propEnd) return@repeat
                        val prop = read(sub, 16)
                        if (prop.size < 12) return null
                        val propId = prop.ascii(0, 4)
                        val propSize = prop.be64(4)
                        if (propSize < 0) return null
                        when (propId) {
                            "FS  " -> if (prop.size >= 16) rate = prop.be32(12).toInt()
                            "CHNL" -> if (prop.size >= 14) channels = prop.be16(12)
                        }
                        sub += 12 + propSize + (propSize and 1)
                    }
                }
                "DSD " -> return if (channels > 0 && rate > 0 && size <= Long.MAX_VALUE / 8) {
                    header((size * 8) / channels, rate, 1, isDsd = true)
                } else null
            }
            offset += 12 + size + (size and 1)
        }
        return null
    }

    private fun header(samples: Long, rate: Int, bits: Int?, isDsd: Boolean = false): AudioHeader? =
        if (samples <= 0 || rate <= 0) null else AudioHeader(samples.toDouble() / rate, rate, bits, isDsd)

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF
    private fun ByteArray.u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
    private fun ByteArray.u32(index: Int) = (u16(index).toLong() or (u16(index + 2).toLong() shl 16))
    private fun ByteArray.u64(index: Int) = u32(index) or (u32(index + 4) shl 32)
    private fun ByteArray.be16(index: Int) = (u8(index) shl 8) or u8(index + 1)
    private fun ByteArray.be32(index: Int) = (be16(index).toLong() shl 16) or be16(index + 2).toLong()
    private fun ByteArray.be64(index: Int) = (be32(index) shl 32) or be32(index + 4)
    private fun ByteArray.ascii(index: Int, length: Int) =
        if (size < index + length) "" else String(this, index, length, Charsets.ISO_8859_1)
}
