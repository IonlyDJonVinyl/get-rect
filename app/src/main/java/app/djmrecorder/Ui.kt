package app.djmrecorder

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Typography: light for the timer and headings, medium for labels, regular for body. */
object Fonts {
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val REG: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MONO: Typeface = Typeface.MONOSPACE
}

fun Context.px(v: Float) = dp(v).toInt()

fun Context.bg(color: Int, radius: Float, stroke: Int = 0, strokeDp: Float = 1f): GradientDrawable = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius); if (stroke != 0) setStroke(px(strokeDp), stroke)
}

fun Context.tv(s: CharSequence, sp: Float, color: Int = C.TEXT, font: Typeface = Fonts.REG): TextView = TextView(this).apply {
    text = s; textSize = sp; setTextColor(color); typeface = font; includeFontPadding = false
}

/** Small caps section label. */
fun Context.label(s: String, color: Int = C.DIM): TextView = tv(s.uppercase(), 10.5f, color, Fonts.MED).apply { letterSpacing = 0.14f }

fun Context.icon(res: Int, tint: Int, sizeDp: Float): ImageView = ImageView(this).apply {
    setImageResource(res); setColorFilter(tint); scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
}

fun Context.ripple(content: Drawable?, circle: Boolean = false): Drawable {
    val mask: Drawable = if (circle) ShapeDrawable(OvalShape()).apply { paint.color = 0xFFFFFFFF.toInt() } else android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt())
    return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, if (content == null) mask else null)
}

/** Round icon-only button with a ripple. */
fun Context.iconButton(res: Int, tint: Int = C.TEXT, sizeDp: Float = 40f, onTap: () -> Unit): ImageView = ImageView(this).apply {
    setImageResource(res); setColorFilter(tint); scaleType = ImageView.ScaleType.FIT_CENTER
    val p = px(9f); setPadding(p, p, p, p)
    background = ripple(null, circle = true)
    layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
    setOnClickListener { onTap() }
}

fun Context.card(padH: Float = 16f, padV: Float = 14f): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = bg(C.CARD, 16f, C.LINE)
    setPadding(px(padH), px(padV), px(padH), px(padV))
}

fun Context.hline(): View = View(this).apply { setBackgroundColor(C.LINE); layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, px(1f))) }
fun Context.vline(): View = View(this).apply { setBackgroundColor(C.LINE); layoutParams = LinearLayout.LayoutParams(maxOf(1, px(1f)), -1) }

fun lp(w: Int = -1, h: Int = -2, top: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top }

/** Option chip: selected = accent fill. */
fun Context.chip(label: String, onTap: () -> Unit): TextView = tv(label, 13f, C.DIM, Fonts.MED).apply {
    gravity = Gravity.CENTER; setPadding(px(14f), px(9f), px(14f), px(9f))
    setOnClickListener { onTap() }
}

fun Context.styleChip(t: TextView, on: Boolean) {
    t.background = if (on) bg(C.RED, 20f) else bg(C.CARD, 20f, C.LINE)
    t.setTextColor(if (on) 0xFFFFFFFF.toInt() else C.DIM)
}

/** Pill-shaped text button. */
fun Context.pillButton(label: String, primary: Boolean = false, onTap: () -> Unit): TextView =
    tv(label, 13f, if (primary) 0xFFFFFFFF.toInt() else C.TEXT, Fonts.MED).apply {
        gravity = Gravity.CENTER; setPadding(px(16f), px(10f), px(16f), px(10f))
        background = ripple(if (primary) bg(C.RED, 20f) else bg(C.CARD, 20f, C.LINE))
        setOnClickListener { onTap() }
    }

fun Context.styledSwitch(checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
    isChecked = checked
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFF9A9AA6.toInt()))
    trackTintList = ColorStateList(states, intArrayOf(C.RED, 0xFF2E2E38.toInt()))
    setOnCheckedChangeListener { _, on -> onChange(on) }
}

/** A settings row: title + optional subtitle on the left, a switch on the right. */
fun Context.switchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(10f), 0, px(10f))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 15f, C.TEXT, Fonts.REG))
        if (sub != null) col.addView(tv(sub, 12f, C.DIM).apply { setLineSpacing(0f, 1.1f) }, lp(top = px(3f)))
        addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(12f) })
        addView(styledSwitch(checked, onChange))
    }

/**
 * Pads [this] by the system bars (status bar, gesture/navigation bar, display cutout) so nothing is drawn under them.
 * targetSdk 35 is edge-to-edge, so the insets arrive here; on older releases they are already applied and come out as 0.
 */
fun View.fitSystemBars() {
    setOnApplyWindowInsetsListener { v, ins ->
        if (Build.VERSION.SDK_INT >= 30) {
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            @Suppress("DEPRECATION")
            ins.consumeSystemWindowInsets()
        }
    }
    requestApplyInsets()
}

fun FrameLayout.addFill(v: View) = addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
