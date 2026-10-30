package com.twilightecho.android.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.runtimehost.AudioSourceDescriptor
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class P2ResamplerOutputTest {
    @Test fun actualPhaseSurvivesQualityChangesAndClearsWhenStopped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "p2-resampler-${System.nanoTime()}.wav")
        val frames = 96000 * 3
        val wave = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(96000).putInt(192000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames * 2)
        repeat(frames) { wave.putShort((sin(2 * Math.PI * 1000 * it / 96000) * 4096).toInt().toShort()) }
        source.writeBytes(wave.array())
        val audio = NativeAudioEngineAdapter(appContext = context)
        val json = DefaultCoreClient.DefaultJson
        fun config(quality: String, phase: String) = CoreEqConfigView(bitPerfect = true,
            dsp = CoreDspConfig(outputStage = CoreDspOutputStage(targetSampleRate = 48000,
                resamplerQuality = quality, resamplerPhase = phase)))
        try {
            audio.setDspConfig(json.encodeToString(config("high", "minimum")))
            audio.load(AudioSourceDescriptor(source.absolutePath, source.absolutePath))
            audio.play(null)
            withTimeout(10000) { while (audio.getSnapshot().positionMs < 250) delay(25) }
            audio.pause(); audio.seek(250)
            for ((quality, phase, effective) in listOf(
                Triple("high", "minimum", "minimum"),
                Triple("ultra", "minimum-smooth", "minimum-smooth"),
                Triple("native", "minimum-smooth", "minimum"),
                Triple("native", "linear-smooth", "linear"),
                Triple("ultra", "linear", "linear"))) {
                audio.setDspConfig(json.encodeToString(config(quality, phase)))
                assertEquals("paused", audio.getSnapshot().state)
                assertEquals(250.0, audio.getSnapshot().positionMs.toDouble(), 3.0)
                audio.play(null)
                withTimeout(10000) {
                    while (audio.getDspStatus().outputStage["effectiveResamplerQuality"]?.jsonPrimitive?.content != quality) delay(25)
                }
                val stage = audio.getDspStatus().outputStage
                assertEquals(if (phase.startsWith("minimum")) "soxr" else "swr", stage["effectiveResampler"]?.jsonPrimitive?.content)
                assertEquals(effective, stage["effectiveResamplerPhase"]?.jsonPrimitive?.content)
                assertEquals(phase != effective, stage["effectiveResamplerPhaseFallback"]?.jsonPrimitive?.boolean)
                audio.pause(); audio.seek(250)
            }
            audio.stop()
            val stopped = audio.getDspStatus().outputStage
            assertEquals(JsonNull, stopped["effectiveResamplerPhase"])
            assertEquals(false, stopped["effectiveResamplerPhaseFallback"]?.jsonPrimitive?.boolean)
        } finally { audio.close(); source.delete() }
    }
}
