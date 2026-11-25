package dev.loudalarm

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var store: AlarmStore
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AlarmStore(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            addView(content)
            padForSystemBars()
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        AlarmService.recover(this)
        if (store.isRinging) {
            startActivity(Intent(this, RingActivity::class.java))
        }
        // Re-registering on every open also repairs alarms wiped by a force stop.
        Scheduler.syncAll(this)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(label("Loud Alarm", 30f, bold = true))
        content.addView(label(nextRingSummary(), 15f, Palette.MUTED), lp(top = 2, bottom = 20))

        setupIssues().forEach { (text, fix) -> content.addView(issueRow(text, fix), lp(bottom = 12)) }

        store.alarms().sortedBy { it.hour * 60 + it.minute }.forEach {
            content.addView(alarmCard(it), lp(bottom = 12))
        }

        content.addView(button("+ Add alarm") {
            pickTime(7, 0) { h, m -> save(Alarm(store.nextId(), h, m)) }
        }, lp(top = 4, bottom = 28))

        content.addView(label("Settings", 13f, Palette.MUTED, bold = true), lp(bottom = 8))
        content.addView(settingsCard(), lp(bottom = 12))

        content.addView(button("Test: ring in 10 seconds", filled = false) {
            if (Scheduler.test(this, 10_000L)) toast("Lock your phone. It rings in 10 seconds.")
            else toast("Allow exact alarms first.")
        })
    }

    private fun nextRingSummary(): String {
        val now = ZonedDateTime.now()
        val nowMs = now.toInstant().toEpochMilli()
        val next = (store.alarms().filter { it.enabled }.map { it.nextTrigger(now).toInstant().toEpochMilli() } +
            listOfNotNull(store.snooze?.at)).minOrNull()
            ?: return "No alarms set"
        return "Next alarm in ${untilText(next - nowMs)}"
    }

    private fun alarmCard(alarm: Alarm): View = card().apply {
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val time = label(formatTime(alarm.hour, alarm.minute), 42f, if (alarm.enabled) Palette.TEXT else Palette.MUTED, bold = true)
        time.setOnClickListener {
            pickTime(alarm.hour, alarm.minute) { h, m -> save(alarm.copy(hour = h, minute = m, enabled = true)) }
        }
        top.addView(time, lp(width = 0, weight = 1f))
        top.addView(Switch(context).apply {
            isChecked = alarm.enabled
            scaleX = 1.2f
            scaleY = 1.2f
            setOnCheckedChangeListener { _, on -> save(alarm.copy(enabled = on)) }
        })
        addView(top)
        addView(label(Days.describe(alarm.days), 14f, Palette.MUTED))

        val days = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (day in DayOfWeek.entries) {
            val on = alarm.repeatsOn(day)
            val chip = label(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), 14f,
                if (on) Palette.BG else Palette.MUTED, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(if (on) Palette.ACCENT else Palette.SURFACE_HIGH, 20)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { save(alarm.copy(days = alarm.days xor day.bit, enabled = true)) }
            }
            days.addView(chip, lp(width = 0, weight = 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        addView(days, lp(top = 12))

        addView(label("Delete", 14f, Palette.MUTED).apply {
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
            setOnClickListener {
                Scheduler.cancel(context, alarm.id)
                store.delete(alarm.id)
                render()
            }
        })
    }

    private fun settingsCard(): View = card().apply {
        val volumeLabel = label("", 15f)
        fun showVolume(p: Int) {
            volumeLabel.text = "Loudness: $p%"
        }
        showVolume(store.volumePercent)
        addView(volumeLabel)
        addView(label("Share of your phone's max alarm volume. Locked in while it rings.", 13f, Palette.MUTED))
        addView(SeekBar(context).apply {
            min = 10
            max = 100
            progress = store.volumePercent
            onChange { store.volumePercent = it; showVolume(it) }
        }, lp(top = 8, bottom = 20))

        val snoozeLabel = label("", 15f)
        fun showSnooze(m: Int) {
            snoozeLabel.text = "Snooze length: $m min"
        }
        showSnooze(store.snoozeMinutes)
        addView(snoozeLabel)
        addView(SeekBar(context).apply {
            min = 1
            max = 30
            progress = store.snoozeMinutes
            onChange { store.snoozeMinutes = it; showSnooze(it) }
        }, lp(top = 8))
    }

    private fun issueRow(text: String, fix: () -> Unit): View = card().apply {
        background = rounded(Palette.SURFACE, 18, strokeColor = Palette.WARN)
        addView(label(text, 14f))
        addView(button("Fix", filled = false, onClick = fix), lp(top = 10))
    }

    private fun setupIssues(): List<Pair<String, () -> Unit>> = buildList {
        val pkg = Uri.parse("package:$packageName")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            add("Notifications are off. Without them the alarm screen can't open over the lock screen." to {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            })
        }
        if (!Scheduler.canSchedule(this@MainActivity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add("Exact alarms are blocked. Alarms won't ring on time." to {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            add("Full-screen alarms are blocked. You'll get a notification instead of the alarm screen." to {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            })
        }
        if (dndBlocksAlarms()) {
            add("Do Not Disturb is blocking alarms right now. Allow alarms in Do Not Disturb settings." to {
                runCatching { startActivity(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            })
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            add("Battery optimization is on. Some phones kill alarm apps in the background." to {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            })
        }
    }

    private fun dndBlocksAlarms(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> true
            // The alarms category only exists from Android 9. Before that, priority mode let alarms through.
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                runCatching {
                    (nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0
                }.getOrDefault(false)
            else -> false
        }
    }

    private fun save(alarm: Alarm) {
        store.upsert(alarm)
        if (!Scheduler.sync(this, alarm)) {
            toast("Android blocked the alarm. Allow exact alarms first.")
        } else if (alarm.enabled) {
            val now = ZonedDateTime.now()
            toast("Rings in ${untilText(alarm.nextTrigger(now).toInstant().toEpochMilli() - now.toInstant().toEpochMilli())}")
        }
        render()
    }

    private fun pickTime(hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPicked(h, m) }, hour, minute, DateFormat.is24HourFormat(this)).show()
    }
}
