package com.twilightecho.android.feature.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.twilightecho.android.coreclient.*
import com.twilightecho.android.ui.components.SupportingText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val SHOWN_ISSUES = 6

/** The EQ an import replaced, so one tap restores it. */
private data class EqImportRecord(val summary: String, val sceneName: String, val saved: Boolean,
    val enabled: Boolean, val preampDb: Double, val eqMode: String, val bands: List<CoreParametricEqBand>)

/** AutoEQ and Equalizer APO text in and out of the current EQ; an import applies completely or not at all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EqTextTransfer(config: CoreEqConfigView, actions: PlayerActions) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(config)
    var issues by remember { mutableStateOf<List<EqTextIssue>>(emptyList()) }
    var record by remember { mutableStateOf<EqImportRecord?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun importText(text: String, fileName: String?) {
        val profile = try {
            EqualizerApoText.parse(text, fileName)
        } catch (error: EqTextImportException) {
            issues = error.issues
            return
        }
        val before = current
        record = EqImportRecord("已导入 ${profile.format.label}：${profile.bands.size} 个滤波器，前级 ${signedDb(profile.preampDb)}",
            sceneName(fileName, profile.format), false, before.enabled, before.preampDb, before.dsp.eqMode, before.dsp.parametricBands)
        notice = null
        actions.editAudio { it.withImportedEq(profile) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val document = withContext(Dispatchers.IO) { runCatching { readDocument(context, uri) }.getOrNull() }
            when {
                document == null -> notice = "无法读取文件"
                document.second.size > EqualizerApoText.MAX_FILE_BYTES -> issues = listOf(EqTextIssue(0, "文件超过 256 KB"))
                else -> importText(EqualizerApoText.decode(document.second), document.first)
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val text = EqualizerApoText.format(current)
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }.isSuccess
                }
                notice = if (saved) "已导出 EqualizerAPO 文本" else "导出失败"
            }
        }
    }

    record?.let { imported ->
        Text(imported.summary, Modifier.padding(horizontal = 18.dp, vertical = 6.dp).testTag("eq-import-summary"),
            style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniAction("撤销导入", Modifier.testTag("eq-import-undo")) {
                actions.editAudio { it.copy(enabled = imported.enabled, preampDb = imported.preampDb,
                    dsp = it.dsp.copy(eqMode = imported.eqMode, parametricBands = imported.bands)) }
                record = null
                notice = "已恢复导入前的均衡器"
            }
            if (!imported.saved) MiniAction("存为场景", enabled = config.scenes.size < 32) {
                actions.saveDspScene(imported.sceneName)
                record = imported.copy(saved = true)
                notice = "已保存场景“${imported.sceneName}”"
            }
        }
    }
    FlowRow(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniAction("导入文件", Modifier.testTag("eq-import-file"), highlighted = true) {
            open.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
        }
        MiniAction("粘贴导入", Modifier.testTag("eq-import-paste")) {
            val text = clipboard.getText()?.text
            if (text.isNullOrBlank()) notice = "剪贴板中没有文本" else importText(text, null)
        }
        MiniAction("导出文件", Modifier.testTag("eq-export-file")) { save.launch("Twilight Echo EQ.txt") }
        MiniAction("复制文本", Modifier.testTag("eq-export-copy")) {
            clipboard.setText(AnnotatedString(EqualizerApoText.format(current)))
            notice = "已复制 EqualizerAPO 文本"
        }
    }
    notice?.let { SupportingText(it, Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) }

    if (issues.isNotEmpty()) AlertDialog(onDismissRequest = { issues = emptyList() }, title = { Text("无法导入") }, text = {
        Column(Modifier.testTag("eq-import-issues"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            issues.take(SHOWN_ISSUES).forEach { Text(it.toString(), style = MaterialTheme.typography.bodyLarge) }
            if (issues.size > SHOWN_ISSUES) SupportingText("另有 ${issues.size - SHOWN_ISSUES} 处")
            SupportingText("均衡器保持原样。")
        }
    }, confirmButton = { TextButton(onClick = { issues = emptyList() }) { Text("知道了") } })
}

internal fun signedDb(value: Double): String =
    "${if (value > 0) "+" else ""}${EqualizerApoText.formatNumber(value)} dB"

/** AutoEQ names files "<headphone> ParametricEQ.txt"; the headphone makes the scene name. */
private fun sceneName(fileName: String?, format: EqTextFormat): String =
    fileName?.substringBeforeLast('.')?.replace(Regex("(?i)\\s*ParametricEQ$"), "")?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        ?: "${format.label} 均衡"

/** Display name and at most one byte past the limit, so oversized files are reported rather than truncated. */
private fun readDocument(context: Context, uri: Uri): Pair<String?, ByteArray> {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    val bytes = resolver.openInputStream(uri)!!.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= EqualizerApoText.MAX_FILE_BYTES) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
    return name to bytes
}
