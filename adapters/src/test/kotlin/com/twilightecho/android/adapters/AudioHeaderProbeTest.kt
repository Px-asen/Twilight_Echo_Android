package com.twilightecho.android.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioHeaderProbeTest {
    private fun probe(bytes: ByteArray) = AudioHeaderProbe.probe { offset, length ->
        if (offset >= bytes.size) ByteArray(0) else bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
    }

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (8 * it)).toByte() }
    private fun be(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (8 * (bytes - 1 - it))).toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    private fun wv(samples: Long, flags: Long, extra: ByteArray = ByteArray(0), high: Int = 0, blockSamples: Long = 1024) =
        ascii("wvpk") + le(24L + extra.size, 4) + le(0x410, 2) + byteArrayOf(0, high.toByte()) + le(samples, 4) +
            le(0, 4) + le(blockSamples, 4) + le(flags, 4) + le(0, 4) + extra

    @Test
    fun `FLAC STREAMINFO gives the exact length, after any ID3 tag`() {
        val samples = 26_460_001L
        // 44.1 kHz, two channels, 24 bits.
        val packed = (44100L shl 44) or (1L shl 41) or (23L shl 36) or samples
        val streamInfo = ByteArray(34).also { info -> for (i in 0 until 8) info[10 + i] = (packed ushr (56 - 8 * i)).toByte() }
        val flac = ascii("fLaC") + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo
        assertEquals(AudioHeader(samples / 44100.0, 44100, 24), probe(flac))
        // A 300-byte tag: its size is four 7-bit groups.
        val id3 = ascii("ID3") + byteArrayOf(4, 0, 0, 0, 0, 2, 0x2C) + ByteArray(300)
        assertEquals(samples / 44100.0, probe(id3 + id3 + flac)!!.durationSeconds, 0.0)
        // A file whose first block is not STREAMINFO is not guessed at.
        assertNull(probe(ascii("fLaC") + byteArrayOf(0x84.toByte(), 0, 0, 34) + streamInfo))
    }

    @Test
    fun `WAV length comes from the data chunk after other chunks`() {
        fun chunk(id: String, body: ByteArray) = ascii(id) + le(body.size.toLong(), 4) + body +
            (if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
        val format = le(1, 2) + le(2, 2) + le(48000, 4) + le(48000L * 6, 4) + le(6, 2) + le(24, 2)
        // Only the data chunk's header is read, so the samples themselves can be left out here.
        val data = ascii("data") + le(48000L * 6 * 125 + 6 * 24000, 4)
        val wav = ascii("RIFF") + le(0, 4) + ascii("WAVE") + chunk("fmt ", format) + chunk("LIST", ByteArray(7)) + data
        assertEquals(AudioHeader(125.5, 48000, 24), probe(wav))
        assertNull(probe(ascii("RIFF") + le(0, 4) + ascii("WAVE") + data))
    }

    @Test
    fun `Monkey's Audio frames give the length in both header layouts`() {
        // 89 full frames of 294 912 blocks and a final frame of 212 832: exactly 600 s at 44.1 kHz.
        val descriptor = ascii("MAC ") + le(3990, 2) + le(0, 2) + le(52, 4) + le(24, 4) + ByteArray(36)
        val header = le(2000, 2) + le(0, 2) + le(294_912, 4) + le(212_832, 4) + le(90, 4) + le(16, 2) + le(2, 2) + le(44100, 4)
        assertEquals(AudioHeader(600.0, 44100, 16), probe(descriptor + header))
        // Before 3.98 the fields follow the version; the frame size depends on it.
        fun old(version: Long, flags: Long, frames: Long, finalBlocks: Long) = ascii("MAC ") + le(version, 2) + le(2000, 2) +
            le(flags, 2) + le(2, 2) + le(44100, 4) + le(44, 4) + le(0, 4) + le(frames, 4) + le(finalBlocks, 4)
        assertEquals(AudioHeader(600.0, 44100, 24), probe(old(3970, 8, 90, 212_832)))
        assertEquals(2 * 9216 / 44100.0, probe(old(3800, 0, 2, 9216))!!.durationSeconds, 0.0)
        assertEquals((73_728 + 100) / 44100.0, probe(old(3900, 1, 2, 100))!!.durationSeconds, 0.0)
        assertEquals(8, probe(old(3900, 1, 2, 100))!!.bitDepth)
    }

    @Test
    fun `WavPack counts 40-bit sample totals and rates outside the table`() {
        assertEquals(AudioHeader(600.0, 44100, 16), probe(wv(26_460_000, (9L shl 23) or 1)))
        // 2^32 + 5 samples are written as 6 with an upper byte of 1.
        assertEquals((4_294_967_296.0 + 5) / 96000, probe(wv(6, 13L shl 23, high = 1))!!.durationSeconds, 0.0)
        // The sample-rate sub-block: ID 0x27 with the odd-size flag, two words holding three bytes.
        val rate = byteArrayOf((0x27 or 0x40).toByte(), 2) + le(50_000, 3) + byteArrayOf(0)
        assertEquals(AudioHeader(10.0, 50_000, 8), probe(wv(500_000, 15L shl 23, rate)))
        // Four-byte rates use only seven bits of their last byte.
        val largeRate = byteArrayOf(0xA7.toByte(), 2, 0, 0) + le(0x81000000, 4)
        assertEquals(AudioHeader(1.0, 16_777_216, 8), probe(wv(16_777_216, 15L shl 23, largeRate)))
        assertNull(probe(wv(0xFFFFFFFFL, 9L shl 23)))
        assertNull(probe(ascii("OggS") + ByteArray(60)))
        assertNull(probe(ByteArray(0)))
    }

    @Test
    fun `WavPack bit depth excludes container padding but keeps extended integer and floating data`() {
        for ((stored, bits) in listOf(0L to 8, 1L to 16, 2L to 24, 3L to 32)) {
            assertEquals(bits, probe(wv(44100, (9L shl 23) or stored))!!.bitDepth)
        }
        assertEquals(20, probe(wv(44100, (9L shl 23) or 2 or (4L shl 13)))!!.bitDepth)
        assertEquals(32, probe(wv(44100, (9L shl 23) or 3 or 0x100))!!.bitDepth)
        assertEquals(32, probe(wv(44100, (9L shl 23) or 3 or 0x80))!!.bitDepth)
    }

    @Test
    fun `WavPack metadata after large payloads is read positionally within its block`() {
        val payload = byteArrayOf(0xA1.toByte(), 0, 0x10, 0) + ByteArray(8192)
        val rate = byteArrayOf(0x67, 2) + le(50_000, 3) + byteArrayOf(0)
        val bytes = wv(500_000, 15L shl 23, payload + rate)
        val reads = mutableListOf<Pair<Long, Int>>()
        val result = AudioHeaderProbe.probe { offset, length ->
            reads += offset to length
            bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
        }
        assertEquals(AudioHeader(10.0, 50_000, 8), result)
        org.junit.Assert.assertTrue(reads.all { it.second <= 64 })
        // A rate physically present after the declared block is not part of that block.
        val outside = bytes.copyOf().apply { le(24L + payload.size, 4).copyInto(this, 4) }
        assertNull(probe(outside))
        assertNull(probe(bytes.copyOf(bytes.size - 2)))
        val wrapper = wv(0, 0, byteArrayOf(0x21, 1, 0, 0), blockSamples = 0)
        assertEquals(result, probe(wrapper + bytes))
    }

    @Test
    fun `WavPack DSD publishes a one-bit clock and uses byte-frame totals for duration`() {
        val dsd = byteArrayOf(0x0E, 1, 3, 0)
        val flags = (12L shl 23) or 0x80000000L // 88.2 kHz * 8 bytes * 8 bits = DSD128.
        assertEquals(AudioHeader(10.0, 5_644_800, 1, isDsd = true), probe(wv(7_056_000, flags, dsd)))
        val customRate = byteArrayOf(0x67, 2) + le(48_000, 3) + byteArrayOf(0)
        assertEquals(AudioHeader(10.0, 3_072_000, 1, isDsd = true),
            probe(wv(3_840_000, (15L shl 23) or 0x80000000L, dsd + customRate)))
        assertNull(probe(wv(1000, flags)))
        assertNull(probe(wv(1000, flags, byteArrayOf(0x0E, 1, 31, 0))))
        assertNull(probe(wv(1000, flags, byteArrayOf(0x0E, 1, 30, 0))))
    }

    @Test
    fun `DSF and DFF headers give exact DSD sample rates and durations`() {
        // DSD64 (2 822 400 Hz), 2 channels, 1 bit, 10 seconds = 28 224 000 samples per channel.
        val dsfChunk = ascii("DSD ") + le(28, 8) + le(10_000_000, 8) + le(0, 8)
        val fmtChunk = ascii("fmt ") + le(52, 8) + le(1, 4) + le(0, 4) + le(2, 4) + le(2, 4) +
            le(2_822_400, 4) + le(1, 4) + le(28_224_000, 8) + le(4096, 4) + le(0, 4)
        assertEquals(AudioHeader(10.0, 2_822_400, 1, isDsd = true), probe(dsfChunk + fmtChunk))
        val msbFmt = fmtChunk.copyOf().apply { this[60 - 28] = 8 }
        assertEquals(AudioHeader(10.0, 2_822_400, 1, isDsd = true), probe(dsfChunk + msbFmt))

        // DSDIFF (FRM8 / DSD ): 2 channels, 2 822 400 Hz, 20 seconds -> (20 * 2_822_400 * 2) / 8 = 14_112_000 bytes.
        fun dffChunk(id: String, body: ByteArray) = ascii(id) + be(body.size.toLong(), 8) + body
        val fsSub = dffChunk("FS  ", be(2_822_400, 4))
        val chnlSub = dffChunk("CHNL", be(2, 2) + ascii("SLFTSRGT"))
        val prop = dffChunk("PROP", ascii("SND ") + fsSub + chnlSub)
        val dsdHeader = ascii("DSD ") + be(14_112_000L, 8)
        val dff = ascii("FRM8") + be(0, 8) + ascii("DSD ") + prop + dsdHeader
        assertEquals(AudioHeader(20.0, 2_822_400, 1, isDsd = true), probe(dff))
    }
}
