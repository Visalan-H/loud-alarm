package dev.loudalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.ceil

/**
 * Plays the alarm until the user solves the problems. It runs as a foreground service so the
 * system won't kill it, and it keeps running if the user leaves the ring screen.
 */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: AlarmStore
    private lateinit var audio: AudioManager

    private var tone: AlarmTone? = null
    private var fallback: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var savedVolume = -1
    private var targetVolume = 0
    private var ticks = 0

    // Once a second: hold the volume, keep a sound playing, re-arm the backup, stop at the cap.
    private val watchdog = object : Runnable {
        override fun run() {
            val since = store.ringingSince
            if (since == 0L || System.currentTimeMillis() - since >= MAX_RING_MS) {
                store.clearRinging()
                stopSelf()
                return
            }
            enforceVolume()
            keepSounding()
            if (ticks++ % 30 == 0) Scheduler.armBackup(this@AlarmService, BACKUP_DELAY_MS)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        store = AlarmStore(this)
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        releaseHandoffLock()
        // A null intent means Android restarted us after killing the process mid-ring.
        if (intent == null && !store.isRinging) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!store.isRinging) {
            store.markRinging(intent?.getIntExtra(Scheduler.EXTRA_ALARM_ID, Scheduler.TEST_ALARM_ID) ?: 0)
        }

        try {
            goForeground()
        } catch (e: Exception) {
            // Android refused a foreground start. Try again in a minute rather than go silent.
            val alarmId = store.ringingAlarmId
            store.clearRinging()
            Scheduler.snooze(this, alarmId, 60_000L)
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock == null) beginRinging()
        openRingScreen()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        fallback?.stop()
        fallback = null
        // Still flagged as ringing means the system is tearing us down: leave the backup armed.
        if (!store.isRinging) Scheduler.disarmBackup(this)
        vibrator?.cancel()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        if (savedVolume >= 0) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        running = false
        super.onDestroy()
    }

    private fun beginRinging() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoudAlarm:ring")
            .apply { acquire(MAX_RING_MS) }

        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        targetVolume = ceil(max * store.volumePercent / 100.0).toInt().coerceIn(1, max)
        if (store.savedVolume < 0) store.savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        savedVolume = store.savedVolume
        enforceVolume()

        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AlarmTone.ATTRIBUTES)
            .build()
            .also { audio.requestAudioFocus(it) }

        keepSounding()
        runCatching { vibrate() }
        handler.post(watchdog)
    }

    /**
     * Makes sure something is audible. A tone that fails or stops gets rebuilt; if the synthesized
     * tone can't start at all, the phone's own alarm ringtone takes over.
     */
    private fun keepSounding() {
        fallback?.let {
            if (!it.isPlaying) runCatching { it.play() }
            return
        }
        try {
            (tone ?: AlarmTone().also { tone = it }).play()
        } catch (e: Exception) {
            runCatching { tone?.release() }
            tone = null
            if (++toneFailures >= 2) startFallback()
        }
    }

    private fun startFallback() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        fallback = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AlarmTone.ATTRIBUTES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            runCatching { play() }
        }
    }

    private var toneFailures = 0

    private fun enforceVolume() {
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != targetVolume) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0) }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 500), 0), AlarmTone.ATTRIBUTES)
        vibrator = v
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // The service plays its own sound.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)

        val ringScreen = PendingIntent.getActivity(
            this, 0, ringScreenIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Wake up")
            .setContentText("Solve ${Problem.REQUIRED} problems to turn this off")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(ringScreen)
            .setFullScreenIntent(ringScreen, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    // The full-screen intent is the reliable path. This direct start only helps where Android allows it.
    private fun openRingScreen() {
        runCatching { startActivity(ringScreenIntent()) }
    }

    private fun ringScreenIntent() = Intent(this, RingActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    companion object {
        /** A ring stops on its own after an hour, in case nobody is there to hear it. */
        const val MAX_RING_MS = 60 * 60 * 1000L
        private const val BACKUP_DELAY_MS = 90_000L
        private const val CHANNEL_ID = "ringing"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
            private set

        // AlarmManager's wake lock ends when the receiver returns. This one bridges the gap until
        // the service is up, so the phone can't fall back asleep in between.
        private var handoffLock: PowerManager.WakeLock? = null

        @Synchronized
        private fun releaseHandoffLock() {
            handoffLock?.takeIf { it.isHeld }?.release()
            handoffLock = null
        }

        @Synchronized
        fun start(context: Context, alarmId: Int) {
            if (handoffLock?.isHeld != true) {
                handoffLock = context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoudAlarm:handoff")
                    .apply { acquire(30_000L) }
            }
            context.startForegroundService(
                Intent(context, AlarmService::class.java).putExtra(Scheduler.EXTRA_ALARM_ID, alarmId),
            )
        }

        fun stop(context: Context) {
            AlarmStore(context).clearRinging()
            Scheduler.disarmBackup(context)
            context.stopService(Intent(context, AlarmService::class.java))
        }

        /** Restarts the sound if the store says we're ringing but the service died. */
        fun recover(context: Context) {
            val store = AlarmStore(context)
            if (store.isRinging && !running) start(context, store.ringingAlarmId)
        }
    }
}
