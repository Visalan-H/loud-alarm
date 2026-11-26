package dev.loudalarm

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Near-black, off-white, one ember accent. Nothing else competes for attention. */
internal object Palette {
    const val BG = 0xFF0A0A0A.toInt()
    const val SURFACE = 0xFF141414.toInt()
    const val SURFACE_HIGH = 0xFF242424.toInt()
    const val TEXT = 0xFFEDEAE4.toInt()
    const val MUTED = 0xFF77746F.toInt()
    const val ACCENT = 0xFFFF4D1A.toInt()
}

internal object Type {
    /** Medium-weight sans for times and the problem. */
    val DISPLAY: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BODY: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

/** Large medium-weight text for times and the problem. */
internal fun Context.display(text: CharSequence, sizeSp: Float, color: Int = Palette.TEXT) =
    label(text, sizeSp, color).apply {
        typeface = Type.DISPLAY
        letterSpacing = -0.02f
        includeFontPadding = false
    }

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

internal fun Context.label(
    text: CharSequence,
    sizeSp: Float = 16f,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
) = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

internal fun Context.rounded(color: Int, radiusDp: Int = 14, strokeColor: Int? = null) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(2), it) }
    }

/** A flat, rounded button. Filled buttons use the accent color, outlined ones a border. */
internal fun Context.button(text: String, filled: Boolean = true, onClick: () -> Unit) =
    TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Type.MEDIUM
        gravity = Gravity.CENTER
        setTextColor(if (filled) Palette.BG else Palette.TEXT)
        val shape = if (filled) rounded(Palette.ACCENT, 10) else rounded(Palette.SURFACE, 10, strokeColor = Palette.SURFACE_HIGH)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isClickable = true
        setOnClickListener { onClick() }
    }

internal fun Context.lp(
    top: Int = 0,
    bottom: Int = 0,
    width: Int = LinearLayout.LayoutParams.MATCH_PARENT,
    height: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
    weight: Float = 0f,
) = LinearLayout.LayoutParams(width, height, weight).apply {
    topMargin = dp(top)
    bottomMargin = dp(bottom)
}

/** Android 15 draws apps edge to edge, so pad the root clear of the status and nav bars. */
@Suppress("DEPRECATION")
internal fun View.padForSystemBars() {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(base[0] + bars.left, base[1] + bars.top, base[2] + bars.right, base[3] + bars.bottom)
        } else {
            v.setPadding(
                base[0] + insets.systemWindowInsetLeft, base[1] + insets.systemWindowInsetTop,
                base[2] + insets.systemWindowInsetRight, base[3] + insets.systemWindowInsetBottom,
            )
        }
        insets
    }
}

internal fun SeekBar.onChange(block: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = block(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    })
}

internal fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

internal fun Context.formatTime(hour: Int, minute: Int): String {
    val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
    return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

/** "8 h 12 min", "2 d 3 h", "1 min". Rounds up so an alarm never sounds sooner than promised. */
fun untilText(ms: Long): String {
    val totalMinutes = ceil(ms / 60_000.0).toLong().coerceAtLeast(1)
    val days = totalMinutes / 1440
    val hours = totalMinutes % 1440 / 60
    val minutes = totalMinutes % 60
    return buildList {
        if (days > 0) add("$days d")
        if (hours > 0) add("$hours h")
        if (minutes > 0 && days == 0L) add("$minutes min")
    }.ifEmpty { listOf("$days d") }.joinToString(" ")
}
