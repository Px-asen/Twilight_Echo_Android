package com.twilightecho.android.nativeintegration

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Generates real 44.1 kHz 16-bit stereo PCM `.wav` files in `context.cacheDir` for the built-in
 * demo tracks (`track-twilight-overture`, `track-nocturne-in-cobalt`, `track-solstice-drift`)
 * so that real hardware audio output, progress clock updates, seeking, hardware Equalizer DSP,
 * and automatic track-end transitions can be verified on any Android device out-of-the-box.
 */
object DemoAudioSynthesizer {

    private const val SAMPLE_RATE = 44100
    private const val CHANNELS = 2
    private const val BITS_PER_SAMPLE = 16
    private const val DURATION_SECONDS = 16

    fun ensureDemoWavFile(context: Context, trackId: String): File {
        val dir = File(context.cacheDir, "twilight_demo_wavs").apply { mkdirs() }
        val safeName = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val targetFile = File(dir, "$safeName.wav")
        if (targetFile.exists() && targetFile.length() > 44L) {
            return targetFile
        }

        val chordProgressionHz: Array<DoubleArray> = when (trackId) {
            "track-nocturne-in-cobalt" -> arrayOf(
                doubleArrayOf(220.00, 261.63, 329.63, 392.00), // Am7
                doubleArrayOf(174.61, 220.00, 261.63, 349.23), // Fmaj7
                doubleArrayOf(196.00, 246.94, 293.66, 392.00), // G6
                doubleArrayOf(164.81, 207.65, 246.94, 329.63)  // E7
            )
            "track-solstice-drift" -> arrayOf(
                doubleArrayOf(293.66, 369.99, 440.00, 554.37), // Dmaj7
                doubleArrayOf(246.94, 293.66, 369.99, 493.88), // Bm7
                doubleArrayOf(196.00, 246.94, 293.66, 369.99), // Gmaj7
                doubleArrayOf(220.00, 277.18, 329.63, 440.00)  // A6
            )
            else -> arrayOf(
                doubleArrayOf(261.63, 329.63, 392.00, 493.88), // Cmaj7
                doubleArrayOf(220.00, 261.63, 329.63, 392.00), // Am7
                doubleArrayOf(174.61, 220.00, 261.63, 349.23), // Fmaj7
                doubleArrayOf(196.00, 246.94, 293.66, 392.00)  // G
            )
        }

        val totalFrames = SAMPLE_RATE * DURATION_SECONDS
        val dataBytes = totalFrames * CHANNELS * (BITS_PER_SAMPLE / 8)
        val tmpFile = File(dir, "$safeName.wav.tmp")

        BufferedOutputStream(FileOutputStream(tmpFile), 64 * 1024).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataBytes)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM chunk size
            header.putShort(1) // PCM format = 1
            header.putShort(CHANNELS.toShort())
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8))
            header.putShort((CHANNELS * (BITS_PER_SAMPLE / 8)).toShort())
            header.putShort(BITS_PER_SAMPLE.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataBytes)
            out.write(header.array())

            val frameBuf = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (frame in 0 until totalFrames) {
                val timeSec = frame.toDouble() / SAMPLE_RATE
                val barIndex = ((timeSec / 2.0).toInt()) % chordProgressionHz.size
                val barPos = timeSec % 2.0
                val chord = chordProgressionHz[barIndex]

                // Warm pad envelope + gentle arpeggio note
                val padEnv = (1.0 - exp(-barPos * 6.0)) * exp(-barPos * 0.55)
                val arpStep = ((barPos * 4.0).toInt()).coerceIn(0, chord.lastIndex)
                val arpNotePos = (barPos * 4.0) % 1.0
                val arpEnv = exp(-arpNotePos * 4.5)

                var left = 0.0
                var right = 0.0
                for (i in chord.indices) {
                    val freq = chord[i]
                    val phase = 2.0 * PI * freq * timeSec
                    val voice = sin(phase) + 0.25 * sin(2.0 * phase)
                    val panLeft = 0.7 - 0.12 * i
                    val panRight = 0.3 + 0.12 * i
                    left += voice * padEnv * panLeft * 0.16
                    right += voice * padEnv * panRight * 0.16
                }

                val arpFreq = chord[arpStep] * 2.0
                val arpVoice = sin(2.0 * PI * arpFreq * timeSec) * arpEnv * 0.22
                left += arpVoice * 0.85
                right += arpVoice * 0.95

                // Master fade-in / fade-out
                val masterGain = when {
                    timeSec < 0.25 -> timeSec / 0.25
                    timeSec > DURATION_SECONDS - 0.5 -> ((DURATION_SECONDS - timeSec) / 0.5).coerceAtLeast(0.0)
                    else -> 1.0
                }

                val sampleL = (left * masterGain * 26000.0).toInt().coerceIn(-32767, 32767).toShort()
                val sampleR = (right * masterGain * 26000.0).toInt().coerceIn(-32767, 32767).toShort()

                if (frameBuf.remaining() < 4) {
                    out.write(frameBuf.array(), 0, frameBuf.position())
                    frameBuf.clear()
                }
                frameBuf.putShort(sampleL)
                frameBuf.putShort(sampleR)
            }
            if (frameBuf.position() > 0) {
                out.write(frameBuf.array(), 0, frameBuf.position())
            }
            out.flush()
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        tmpFile.renameTo(targetFile)
        return targetFile
    }
}
