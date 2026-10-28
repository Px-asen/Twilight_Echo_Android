package com.twilightecho.android.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioHeaderProbeTest {
    private fun probe(bytes: ByteArray) = AudioHeaderProbe.probe { offset, length ->
        if (offset >= bytes.size) ByteArray(0) else bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
    }

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (8 * it)).toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

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
        fun block(samplesHigh: Int, samplesLow: Long, flags: Long, extra: ByteArray = ByteArray(0)) =
            ascii("wvpk") + le(24L + extra.size, 4) + le(0x410, 2) + byteArrayOf(0, samplesHigh.toByte()) + le(samplesLow, 4) +
                le(0, 4) + le(0, 4) + le(flags, 4) + le(0, 4) + extra
        assertEquals(AudioHeader(600.0, 44100, null), probe(block(0, 26_460_000, 9L shl 23)))
        // 2^32 + 5 samples are written as 6 with an upper byte of 1.
        assertEquals((4_294_967_296.0 + 5) / 96000, probe(block(1, 6, 13L shl 23))!!.durationSeconds, 0.0)
        // The sample-rate sub-block: ID 0x27 with the odd-size flag, two words holding three bytes.
        val rate = byteArrayOf((0x27 or 0x40).toByte(), 2) + le(50_000, 3) + byteArrayOf(0)
        assertEquals(AudioHeader(10.0, 50_000, null), probe(block(0, 500_000, 15L shl 23, rate)))
        assertNull(probe(block(0, 0xFFFFFFFFL, 9L shl 23)))
        // DSD counts are not PCM samples.
        assertNull(probe(block(0, 1000, (9L shl 23) or 0x80000000L)))
        assertNull(probe(ascii("OggS") + ByteArray(60)))
        assertNull(probe(ByteArray(0)))
    }
}
