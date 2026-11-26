package dev.loudalarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var store: AlarmStore
    private lateinit var content: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private var lastDeleted: Alarm? = null
    private val clearUndo = Runnable {
        lastDeleted = null
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AlarmStore(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(40), dp(22), dp(16))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            // Fill the viewport so the settings can sit at the bottom instead of floating mid-screen.
            isFillViewport = true
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
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

    override fun onDestroy() {
        handler.removeCallbacks(clearUndo)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    private fun render() {
        content.removeAllViews()
        header()

        setupIssues().forEach { (text, fix) -> content.addView(issueRow(text, fix), lp(bottom = 10)) }

        lastDeleted?.let { content.addView(undoBar(it), lp(top = 8)) }

        val alarms = store.alarms().sortedBy { it.hour * 60 + it.minute }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (alarms.isNotEmpty()) list.addView(divider())
        alarms.forEach {
            list.addView(alarmRow(it))
            list.addView(divider())
        }
        content.addView(list, lp(top = 28))

        content.addView(label("+ Add alarm", 18f, Palette.ACCENT).apply {
            typeface = Type.MEDIUM
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { pickTime(7, 0) { h, m -> save(Alarm(store.nextId(), h, m)) } }
        }, lp(top = 4))

        content.addView(View(this), lp(height = 0, weight = 1f))

        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settings.addView(divider())
        settings.addView(settingRow("Loudness", "${store.volumePercent}%") { loudnessDialog() })
        settings.addView(divider())
        settings.addView(settingRow("Snooze", "${store.snoozeMinutes} min") { snoozeDialog() })
        settings.addView(divider())
        settings.addView(settingRow("Test alarm", "Rings in 10 s") {
            if (Scheduler.test(this, 10_000L)) toast("Lock your phone. It rings in 10 seconds.")
            else toast("Allow exact alarms first.")
        })
        content.addView(settings, lp(top = 24))
    }

    private fun header() {
        val now = ZonedDateTime.now()
        val next = (store.alarms().filter { it.enabled }.map { it.nextTrigger(now).toInstant().toEpochMilli() } +
            listOfNotNull(store.snooze?.at)).minOrNull()
        if (next == null) {
            content.addView(label("Next alarm", 17f, Palette.MUTED))
            content.addView(display("None", 84f, Palette.SURFACE_HIGH), lp(top = 4))
        } else {
            val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(next), now.zone)
            content.addView(label("Next alarm in ${untilText(next - now.toInstant().toEpochMilli())}", 17f, Palette.MUTED))
            content.addView(display(formatTime(at.hour, at.minute), 84f), lp(top = 4))
        }
        val streak = Lines.streakLine(store.streak.count)
        if (streak.isNotEmpty()) content.addView(label(streak, 16f, Palette.MUTED), lp(top = 8))
        content.addView(View(this), lp(height = dp(24)))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFF1F1F1F.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun alarmRow(alarm: Alarm): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(18), 0, dp(18))
        isClickable = true
        setOnClickListener { editDialog(alarm) }

        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(display(formatTime(alarm.hour, alarm.minute), 42f, if (alarm.enabled) Palette.TEXT else Palette.MUTED))
        left.addView(label("${Days.describe(alarm.days)}  ·  Edit", 15f, Palette.MUTED), lp(top = 4))
        addView(left, lp(width = 0, weight = 1f))

        addView(Switch(context).apply {
            scaleX = 1.3f
            scaleY = 1.3f
            isChecked = alarm.enabled
            setOnCheckedChangeListener { _, on -> save(alarm.copy(enabled = on)) }
        })
    }

    private fun editDialog(original: Alarm) {
        var alarm = original
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }
        val time = display(formatTime(alarm.hour, alarm.minute), 40f)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun drawChips() {
            chips.removeAllViews()
            for (day in DayOfWeek.entries) {
                val on = alarm.repeatsOn(day)
                val chip = label(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), 14f,
                    if (on) Palette.BG else Palette.MUTED).apply {
                    typeface = Type.MEDIUM
                    gravity = Gravity.CENTER
                    background = rounded(if (on) Palette.ACCENT else Palette.SURFACE_HIGH, 6)
                    setPadding(0, dp(9), 0, dp(9))
                    setOnClickListener {
                        alarm = alarm.copy(days = alarm.days xor day.bit, enabled = true)
                        save(alarm, announce = false)
                        drawChips()
                    }
                }
                chips.addView(chip, lp(width = 0, weight = 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
        }

        time.setOnClickListener {
            pickTime(alarm.hour, alarm.minute) { h, m ->
                alarm = alarm.copy(hour = h, minute = m, enabled = true)
                time.text = formatTime(h, m)
                save(alarm)
            }
        }
        view.addView(time)
        view.addView(label("Repeat", 14f, Palette.MUTED), lp(top = 18, bottom = 8))
        drawChips()
        view.addView(chips)

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Delete") { _, _ -> delete(alarm) }
            .show()
    }

    private fun delete(alarm: Alarm) {
        Scheduler.cancel(this, alarm.id)
        store.delete(alarm.id)
        lastDeleted = alarm
        handler.removeCallbacks(clearUndo)
        handler.postDelayed(clearUndo, 5_000L)
        render()
    }

    private fun undoBar(alarm: Alarm): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Palette.SURFACE, 10)
        setPadding(dp(16), dp(10), dp(12), dp(10))
        addView(label("Alarm deleted", 14f), lp(width = 0, weight = 1f))
        addView(label("Undo", 14f, Palette.ACCENT).apply {
            typeface = Type.MEDIUM
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                handler.removeCallbacks(clearUndo)
                lastDeleted = null
                save(alarm)
            }
        })
    }

    private fun settingRow(title: String, value: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(18), 0, dp(18))
        isClickable = true
        setOnClickListener { onClick() }
        addView(label(title, 18f), lp(width = 0, weight = 1f))
        addView(label("$value  ›", 18f, Palette.MUTED))
    }

    private fun loudnessDialog() = sliderDialog("Loudness", 10, 100, store.volumePercent, { "$it%" }) {
        store.volumePercent = it
    }

    private fun snoozeDialog() = sliderDialog("Snooze", 1, 30, store.snoozeMinutes, { "$it min" }) {
        store.snoozeMinutes = it
    }

    private fun sliderDialog(title: String, min: Int, max: Int, value: Int, format: (Int) -> String, onSet: (Int) -> Unit) {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }
        val readout: TextView = display(format(value), 40f)
        view.addView(label(title, 14f, Palette.MUTED))
        view.addView(readout, lp(top = 4))
        view.addView(SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value
            onChange {
                onSet(it)
                readout.text = format(it)
            }
        }, lp(top = 16))
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Done", null)
            .setOnDismissListener { render() }
            .show()
    }

    private fun issueRow(text: String, fix: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Palette.BG, 10, strokeColor = Palette.ACCENT).apply { setStroke(dp(1), Palette.ACCENT) }
        setPadding(dp(16), dp(14), dp(10), dp(14))
        addView(label(text, 15f), lp(width = 0, weight = 1f))
        addView(label("Fix", 16f, Palette.ACCENT).apply {
            typeface = Type.MEDIUM
            setPadding(dp(12), dp(6), dp(6), dp(6))
            setOnClickListener { fix() }
        })
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
            add("Do Not Disturb is blocking alarms right now." to {
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

    private fun save(alarm: Alarm, announce: Boolean = true) {
        store.upsert(alarm)
        if (!Scheduler.sync(this, alarm)) {
            toast("Android blocked the alarm. Allow exact alarms first.")
        } else if (alarm.enabled && announce) {
            val now = ZonedDateTime.now()
            toast("Rings in ${untilText(alarm.nextTrigger(now).toInstant().toEpochMilli() - now.toInstant().toEpochMilli())}")
        }
        render()
    }

    private fun pickTime(hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPicked(h, m) }, hour, minute, DateFormat.is24HourFormat(this)).show()
    }
}
