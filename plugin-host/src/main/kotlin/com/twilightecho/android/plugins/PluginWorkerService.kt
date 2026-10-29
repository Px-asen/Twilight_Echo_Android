package com.twilightecho.android.plugins

import android.app.Service
import android.content.*
import android.os.*
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.*
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

interface PluginHostCallback { fun call(method: String, args: String): String }

/**
 * Binder transactions share about 1 MB per process and Parcel strings are UTF-16, so scripts, inputs, results and
 * host-call payloads cross in slices of this many chars.
 */
internal const val SLICE_CHARS = 64 * 1024
internal const val MAX_TRANSFER_CHARS = 12 * 1024 * 1024
private const val MAX_SCRIPT_CHARS = 4 * 1024 * 1024
private const val MAX_ENGINES = 4
private const val ENGINE_MEMORY_BUDGET = 64L * 1024 * 1024

// Worker transactions. EXECUTE replies (status, first slice, more, result key); READ replies (slice, more).
private const val EXECUTE = 1
private const val APPEND = 2
private const val READ = 3
// Host-call transactions on the app's callback binder. CALL and READ reply (slice, more).
private const val HOST_CALL = 1
private const val HOST_APPEND = 2
private const val HOST_READ = 3
private const val STATUS_OK = 0
/** The worker unloaded this plugin's engine (memory or LRU); the app activates it again. */
private const val STATUS_NOT_LOADED = 1

/** Where the slice starting at [start] ends; a surrogate pair is never split. */
internal fun sliceEnd(text: String, start: Int): Int {
    var end = minOf(text.length, start + SLICE_CHARS)
    if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1])) end--
    return end
}

/** A string handed out one slice per transaction. */
private class Outgoing(private val text: String) {
    private var offset = 0
    val done get() = offset >= text.length
    fun next(reply: Parcel) {
        val end = sliceEnd(text, offset)
        reply.writeString(text.substring(offset, end)); reply.writeInt(if (end < text.length) 1 else 0)
        offset = end
    }
}

/** A string assembled from slices; it stops growing past [MAX_TRANSFER_CHARS] and reports that when read. */
private class Incoming {
    private val text = StringBuilder()
    private var overflow = false
    fun append(slice: String) { if (text.length + slice.length > MAX_TRANSFER_CHARS) overflow = true else text.append(slice) }
    fun take(last: String = ""): String {
        append(last)
        check(!overflow) { "插件数据过大" }
        return text.toString().also { text.setLength(0) }
    }
}

private inline fun <T> transaction(binder: IBinder, code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
    val request = Parcel.obtain(); val response = Parcel.obtain()
    try {
        request.write()
        if (!binder.transact(code, request, response, 0)) throw RemoteException("插件进程已退出，请重试")
        return response.read()
    } finally { request.recycle(); response.recycle() }
}

/** Reads the rest of a sliced reply whose first slice is [first]. */
private inline fun collect(first: String, more: Boolean, next: () -> Pair<String, Boolean>): String {
    if (!more) return first
    val text = StringBuilder(first)
    while (true) {
        val (slice, again) = next()
        text.append(slice)
        check(text.length <= MAX_TRANSFER_CHARS) { "插件数据过大" }
        if (!again) return text.toString()
    }
}

private fun errorEnvelope(message: String) = """{"ok":false,"error":${org.json.JSONObject.quote(message)}}"""

/** No network, files or application UID. A stuck script can only kill this worker. */
class PluginWorkerService : Service() {
    /** QuickJS runs on the caller's native stack; bundled crypto and parsers recurse deeply. */
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(null, task, "plugin-js", 8L * 1024 * 1024) }
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private class Engine(val js: QuickJs, val v2: Boolean)
    /** Access-ordered, so memory pressure unloads the least recently used plugin first. Executor thread only. */
    private val engines = LinkedHashMap<String, Engine>(8, 0.75f, true)
    /** The app binder of the invocation running now: every engine's host calls go to it. Executor thread only. */
    private var host: IBinder? = null
    private val uploads = HashMap<String, Incoming>()
    private val results = HashMap<String, Outgoing>()
    private val inFlight = AtomicInteger()

    override fun onCreate() {
        super.onCreate()
        watchdog.scheduleAtFixedRate({
            if (usedMemory() > 96L * 1024 * 1024) Process.killProcess(Process.myPid())
        }, 250, 250, TimeUnit.MILLISECONDS)
    }
    private fun usedMemory() = Debug.getNativeHeapAllocatedSize() + Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                APPEND -> {
                    val key = data.readString()!!; val slice = data.readString()!!
                    synchronized(uploads) { uploads.getOrPut(key) { Incoming() }.append(slice) }
                }
                READ -> {
                    val key = data.readString()!!
                    val outgoing = synchronized(results) { results[key] }
                    if (outgoing == null) { reply?.writeString(""); reply?.writeInt(0) }
                    else synchronized(outgoing) { reply?.let(outgoing::next); if (outgoing.done) synchronized(results) { results.remove(key) } }
                }
                EXECUTE -> reply?.let { execute(data, it) }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    private fun execute(data: Parcel, reply: Parcel) {
        val id = data.readString()!!
        val scriptKey = data.readString(); val inputKey = data.readString(); val inline = data.readString()
        val callback = data.readStrongBinder()
        inFlight.incrementAndGet()
        val timeout = watchdog.schedule({ Process.killProcess(Process.myPid()) }, 60, TimeUnit.SECONDS)
        var status = STATUS_OK
        val result = try {
            val (script, input) = synchronized(uploads) {
                // The app runs one invocation at a time; anything else still here was abandoned.
                (scriptKey?.let { uploads[it]?.take() } to (inputKey?.let { uploads[it]?.take() } ?: inline)).also { uploads.clear() }
            }
            synchronized(results) { results.clear() }
            check(input != null && (scriptKey == null || script != null)) { "插件数据传输不完整" }
            executor.submit<String?> { run(id, script, input, callback) }.get() ?: "".also { status = STATUS_NOT_LOADED }
        } catch (error: Throwable) {
            errorEnvelope((if (error is java.util.concurrent.ExecutionException) error.cause else error)?.message ?: "插件运行失败")
        } finally { timeout.cancel(false); inFlight.decrementAndGet() }
        val outgoing = Outgoing(result)
        reply.writeInt(status)
        outgoing.next(reply)
        reply.writeString(if (outgoing.done) null else UUID.randomUUID().toString().also { synchronized(results) { results[it] = outgoing } })
    }

    /** Returns null when [id] has no engine and the request carried no script to load. */
    private fun run(id: String, script: String?, input: String, callback: IBinder): String? {
        if (input == "close") { engines.remove(id)?.js?.close(); return """{"ok":true,"value":null}""" }
        val engine = if (script != null) load(id, script) else engines[id] ?: return null
        host = callback
        try {
            val quoted = org.json.JSONObject.quote(input)
            return if (engine.v2) engine.js.evaluate("__TWILIGHT_PLUGIN__.invoke($quoted)", "<plugin-call>") as String
            // ABI v1 bundles use serial host IO + lowered async, not native Promise jobs.
            else engine.js.evaluate("""
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
        } finally { host = null }
    }

    private fun load(id: String, script: String): Engine {
        require(script.length <= MAX_SCRIPT_CHARS) { "插件脚本过大" }
        engines.remove(id)?.js?.close()
        fun unloadOthers(over: () -> Boolean) {
            val iterator = engines.entries.iterator()
            while (iterator.hasNext() && over()) { iterator.next().value.js.close(); iterator.remove() }
        }
        unloadOthers { engines.size >= MAX_ENGINES || usedMemory() > ENGINE_MEMORY_BUDGET }
        val js = QuickJs.create()
        try {
            js.set("__PLUGIN_HOST__", PluginHostCallback::class.java, object : PluginHostCallback {
                override fun call(method: String, args: String) = hostCall(method, args)
            })
            js.evaluate(script, "$id/android.js")
            val v2 = js.evaluate("typeof __TWILIGHT_PLUGIN__==='object'&&__TWILIGHT_PLUGIN__!==null&&__TWILIGHT_PLUGIN__.apiVersion===2" +
                "&&typeof __TWILIGHT_PLUGIN__.invoke==='function'", "<abi>") == true
            unloadOthers { usedMemory() > ENGINE_MEMORY_BUDGET }
            return Engine(js, v2).also { engines[id] = it }
        } catch (error: Throwable) { js.close(); throw error }
    }

    /** `__PLUGIN_HOST__.call` from any engine. Failures come back as envelopes, as the SDK expects. */
    private fun hostCall(method: String, args: String): String {
        val callback = host ?: return errorEnvelope("插件宿主不可用")
        return try {
            var start = 0
            while (true) {
                val end = sliceEnd(args, start)
                if (end >= args.length) break
                transaction(callback, HOST_APPEND, { writeString(args.substring(start, end)) }) {}
                start = end
            }
            val (first, more) = transaction(callback, HOST_CALL, { writeString(method); writeString(args.substring(start)) }) { readString()!! to (readInt() == 1) }
            collect(first, more) { transaction(callback, HOST_READ, {}) { readString()!! to (readInt() == 1) } }
        } catch (error: Exception) { errorEnvelope(error.message ?: "插件宿主调用失败") }
    }

    override fun onDestroy() {
        if (inFlight.get() > 0) Process.killProcess(Process.myPid())
        executor.submit { engines.values.forEach { it.js.close() }; engines.clear() }
        executor.shutdown(); watchdog.shutdownNow(); super.onDestroy()
    }
}

/** The app side of one invocation's host calls; [handler] never throws. */
private class HostBinder(private val handler: (String, String) -> String) : Binder() {
    private val pending = Incoming()
    private var outgoing: Outgoing? = null
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            HOST_APPEND -> synchronized(this) { pending.append(data.readString()!!) }
            HOST_CALL -> {
                val method = data.readString()!!
                val args = synchronized(this) { runCatching { pending.take(data.readString()!!) } }
                val result = args.fold({ handler(method, it) }, { errorEnvelope(it.message ?: "插件数据过大") })
                synchronized(this) { Outgoing(result).also { outgoing = it }.let { out -> reply?.let(out::next) } }
            }
            HOST_READ -> synchronized(this) {
                val out = outgoing
                if (out == null) { reply?.writeString(""); reply?.writeInt(0) } else reply?.let(out::next)
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
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

    /**
     * Runs [input] in plugin [id]'s engine, loading [script] into a fresh engine first when given. Returns null when the
     * worker has unloaded the engine and the caller must activate the plugin again.
     */
    fun execute(id: String, script: String?, input: String, callback: (String, String) -> String): String? {
        val binder = binder ?: throw RemoteException("插件进程已退出，请重试")
        val scriptKey = script?.let { upload(binder, it) }
        val inputKey = if (input.length > SLICE_CHARS) upload(binder, input) else null
        val host = HostBinder(callback)
        val status = IntArray(1); var more = false; var resultKey: String? = null
        val first = transaction(binder, EXECUTE, {
            writeString(id); writeString(scriptKey); writeString(inputKey); writeString(if (inputKey == null) input else null)
            writeStrongBinder(host)
        }) { status[0] = readInt(); readString()!!.also { more = readInt() == 1; resultKey = readString() } }
        if (status[0] == STATUS_NOT_LOADED) return null
        val key = resultKey
        return collect(first, more && key != null) { transaction(binder, READ, { writeString(key) }) { readString()!! to (readInt() == 1) } }
    }
    private fun upload(binder: IBinder, text: String): String {
        val key = UUID.randomUUID().toString()
        var start = 0
        do {
            val end = sliceEnd(text, start)
            transaction(binder, APPEND, { writeString(key); writeString(text.substring(start, end)) }) {}
            start = end
        } while (start < text.length)
        return key
    }
    override fun close() { connection?.let { runCatching { context.unbindService(it) } }; binder = null; connection = null }
}
