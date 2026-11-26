package dev.loudalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime

/**
 * Every alarm goes through AlarmManager.setAlarmClock(): the one API Android treats as a real
 * wake-up alarm. It fires exactly, ignores Doze, and shows the clock icon in the status bar.
 */
object Scheduler {
    const val ACTION_FIRE = "dev.loudalarm.action.FIRE"
    const val ACTION_SNOOZE = "dev.loudalarm.action.SNOOZE"
    const val ACTION_TEST = "dev.loudalarm.action.TEST"
    const val ACTION_RESUME = "dev.loudalarm.action.RESUME"
    const val EXTRA_ALARM_ID = "alarm_id"

    /** Alarm id used by the "ring in 10 seconds" test. */
    const val TEST_ALARM_ID = -1

    fun canSchedule(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager(context).canScheduleExactAlarms()

    /** Re-registers every alarm and any pending snooze. Safe to call any number of times. */
    fun syncAll(context: Context) {
        val store = AlarmStore(context)
        store.alarms().forEach { sync(context, it) }

        val snooze = store.snooze ?: return
        val now = System.currentTimeMillis()
        when {
            snooze.at > now -> setClock(context, snooze.at, pending(context, ACTION_SNOOZE, snooze.alarmId))
            // Phone was off when the snooze ended. Ring again shortly instead of letting it slide.
            now - snooze.at < AlarmService.MAX_RING_MS -> snooze(context, snooze.alarmId, 30_000L)
            else -> store.snooze = null
        }
    }

    /** Schedules or cancels [alarm] to match its enabled flag. Returns false if Android refused. */
    fun sync(context: Context, alarm: Alarm): Boolean =
        if (alarm.enabled) schedule(context, alarm) else {
            cancel(context, alarm.id)
            true
        }

    fun schedule(context: Context, alarm: Alarm, after: ZonedDateTime = ZonedDateTime.now()): Boolean {
        val at = alarm.nextTrigger(after).toInstant().toEpochMilli()
        return setClock(context, at, pending(context, ACTION_FIRE, alarm.id))
    }

    fun cancel(context: Context, alarmId: Int) {
        manager(context).cancel(pending(context, ACTION_FIRE, alarmId))
        val store = AlarmStore(context)
        if (store.snooze?.alarmId == alarmId) {
            manager(context).cancel(pending(context, ACTION_SNOOZE, alarmId))
            store.snooze = null
        }
    }

    fun snooze(context: Context, alarmId: Int, delayMs: Long): Boolean {
        val at = System.currentTimeMillis() + delayMs
        AlarmStore(context).snooze = Snooze(alarmId, at)
        return setClock(context, at, pending(context, ACTION_SNOOZE, alarmId))
    }

    /**
     * A dead-man's switch while ringing: the service keeps pushing this forward. If the process
     * dies and Android never restarts it, this fires and brings the ring back.
     */
    fun armBackup(context: Context, delayMs: Long) {
        setClock(context, System.currentTimeMillis() + delayMs, pending(context, ACTION_RESUME, 0))
    }

    fun disarmBackup(context: Context) {
        manager(context).cancel(pending(context, ACTION_RESUME, 0))
    }

    fun test(context: Context, delayMs: Long): Boolean =
        setClock(context, System.currentTimeMillis() + delayMs, pending(context, ACTION_TEST, TEST_ALARM_ID))

    private fun setClock(context: Context, at: Long, operation: PendingIntent): Boolean {
        if (!canSchedule(context)) return false
        val showIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            manager(context).setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent), operation)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    // Each action gets its own PendingIntent; FIRE intents are told apart by alarm id.
    private fun pending(context: Context, action: String, alarmId: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_ALARM_ID, alarmId)
        val requestCode = if (action == ACTION_FIRE) alarmId else 0
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun manager(context: Context) = context.getSystemService(AlarmManager::class.java)
}
