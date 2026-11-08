package com.twilightecho.android.feature.player

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.twilightecho.android.coreclient.SleepTimerState
import com.twilightecho.android.coreclient.nextSleepTime
import com.twilightecho.android.ui.components.TwilightIcons
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun sleepRemainingLabel(timer: SleepTimerState, wallMs: Long, elapsedMs: Long): String {
    if (timer.waitingForTrackEnd) return "等待本曲结束"
    if (timer.triggered) return "正在暂停…"
    val seconds = (timer.remainingMs(wallMs, elapsedMs) + 999) / 1000
    val clock = if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    return "$clock 后暂停"
}

@Composable
internal fun sleepTimerLabel(timer: SleepTimerState?): String {
    if (timer == null) return "未开启"
    var label by remember(timer) { mutableStateOf(sleepRemainingLabel(timer, System.currentTimeMillis(), SystemClock.elapsedRealtime())) }
    LaunchedEffect(timer) {
        while (true) {
            label = sleepRemainingLabel(timer, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            delay(1000)
        }
    }
    return label
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SleepTimerSheet(state: PlayerUiState, actions: PlayerActions) {
    val context = LocalContext.current
    val timer = state.sleepTimer.timer
    var atTime by rememberSaveable { mutableStateOf(timer?.schedule?.clock == "local") }
    var minutes by rememberSaveable { mutableStateOf(timer?.takeIf { it.schedule?.clock == "elapsed" }
        ?.let { ((it.remainingMs(System.currentTimeMillis(), SystemClock.elapsedRealtime()) + 59_999) / 60_000).coerceIn(1, 720).toString() } ?: "30") }
    val initialTime = remember { ZonedDateTime.now().plusMinutes(30) }
    var hour by rememberSaveable { mutableIntStateOf(timer?.schedule?.hour ?: initialTime.hour) }
    var minute by rememberSaveable { mutableIntStateOf(timer?.schedule?.minute ?: initialTime.minute) }
    var finishTrack by rememberSaveable { mutableStateOf(timer?.finishCurrentTrack ?: false) }
    fun allowed() = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    var exactAllowed by remember { mutableStateOf(allowed()) }
    LifecycleStartEffect(Unit) {
        exactAllowed = allowed()
        onStopOrDispose { }
    }
    val validMinutes = minutes.toIntOrNull()?.takeIf { it in 1..720 }
    val now = ZonedDateTime.now()
    val end = nextSleepTime(hour, minute, now)
    val endLabel = (if (end.toLocalDate() == now.toLocalDate()) "今天 " else "明天 ") + end.format(DateTimeFormatter.ofPattern("HH:mm"))
    ModalBottomSheet(onDismissRequest = { actions.sleepTimerOpen(false) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        com.twilightecho.android.feature.player.zones.SheetSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = 24.dp).padding(bottom = 28.dp).testTag("sleepTimerSheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("睡眠与定时", style = MaterialTheme.typography.headlineSmall)
                    Text("此手机", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(TwilightIcons.Sleep, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (timer != null) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(sleepTimerLabel(timer), Modifier.weight(1f).testTag("sleepTimerStatus"), style = MaterialTheme.typography.titleMedium)
                        TextButton(actions.cancelSleepTimer, enabled = !state.sleepTimerBusy, modifier = Modifier.testTag("cancelSleepTimer")) { Text("取消") }
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!atTime, { atTime = false }, SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.testTag("sleepCountdownMode")) { Text("倒计时") }
                SegmentedButton(atTime, { atTime = true }, SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.testTag("sleepAtTimeMode")) { Text("指定时刻") }
            }
            if (!atTime) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60, 90).forEach { value ->
                        FilterChip(minutes == value.toString(), { minutes = value.toString() },
                            label = { Text("$value 分钟") }, modifier = Modifier.testTag("sleepPreset$value"))
                    }
                }
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("自定义时长") },
                    suffix = { Text("分钟") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, isError = validMinutes == null,
                    supportingText = { Text("1–720 分钟") }, modifier = Modifier.fillMaxWidth().testTag("sleepMinutes"))
            } else {
                OutlinedButton({ TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("sleepTimePicker")) {
                    Text(endLabel, style = MaterialTheme.typography.headlineSmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("播完本曲再暂停", style = MaterialTheme.typography.titleMedium)
                    Text("到时等待正在播放的歌曲结束", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(finishTrack, { finishTrack = it }, modifier = Modifier.testTag("sleepFinishTrack"))
            }
            if (!exactAllowed) Text("开启“闹钟与提醒”后，锁屏也能准时暂停。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.sleepTimerError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sleepTimerError")) }
            Button(onClick = {
                if (!allowed() && Build.VERSION.SDK_INT >= 31) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                } else if (atTime) actions.startSleepAtTime(hour, minute, finishTrack)
                else validMinutes?.let { actions.startSleepCountdown(it, finishTrack) }
            }, enabled = !state.sleepTimerBusy && (atTime || validMinutes != null),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("startSleepTimer")) {
                Text(if (state.sleepTimerBusy) "正在设置…" else if (!exactAllowed) "开启闹钟与提醒权限" else if (timer != null) "更新定时" else "开始定时")
            }
        }
    }
}
