package dev.loudalarm

import java.time.LocalDate

/** Short, plain lines for the ring screen. One per day, so the same morning reads the same. */
object Lines {
    val wake = listOf(
        "You set this for a reason.",
        "Feet on the floor.",
        "Yesterday you made the plan. Keep it.",
        "Nobody else is getting you up.",
        "You don't have to feel ready.",
        "Do the hard part first.",
        "The day starts when you stand up.",
        "Five more minutes never helped.",
        "Wanting it is easy. This is the test.",
        "Get up. Then decide how you feel.",
    )

    fun forDay(day: LocalDate = LocalDate.now()): String = wake[day.dayOfYear % wake.size]

    fun streakLine(count: Int): String = when (count) {
        0 -> "No streak yet. Tomorrow counts."
        1 -> "1 morning up without snoozing."
        else -> "$count mornings in a row, no snooze."
    }
}
