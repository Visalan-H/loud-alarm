package dev.loudalarm

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The screen that shows over the lock screen while the alarm rings. Snooze and dismiss stay hidden
 * until the user solves [Problem.REQUIRED] problems. Back and the volume keys do nothing here.
 */
class RingActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: AlarmStore

    private var problem = Problem.random()
    private var solved = 0
    private var ringSince = 0L
    private var entry = ""
    private var lastWrong = false

    private lateinit var dateView: TextView
    private lateinit var clockView: TextView
    private lateinit var statusView: TextView
    private lateinit var segments: List<View>
    private lateinit var problemView: TextView
    private lateinit var entryView: TextView
    private lateinit var streakView: TextView
    private lateinit var keypad: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var emergency: TextView

    private val isTest: Boolean get() = store.ringingAlarmId == Scheduler.TEST_ALARM_ID

    private val ticker = object : Runnable {
        override fun run() {
            if (!store.isRinging) {
                finish()
                return
            }
            resetIfNewRing()
            val now = LocalTime.now()
            clockView.text = formatTime(now.hour, now.minute)
            dateView.text = todayText()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AlarmStore(this)
        if (!store.isRinging) {
            finish()
            return
        }
        showOverLockScreen()
        ringSince = store.ringingSince
        // Progress only carries over if it belongs to the ring that is still going.
        if (savedInstanceState?.getLong(KEY_SINCE) == ringSince) {
            solved = savedInstanceState.getInt(KEY_SOLVED)
        }
        setContentView(buildLayout())
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resetIfNewRing()
    }

    override fun onResume() {
        super.onResume()
        AlarmService.recover(this)
        resetIfNewRing()
        handler.post(ticker)
    }

    // singleTask reuses this screen, so a new ring must not inherit an old ring's solved problems.
    private fun resetIfNewRing() {
        if (!store.isRinging || store.ringingSince == ringSince) return
        ringSince = store.ringingSince
        solved = 0
        entry = ""
        lastWrong = false
        problem = Problem.random()
        render()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SOLVED, solved)
        outState.putLong(KEY_SINCE, ringSince)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = Unit

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun todayText(): String =
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())).uppercase()

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setBackgroundColor(Palette.BG)
            setPadding(dp(24), dp(28), dp(24), dp(20))
            padForSystemBars()
        }

        dateView = eyebrow(todayText())
        clockView = display("", 88f)
        val line = label(Lines.forDay(), 20f).apply { typeface = Type.BODY }
        root.addView(dateView, lp())
        root.addView(clockView, lp(top = 4))
        root.addView(line, lp(top = 8))
        root.addView(View(this), lp(height = 0, weight = 1f))

        statusView = eyebrow("")
        root.addView(statusView, lp())
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        segments = List(Problem.REQUIRED) { i ->
            View(this).also {
                bar.addView(it, lp(width = 0, height = dp(4), weight = 1f).apply {
                    if (i > 0) marginStart = dp(6)
                })
            }
        }
        root.addView(bar, lp(top = 10))

        problemView = display("", 52f)
        entryView = display("", 52f, Palette.ACCENT).apply { minHeight = dp(60) }
        streakView = label("", 15f, Palette.MUTED).apply { typeface = Type.BODY }
        root.addView(problemView, lp(top = 20))
        root.addView(entryView, lp(top = 2))
        root.addView(streakView, lp(top = 4))
        root.addView(View(this), lp(height = 0, weight = 1f))

        keypad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(DELETE, "0", SUBMIT),
        ).forEach { row ->
            // Baseline alignment would push the small-text DEL/OK keys below the digits and clip them.
            val keys = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            row.forEachIndexed { i, key ->
                val view = button(key, filled = key == SUBMIT) { press(key) }.apply {
                    if (key != DELETE && key != SUBMIT) {
                        textSize = 26f
                        letterSpacing = 0f
                        typeface = Type.DISPLAY
                    }
                }
                keys.addView(view, lp(width = 0, height = dp(68), weight = 1f).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
            keypad.addView(keys, lp(bottom = 8))
        }
        root.addView(keypad, lp())
        emergency = emergencyButton()
        root.addView(emergency, lp(top = 4, height = dp(48)))

        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.addView(button("Turn off") { dismiss() }, lp(bottom = 12, height = dp(64)))
        actions.addView(button("Snooze ${store.snoozeMinutes} min", filled = false) { snooze() }, lp(height = dp(64)))
        actions.addView(label("Snoozing resets your streak.", 13f, Palette.MUTED).apply {
            typeface = Type.BODY
            gravity = Gravity.CENTER
        }, lp(top = 8))
        root.addView(actions, lp())
        return root
    }

    private fun press(key: String) {
        when (key) {
            DELETE -> entry = entry.dropLast(1)
            SUBMIT -> check()
            else -> if (entry.length < 4) entry += key
        }
        render()
    }

    private fun check() {
        if (entry.isEmpty()) return
        lastWrong = entry.toIntOrNull() != problem.answer
        if (!lastWrong) {
            solved++
            problem = Problem.random()
        }
        entry = ""
    }

    private fun render() {
        val done = solved >= Problem.REQUIRED
        val wrong = lastWrong && !done
        statusView.text = when {
            done -> "AWAKE"
            wrong -> "WRONG. AGAIN."
            else -> "PROVE YOU'RE AWAKE"
        }
        statusView.setTextColor(if (wrong) Palette.ACCENT else Palette.MUTED)
        segments.forEachIndexed { i, view ->
            view.background = rounded(if (i < solved) Palette.ACCENT else Palette.SURFACE_HIGH, 2)
        }

        if (done) {
            problemView.text = "You're up."
            entryView.visibility = View.GONE
            streakView.visibility = View.VISIBLE
            val next = store.streak.after(!store.snoozedToday, LocalDate.now()).count
            streakView.text = if (isTest) "Test alarm. Your streak is untouched." else Lines.streakLine(next)
        } else {
            problemView.text = problem.toString()
            entryView.visibility = View.VISIBLE
            entryView.text = entry.ifEmpty { "_" }
            entryView.setTextColor(if (entry.isEmpty()) Palette.SURFACE_HIGH else Palette.ACCENT)
            streakView.visibility = View.GONE
        }
        keypad.visibility = if (done) View.GONE else View.VISIBLE
        emergency.visibility = if (done) View.GONE else View.VISIBLE
        actions.visibility = if (done) View.VISIBLE else View.GONE
    }

    /**
     * The escape hatch: hold for [EMERGENCY_HOLD_MS] to stop without solving. Letting go resets it,
     * and the alarm keeps blaring the whole time, so it's no shortcut when you're just sleepy.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun emergencyButton(): TextView {
        val fill = ClipDrawable(rounded(Palette.ACCENT, 6), Gravity.START, ClipDrawable.HORIZONTAL)
        val seconds = EMERGENCY_HOLD_MS / 1000
        val view = eyebrow("").apply {
            gravity = Gravity.CENTER
            background = LayerDrawable(arrayOf(rounded(Palette.SURFACE, 6), fill))
        }
        var pressedAt = 0L
        fun idle() {
            fill.level = 0
            view.text = "HOLD $seconds S TO STOP WITHOUT SOLVING"
            view.setTextColor(Palette.MUTED)
        }
        val progress = object : Runnable {
            override fun run() {
                val held = System.currentTimeMillis() - pressedAt
                if (held >= EMERGENCY_HOLD_MS) {
                    emergencyStop()
                    return
                }
                fill.level = (held * 10_000 / EMERGENCY_HOLD_MS).toInt()
                view.text = "KEEP HOLDING · ${(EMERGENCY_HOLD_MS - held + 999) / 1000}"
                view.setTextColor(Palette.TEXT)
                handler.postDelayed(this, 50)
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedAt = System.currentTimeMillis()
                    handler.post(progress)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(progress)
                    idle()
                }
            }
            true
        }
        idle()
        return view
    }

    private fun snooze() {
        val alarmId = store.ringingAlarmId
        val minutes = store.snoozeMinutes
        // Schedule first: if Android refuses, the alarm keeps ringing instead of vanishing.
        if (Scheduler.snooze(this, alarmId, minutes * 60_000L)) {
            if (alarmId != Scheduler.TEST_ALARM_ID) store.snoozedToday = true
            AlarmService.stop(this)
            toast("Snoozed for $minutes min")
            finish()
        } else {
            toast("Android blocked the snooze. Solve again later or turn it off.")
        }
    }

    private fun dismiss() {
        val test = isTest
        AlarmService.stop(this)
        if (!test) toast(Lines.streakLine(store.recordWake(clean = true).count))
        finish()
    }

    private fun emergencyStop() {
        val test = isTest
        AlarmService.stop(this)
        if (!test) {
            store.recordWake(clean = false)
            toast("Streak reset. Tomorrow counts.")
        }
        finish()
    }

    private companion object {
        const val KEY_SOLVED = "solved"
        const val KEY_SINCE = "ring_since"
        const val DELETE = "Del"
        const val SUBMIT = "OK"
        const val EMERGENCY_HOLD_MS = 5_000L
    }
}
