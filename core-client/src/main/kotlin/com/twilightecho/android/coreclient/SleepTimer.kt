package com.twilightecho.android.coreclient

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@Serializable
data class SleepTimerSchedule(val id: String, val clock: String, val deadlineMs: Long, val bootId: String,
    val localDate: String? = null, val hour: Int? = null, val minute: Int? = null)

@Serializable
data class SleepTimerState(val mode: String = "minutes", val endsAt: Long? = null,
    val fadeSeconds: Int = 0, val active: Boolean = true, val triggered: Boolean = false,
    val action: String? = null, val schedule: SleepTimerSchedule? = null,
    val finishCurrentTrack: Boolean = false, val waitingForTrackEnd: Boolean = false) {
    fun remainingMs(wallMs: Long, elapsedMs: Long): Long = when (schedule?.clock) {
        "elapsed" -> schedule.deadlineMs - elapsedMs
        else -> (endsAt ?: wallMs) - wallMs
    }.coerceAtLeast(0)
}

@Serializable
data class CoreSleepTimerState(val revision: Long = 0, val timer: SleepTimerState? = null)

/** A clock selection is resolved once, including its local date, so a time change never adds a day. */
fun nextSleepTime(hour: Int, minute: Int, now: ZonedDateTime): ZonedDateTime {
    val time = LocalTime.of(hour, minute)
    val today = now.toLocalDate().atTime(time).atZone(now.zone)
    return listOf(today, today.withLaterOffsetAtOverlap()).distinct().firstOrNull { it.isAfter(now) }
        ?: now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
}

fun SleepTimerState.inTimeZone(zone: ZoneId): SleepTimerState {
    val schedule = schedule ?: return this
    if (schedule.clock != "local") return this
    val local = LocalDate.parse(schedule.localDate).atTime(schedule.hour!!, schedule.minute!!).atZone(zone)
    val candidates = listOf(local, local.withLaterOffsetAtOverlap()).map { it.toInstant().toEpochMilli() }
    val deadline = endsAt?.takeIf { it in candidates } ?: candidates.first()
    return copy(endsAt = deadline, schedule = schedule.copy(deadlineMs = deadline))
}

/** Always construct with the phone runtime transport, never the selected desktop transport. */
class SleepTimerClient(private val transport: CoreTransport, private val epoch: () -> String) {
    private val json = DefaultCoreClient.DefaultJson
    private suspend fun request(kind: String, name: String, payload: JsonObject): JsonElement {
        val result = transport.sendRequest(CoreRequestEnvelope(requestId = "sleep-${UUID.randomUUID()}",
            epoch = epoch(), kind = kind, name = name, payload = payload))
        if (!result.ok) throw CoreRequestException(result.error ?: CoreErrorView(CoreErrorCode.FAILED, "睡眠定时操作失败"))
        return result.value ?: JsonObject(emptyMap())
    }
    suspend fun state(): CoreSleepTimerState = json.decodeFromJsonElement(request("query", "sleep.state", buildJsonObject {}))
    suspend fun configure(revision: Long, timer: SleepTimerState) {
        request("command", "sleep.configure", buildJsonObject {
            put("revision", revision); put("mode", "minutes"); put("action", "pause")
            put("endsAt", timer.endsAt); put("finishCurrentTrack", timer.finishCurrentTrack)
            put("schedule", json.encodeToJsonElement(timer.schedule!!))
        })
    }
    suspend fun cancel(revision: Long) { request("command", "sleep.cancel", buildJsonObject { put("revision", revision) }) }
}
