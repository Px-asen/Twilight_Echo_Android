package com.twilightecho.android.runtimehost

import app.cash.quickjs.QuickJs
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable

/** The embedded Music Core could not be created, evaluated, booted or called. */
class PortableCoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * The embedded JavaScript engine running `music-core.js`.
 *
 * The bundle exposes `globalThis.__TWILIGHT_PORTABLE_CORE__`, whose entry points take a JSON
 * string and return JSON text. Nothing calls back into Kotlin from JavaScript: port calls,
 * responses and state events are pulled with `drain()`, so the engine is never re-entered.
 * Every failure throws; there is no fallback Core.
 */
interface JsRuntimeEngine : Closeable {
    fun evaluate(script: String, fileName: String)
    fun callCore(method: String, argJson: String): String
}

/**
 * Production QuickJS implementation backed by `app.cash.quickjs:quickjs-android`.
 * Must be created, accessed, and closed exclusively on `CoreRuntimeThreadDispatcher`.
 */
class CashQuickJsRuntimeEngine(
    private val runtimeDispatcher: CoreRuntimeThreadDispatcher
) : JsRuntimeEngine {

    private var quickJs: QuickJs? = run {
        runtimeDispatcher.checkOnRuntimeThread()
        try {
            QuickJs.create()
        } catch (t: Throwable) {
            throw PortableCoreException("QuickJS native runtime is unavailable on this device", t)
        }
    }

    override fun evaluate(script: String, fileName: String) {
        val engine = requireEngine()
        try {
            engine.evaluate(script, fileName)
        } catch (t: Throwable) {
            throw PortableCoreException("Failed to evaluate $fileName: ${t.message ?: t.javaClass.simpleName}", t)
        }
    }

    override fun callCore(method: String, argJson: String): String {
        require(CORE_METHOD.matches(method)) { "Invalid Core entry point: $method" }
        val engine = requireEngine()
        val expression = "globalThis.__TWILIGHT_PORTABLE_CORE__.$method(${JsonPrimitive(argJson)})"
        val result = try {
            engine.evaluate(expression, "<host-call:$method>")
        } catch (t: Throwable) {
            throw PortableCoreException("Core $method threw: ${t.message ?: t.javaClass.simpleName}", t)
        }
        return result as? String
            ?: throw PortableCoreException("Core $method returned ${result?.javaClass?.simpleName ?: "nothing"} instead of JSON text")
    }

    override fun close() {
        runtimeDispatcher.checkOnRuntimeThread()
        quickJs?.close()
        quickJs = null
    }

    private fun requireEngine(): QuickJs {
        runtimeDispatcher.checkOnRuntimeThread()
        return quickJs ?: throw PortableCoreException("QuickJS runtime is closed")
    }

    private companion object {
        val CORE_METHOD = Regex("[A-Za-z]+")
    }
}
