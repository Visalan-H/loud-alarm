package dev.loudalarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class AlarmTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)

    @Test
    fun oneShotLaterTodayRingsToday() {
        val now = at(2026, 11, 25, 6, 0) // Wednesday
        assertEquals(at(2026, 11, 25, 7, 30), Alarm(1, 7, 30).nextTrigger(now))
    }

    @Test
    fun oneShotEarlierTodayRingsTomorrow() {
        val now = at(2026, 11, 25, 8, 0)
        assertEquals(at(2026, 11, 26, 7, 30), Alarm(1, 7, 30).nextTrigger(now))
    }

    @Test
    fun exactlyNowRollsToTomorrow() {
        val now = at(2026, 11, 25, 7, 30)
        assertEquals(at(2026, 11, 26, 7, 30), Alarm(1, 7, 30).nextTrigger(now))
    }

    @Test
    fun weekdaysFromFridayEveningSkipsToMonday() {
        val now = at(2026, 11, 27, 20, 0) // Friday
        assertEquals(at(2026, 11, 30, 7, 0), Alarm(1, 7, 0, Days.WEEKDAYS).nextTrigger(now))
    }

    @Test
    fun singleRepeatDayCanBeAWeekAway() {
        val now = at(2026, 11, 25, 8, 0) // Wednesday, after the alarm time
        val alarm = Alarm(1, 7, 0, DayOfWeek.WEDNESDAY.bit)
        assertEquals(at(2026, 12, 2, 7, 0), alarm.nextTrigger(now))
    }

    @Test
    fun alarmInsideSpringForwardGapShiftsLater() {
        // 2:30 does not exist in New York on 2026-03-08; java.time moves it to 3:30.
        val now = at(2026, 3, 8, 0, 0)
        val next = Alarm(1, 2, 30).nextTrigger(now)
        assertEquals(3, next.hour)
        assertEquals(8, next.dayOfMonth)
    }

    @Test
    fun alarmKeepsWallClockTimeAcrossDst() {
        val now = at(2026, 3, 7, 12, 0) // Day before spring forward
        val next = Alarm(1, 7, 0, Days.EVERY_DAY).nextTrigger(now)
        assertEquals(at(2026, 3, 8, 7, 0), next)
        assertEquals(7, next.hour)
    }

    @Test
    fun describesDays() {
        assertEquals("Once", Days.describe(0))
        assertEquals("Every day", Days.describe(Days.EVERY_DAY))
        assertEquals("Weekdays", Days.describe(Days.WEEKDAYS))
        assertEquals("Weekends", Days.describe(Days.WEEKENDS))
        assertEquals("Mon, Wed", Days.describe(DayOfWeek.MONDAY.bit or DayOfWeek.WEDNESDAY.bit, Locale.US))
    }
}
