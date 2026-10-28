package com.twilightecho.android.plugins

import android.app.Service
import android.content.*
import android.os.*
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.*
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

interface PluginHostCallback { fun call(method: String, args: String): String }

/** No network, files or application UID. A stuck script can only kill this worker. */
class PluginWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val engines = mutableMapOf<String, QuickJs>()
    private val inFlight = AtomicInteger()
    override fun onCreate() {
        super.onCreate()
        watchdog.scheduleAtFixedRate({
            val javaHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            if (Debug.getNativeHeapAllocatedSize() + javaHeap > 96L * 1024 * 1024) Process.killProcess(Process.myPid())
        }, 250, 250, TimeUnit.MILLISECONDS)
    }
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1) return super.onTransact(code, data, reply, flags)
            val id = data.readString()!!
            val script = data.readString()
            val input = data.readString()!!
            val callback = data.readStrongBinder()
            inFlight.incrementAndGet()
            val timeout = watchdog.schedule({ Process.killProcess(Process.myPid()) }, 60, TimeUnit.SECONDS)
            try {
                val result = executor.submit<String> {
                    if (input == "close") { engines.remove(id)?.close(); return@submit "{\"ok\":true,\"value\":null}" }
                    val engine = engines[id] ?: QuickJs.create().also { q ->
                        require(script != null && script.length <= 500_000) { "插件脚本过大" }
                        q.set("__PLUGIN_HOST__", PluginHostCallback::class.java, object : PluginHostCallback {
                            override fun call(method: String, args: String): String {
                                val request = Parcel.obtain(); val response = Parcel.obtain()
                                try {
                                    request.writeString(method); request.writeString(args)
                                    check(callback.transact(1, request, response, 0))
                                    return response.readString()!!
                                } finally { request.recycle(); response.recycle() }
                            }
                        })
                        try { q.evaluate(script, "$id/android.js"); engines[id] = q }
                        catch (error: Throwable) { q.close(); throw error }
                    }
                    // ABI v1 bundles use serial host IO + lowered async, not native Promise jobs.
                    val quoted = org.json.JSONObject.quote(input)
                    engine.evaluate("""
                        (function(){
                          var out; try {
                            var input=JSON.parse($quoted);
                            var result=input.operation==='activate'?__TWILIGHT_PLUGIN__.activate():__TWILIGHT_PLUGIN__.call(input);
                            Promise.resolve(result).then(function(v){out={ok:true,value:v===undefined?null:v}},function(e){out={ok:false,error:String(e.message||e)}});
                            if(!out) throw new Error('插件必须使用 Android serial ABI v1');
                          } catch(e){out={ok:false,error:String(e.message||e)}}
                          return JSON.stringify(out);
                        })()
                    """.trimIndent(), "<plugin-call>") as String
                }.get()
                require(result.length <= 500_000) { "插件返回值过大" }
                reply?.writeString(result)
            } catch (error: Throwable) {
                reply?.writeString(org.json.JSONObject().put("ok", false).put("error", error.cause?.message ?: error.message ?: "插件运行失败").toString())
            } finally { timeout.cancel(false); inFlight.decrementAndGet() }
            return true
        }
    }
    override fun onDestroy() {
        if (inFlight.get() > 0) Process.killProcess(Process.myPid())
        executor.submit { engines.values.forEach { it.close() }; engines.clear() }
        executor.shutdown(); watchdog.shutdownNow(); super.onDestroy()
    }
}

internal class PluginWorkerConnection(private val context: Context) : Closeable {
    private var binder: IBinder? = null
    private var connection: ServiceConnection? = null
    var generation = 0; private set
    suspend fun connect() {
        if (binder?.isBinderAlive == true) return
        withContext(Dispatchers.Main) {
            connection?.let { runCatching { context.unbindService(it) } }
            val ready = CompletableDeferred<IBinder>()
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; generation++; ready.complete(service) }
                override fun onServiceDisconnected(name: ComponentName) { binder = null }
                override fun onBindingDied(name: ComponentName) { binder = null; ready.completeExceptionally(IllegalStateException("插件进程已退出")) }
                override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("插件运行环境不可用")) }
            }
            connection = conn
            check(context.bindService(Intent(context, PluginWorkerService::class.java), conn, Context.BIND_AUTO_CREATE)) { "无法连接插件运行环境" }
            withTimeout(10_000) { ready.await() }
        }
    }
    fun execute(id: String, script: String?, input: String, callback: (String, String) -> String): String {
        val request = Parcel.obtain(); val response = Parcel.obtain()
        try {
            request.writeString(id); request.writeString(script); request.writeString(input)
            request.writeStrongBinder(object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    reply?.writeString(callback(data.readString()!!, data.readString()!!)); return true
                }
            })
            check(binder?.transact(1, request, response, 0) == true) { "插件进程已退出，请重试" }
            return response.readString()!!
        } finally { request.recycle(); response.recycle() }
    }
    override fun close() { connection?.let { runCatching { context.unbindService(it) } }; binder = null; connection = null }
}
