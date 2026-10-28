package com.twilightecho.android.app

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.NcmPlaybackQuality
import com.twilightecho.android.playbackhost.ncmPlaybackArguments
import com.twilightecho.android.nativeintegration.NativeAudioEngineAdapter
import com.twilightecho.android.nativeintegration.ProviderStreamDataSource
import com.twilightecho.android.plugins.PluginWorkerService
import com.twilightecho.android.runtimehost.AudioSourceDescriptor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/** Controlled gateway/CDN bytes; original packaged NCM and real FFmpeg/DSP/AAudio execution. */
@RunWith(AndroidJUnit4::class)
class PluginAudioInteropTest {
    @Test fun isolatedNcmThroughRangeFfmpegDspSeekAndNext() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = CountDownLatch(1); lateinit var worker: IBinder
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { worker = service; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        instrumentation.runOnMainSync { check(context.bindService(Intent(context, PluginWorkerService::class.java), connection, Context.BIND_AUTO_CREATE)) }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        var childUid = -1
        val gatewayRequests = AtomicInteger()
        val qualityRequests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                childUid = getCallingUid()
                val method = data.readString(); val args = Json.parseToJsonElement(data.readString()!!).jsonObject
                val value: JsonElement = when (method) {
                    "settings.get" -> if (args["key"]?.jsonPrimitive?.content == "cookie") JsonPrimitive("MUSIC_U=fixture-not-a-real-account") else JsonNull
                    "ncm" -> {
                        gatewayRequests.incrementAndGet()
                        qualityRequests += args.getValue("data").jsonObject.getValue("level").jsonPrimitive.content
                        assertEquals("/api/song/enhance/player/url/v1", args.getValue("uri").jsonPrimitive.content)
                        assertEquals("xeapi", args.getValue("options").jsonObject.getValue("crypto").jsonPrimitive.content)
                        buildJsonObject {
                            put("status", 200); put("cookie", JsonArray(emptyList()))
                            put("body", buildJsonObject { put("code", 200); put("data", buildJsonArray {
                                add(buildJsonObject { put("id", 42); put("url", "https://m1.music.126.net/fixture.flac"); put("type", "flac"); put("level", "lossless"); put("expi", 1200) })
                            }) })
                        }
                    }
                    "sleep", "settings.set", "settings.delete" -> JsonNull
                    else -> error("Unexpected fixture host method $method")
                }
                reply?.writeString(buildJsonObject { put("ok", true); put("value", value) }.toString()); return true
            }
        }
        fun execute(script: String?, input: String): JsonElement {
            val request = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                request.writeString("interop-ncm"); request.writeString(script); request.writeString(input); request.writeStrongBinder(callback)
                check(worker.transact(1, request, reply, 0))
                val result = Json.parseToJsonElement(reply.readString()!!).jsonObject
                check(result["ok"]?.jsonPrimitive?.boolean == true) { result.toString() }
                return result["value"] ?: JsonNull
            } finally { request.recycle(); reply.recycle() }
        }
        val script = ZipInputStream(context.assets.open("plugins/ncm.tep")).use { zip ->
            var found: String? = null
            while (true) { val entry = zip.nextEntry ?: break; if (entry.name == "android.js") { found = zip.readBytes().toString(Charsets.UTF_8); break } }
            checkNotNull(found)
        }
        val bytes = instrumentation.context.assets.open("music/01-Golden-Hour.flac").use { it.readBytes() }
        val reads = AtomicInteger()
        val adapter = NativeAudioEngineAdapter(appContext = context, providerStream = {
            ProviderStreamDataSource(resolveUrl = {
                execute(null, "{\"method\":\"getPlaybackUrl\",\"args\":[{\"ncmSongId\":42},{\"quality\":\"standard\"}]}").jsonPrimitive.content
            }, openConnection = { uri -> FixtureCdn(uri.toURL(), bytes, reads) })
        })
        try {
            assertEquals("ncm", execute(script, "{\"operation\":\"activate\"}").jsonObject.getValue("id").jsonPrimitive.content)
            for (quality in NcmPlaybackQuality.entries) {
                val request = buildJsonObject { put("method", "getPlaybackUrl"); put("args", ncmPlaybackArguments("42", quality, true)) }
                assertEquals("https://m1.music.126.net/fixture.flac", execute(null, request.toString()).jsonPrimitive.content)
            }
            assertEquals(listOf("hires", "hires", "lossless", "exhigh", "standard"), qualityRequests.toList())
            assertTrue("Plugin callbacks must originate from a different Android UID", childUid != context.applicationInfo.uid && childUid > 0)
            adapter.loadQueue(listOf(CoreTrackView("provider://ncm/42", title = "Shared NCM", source = "provider://ncm/42", duration = 42.0),
                CoreTrackView("provider://ncm/43", title = "Next NCM", source = "provider://ncm/43", duration = 42.0)), 0)
            adapter.load(AudioSourceDescriptor("provider://ncm/42", "provider://ncm/42"))
            adapter.setDspConfig("{\"enabled\":true,\"preampDb\":-3.0,\"bandsDb\":[0,1,2,0,0,0,0,0,0,0]}")
            adapter.play(0.0)
            withTimeout(12_000) { while (adapter.getSnapshot().positionMs < 300) delay(50) }
            assertTrue(adapter.getSnapshot().dspActive)
            adapter.pause(); adapter.seek(12_000)
            assertTrue(adapter.getSnapshot().positionMs in 11_900..12_100)
            adapter.play(null); adapter.seek(41_500)
            withTimeout(15_000) { while (adapter.getSnapshot().queueIndex != 1 || adapter.getSnapshot().positionMs < 300) delay(50) }
            assertEquals("oboe", adapter.getSnapshot().outputBackend)
            assertTrue(reads.get() > 3); assertTrue(gatewayRequests.get() > 0)
        } finally { adapter.close(); context.unbindService(connection) }
    }

    private class FixtureCdn(url: URL, private val bytes: ByteArray, private val reads: AtomicInteger) : HttpURLConnection(url) {
        private val bounds get() = getRequestProperty("Range").removePrefix("bytes=").split('-').map(String::toInt)
        override fun getResponseCode(): Int = 206
        override fun getHeaderField(name: String): String? = when (name) {
            "Content-Range" -> "bytes ${bounds[0]}-${bounds[1]}/${bytes.size}"
            "ETag" -> "fixture-v1"
            else -> null
        }
        override fun getInputStream(): java.io.InputStream { reads.incrementAndGet(); return bytes.inputStream().also { it.skip(bounds[0].toLong()) } }
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun connect() {}
    }
}
