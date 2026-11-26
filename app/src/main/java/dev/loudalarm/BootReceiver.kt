package dev.loudalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager

/**
 * Android drops every scheduled alarm on reboot, and clock changes can shift them. This puts them
 * back. It runs before the first unlock too (LOCKED_BOOT_COMPLETED).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = AlarmStore(context)

        // A ring was in progress but the process is gone: the phone rebooted or the app was
        // updated mid-alarm. Restarting the phone should not count as solving the problems.
        if (store.isRinging && !AlarmService.running) {
            val alarmId = store.ringingAlarmId
            val stillRelevant = System.currentTimeMillis() - store.ringingSince < AlarmService.MAX_RING_MS
            // The dead ring never ran its cleanup, so put the user's volume back here.
            val saved = store.savedVolume
            if (saved >= 0) {
                runCatching {
                    context.getSystemService(AudioManager::class.java)
                        .setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
                }
            }
            store.clearRinging()
            if (stillRelevant) Scheduler.snooze(context, alarmId, 60_000L)
        }

        Scheduler.syncAll(context)
    }
}
