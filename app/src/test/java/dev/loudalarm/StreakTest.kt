package dev.loudalarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StreakTest {
    private val mon = LocalDate.of(2026, 11, 23)

    @Test
    fun cleanMorningsAddUp() {
        val s = Streak().after(true, mon).after(true, mon.plusDays(1)).after(true, mon.plusDays(2))
        assertEquals(3, s.count)
    }

    @Test
    fun skippedDaysDoNotBreakIt() {
        val s = Streak().after(true, mon).after(true, mon.plusDays(5))
        assertEquals(2, s.count)
    }

    @Test
    fun secondCleanAlarmSameDayCountsOnce() {
        val s = Streak().after(true, mon).after(true, mon)
        assertEquals(1, s.count)
    }

    @Test
    fun snoozeOrEmergencyResets() {
        val s = Streak(7, mon).after(false, mon.plusDays(1))
        assertEquals(0, s.count)
        assertEquals(1, s.after(true, mon.plusDays(2)).count)
    }

    @Test
    fun streakLineIsPlainAndSilentAtZero() {
        assertEquals("", Lines.streakLine(0))
        assertEquals("1 morning without snoozing", Lines.streakLine(1))
        assertEquals("4 mornings without snoozing", Lines.streakLine(4))
    }
}
