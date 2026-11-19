package app.djmrecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object C {
    const val BG = 0xFF0A0A0D.toInt()
    const val CARD = 0xFF15151B.toInt()
    const val LINE = 0xFF24242D.toInt()
    const val TEXT = 0xFFF2F2F5.toInt()
    const val DIM = 0xFF7C7C8A.toInt()
    const val RED = 0xFFFF2D46.toInt()
    const val GREEN = 0xFF2EE59D.toInt()
    const val AMBER = 0xFFFFB020.toInt()
    const val CYAN = 0xFF3DD6F5.toInt()
    const val LED_OFF = 0xFF24242C.toInt()
}

fun Context.dp(v: Float) = v * resources.displayMetrics.density

/**
 * Big round record button. Idle: dark disc, thin ring, red dot. Armed: the ring breathes. Recording: red square
 * in the middle and a red arc that circles the glowing ring.
 */
class RecordButton(ctx: Context) : View(ctx) {
    /** 0 idle, 1 armed, 2 recording, 3 paused (mixer gone) */
    var mode = 0; set(v) { field = v; invalidate() }
    var sizeDp = 132f; set(v) { field = v; requestLayout() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val s = context.dp(sizeDp).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(cx, cy)
        val ringR = r * 0.74f
        val t = SystemClock.uptimeMillis()
        val pulse = ((kotlin.math.sin(t / 380.0) + 1) / 2).toFloat()
        val live = mode == 2
        val accent = if (mode == 3) C.AMBER else C.RED
        if (live || mode == 3) {
            // glow: stacked translucent strokes, widest first
            for (i in 8 downTo 1) {
                ring.style = Paint.Style.STROKE; ring.color = accent
                ring.strokeWidth = context.dp(3f) + i * context.dp(2.4f)
                ring.alpha = ((26 - i * 2.6f) * (0.75f + 0.25f * pulse)).toInt().coerceAtLeast(2)
                c.drawCircle(cx, cy, ringR, ring)
            }
        }
        fill.style = Paint.Style.FILL; fill.color = 0xFF0F0F13.toInt()
        c.drawCircle(cx, cy, ringR, fill)
        ring.alpha = 255; ring.strokeWidth = context.dp(3f)
        ring.color = if (mode == 1) C.RED else 0xFF2C2C35.toInt()
        if (mode == 1) ring.alpha = (110 + 120 * pulse).toInt()
        c.drawCircle(cx, cy, ringR, ring)
        ring.alpha = 255
        if (live || mode == 3) {
            ring.color = accent; ring.strokeWidth = context.dp(3.5f)
            oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            val start = if (mode == 3) -90f else (t / 14 % 360).toFloat()
            c.drawArc(oval, start, if (mode == 3) 360f else 290f, false, ring)
        }
        fill.color = accent
        if (live) {
            val s = ringR * 0.36f
            c.drawRoundRect(RectF(cx - s, cy - s, cx + s, cy + s), context.dp(6f), context.dp(6f), fill)
        } else if (mode == 3) {
            val s = ringR * 0.36f
            c.drawRoundRect(RectF(cx - s, cy - s, cx + s, cy + s), context.dp(6f), context.dp(6f), fill)
        } else {
            fill.color = C.RED
            c.drawCircle(cx, cy, ringR * 0.40f, fill)
        }
        if (live || mode == 1 || mode == 3) postInvalidateOnAnimation()
    }
}

/**
 * Stereo LED-segment meter: many thin vertical segments, grey going to red near the top, dB value on the right and
 * a peak-hold segment. Values in dBFS.
 */
class LedMeter(ctx: Context) : View(ctx) {
    private val l = floatArrayOf(-90f, -90f)
    private val hold = floatArrayOf(-90f, -90f)
    private val seg = Paint()
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    var clipped = false; private set
    private val rect = RectF()

    fun reset() { l.fill(-90f); hold.fill(-90f); clipped = false; invalidate() }
    fun resetClip() { clipped = false; invalidate() }

    fun set(left: Float, right: Float) {
        l[0] = max(left, l[0] - 1.8f); l[1] = max(right, l[1] - 1.8f)
        for (i in 0..1) hold[i] = if (l[i] > hold[i]) l[i] else hold[i] - 0.45f
        if (left > -0.3f || right > -0.3f) clipped = true
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(38f).toInt())

    private fun segColor(f: Float): Int {
        // grey ramp, then into red over the top ~18%
        val g0 = 0xFF6B6B76.toInt(); val g1 = 0xFFC9C9D2.toInt()
        return if (f < 0.80f) mix(g0, g1, f / 0.80f) else mix(g1, C.RED, ((f - 0.80f) / 0.10f).coerceIn(0f, 1f))
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = ((a shr 16 and 0xff) + ((b shr 16 and 0xff) - (a shr 16 and 0xff)) * t).toInt()
        val g = ((a shr 8 and 0xff) + ((b shr 8 and 0xff) - (a shr 8 and 0xff)) * t).toInt()
        val bl = ((a and 0xff) + ((b and 0xff) - (a and 0xff)) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val rowH = height / 2f
        val labW = context.dp(18f); val valW = context.dp(64f)
        val x0 = labW; val x1 = w - valW
        val pitch = context.dp(4f); val sw = context.dp(2.4f)
        val n = ((x1 - x0) / pitch).toInt().coerceAtLeast(8)
        val segH = context.dp(12f)
        for (ch in 0..1) {
            val cy = rowH * ch + rowH / 2f
            txt.color = C.DIM; txt.textSize = context.dp(12f); txt.textAlign = Paint.Align.LEFT
            c.drawText(if (ch == 0) "L" else "R", 0f, cy + context.dp(4f), txt)
            val lv = l[ch]; val hv = hold[ch]
            val holdIdx = if (hv > -60f) (((hv.coerceIn(-60f, 0f) + 60f) / 60f) * n).toInt().coerceIn(0, n - 1) else -1
            for (i in 0 until n) {
                val db = -60f + 60f * (i + 1) / n
                val f = i / (n - 1f)
                val on = lv >= db
                seg.color = if (on) segColor(f) else C.LED_OFF
                val x = x0 + i * pitch
                rect.set(x, cy - segH / 2, x + sw, cy + segH / 2)
                c.drawRoundRect(rect, sw / 2, sw / 2, seg)
                if (i == holdIdx && !on) {
                    seg.color = segColor(f)
                    c.drawRoundRect(rect, sw / 2, sw / 2, seg)
                }
            }
            val t = if (lv <= -89f) "-- dB" else String.format(Locale.US, "%.1f dB", lv)
            txt.textAlign = Paint.Align.RIGHT; txt.textSize = context.dp(12.5f)
            txt.color = if (lv > -0.3f) C.RED else C.TEXT
            c.drawText(t, w, cy + context.dp(4.5f), txt)
        }
    }
}

/** Bottom navigation: icon + label per tab, the selected one red with an underline. */
class NavBar(ctx: Context, items: List<Pair<Int, String>>, private val onSelect: (Int) -> Unit) : LinearLayout(ctx) {
    private val icons = ArrayList<ImageView>()
    private val labels = ArrayList<TextView>()
    private val bars = ArrayList<View>()
    var selected = 0; private set

    init {
        orientation = HORIZONTAL
        setBackgroundColor(C.BG)
        items.forEachIndexed { i, (res, name) ->
            val col = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(View(ctx), LayoutParams(1, ctx.px(8f)))
            val ic = ctx.icon(res, C.DIM, 22f); icons.add(ic)
            col.addView(ic, LayoutParams(ctx.px(22f), ctx.px(22f)))
            val tvv = ctx.tv(name, 12f, C.DIM, Fonts.MED); labels.add(tvv)
            col.addView(tvv, LayoutParams(-2, -2).apply { topMargin = ctx.px(4f) })
            col.addView(View(ctx), LayoutParams(1, 0, 1f))
            val bar = View(ctx).apply { setBackgroundColor(C.RED) }; bars.add(bar)
            col.addView(bar, LayoutParams(ctx.px(56f), ctx.px(2f)))
            col.isClickable = true
            col.background = ctx.ripple(null)
            col.setOnClickListener { select(i, true) }
            addView(col, LayoutParams(0, -1, 1f))
        }
        select(0, false)
    }

    fun select(i: Int, notify: Boolean) {
        selected = i
        for (k in icons.indices) {
            val on = k == i
            icons[k].setColorFilter(if (on) C.RED else C.DIM)
            labels[k].setTextColor(if (on) C.RED else C.DIM)
            bars[k].visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        if (notify) onSelect(i)
    }
}
