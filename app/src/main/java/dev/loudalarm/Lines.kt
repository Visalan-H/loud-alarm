package dev.loudalarm

object Lines {
    /** Empty at zero: the main screen shows nothing rather than a reminder of nothing. */
    fun streakLine(count: Int): String = when (count) {
        0 -> ""
        1 -> "1 morning without snoozing"
        else -> "$count mornings without snoozing"
    }
}
