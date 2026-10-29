package com.twilightecho.android.coreclient

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** A file the phone cannot read as scenes at all; problems with single scenes are reported per scene. */
class DspPackageException(message: String) : IllegalArgumentException(message)

/** An impulse response a phone scene uses, described the way the desktop asset library stores it. */
data class DspImpulseFile(val sha256: String, val byteSize: Long, val sampleRate: Int? = null, val channels: Int? = null) {
    val assetId: String get() = "impulseResponse:$sha256"
    /** The desktop's `assetArchivePath`: kind folder, the ID with unsafe characters replaced, and the extension. */
    val archivePath: String get() = "assets/impulseResponse/impulseResponse_$sha256.wav"
}

class DspPackageExport(
    val manifest: JsonObject,
    /** Archive path to the phone file it is copied from. */
    val files: Map<String, File>,
    val sceneCount: Int,
    val notes: List<String>
)

/** An asset a package lists; scenes only use the ones the caller extracted and verified. */
data class DspPackageAsset(val id: String, val kind: String, val name: String, val sha256: String, val archivePath: String)

class ImportedDspScene(
    val scene: CoreDspScene,
    /** What differs from the source scene, such as modules the phone cannot run. */
    val notes: List<String>,
    /** Package asset IDs whose files this scene's convolver uses. */
    val impulseAssetIds: Set<String>
)

data class DspSceneRejection(val name: String, val reasons: List<String>)

class DspPackageImport(val scenes: List<ImportedDspScene>, val rejected: List<DspSceneRejection>)

class DspSceneMerge(
    val scenes: List<CoreDspScene>,
    val added: List<CoreDspScene>,
    /** The versions that imported scenes with the same ID replaced, kept for undo. */
    val replaced: List<CoreDspScene>,
    val unchanged: Int,
    /** Left out because the phone keeps at most [DspScenePackage.MAX_SCENES] scenes. */
    val overflow: List<CoreDspScene>
) {
    /** Removes what the import added and restores what it replaced; other scene edits since then stay. */
    fun undo(current: List<CoreDspScene>): List<CoreDspScene> {
        val addedIds = added.mapTo(HashSet()) { it.id }
        val previous = replaced.associateBy { it.id }
        return current.filterNot { it.id in addedIds }.map { previous[it.id] ?: it }
    }
}

/**
 * Phone scenes as a desktop DSP profile (`.tedsp`, the desktop's `dspProfileArchive.ts`). Each scene's graph
 * carries the sound in the desktop's terms; phone settings with no place in a graph ride in a per-scene
 * `android` object, which the desktop drops. Import reads `.tedsp` manifests and the same JSON as a profile,
 * a scene list, one scene, or a desktop settings backup. Nothing is clamped silently: a scene whose EQ the
 * phone cannot hold is rejected with the parameters, and anything left out of an accepted scene is noted.
 */
object DspScenePackage {
    const val KIND = "twilight-echo-dsp-profile"
    const val FILE_EXTENSION = "tedsp"
    const val MAX_SCENES = 32
    private const val ANDROID = "android"
    private const val PHONE_EQ_ID = "phone-eq"
    private const val MAX_NODES = 14
    /** Phone graphic sliders, and the range Core keeps for them. */
    private const val GRAPHIC_GAIN_DB = 12.0
    /** Both cores store Q up to 30 and the shared biquads clamp it to [EqLimits.MAX_Q], so the sound matches. */
    private const val STORED_MAX_Q = 30.0
    private const val ISSUES_PER_SCENE = 4
    private val NODE_TYPES = setOf("replayGain", "dynamicEqualizer", "convolver", "crossfeed", "channelMatrix", "channelStrip",
        "bassManagement", "gate", "compressor", "multibandCompressor", "stereoField", "loudnessContour", "truePeakLimiter", "meter")
    private val FILTER_TYPES = setOf("peak", "lowShelf", "highShelf", "lowPass", "highPass", "bandPass", "allPass", "notch")
    /** Modules whose place relative to the EQ does not change the sound. */
    private val ORDER_FREE = setOf("replayGain", "meter")
    private val FLAT = List(10) { 0.0 }
    private val json get() = DefaultCoreClient.DefaultJson

    fun export(
        config: CoreEqConfigView,
        describeImpulse: (String) -> DspImpulseFile?,
        now: Instant = Instant.now(),
        profileId: String = "dsp-profile-${UUID.randomUUID()}"
    ): DspPackageExport {
        val notes = mutableListOf<String>()
        val described = HashMap<String, DspImpulseFile?>()
        val assets = LinkedHashMap<String, Triple<DspImpulseFile, File, String>>()
        val references = HashMap<String, Int>()
        val exported = config.scenes.mapNotNull { scene ->
            val saved = try {
                CoreEqConfigView().withScene(scene)
            } catch (_: IllegalArgumentException) {
                notes += "“${scene.name}”无法读取，未导出"
                return@mapNotNull null
            }
            if (!saved.dsp.enabled) notes += "“${scene.name}”保存时处于旁路；电脑没有旁路，会按模块设置处理"
            val nodes = saved.dsp.nodes.map { node ->
                val path = node.params.string("impulseResponsePath")
                // A phone path means nothing on the computer, which would refuse the whole package for it.
                val params = node.params - "impulseResponsePath"
                if (node.type != "convolver" || path.isNullOrBlank()) return@map node.copy(params = JsonObject(params))
                val file = described.getOrPut(path) { describeImpulse(path) }
                if (file == null) {
                    notes += "“${scene.name}”的脉冲响应文件已不存在，未包含"
                    node.copy(params = JsonObject(params))
                } else {
                    assets.getOrPut(file.assetId) { Triple(file, File(path), "${scene.name} 脉冲响应") }
                    references[file.assetId] = (references[file.assetId] ?: 0) + 1
                    node.copy(params = JsonObject(params + ("impulseResponseAssetId" to JsonPrimitive(file.assetId))))
                }
            }
            scene to desktopScene(scene, saved, nodes)
        }
        val stamp = now.toString()
        val pinned = exported.firstOrNull { (scene, _) -> config.matchesScene(scene) }?.first?.id
        val manifest = buildJsonObject {
            put("kind", KIND)
            put("schemaVersion", 1)
            put("profile", buildJsonObject {
                put("schemaVersion", 1)
                put("id", profileId)
                put("name", "Twilight Echo 手机场景")
                put("createdAt", stamp)
                put("updatedAt", stamp)
                put("scenes", JsonArray(exported.map { it.second }))
                put("pinnedSceneId", pinned?.let(::JsonPrimitive) ?: JsonNull)
                put("assetIds", JsonArray(assets.keys.sorted().map(::JsonPrimitive)))
            })
            put("assets", JsonArray(assets.values.map { (file, _, name) ->
                buildJsonObject {
                    put("asset", buildJsonObject {
                        put("id", file.assetId)
                        put("kind", "impulseResponse")
                        put("name", name.take(120))
                        put("fileName", file.archivePath.substringAfterLast('/'))
                        put("sha256", file.sha256)
                        put("byteSize", file.byteSize)
                        put("mediaType", "audio/wav")
                        put("createdAt", stamp)
                        file.sampleRate?.let { put("sourceSampleRate", it) }
                        file.channels?.let { put("sourceChannels", it) }
                        put("referenceCount", references[file.assetId] ?: 0)
                    })
                    put("archivePath", file.archivePath)
                }
            }))
        }
        return DspPackageExport(manifest, assets.values.associate { (file, source, _) -> file.archivePath to source },
            exported.size, notes.distinct())
    }

    private fun desktopScene(scene: CoreDspScene, saved: CoreEqConfigView, nodes: List<CoreDspNode>) = buildJsonObject {
        val dsp = saved.dsp
        put("id", scene.id)
        put("name", scene.name)
        put("enabled", true)
        put("priority", 0)
        put("rules", JsonObject(emptyMap()))
        put("graph", buildJsonObject {
            put("version", 2)
            put("nodes", buildJsonArray {
                // Graphic mode keeps each band's Q in the shared engine, so the phone's Q 1.4 sliders sound the same.
                add(buildJsonObject {
                    put("id", PHONE_EQ_ID)
                    put("type", "equalizer")
                    put("enabled", saved.enabled)
                    put("params", buildJsonObject {
                        put("mode", dsp.eqMode)
                        put("preampDb", saved.preampDb)
                        put("bands", bandsJson(saved.effectiveEqBands()))
                    })
                })
                nodes.forEach { add(json.encodeToJsonElement(CoreDspNode.serializer(), it)) }
            })
            put("outputStage", buildJsonObject {
                val stage = dsp.outputStage
                if (stage.targetSampleRate > 0) put("targetSampleRate", stage.targetSampleRate) else put("targetSampleRate", "device")
                put("targetSampleRateMode", if (stage.targetSampleRate > 0) "fixed" else "device")
                put("resamplerQuality", stage.resamplerQuality)
                put("resamplerPhase", stage.resamplerPhase)
                put("dither", stage.dither)
                put("headroomEnabled", stage.headroomEnabled)
                put("headroomGainDb", stage.headroomGainDb)
                put("safetyClamp", stage.safetyClamp)
            })
        })
        put(ANDROID, buildJsonObject {
            put("schemaVersion", 1)
            put("dspEnabled", dsp.enabled)
            put("preset", saved.preset)
            // Both EQ layouts, so a phone that imports this keeps the one the scene was not using.
            put("bandsDb", JsonArray(saved.bandsDb.map(::JsonPrimitive)))
            put("parametricBands", bandsJson(dsp.parametricBands))
            put("bitPerfect", saved.bitPerfect)
            put("gapless", saved.gapless)
            put("crossfadeSeconds", dsp.crossfadeSeconds)
            put("crossfadeCurve", dsp.crossfadeCurve)
            put("crossfadeContent", dsp.crossfadeContent)
            put("exclusive", dsp.exclusive)
            put("bufferFrames", dsp.bufferFrames)
            put("continuitySampleRate", dsp.continuitySampleRate)
            put("sampleRateFamilySync", dsp.sampleRateFamilySync)
            put("allowedSampleRates", JsonArray(dsp.allowedSampleRates.map(::JsonPrimitive)))
            put("playbackRate", dsp.playbackRate)
        })
    }

    private fun bandsJson(bands: List<CoreParametricEqBand>) =
        JsonArray(bands.map { json.encodeToJsonElement(CoreParametricEqBand.serializer(), it) })

    /** The impulse responses a `.tedsp` manifest lists; empty for plain scene JSON. */
    fun assets(root: JsonElement): List<DspPackageAsset> {
        val manifest = (root as? JsonObject)?.takeIf { it.string("kind") == KIND } ?: return emptyList()
        return (manifest["assets"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val asset = (entry as? JsonObject)?.get("asset") as? JsonObject ?: return@mapNotNull null
            DspPackageAsset(asset.string("id") ?: return@mapNotNull null, asset.string("kind").orEmpty(), asset.string("name").orEmpty(),
                asset.string("sha256")?.lowercase() ?: return@mapNotNull null, (entry as JsonObject).string("archivePath") ?: return@mapNotNull null)
        }
    }

    /**
     * Converts every scene in [root]. [impulsePaths] maps verified package assets to the phone file each one
     * becomes; settings only the phone has come from the scene's `android` object, otherwise from [base].
     * [moduleName] labels module types in notes.
     */
    fun import(
        root: JsonElement,
        base: CoreEqConfigView,
        impulsePaths: Map<String, String> = emptyMap(),
        moduleName: (String) -> String = { it }
    ): DspPackageImport {
        val scenes = mutableListOf<ImportedDspScene>()
        val rejected = mutableListOf<DspSceneRejection>()
        val ids = HashSet<String>()
        sceneList(root).forEachIndexed { index, raw ->
            val name = raw.string("name")?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "导入场景 ${index + 1}"
            val id = raw.string("id")?.trim()?.take(128)?.takeIf { it.isNotEmpty() } ?: "scene-${index + 1}"
            if (!ids.add(id)) {
                rejected += DspSceneRejection(name, listOf("与前面的场景 ID 重复"))
                return@forEachIndexed
            }
            val issues = mutableListOf<String>()
            val scene = convertScene(raw, id, name, base, impulsePaths, moduleName, issues)
            if (scene != null) scenes += scene
            else rejected += DspSceneRejection(name, issues.take(ISSUES_PER_SCENE) +
                listOfNotNull("另有 ${issues.size - ISSUES_PER_SCENE} 处".takeIf { issues.size > ISSUES_PER_SCENE }))
        }
        return DspPackageImport(scenes, rejected)
    }

    private fun sceneList(root: JsonElement): List<JsonObject> {
        val list = when {
            root is JsonArray -> root
            root !is JsonObject -> null
            "kind" in root -> {
                if (root.string("kind") != KIND) throw DspPackageException("不是 Twilight Echo DSP 配置包")
                if (root.number("schemaVersion") != 1.0) throw DspPackageException("配置包版本不受支持")
                (root["profile"] as? JsonObject)?.get("scenes")
            }
            "graph" in root -> JsonArray(listOf(root))
            "dspScenes" in root -> root["dspScenes"]
            else -> root["scenes"]
        }
        val scenes = (list as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        if (scenes.isEmpty()) throw DspPackageException("文件中没有 DSP 场景")
        return scenes
    }

    private class EqPart(val enabled: Boolean, val preampDb: Double, val bands: List<CoreParametricEqBand>, val graphic: List<Double>?)

    private fun convertScene(
        raw: JsonObject, id: String, name: String, base: CoreEqConfigView, impulsePaths: Map<String, String>,
        moduleName: (String) -> String, issues: MutableList<String>
    ): ImportedDspScene? {
        val graph = raw["graph"] as? JsonObject ?: return null.also { issues += "缺少处理图" }
        val nodes = (graph["nodes"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val equalizers = nodes.filter { it.string("type") == "equalizer" }
        val eqNode = equalizers.firstOrNull { it.bool("enabled") != false } ?: equalizers.firstOrNull()
        val eq = eqNode?.let { equalizer(it, issues) }
        if (issues.isNotEmpty()) return null

        val notes = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        val overflow = mutableListOf<String>()
        val phoneNodes = mutableListOf<CoreDspNode>()
        val usedIds = hashSetOf(PHONE_EQ_ID)
        val impulseIds = HashSet<String>()
        val eqIndex = nodes.indexOf(eqNode)
        var reordered = false
        nodes.forEachIndexed { index, node ->
            if (node === eqNode) return@forEachIndexed
            val type = node.string("type").orEmpty()
            when {
                type == "equalizer" -> duplicates += "均衡器"
                type !in NODE_TYPES -> unsupported += when (type) {
                    "vst3Plugin" -> "VST3 插件"
                    "nativePlugin" -> "原生插件"
                    else -> moduleName(type)
                }
                phoneNodes.any { it.type == type } -> duplicates += moduleName(type)
                phoneNodes.size >= MAX_NODES -> overflow += moduleName(type)
                else -> {
                    val phoneNode = phoneNode(node, type, index, usedIds, impulsePaths, impulseIds, notes, moduleName)
                    if (index < eqIndex && phoneNode.enabled && eq?.enabled == true && type !in ORDER_FREE) reordered = true
                    phoneNodes += phoneNode
                }
            }
        }
        if (unsupported.isNotEmpty()) notes += "手机不支持，未导入：${unsupported.distinct().joinToString("、")}"
        if (duplicates.isNotEmpty()) notes += "同类模块只保留第一个，未导入：${duplicates.joinToString("、")}"
        if (overflow.isNotEmpty()) notes += "手机最多 $MAX_NODES 个模块，未导入：${overflow.joinToString("、")}"
        if (reordered) notes += "手机上均衡器排在最前，处理顺序与原场景不同"
        val rules = raw["rules"] as? JsonObject
        if (rules != null && rules.values.any { it !is JsonNull && !(it is JsonArray && it.isEmpty()) }) {
            notes += "电脑上的自动匹配条件未导入，可在“设备预设”中绑定输出"
        }
        val stage = outputStage(graph["outputStage"] as? JsonObject, notes)

        val phone = raw[ANDROID] as? JsonObject
        val savedGraphic = (phone?.get("bandsDb") as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: Double.NaN }
            ?.takeIf { gains -> gains.size == 10 && gains.all { it.isFinite() && abs(it) <= GRAPHIC_GAIN_DB } }
        val savedBands = (phone?.get("parametricBands") as? JsonArray)?.let { array ->
            val bandIssues = mutableListOf<String>()
            array.mapIndexedNotNull { index, band -> band(band, index + 1, graphicMode = false, bandIssues) }
                .takeIf { bandIssues.isEmpty() && array.size <= EqLimits.MAX_PARAMETRIC_BANDS }
        }
        val graphic = eq?.graphic ?: savedGraphic ?: FLAT
        val defaults = base.dsp
        val familySync = phone?.bool("sampleRateFamilySync") ?: false
        val rawRates = phone?.get("allowedSampleRates")
        val allowedRates = (rawRates as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        if ((phone != null && "sampleRateFamilySync" in phone && phone.bool("sampleRateFamilySync") == null) ||
            (rawRates != null && (rawRates !is JsonArray || allowedRates.size != rawRates.size)) ||
            allowedRates.size > 32 || allowedRates.any { it !in 8000..384000 } || allowedRates.distinct().size != allowedRates.size) {
            issues += "采样率策略或允许集合无效"
            return null
        }
        val sourceFirst = phone?.bool("bitPerfect") ?: base.bitPerfect
        if (familySync && (!sourceFirst || stage.targetSampleRate != 0)) {
            issues += "家族同步不能与固定采样率或连续播放策略同时启用"
            return null
        }
        if (sourceFirst && stage.targetSampleRate > 0 && allowedRates.isNotEmpty() && stage.targetSampleRate !in allowedRates) {
            issues += "固定采样率不在允许集合中"
            return null
        }
        val config = CoreEqConfigView(
            enabled = eq?.enabled ?: false,
            preset = phone?.string("preset")?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
                ?: CoreEqConfigView.PRESETS.entries.firstOrNull { it.value == graphic }?.key ?: "Custom",
            preampDb = eq?.preampDb ?: 0.0,
            bandsDb = graphic,
            bitPerfect = sourceFirst,
            gapless = phone?.bool("gapless") ?: base.gapless,
            dsp = CoreDspConfig(
                enabled = phone?.bool("dspEnabled") ?: true,
                eqMode = if (eq == null || eq.graphic != null) "graphic" else "parametric",
                parametricBands = eq?.takeIf { it.graphic == null }?.bands ?: savedBands ?: CoreDspConfig().parametricBands,
                nodes = phoneNodes,
                outputStage = stage,
                crossfadeSeconds = phone?.number("crossfadeSeconds")?.takeIf { it in 0.0..12.0 } ?: defaults.crossfadeSeconds,
                crossfadeCurve = phone?.string("crossfadeCurve")?.takeIf { it in setOf("linear", "equal-power") } ?: defaults.crossfadeCurve,
                crossfadeContent = phone?.string("crossfadeContent")?.takeIf { it in setOf("conservative", "all", "live") }
                    ?: defaults.crossfadeContent,
                // A scene never carries an output choice; applying it keeps the current route's.
                outputDeviceId = "auto",
                exclusive = phone?.bool("exclusive") ?: defaults.exclusive,
                bufferFrames = phone?.number("bufferFrames")?.takeIf { it in 0.0..32768.0 && it == Math.floor(it) }?.toInt()
                    ?: defaults.bufferFrames,
                continuitySampleRate = phone?.number("continuitySampleRate")?.takeIf { it in setOf(44100.0, 48000.0, 96000.0) }?.toInt()
                    ?: defaults.continuitySampleRate,
                playbackRate = phone?.number("playbackRate")?.takeIf { it in 0.5..2.0 } ?: defaults.playbackRate,
                sampleRateFamilySync = familySync,
                allowedSampleRates = allowedRates.sorted()
            )
        )
        val sceneConfig = JsonObject(json.encodeToJsonElement(CoreEqConfigView.serializer(), config).jsonObject - "scenes")
        return ImportedDspScene(CoreDspScene(id, name, sceneConfig), notes, impulseIds)
    }

    private fun equalizer(node: JsonObject, issues: MutableList<String>): EqPart? {
        val params = node["params"] as? JsonObject ?: JsonObject(emptyMap())
        // The engine runs a graph EQ node without a mode as parametric, and graphic bands as peaks.
        val graphicMode = params.string("mode") == "graphic"
        val preamp = if ("preampDb" in params) params.number("preampDb") ?: Double.NaN else 0.0
        when {
            !preamp.isFinite() -> issues += "前级无效"
            abs(preamp) > EqLimits.PREAMP_DB -> issues += "前级 ${fmt(preamp)} dB 超出手机范围（±${fmt(EqLimits.PREAMP_DB)} dB）"
        }
        val rawBands = (params["bands"] as? JsonArray).orEmpty()
        if (rawBands.size > EqLimits.MAX_PARAMETRIC_BANDS) {
            issues += "均衡器有 ${rawBands.size} 段，手机最多 ${EqLimits.MAX_PARAMETRIC_BANDS} 段"
        }
        val bands = rawBands.mapIndexedNotNull { index, band -> band(band, index + 1, graphicMode, issues) }
        if (issues.isNotEmpty()) return null
        // An empty EQ or the phone's own ten sliders open as sliders; anything else as parametric bands.
        val graphic = when {
            bands.isEmpty() -> FLAT
            bands.size == 10 && bands.withIndex().all { (index, band) ->
                band.frequency == EqLimits.GRAPHIC_FREQUENCIES_HZ[index] && band.q == EqLimits.GRAPHIC_Q && band.filterType == "peak" &&
                    band.enabled && band.channelMask == EqLimits.ALL_CHANNELS && abs(band.gain) <= GRAPHIC_GAIN_DB
            } -> bands.map { it.gain }
            else -> null
        }
        return EqPart(node.bool("enabled") != false, preamp, bands, graphic)
    }

    private fun band(element: JsonElement, number: Int, graphicMode: Boolean, issues: MutableList<String>): CoreParametricEqBand? {
        val raw = element as? JsonObject ?: return null.also { issues += "第 $number 段格式无效" }
        val before = issues.size
        // Missing values take the engine's defaults; present ones must be numbers in the phone's range.
        fun value(key: String, label: String, default: Double, unit: String, range: ClosedFloatingPointRange<Double>, rangeText: String): Double {
            val value = if (key in raw) raw.number(key) ?: Double.NaN else default
            when {
                value.isNaN() && key !in raw -> issues += "第 $number 段缺少$label"
                !value.isFinite() -> issues += "第 $number 段${label}无效"
                value !in range -> issues += "第 $number 段$label ${fmt(value)}$unit 超出 $rangeText"
            }
            return value
        }
        val frequency = value("frequency", "频率", Double.NaN, " Hz", EqLimits.MIN_FREQUENCY_HZ..EqLimits.MAX_FREQUENCY_HZ,
            "${fmt(EqLimits.MIN_FREQUENCY_HZ)}–${fmt(EqLimits.MAX_FREQUENCY_HZ)} Hz")
        val gain = value("gain", "增益", 0.0, " dB", -EqLimits.GAIN_DB..EqLimits.GAIN_DB, "±${fmt(EqLimits.GAIN_DB)} dB")
        val q = value("q", " Q 值", 1.0, "", EqLimits.MIN_Q..STORED_MAX_Q, "${fmt(EqLimits.MIN_Q)}–${fmt(STORED_MAX_Q)}")
        val mask = if ("channelMask" in raw) raw.number("channelMask") ?: Double.NaN else EqLimits.ALL_CHANNELS.toDouble()
        val filterType = if (graphicMode) "peak" else raw.string("filterType") ?: "peak"
        if (filterType !in FILTER_TYPES) issues += "第 $number 段滤波器“$filterType”手机不支持"
        if (!mask.isFinite() || mask != Math.floor(mask) || mask !in 0.0..EqLimits.ALL_CHANNELS.toDouble()) issues += "第 $number 段声道设置无效"
        if (issues.size > before) return null
        return CoreParametricEqBand(frequency, gain, q, filterType, raw.bool("enabled") != false, mask.toLong())
    }

    private fun phoneNode(
        raw: JsonObject, type: String, index: Int, usedIds: MutableSet<String>, impulsePaths: Map<String, String>,
        impulseIds: MutableSet<String>, notes: MutableList<String>, moduleName: (String) -> String
    ): CoreDspNode {
        val wanted = raw.string("id")?.trim().orEmpty()
        val id = (sequenceOf(wanted, "phone-$type") + generateSequence(index + 1) { it + 1 }.map { "$type-$it" })
            .first { it.isNotEmpty() && it !in usedIds }
        usedIds += id
        val source = raw["params"] as? JsonObject ?: JsonObject(emptyMap())
        val params = source - "impulseResponsePath" - "impulseResponseAssetId"
        var enabled = raw.bool("enabled") != false
        if (type != "convolver") return CoreDspNode(id, type, enabled, JsonObject(params))
        val assetId = source.string("impulseResponseAssetId")
        val path = assetId?.let(impulsePaths::get)
        if (path != null) {
            impulseIds += assetId
            return CoreDspNode(id, type, enabled, JsonObject(params + ("impulseResponsePath" to JsonPrimitive(path))))
        }
        if (enabled || assetId != null || "impulseResponsePath" in source) {
            notes += "${moduleName(type)}没有随文件提供的 WAV 脉冲响应，已关闭"
        }
        enabled = false
        return CoreDspNode(id, type, enabled, JsonObject(params))
    }

    private fun outputStage(raw: JsonObject?, notes: MutableList<String>): CoreDspOutputStage {
        val stage = raw ?: JsonObject(emptyMap())
        val mode = stage.string("targetSampleRateMode")
        val rate = stage.number("targetSampleRate")
        val target = when {
            mode == "max-pcm" || mode == "power-of-2" -> 0.also { notes += "输出采样率规则在手机上改为跟随设备" }
            mode == "device" || rate == null || !rate.isFinite() -> 0
            // The desktop keeps a fixed rate within these bounds too.
            else -> rate.toInt().coerceIn(8000, 768000)
        }
        val quality = when (val value = stage.string("resamplerQuality")) {
            "high", "ultra" -> value
            "soxrHq", "soxrVhq" -> "ultra".also { notes += "手机没有 SoX 重采样，改用内置最高质量" }
            else -> "native"
        }
        return CoreDspOutputStage(
            targetSampleRate = target,
            resamplerQuality = quality,
            resamplerPhase = stage.string("resamplerPhase")?.takeIf { it in setOf("linear", "minimum", "linear-smooth", "minimum-smooth") }
                ?: "linear",
            dither = stage.string("dither")?.takeIf { it in setOf("tpdf", "highpassTpdf", "noiseShaped") } ?: "off",
            headroomEnabled = stage.bool("headroomEnabled") ?: false,
            headroomGainDb = stage.number("headroomGainDb")?.takeIf { it.isFinite() }?.let { (it * 10).roundToInt() / 10.0 }
                ?.coerceIn(-30.0, 0.0) ?: -3.0,
            safetyClamp = stage.bool("safetyClamp") != false
        )
    }

    /**
     * Adds imported scenes to [current]: a scene with a known ID replaces that scene unless nothing changed,
     * a new one is appended under a unique name, and scenes past the limit are left out.
     */
    fun merge(current: List<CoreDspScene>, imported: List<CoreDspScene>): DspSceneMerge {
        val scenes = current.toMutableList()
        val added = mutableListOf<CoreDspScene>()
        val replaced = mutableListOf<CoreDspScene>()
        val overflow = mutableListOf<CoreDspScene>()
        var unchanged = 0
        imported.forEach { scene ->
            val index = scenes.indexOfFirst { it.id == scene.id }
            when {
                index >= 0 && scenes[index].name == scene.name && sameSound(scenes[index], scene) -> unchanged++
                index >= 0 -> {
                    replaced += scenes[index]
                    scenes[index] = scene
                }
                scenes.size >= MAX_SCENES -> overflow += scene
                else -> {
                    val named = scene.copy(name = uniqueName(scene.name, scenes))
                    scenes += named
                    added += named
                }
            }
        }
        return DspSceneMerge(scenes, added, replaced, unchanged, overflow)
    }

    private fun sameSound(a: CoreDspScene, b: CoreDspScene): Boolean = try {
        CoreEqConfigView().withScene(a).matchesScene(b)
    } catch (_: IllegalArgumentException) { false }

    private fun uniqueName(name: String, scenes: List<CoreDspScene>): String {
        val names = scenes.mapTo(HashSet()) { it.name }
        if (name !in names) return name
        return generateSequence(2) { it + 1 }.map { "${name.take(72)} ($it)" }.first { it !in names }
    }

    private fun fmt(value: Double) = EqualizerApoText.formatNumber(value)
}

/** The `.tedsp` container: a stored zip holding `manifest.json` and the files under `assets/`, within the desktop's limits. */
object DspPackageArchive {
    const val MANIFEST = "manifest.json"
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private const val MAX_ENTRIES = 1024
    /** Half the desktop's limit: the phone copies assets into its own storage. */
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    private val pretty = Json { prettyPrint = true }

    /** The manifest, or the scene JSON, and each asset extracted under the caller's temporary directory. */
    class Contents(val root: JsonElement, val files: Map<String, File>)

    /** Reads a `.tedsp` package or a scene JSON file. */
    fun read(input: InputStream, directory: File): Contents {
        val buffered = BufferedInputStream(input)
        buffered.mark(4)
        val magic = ByteArray(4)
        var filled = 0
        while (filled < magic.size) {
            val read = buffered.read(magic, filled, magic.size - filled)
            if (read < 0) break
            filled += read
        }
        buffered.reset()
        val zip = filled == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() && magic[2] == 3.toByte() && magic[3] == 4.toByte()
        return try {
            if (zip) readZip(ZipInputStream(buffered), directory) else Contents(parse(readLimited(buffered, MAX_MANIFEST_BYTES)), emptyMap())
        } catch (_: ZipException) {
            throw DspPackageException("无法读取配置包")
        }
    }

    private fun readZip(zip: ZipInputStream, directory: File): Contents {
        var manifest: ByteArray? = null
        val files = LinkedHashMap<String, File>()
        var entries = 0
        var total = 0L
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            if (name.isEmpty() || name.length > 4096 || '\\' in name || name.startsWith("/") || name.split('/').any { it == ".." }) {
                throw DspPackageException("配置包包含非法路径")
            }
            if (entry.isDirectory) continue
            if (++entries > MAX_ENTRIES) throw DspPackageException("配置包文件过多")
            when {
                name == MANIFEST -> manifest = readLimited(zip, MAX_MANIFEST_BYTES).also { total += it.size }
                name.startsWith("assets/") -> {
                    val file = File(directory, "asset-$entries.bin")
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_TOTAL_BYTES) throw DspPackageException("配置包解压后过大")
                            output.write(buffer, 0, read)
                        }
                    }
                    files[name] = file
                }
            }
        }
        return Contents(parse(manifest ?: throw DspPackageException("配置包缺少清单")), files)
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > limit) throw DspPackageException("文件过大")
        }
        return output.toByteArray()
    }

    private fun parse(bytes: ByteArray): JsonElement = try {
        DefaultCoreClient.DefaultJson.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("﻿"))
    } catch (_: SerializationException) {
        throw DspPackageException("文件不是有效的场景 JSON")
    }

    /** Writes the stored zip the desktop writes, manifest first. */
    fun write(output: OutputStream, manifest: JsonObject, files: Map<String, File>) {
        val zip = ZipOutputStream(output)
        val manifestBytes = pretty.encodeToString(JsonObject.serializer(), manifest).toByteArray()
        zip.putNextEntry(storedEntry(MANIFEST, manifestBytes.size.toLong(), CRC32().apply { update(manifestBytes) }.value))
        zip.write(manifestBytes)
        zip.closeEntry()
        val buffer = ByteArray(64 * 1024)
        files.forEach { (path, file) ->
            val crc = CRC32()
            var size = 0L
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                    size += read
                }
            }
            zip.putNextEntry(storedEntry(path, size, crc.value))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
    }

    private fun storedEntry(name: String, size: Long, crc: Long) = ZipEntry(name).apply {
        method = ZipEntry.STORED
        this.size = size
        compressedSize = size
        this.crc = crc
    }
}

/** Hash, size and format of a RIFF/WAVE impulse response; null once the file is gone or is not WAVE. */
fun describeImpulseFile(file: File): DspImpulseFile? {
    if (!file.isFile) return null
    val digest = MessageDigest.getInstance("SHA-256")
    val head = ByteArray(64 * 1024)
    var headSize = 0
    var size = 0L
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (headSize < head.size) {
                val copied = minOf(read, head.size - headSize)
                System.arraycopy(buffer, 0, head, headSize, copied)
                headSize += copied
            }
            digest.update(buffer, 0, read)
            size += read
        }
    }
    if (!isWave(head, headSize)) return null
    var channels: Int? = null
    var sampleRate: Int? = null
    var offset = 12L
    while (offset + 8 <= headSize) {
        val at = offset.toInt()
        val chunkSize = littleEndian(head, at + 4, 4)
        if (String(head, at, 4, Charsets.US_ASCII) == "fmt " && at + 16 <= headSize) {
            channels = littleEndian(head, at + 10, 2).toInt()
            sampleRate = littleEndian(head, at + 12, 4).toInt()
            break
        }
        offset += 8 + chunkSize + (chunkSize and 1L)
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    return DspImpulseFile(sha256, size, sampleRate?.takeIf { it > 0 }, channels?.takeIf { it > 0 })
}

/** The header the phone requires of impulse responses. */
fun isWave(head: ByteArray, size: Int = head.size): Boolean =
    size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE"

private fun littleEndian(bytes: ByteArray, offset: Int, length: Int): Long =
    (0 until length).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 0xFF) shl (8 * index)) }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
