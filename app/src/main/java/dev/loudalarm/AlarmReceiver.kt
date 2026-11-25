package dev.loudalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime

/** Receives the AlarmManager fire and hands off to the ringing service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = AlarmStore(context)
        val alarmId = intent.getIntExtra(Scheduler.EXTRA_ALARM_ID, Scheduler.TEST_ALARM_ID)

        when (intent.action) {
            Scheduler.ACTION_RESUME -> {
                if (!store.isRinging || AlarmService.running) return
                AlarmService.start(context, store.ringingAlarmId)
                return
            }
            Scheduler.ACTION_SNOOZE -> store.snooze = null
            Scheduler.ACTION_TEST -> Unit
            Scheduler.ACTION_FIRE -> {
                val alarm = store.find(alarmId) ?: return
                if (!alarm.enabled) return
                if (alarm.repeats) {
                    // Look a minute ahead so this same occurrence is never scheduled twice.
                    Scheduler.schedule(context, alarm, ZonedDateTime.now().plusMinutes(1))
                } else {
                    store.upsert(alarm.copy(enabled = false))
                }
            }
            else -> return
        }

        AlarmService.start(context, alarmId)
    }
}
