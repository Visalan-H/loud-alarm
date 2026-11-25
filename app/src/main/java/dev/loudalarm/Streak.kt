package dev.loudalarm

import java.time.LocalDate

/**
 * Clean wake-ups in a row: mornings you got up without snoozing or using the emergency stop.
 * Days without an alarm don't break it. One snooze or emergency stop resets it to zero.
 */
data class Streak(val count: Int = 0, val lastDay: LocalDate? = null) {
    fun after(clean: Boolean, today: LocalDate): Streak = when {
        !clean -> Streak(0, today)
        lastDay == today && count > 0 -> this
        else -> Streak(count + 1, today)
    }
}
