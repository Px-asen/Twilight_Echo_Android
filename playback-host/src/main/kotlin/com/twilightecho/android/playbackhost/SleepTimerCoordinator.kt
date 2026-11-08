package com.twilightecho.android.playbackhost

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Keeps one OS wakeup for the Core-owned phone timer. A broadcast never pauses a different session. */
class SleepTimerCoordinator(private val context: Context, transport: CoreTransport,
    private val local: CoreClient, private val scope: CoroutineScope,
    private val notice: (String) -> Unit) : Closeable {
    private val client = SleepTimerClient(transport) { local.snapshot.value?.epoch ?: "epoch-boot" }
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CoreSleepTimerState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var armed: SleepTimerSchedule? = null
    private var registered = false
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                try { clockChanged() }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { notice(error.message ?: "定时更新失败") }
            }
        }
    }
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    private fun bootId() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toString()
    fun start() {
        if (observation != null) return
        ContextCompat.registerReceiver(context, clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        observation = scope.launch {
            local.snapshot.mapNotNull { it?.sleepTimer }.distinctUntilChanged().collect { value ->
                try {
                    mutableState.value = value
                    val timer = value.timer
                    if (timer?.active == true && timer.schedule?.clock == "local") {
                        val adjusted = timer.inTimeZone(ZoneId.systemDefault())
                        if (adjusted != timer) {
                            mutex.withLock {
                                val latest = local.snapshot.value?.sleepTimer?.timer
                                if (latest?.active == true && latest.schedule?.id == timer.schedule?.id)
                                    configure(latest.inTimeZone(ZoneId.systemDefault()))
                            }
                            return@collect
                        }
                    }
                    try { updateAlarm(value.timer) }
                    catch (error: SecurityException) {
                        notice("请开启“闹钟与提醒”权限后重新设置定时")
                        mutex.withLock { client.cancel(client.state().revision) }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { notice(error.message ?: "定时更新失败，请重新设置") }
            }
        }
    }
    suspend fun countdown(minutes: Int, finishTrack: Boolean) = mutex.withLock {
        require(minutes in 1..720) { "请输入 1–720 分钟" }
        check(canScheduleExact()) { "请先开启“闹钟与提醒”权限" }
        val duration = minutes * 60_000L
        configure(SleepTimerState(endsAt = System.currentTimeMillis() + duration, action = "pause",
            schedule = SleepTimerSchedule(UUID.randomUUID().toString(), "elapsed", SystemClock.elapsedRealtime() + duration, bootId()),
            finishCurrentTrack = finishTrack))
    }
    suspend fun atTime(hour: Int, minute: Int, finishTrack: Boolean) = mutex.withLock {
        check(canScheduleExact()) { "请先开启“闹钟与提醒”权限" }
        val end = nextSleepTime(hour, minute, ZonedDateTime.now())
        val deadline = end.toInstant().toEpochMilli()
        configure(SleepTimerState(endsAt = deadline, action = "pause",
            schedule = SleepTimerSchedule(UUID.randomUUID().toString(), "local", deadline, bootId(), end.toLocalDate().toString(), hour, minute),
            finishCurrentTrack = finishTrack))
    }
    private suspend fun configure(timer: SleepTimerState) {
        client.configure(client.state().revision, timer)
        local.reconcileState()
    }
    suspend fun cancel() = mutex.withLock {
        client.cancel(client.state().revision)
        local.reconcileState()
    }
    suspend fun wake(id: String) = mutex.withLock {
        // state() checks the current clock. Old/cancelled alarms can only query, never target a new timer.
        if (local.snapshot.value?.sleepTimer?.timer?.schedule?.id == id) {
            mutableState.value = client.state()
            local.reconcileState()
        }
    }
    private suspend fun clockChanged() = mutex.withLock {
        val current = mutableState.value.timer ?: return@withLock
        if (!current.active || current.schedule?.clock != "local") return@withLock
        val updated = current.inTimeZone(ZoneId.systemDefault())
        if (updated != current) configure(updated) else {
            mutableState.value = client.state()
            local.reconcileState()
        }
    }
    private fun pending(schedule: SleepTimerSchedule) = PendingIntent.getBroadcast(context, 0,
        Intent(context, SleepTimerReceiver::class.java).setAction(ACTION).setData(Uri.parse("twilight://sleep/${schedule.id}")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun updateAlarm(timer: SleepTimerState?) {
        val next = timer?.takeIf { it.active }?.schedule
        if (armed == next) return
        armed?.let { alarms.cancel(pending(it)) }
        armed = null
        if (next != null) {
            checkPermission()
            alarms.setExactAndAllowWhileIdle(if (next.clock == "elapsed") AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.RTC_WAKEUP,
                next.deadlineMs, pending(next))
            armed = next
        }
    }
    private fun checkPermission() { if (!canScheduleExact()) throw SecurityException("Exact alarm permission is required") }
    override fun close() {
        observation?.cancel(); observation = null
        if (registered) { context.unregisterReceiver(clockReceiver); registered = false }
        armed?.let { alarms.cancel(pending(it)) }; armed = null
    }
    companion object { const val ACTION = "com.twilightecho.android.SLEEP_TIMER" }
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SleepTimerCoordinator.ACTION) return
        val id = intent.data?.lastPathSegment ?: return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeout(9_000) {
                    val host = AndroidPlaybackHost.getInstance(context)
                    host.awaitStarted()
                    host.sleepTimer.wake(id)
                }
            } catch (error: Exception) { android.util.Log.e("SleepTimer", "Timer wake failed", error) }
            finally { result.finish() }
        }
    }
}
