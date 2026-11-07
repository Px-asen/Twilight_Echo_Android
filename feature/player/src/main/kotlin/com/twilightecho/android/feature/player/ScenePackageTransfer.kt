package com.twilightecho.android.feature.player

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.ui.components.SupportingText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Reads and writes `.tedsp` scene packages against the app's files; the conversion itself is [DspScenePackage]. */
object ScenePackageFiles {
    /** The folder [com.twilightecho.android.playbackhost.AndroidPlaybackHost.importImpulseResponse] keeps impulse responses in. */
    private const val IMPULSE_DIRECTORY = "dsp-impulses"

    fun export(context: Context, config: CoreEqConfigView, uri: Uri): DspPackageExport {
        val export = DspScenePackage.export(config, { path -> describeImpulseFile(File(path)) })
        val output = context.contentResolver.openOutputStream(uri) ?: throw DspPackageException("无法写入文件")
        output.use { DspPackageArchive.write(it, export.manifest, export.files) }
        return export
    }

    /**
     * Converts a package's scenes against [base]. WAV/FLAC/AIFF impulse responses count when their content
     * hash matches the manifest; the ones accepted scenes use are copied to phone storage by content.
     */
    fun import(context: Context, uri: Uri, base: CoreEqConfigView): DspPackageImport {
        val temporary = File(context.cacheDir, "scene-package-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw DspPackageException("无法读取文件")
            val contents = input.use { DspPackageArchive.read(it, temporary) }
            return DspPackageArchive.importScenes(contents, base, File(context.filesDir, IMPULSE_DIRECTORY), ::moduleTitle)
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun moduleTitle(type: String) = dspModules.firstOrNull { it.type == type }?.title ?: type
}

/** An import's outcome; [merge] lets one tap take back what it added or replaced. */
private class ScenePackageRecord(val summary: String, val details: List<String>, val merge: DspSceneMerge? = null)

/** `.tedsp` packages shared with the desktop app: export the saved scenes, import them back as scenes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScenePackageTransfer(config: CoreEqConfigView, actions: PlayerActions) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(config)
    var record by remember { mutableStateOf<ScenePackageRecord?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun show(next: ScenePackageRecord) {
        record = next
        expanded = false
        notice = null
    }
    suspend fun accept(imported: DspPackageImport) {
        val scenes = imported.scenes.map { it.scene }
        if (scenes.isEmpty()) return show(ScenePackageRecord("没有可导入的场景", importDetails(imported, null)))
        actions.commitAudioEdit { latest -> latest.copy(scenes = DspScenePackage.merge(latest.scenes, scenes).scenes) }
            .fold({ receipt ->
                val merge = DspScenePackage.merge(receipt.before.scenes, scenes)
                show(ScenePackageRecord(importSummary(imported, merge), importDetails(imported, merge), merge))
            }, { error -> notice = "未能确认导入：${error.message ?: "未收到提交结果"}" })
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            notice = null
            try {
                accept(withContext(Dispatchers.IO) { ScenePackageFiles.import(context, uri, current) })
            } catch (error: CancellationException) {
                throw error
            } catch (error: DspPackageException) {
                notice = "无法导入：${error.message}"
            } catch (_: Exception) {
                notice = "无法导入：文件读取失败"
            } finally {
                busy = false
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val snapshot = current
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { ScenePackageFiles.export(context, snapshot, uri) } }
            busy = false
            show(result.fold({ export ->
                val impulses = export.files.size.takeIf { it > 0 }?.let { "，含 $it 个脉冲响应" }.orEmpty()
                ScenePackageRecord("已导出 ${export.sceneCount} 个场景$impulses", export.notes)
            }, { ScenePackageRecord("导出失败", emptyList()) }))
        }
    }

    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniAction("导入配置包", Modifier.testTag("scene-package-import"), enabled = !busy) { open.launch(arrayOf("*/*")) }
        MiniAction("导出配置包", Modifier.testTag("scene-package-export"), enabled = !busy && config.scenes.isNotEmpty()) {
            save.launch("Twilight Echo 场景.${DspScenePackage.FILE_EXTENSION}")
        }
    }
    if (busy) SupportingText("正在处理配置包…", Modifier.padding(horizontal = 18.dp, vertical = 4.dp).testTag("scene-package-pending"))
    notice?.let { SupportingText(it, Modifier.padding(horizontal = 18.dp, vertical = 4.dp).testTag("scene-package-notice")) }
    val shown = record
    if (shown == null) {
        SupportingText("配置包与电脑版通用。", Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        return
    }
    Text(shown.summary, Modifier.padding(horizontal = 18.dp, vertical = 4.dp).testTag("scene-package-summary"),
        style = MaterialTheme.typography.bodyLarge)
    val undoable = shown.merge?.takeIf { it.added.isNotEmpty() || it.replaced.isNotEmpty() }
    if (undoable != null || shown.details.isNotEmpty()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (undoable != null) MiniAction("撤销导入", Modifier.testTag("scene-package-undo"), enabled = !busy) {
                scope.launch {
                    busy = true
                    notice = null
                    try {
                        actions.commitAudioEdit { latest -> latest.copy(scenes = undoable.undo(latest.scenes)) }
                            .fold({ show(ScenePackageRecord("已撤销导入", emptyList())) },
                                { error -> notice = "未能确认撤销：${error.message ?: "未收到提交结果"}" })
                    } finally { busy = false }
                }
            }
            if (shown.details.isNotEmpty()) MiniAction(if (expanded) "收起" else "详情（${shown.details.size}）",
                Modifier.testTag("scene-package-details")) { expanded = !expanded }
        }
    }
    if (expanded) Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.details.forEach { SupportingText(it) }
    }
}

private fun importSummary(imported: DspPackageImport, merge: DspSceneMerge): String {
    val changes = listOfNotNull(
        "新增 ${merge.added.size} 个".takeIf { merge.added.isNotEmpty() },
        "更新 ${merge.replaced.size} 个".takeIf { merge.replaced.isNotEmpty() },
        "${merge.unchanged} 个已存在".takeIf { merge.unchanged > 0 }
    )
    val skipped = imported.rejected.size + merge.overflow.size
    val head = if (merge.added.isEmpty() && merge.replaced.isEmpty()) "场景没有变化" else "已导入场景"
    return listOfNotNull(head + changes.joinToString("、", prefix = "：").takeIf { changes.isNotEmpty() }.orEmpty(),
        "$skipped 个未导入".takeIf { skipped > 0 }).joinToString("，")
}

private fun importDetails(imported: DspPackageImport, merge: DspSceneMerge?): List<String> =
    imported.scenes.flatMap { scene -> scene.notes.map { "“${scene.scene.name}”：$it" } } +
        imported.rejected.map { "“${it.name}”未导入：${it.reasons.joinToString("；")}" } +
        listOfNotNull(merge?.overflow?.takeIf { it.isNotEmpty() }?.let { overflow ->
            "场景已满 ${DspScenePackage.MAX_SCENES} 个，未导入：${overflow.joinToString("、") { it.name }}"
        })
