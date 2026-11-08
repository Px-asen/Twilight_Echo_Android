package com.twilightecho.android.coreclient

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SleepTimerTest {
    @Test fun nextTimeIsTodayOrTomorrowAndSecondsNeverMoveTheDeadlineBackwards() {
        val now = ZonedDateTime.parse("2026-11-09T23:20:25+08:00[Asia/Shanghai]")
        assertEquals("2026-11-09T23:30+08:00[Asia/Shanghai]", nextSleepTime(23, 30, now).toString())
        assertEquals("2026-11-10T00:10+08:00[Asia/Shanghai]", nextSleepTime(0, 10, now).toString())
        assertEquals("2026-11-10T23:20+08:00[Asia/Shanghai]", nextSleepTime(23, 20, now).toString())
    }
    @Test fun elapsedCountdownIsUnaffectedByWallClockJump() {
        val timer = SleepTimerState(endsAt = 100_000, schedule = SleepTimerSchedule("one", "elapsed", 65_000, "boot"))
        assertEquals(60_000, timer.remainingMs(99_000_000, 5_000))
        assertEquals(0, timer.remainingMs(0, 70_000))
    }
    @Test fun repeatedHourSelectsTheNextOccurrenceAndKeepsItOnReconciliation() {
        val now = ZonedDateTime.parse("2026-11-01T01:45-04:00[America/New_York]")
        val end = nextSleepTime(1, 30, now)
        assertEquals("2026-11-01T01:30-05:00[America/New_York]", end.toString())
        val timer = SleepTimerState(endsAt = end.toInstant().toEpochMilli(), schedule = SleepTimerSchedule("one", "local",
            end.toInstant().toEpochMilli(), "boot", "2026-11-01", 1, 30))
        assertEquals(timer, timer.inTimeZone(now.zone))
    }
    @Test fun timeZoneChangePreservesSelectedLocalDateAndTime() {
        val timer = SleepTimerState(action = "pause", endsAt = 1,
            schedule = SleepTimerSchedule("one", "local", 1, "boot", "2026-11-09", 23, 30))
        val updated = timer.inTimeZone(ZoneId.of("Asia/Shanghai"))
        assertEquals(ZonedDateTime.parse("2026-11-09T23:30+08:00[Asia/Shanghai]").toInstant().toEpochMilli(), updated.endsAt)
        assertEquals(updated.endsAt, updated.schedule?.deadlineMs)
        assertEquals("one", updated.schedule?.id)
    }
    @Test fun optionalTimerDoesNotBreakOldOrDesktopSnapshots() {
        val json = DefaultCoreClient.DefaultJson
        val old = json.decodeFromString<CoreSnapshot>("""{"epoch":"old","sequence":1}""")
        assertNull(old.sleepTimer)
        val state = json.decodeFromString<CoreSleepTimerState>("""{"revision":2,"timer":{"mode":"trackEnd","endsAt":null,"fadeSeconds":10,"active":true,"triggered":false}}""")
        assertNull(state.timer?.schedule)
    }
}
