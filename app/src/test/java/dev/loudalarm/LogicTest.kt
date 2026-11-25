package dev.loudalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class LogicTest {
    @Test
    fun problemsAreTwoDigitAdditions() {
        val rng = Random(42)
        repeat(1000) {
            val p = Problem.random(rng)
            assertTrue(p.a in 12..59 && p.b in 12..39)
            assertEquals(p.a + p.b, p.answer)
        }
    }

    @Test
    fun untilTextRoundsUpAndDropsZeros() {
        assertEquals("1 min", untilText(1))
        assertEquals("1 min", untilText(60_000))
        assertEquals("2 min", untilText(60_001))
        assertEquals("8 h 12 min", untilText((8 * 60 + 12) * 60_000L))
        assertEquals("1 h", untilText(60 * 60_000L))
        assertEquals("2 d 3 h", untilText(((2 * 24 + 3) * 60 + 5) * 60_000L))
    }

    @Test
    fun tonePatternIsLoudThenSilent() {
        val rate = 44_100
        val samples = alarmPattern(rate)
        assertEquals(rate, samples.size)
        val peak = samples.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak should be near full scale", peak > 30_000)
        // The last 0.35 s is the pause between bursts.
        assertTrue(samples.copyOfRange((rate * 0.65).toInt(), rate).all { it.toInt() == 0 })
    }
}
