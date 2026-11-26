package dev.loudalarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import java.time.LocalDate

data class Snooze(val alarmId: Int, val at: Long)

/**
 * All app state, kept in device-protected storage so alarms can fire after a reboot before the
 * user unlocks the phone. Writes use commit() because receivers may die right after returning.
 */
class AlarmStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("loud_alarm", Context.MODE_PRIVATE)

    fun alarms(): List<Alarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { Alarm.fromJson(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun find(id: Int): Alarm? = alarms().firstOrNull { it.id == id }

    fun upsert(alarm: Alarm) = write(alarms().filter { it.id != alarm.id } + alarm)

    fun delete(id: Int) = write(alarms().filter { it.id != id })

    fun nextId(): Int = (alarms().maxOfOrNull { it.id } ?: 0) + 1

    private fun write(list: List<Alarm>) {
        prefs.edit().putString(KEY_ALARMS, JSONArray(list.map { it.toJson() }).toString()).commit()
    }

    /** Alarm volume as a percent of the alarm stream's maximum. */
    var volumePercent: Int
        get() = prefs.getInt(KEY_VOLUME, 50)
        set(value) {
            prefs.edit().putInt(KEY_VOLUME, value.coerceIn(10, 100)).apply()
        }

    var snoozeMinutes: Int
        get() = prefs.getInt(KEY_SNOOZE_MINUTES, 5)
        set(value) {
            prefs.edit().putInt(KEY_SNOOZE_MINUTES, value.coerceIn(1, 30)).apply()
        }

    var snooze: Snooze?
        get() {
            val at = prefs.getLong(KEY_SNOOZE_AT, 0L)
            return if (at == 0L) null else Snooze(prefs.getInt(KEY_SNOOZE_ID, 0), at)
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                edit.remove(KEY_SNOOZE_AT).remove(KEY_SNOOZE_ID)
            } else {
                edit.putLong(KEY_SNOOZE_AT, value.at).putInt(KEY_SNOOZE_ID, value.alarmId)
            }
            edit.commit()
        }

    var streak: Streak
        get() {
            val day = prefs.getLong(KEY_STREAK_DAY, -1L)
            return Streak(prefs.getInt(KEY_STREAK, 0), if (day < 0) null else LocalDate.ofEpochDay(day))
        }
        set(value) {
            prefs.edit()
                .putInt(KEY_STREAK, value.count)
                .putLong(KEY_STREAK_DAY, value.lastDay?.toEpochDay() ?: -1L)
                .commit()
        }

    /** Set when this morning's alarm was snoozed, so the final turn-off doesn't count as clean. */
    var snoozedToday: Boolean
        get() = prefs.getBoolean(KEY_SNOOZED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SNOOZED, value).commit()
        }

    /** Records how a real (non-test) alarm ended and returns the updated streak. */
    fun recordWake(clean: Boolean, today: LocalDate = LocalDate.now()): Streak {
        val next = streak.after(clean && !snoozedToday, today)
        streak = next
        snoozedToday = false
        return next
    }

    /** When the current ring started, or 0 when nothing is ringing. */
    val ringingSince: Long get() = prefs.getLong(KEY_RING_SINCE, 0L)
    val isRinging: Boolean get() = ringingSince != 0L
    val ringingAlarmId: Int get() = prefs.getInt(KEY_RING_ID, 0)

    fun markRinging(alarmId: Int) {
        prefs.edit()
            .putLong(KEY_RING_SINCE, System.currentTimeMillis())
            .putInt(KEY_RING_ID, alarmId)
            .commit()
    }

    fun clearRinging() {
        prefs.edit().remove(KEY_RING_SINCE).remove(KEY_RING_ID).remove(KEY_SAVED_VOLUME).commit()
    }

    /**
     * The alarm volume from before this ring forced it up, or -1. Persisted so a service restart
     * mid-ring doesn't mistake the forced volume for the user's own.
     */
    var savedVolume: Int
        get() = prefs.getInt(KEY_SAVED_VOLUME, -1)
        set(value) {
            prefs.edit().putInt(KEY_SAVED_VOLUME, value).commit()
        }

    private companion object {
        const val KEY_ALARMS = "alarms"
        const val KEY_VOLUME = "volume_percent"
        const val KEY_SNOOZE_MINUTES = "snooze_minutes"
        const val KEY_SNOOZE_AT = "snooze_at"
        const val KEY_SNOOZE_ID = "snooze_id"
        const val KEY_RING_SINCE = "ringing_since"
        const val KEY_RING_ID = "ringing_id"
        const val KEY_SAVED_VOLUME = "saved_volume"
        const val KEY_STREAK = "streak"
        const val KEY_STREAK_DAY = "streak_day"
        const val KEY_SNOOZED = "snoozed_today"
    }
}
