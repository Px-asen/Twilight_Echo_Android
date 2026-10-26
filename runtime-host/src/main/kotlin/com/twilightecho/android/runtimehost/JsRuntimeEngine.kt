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

    init {
        runtimeDispatcher.checkOnRuntimeThread()
        quickJs = runCatching { QuickJs.create() }.getOrNull()
    }

    val isNativeQuickJsAvailable: Boolean
        get() = quickJs != null

    override fun bindHostBridge(bridge: JsHostCallbackBridge) {
        runtimeDispatcher.checkOnRuntimeThread()
        hostBridge = bridge
        val engine = quickJs ?: return
        engine.set("__TWILIGHT_HOST_BRIDGE__", JsHostCallbackBridge::class.java, bridge)
    }

    override fun evaluateScript(script: String, fileName: String): Any? {
        runtimeDispatcher.checkOnRuntimeThread()
        val engine = quickJs ?: return null
        return engine.evaluate(script, fileName)
    }

    override fun invokeGlobalFunction(functionExpr: String, argJson: String): String {
        runtimeDispatcher.checkOnRuntimeThread()
        val engine = quickJs ?: return "{}"
        val escapedArg = org.json.JSONObject.quote(argJson)
        val expression = "JSON.stringify($functionExpr(JSON.parse($escapedArg)))"
        val result = engine.evaluate(expression, "<host-call>")
        return (result as? String) ?: "{}"
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
