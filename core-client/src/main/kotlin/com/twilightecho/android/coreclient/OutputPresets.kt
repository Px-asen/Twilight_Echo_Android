package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private const val MAX_OUTPUT_PRESETS = 64

/**
 * An output as presets remember it: route class plus the product name the platform reports. The
 * connection-time device ID changes on every reconnect, so it only selects the current route. Two outputs
 * of one class with the same name are the same output to presets.
 */
@Serializable
data class OutputDeviceIdentity(val routeType: String, val name: String = "") {
    val key: String get() = "$routeType|$name"
    val label: String get() = name.ifBlank { outputRouteTypeLabel(routeType) }
}

fun outputRouteTypeLabel(routeType: String): String = when (routeType) {
    "usb_dac" -> "USB 解码器"
    "wired_headset" -> "有线耳机"
    "bluetooth" -> "蓝牙设备"
    "speaker" -> "手机扬声器"
    else -> "其他类型设备"
}

@Serializable
enum class OutputPresetScope { DEVICE, ROUTE_TYPE, OTHER }

/** A saved scene for an output. A device binding beats a route-type binding, which beats the catch-all. */
@Serializable
data class OutputPresetBinding(
    val scope: OutputPresetScope,
    val sceneId: String,
    /** Shown if the scene is deleted after binding. */
    val sceneName: String = "",
    val routeType: String = "",
    val deviceName: String = ""
) {
    /** Higher wins; null when this binding does not cover the output. */
    fun rank(output: OutputDeviceIdentity): Int? = when (scope) {
        OutputPresetScope.DEVICE -> 2.takeIf { routeType == output.routeType && deviceName == output.name }
        OutputPresetScope.ROUTE_TYPE -> 1.takeIf { routeType == output.routeType }
        OutputPresetScope.OTHER -> 0
    }

    /** A new binding replaces the old one for the same target. */
    val target: String get() = when (scope) {
        OutputPresetScope.DEVICE -> "device|$routeType|$deviceName"
        OutputPresetScope.ROUTE_TYPE -> "type|$routeType"
        OutputPresetScope.OTHER -> "other"
    }

    val targetLabel: String get() = when (scope) {
        OutputPresetScope.DEVICE -> deviceName.ifBlank { outputRouteTypeLabel(routeType) }
        OutputPresetScope.ROUTE_TYPE -> outputRouteTypeLabel(routeType).let { if (it.first().code < 128) "所有 $it" else "所有$it" }
        OutputPresetScope.OTHER -> "其他输出"
    }
}

@Serializable
data class OutputPresetPreferences(
    val autoSwitch: Boolean = true,
    val bindings: List<OutputPresetBinding> = emptyList(),
    /** The output whose preset was last handled; restarting on the same output keeps manual changes. */
    val lastOutputKey: String? = null
) {
    fun match(output: OutputDeviceIdentity): OutputPresetBinding? =
        bindings.mapNotNull { binding -> binding.rank(output)?.let { binding to it } }.maxByOrNull { it.second }?.first

    fun bind(binding: OutputPresetBinding) = copy(bindings = bindings.filterNot { it.target == binding.target } + binding)

    fun unbind(binding: OutputPresetBinding) = copy(bindings = bindings.filterNot { it.target == binding.target })

    fun normalized() = copy(bindings = bindings.asReversed().distinctBy { it.target }.asReversed().takeLast(MAX_OUTPUT_PRESETS)
        .map { it.copy(sceneName = it.sceneName.take(80)) })
}

/**
 * Applies a saved scene. The scene's output device choice was a connection-time ID, so the current
 * route's choice stays; everything else comes from the scene.
 */
fun CoreEqConfigView.withScene(scene: CoreDspScene): CoreEqConfigView {
    val saved = DefaultCoreClient.DefaultJson.decodeFromJsonElement(CoreEqConfigView.serializer(), scene.config)
    return saved.copy(scenes = scenes, dsp = saved.dsp.copy(outputDeviceId = dsp.outputDeviceId))
}

/** Whether the sound equals the scene's, ignoring the scene list and output device choice. */
fun CoreEqConfigView.matchesScene(scene: CoreDspScene): Boolean = try {
    soundSettings(this) == soundSettings(withScene(scene))
} catch (_: IllegalArgumentException) { false }

private fun soundSettings(config: CoreEqConfigView): Any? = canonical(DefaultCoreClient.DefaultJson.encodeToJsonElement(
    CoreEqConfigView.serializer(), config.copy(scenes = emptyList(), dsp = config.dsp.copy(outputDeviceId = ""))))

/** Numbers compare by value: Core may print a stored 2.0 as 2. */
private fun canonical(element: JsonElement): Any? = when (element) {
    is JsonObject -> element.mapValues { canonical(it.value) }
    is JsonArray -> element.map(::canonical)
    JsonNull -> null
    is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.doubleOrNull ?: element.content
}

data class OutputPresetStatus(
    /** The output the phone is playing through; null while nothing plays. */
    val output: OutputDeviceIdentity? = null,
    val binding: OutputPresetBinding? = null,
    /** Set once this output's preset applied, so a later difference reads as a manual change. */
    val appliedSceneId: String? = null,
    val failure: String? = null
)

/**
 * Applies the bound scene when the phone's output changes:
 * - Only a different output, or one that reconnected, applies its preset. Pausing, a format change or a
 *   restart on the same output does not, so manual changes stay until then or until restored.
 * - Binding a scene to the current output, or turning automatic switching on, applies it immediately.
 * - With no matching binding the current sound stays.
 * - A deleted scene or a rejected config leaves the sound unchanged and is reported; nothing retries
 *   until the next output change or an explicit retry.
 */
class OutputPresetCoordinator(
    private val scope: CoroutineScope,
    private val preferences: StateFlow<UserPreferences>,
    private val updatePreferences: ((UserPreferences) -> UserPreferences) -> Unit,
    /** Shared with UI edits, so neither read-modify-write loses the other's change. */
    private val configMutex: Mutex,
    private val readConfig: suspend () -> CoreEqConfigView,
    private val writeConfig: suspend (CoreEqConfigView) -> Unit
) {
    private sealed interface Event {
        class Output(val resolve: () -> OutputDeviceIdentity?) : Event
        class Removed(val keys: Set<String>) : Event
        class Settings(val previous: OutputPresetPreferences, val current: OutputPresetPreferences) : Event
        data object Retry : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(OutputPresetStatus())
    val status: StateFlow<OutputPresetStatus> = mutable.asStateFlow()
    /** The output the applied and failure fields describe; survives pauses, not reconnects. */
    private var sessionKey: String? = null

    /** [resolve] runs in event order, after earlier changes were handled. */
    fun outputChanged(resolve: () -> OutputDeviceIdentity?) { events.trySend(Event.Output(resolve)) }

    fun outputsRemoved(outputs: Collection<OutputDeviceIdentity>) {
        if (outputs.isNotEmpty()) events.trySend(Event.Removed(outputs.mapTo(HashSet()) { it.key }))
    }

    /** Applies the current output's preset again, replacing manual changes or retrying a failure. */
    fun reapply() { events.trySend(Event.Retry) }

    fun start(): Job = scope.launch {
        launch {
            var previous = preferences.value.outputPresets
            preferences.collect { value ->
                val current = value.outputPresets
                if (current.autoSwitch != previous.autoSwitch || current.bindings != previous.bindings) {
                    events.send(Event.Settings(previous, current))
                }
                previous = current
            }
        }
        for (event in events) handle(event)
    }

    private suspend fun handle(event: Event) {
        val settings = preferences.value.outputPresets
        when (event) {
            is Event.Output -> {
                val output = event.resolve()
                if (output != null && output.key != sessionKey) {
                    sessionKey = output.key
                    mutable.update { it.copy(appliedSceneId = null, failure = null) }
                }
                mutable.update { it.copy(output = output, binding = output?.let(settings::match)) }
                if (output != null && settings.autoSwitch && output.key != settings.lastOutputKey) {
                    remember(output.key)
                    apply(settings.match(output))
                }
            }
            is Event.Removed -> {
                if (sessionKey in event.keys) sessionKey = null
                if (settings.lastOutputKey in event.keys) remember(null)
            }
            is Event.Settings -> {
                val output = mutable.value.output
                val next = output?.let(event.current::match)
                mutable.update { it.copy(binding = next) }
                when {
                    !event.current.autoSwitch -> Unit
                    !event.previous.autoSwitch -> {
                        // Idle: the next output applies its preset even if it was the last one handled.
                        remember(output?.key)
                        if (output != null) apply(next)
                    }
                    output != null && next != null && next.sceneId != event.previous.match(output)?.sceneId -> {
                        remember(output.key)
                        apply(next)
                    }
                }
            }
            Event.Retry -> mutable.value.output?.let { apply(settings.match(it)) }
        }
    }

    private fun remember(key: String?) {
        if (preferences.value.outputPresets.lastOutputKey != key) {
            updatePreferences { it.copy(outputPresets = it.outputPresets.copy(lastOutputKey = key)) }
        }
    }

    private suspend fun apply(binding: OutputPresetBinding?) {
        if (binding == null) return
        var name = binding.sceneName
        val failure = try {
            configMutex.withLock {
                val current = readConfig()
                val scene = current.scenes.firstOrNull { it.id == binding.sceneId }
                if (scene == null) "场景“$name”已删除，声音保持不变。" else {
                    name = scene.name
                    writeConfig(current.withScene(scene))
                    null
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            "未能应用场景“$name”，声音保持不变。"
        }
        mutable.update { it.copy(appliedSceneId = binding.sceneId.takeIf { failure == null }, failure = failure) }
    }
}
