package com.twilightecho.android.runtimehost

import app.cash.quickjs.QuickJs
import java.io.Closeable

/**
 * Host bridge interface exposed inside the JavaScript global scope (`__TWILIGHT_HOST_BRIDGE__`)
 * so that `music-core.js` can invoke synchronous/asynchronous Host Ports and emit state snapshots.
 */
interface JsHostCallbackBridge {
    fun invokePort(portName: String, methodName: String, argsJson: String): String
    fun emitCoreEvent(eventJson: String)
}

/**
 * Abstraction over the embedded JavaScript engine (`QuickJs` on Android device/emulator,
 * or a deterministic fallback engine in unit tests where native `libquickjs.so` is absent).
 */
interface JsRuntimeEngine : Closeable {
    val isNativeQuickJsAvailable: Boolean
        get() = false
    fun bindHostBridge(bridge: JsHostCallbackBridge)
    fun evaluateScript(script: String, fileName: String = "music-core.js"): Any?
    fun invokeGlobalFunction(functionExpr: String, argJson: String): String
    fun drainMicrotasks()
}

/**
 * Production QuickJS implementation backed by `app.cash.quickjs:quickjs-android`.
 * Must be created, accessed, and closed exclusively on `CoreRuntimeThreadDispatcher`.
 */
class CashQuickJsRuntimeEngine(
    private val runtimeDispatcher: CoreRuntimeThreadDispatcher
) : JsRuntimeEngine {

    private var quickJs: QuickJs? = null
    private var hostBridge: JsHostCallbackBridge? = null
    private var scriptEvaluatedOk: Boolean = false

    init {
        runtimeDispatcher.checkOnRuntimeThread()
        quickJs = runCatching { QuickJs.create() }.getOrNull()
    }

    override val isNativeQuickJsAvailable: Boolean
        get() = quickJs != null && scriptEvaluatedOk

    override fun bindHostBridge(bridge: JsHostCallbackBridge) {
        runtimeDispatcher.checkOnRuntimeThread()
        hostBridge = bridge
        val engine = quickJs ?: return
        runCatching {
            engine.set("__TWILIGHT_HOST_BRIDGE__", JsHostCallbackBridge::class.java, bridge)
        }.onFailure { err ->
            runCatching { android.util.Log.e("CashQuickJsEngine", "Failed to bind host bridge", err) }
        }
    }

    override fun evaluateScript(script: String, fileName: String): Any? {
        runtimeDispatcher.checkOnRuntimeThread()
        val engine = quickJs ?: return null
        return runCatching {
            val res = engine.evaluate(script, fileName)
            scriptEvaluatedOk = true
            res
        }.onFailure { err ->
            scriptEvaluatedOk = false
            runCatching { android.util.Log.e("CashQuickJsEngine", "Failed to evaluate $fileName", err) }
        }.getOrNull()
    }

    override fun invokeGlobalFunction(functionExpr: String, argJson: String): String {
        runtimeDispatcher.checkOnRuntimeThread()
        val engine = quickJs ?: return "{}"
        if (!scriptEvaluatedOk) return "{}"
        val escapedArg = org.json.JSONObject.quote(argJson)
        val expression = "(function(a){var r=$functionExpr(a);return typeof r==='string'?r:JSON.stringify(r);})(JSON.parse($escapedArg))"
        return runCatching {
            val result = engine.evaluate(expression, "<host-call>")
            (result as? String) ?: "{}"
        }.onFailure { err ->
            runCatching { android.util.Log.e("CashQuickJsEngine", "Failed to invoke $functionExpr", err) }
        }.getOrDefault("{}")
    }

    override fun drainMicrotasks() {
        runtimeDispatcher.checkOnRuntimeThread()
        // CashApp QuickJS automatically drains pending jobs after evaluate calls.
    }

    override fun close() {
        runtimeDispatcher.checkOnRuntimeThread()
        quickJs?.close()
        quickJs = null
    }
}
