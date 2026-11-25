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
    fun linesStayPlain() {
        assertEquals("No streak yet. Tomorrow counts.", Lines.streakLine(0))
        assertEquals("1 morning up without snoozing.", Lines.streakLine(1))
        assertEquals("4 mornings in a row, no snooze.", Lines.streakLine(4))
        Lines.wake.forEach { assert(!it.contains('!')) { "no exclamation marks: $it" } }
    }
}
