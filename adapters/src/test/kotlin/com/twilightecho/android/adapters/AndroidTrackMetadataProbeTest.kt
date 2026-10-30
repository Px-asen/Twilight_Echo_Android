package com.twilightecho.android.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidTrackMetadataProbeTest {
    private fun probeChannels(bytes: ByteArray) = AndroidTrackMetadataProbe.probeAudioChannels { offset, length ->
        if (offset >= bytes.size) ByteArray(0)
        else bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
    }

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (8 * it)).toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `FLAC channel count is read from STREAMINFO including after ID3v2 tags`() {
        fun flacWithChannels(channels: Int): ByteArray {
            val packed = (48000L shl 44) or (((channels - 1).toLong() and 0x7L) shl 41) or (23L shl 36) or 480_000L
            val streamInfo = ByteArray(34).also { info ->
                for (i in 0 until 8) info[10 + i] = (packed ushr (56 - 8 * i)).toByte()
            }
            return ascii("fLaC") + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo
        }

        assertEquals(1, probeChannels(flacWithChannels(1)))
        assertEquals(2, probeChannels(flacWithChannels(2)))
        val id3 = ascii("ID3") + byteArrayOf(4, 0, 0, 0, 0, 2, 0x2C) + ByteArray(300)
        assertEquals(6, probeChannels(id3 + flacWithChannels(6)))
    }

    @Test
    fun `WAV channel count is read from fmt chunk`() {
        fun chunk(id: String, body: ByteArray) = ascii(id) + le(body.size.toLong(), 4) + body +
            (if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
        val format = le(1, 2) + le(2, 2) + le(44100, 4) + le(44100L * 4, 4) + le(4, 2) + le(16, 2)
        val wav = ascii("RIFF") + le(0, 4) + ascii("WAVE") + chunk("JUNK", ByteArray(8)) + chunk("fmt ", format)
        assertEquals(2, probeChannels(wav))
    }

    @Test
    fun `Monkey's Audio channel count works for both modern and legacy headers`() {
        val descriptor = ascii("MAC ") + le(3990, 2) + le(0, 2) + le(52, 4) + le(24, 4) + ByteArray(36)
        val header = le(2000, 2) + le(0, 2) + le(294_912, 4) + le(212_832, 4) + le(90, 4) + le(16, 2) + le(2, 2) + le(44100, 4)
        assertEquals(2, probeChannels(descriptor + header))

        val legacyMono = ascii("MAC ") + le(3970, 2) + le(2000, 2) + le(8, 2) + le(1, 2) + le(44100, 4) + ByteArray(20)
        assertEquals(1, probeChannels(legacyMono))

        assertNull(probeChannels(ByteArray(0)))
        assertNull(probeChannels(ascii("OggS") + ByteArray(32)))
    }

    @Test
    fun `formatDocumentIdPath formats primary volume, external volume, and raw document IDs`() {
        assertEquals(
            "内部存储/Music/Lossless/track.flac",
            AndroidTrackMetadataProbe.formatDocumentIdPath("primary:Music/Lossless/track.flac")
        )
        assertEquals(
            "/storage/emulated/0/Music/track.mp3",
            AndroidTrackMetadataProbe.formatDocumentIdPath("raw:/storage/emulated/0/Music/track.mp3")
        )
        assertEquals(
            "1A2B-3C4D/HiRes/album.ape",
            AndroidTrackMetadataProbe.formatDocumentIdPath("1A2B-3C4D:HiRes/album.ape")
        )
        assertNull(AndroidTrackMetadataProbe.formatDocumentIdPath(""))
    }
}
