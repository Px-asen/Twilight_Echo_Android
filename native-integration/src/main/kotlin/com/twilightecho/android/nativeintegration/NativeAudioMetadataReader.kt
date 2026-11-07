package com.twilightecho.android.nativeintegration

import android.content.Context
import com.twilightecho.android.coreclient.AudioFileMetadata
import com.twilightecho.android.coreclient.DefaultCoreClient
import java.io.IOException

/** A lazy metadata-only engine: its SAF grants and cancellation are independent of playback. */
class NativeAudioMetadataReader(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val jni = TwilightAudioEngineJni()
    private val inputs = NativeAudioInputProvider { source ->
        if (source.startsWith("content://")) ContentAudioDataSource(appContext, source) else null
    }
    private var engine = 0L

    @Synchronized
    fun read(source: String): AudioFileMetadata {
        if (!jni.isNativeLibraryLoaded) throw IOException("当前版本未启用共享音频元数据读取")
        if (engine == 0L) {
            engine = jni.nativeCreateEngine()
            check(engine != 0L) { "无法创建音频元数据读取器" }
            check(jni.nativeSetInputProvider(engine, inputs) == 0)
        }
        val metadata = DefaultCoreClient.DefaultJson.decodeFromString<AudioFileMetadata>(jni.nativeGetMetadataJson(engine, source))
        if (metadata.error.isNotEmpty()) throw IOException(metadata.error)
        return metadata
    }

    override fun close() {
        inputs.cancelAll()
        synchronized(this) {
            if (engine != 0L) jni.nativeDestroyEngine(engine)
            engine = 0L
        }
    }
}
