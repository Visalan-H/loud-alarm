package dev.loudalarm

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Bit for this day in [Alarm.days]. Monday is bit 0, Sunday is bit 6. */
val DayOfWeek.bit: Int get() = 1 shl (value - 1)

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    /** Repeat days as a bitmask of [DayOfWeek.bit]. Zero means ring once. */
    val days: Int = 0,
    val enabled: Boolean = true,
) {
    val repeats: Boolean get() = days != 0

    fun repeatsOn(day: DayOfWeek): Boolean = (days and day.bit) != 0

    /**
     * The first moment strictly after [now] when this alarm should ring. Works on local dates so a
     * DST change never shifts the alarm by an hour on the following days.
     */
    fun nextTrigger(now: ZonedDateTime): ZonedDateTime {
        val time = LocalTime.of(hour, minute)
        var date = now.toLocalDate()
        while (true) {
            val candidate = ZonedDateTime.of(date, time, now.zone)
            if (candidate.isAfter(now) && (!repeats || repeatsOn(date.dayOfWeek))) return candidate
            date = date.plusDays(1)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hour", hour)
        .put("minute", minute)
        .put("days", days)
        .put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            days = o.optInt("days", 0),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

object Days {
    const val WEEKDAYS = 0b0011111
    const val WEEKENDS = 0b1100000
    const val EVERY_DAY = 0b1111111

    fun describe(days: Int, locale: Locale = Locale.getDefault()): String = when (days) {
        0 -> "Once"
        EVERY_DAY -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKENDS -> "Weekends"
        else -> DayOfWeek.entries
            .filter { (days and it.bit) != 0 }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}
