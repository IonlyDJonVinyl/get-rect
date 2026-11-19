package app.djmrecorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object WaveColors {
    const val LOW = 0xFF2A6BFF.toInt()
    const val MID = 0xFFFF9A1F.toInt()
    const val HIGH = 0xFFFFFFFF.toInt()
    const val MARK = 0xFFFFB020.toInt()
    const val EVENT = 0xFFFF2D46.toInt()
    const val PANEL = 0xFF0F0F14.toInt()
}

private class WaveEnvelope {
    private val paths = Array(3) { Path() }
    private val paints = intArrayOf(WaveColors.LOW, WaveColors.MID, WaveColors.HIGH).map { color ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    }

    fun begin(x: Float) {
        for (path in paths) {
            path.rewind()
            path.moveTo(x, 0f)
        }
    }

    fun point(x: Float, scale: Float, low: Int, mid: Int, high: Int) {
        paths[0].lineTo(x, -low * scale)
        paths[1].lineTo(x, -mid * scale)
        paths[2].lineTo(x, -high * scale)
    }

    fun draw(canvas: Canvas, right: Float, center: Float) {
        canvas.save()
        canvas.translate(0f, center)
        for ((index, path) in paths.withIndex()) {
            path.lineTo(right, 0f)
            path.close()
            canvas.drawPath(path, paints[index])
            canvas.scale(1f, -1f)
            canvas.drawPath(path, paints[index])
            canvas.scale(1f, -1f)
        }
        canvas.restore()
    }
}

/** Mirrored 3-band columns, rekordbox style: low (blue) behind, mid (amber), high (white) on top. */
object WaveDraw {
    private val detailWave = WaveEnvelope()
    private val pl = Paint().apply { color = WaveColors.LOW }
    private val pm = Paint().apply { color = WaveColors.MID }
    private val ph = Paint().apply { color = WaveColors.HIGH }

    fun column(c: Canvas, x0: Float, x1: Float, cy: Float, half: Float, packed: Int) {
        val l = (packed shr 16) and 0xff; val m = (packed shr 8) and 0xff; val h = packed and 0xff
        if (l == 0 && m == 0 && h == 0) { c.drawRect(x0, cy - 0.5f, x1, cy + 0.5f, pl); return }
        var a = l / 255f * half; c.drawRect(x0, cy - max(a, 0.5f), x1, cy + max(a, 0.5f), pl)
        a = m / 255f * half; c.drawRect(x0, cy - a, x1, cy + a, pm)
        a = h / 255f * half; c.drawRect(x0, cy - a, x1, cy + a, ph)
    }

    /**
     * Scrolling detail waveform. [center] is the entry (10 ms unit, fractional) drawn at [playX];
     * [perPx] is entries per pixel. Draws into rect (0, top, w, top+h) of [c].
     */
    fun detail(c: Canvas, d: WaveData?, center: Float, perPx: Float, w: Int, top: Float, h: Float, playX: Float) {
        if (d == null) return
        val start = max(0f, playX - center / perPx)
        val end = min(w.toFloat(), playX + (d.count - center) / perPx)
        if (start >= end) return
        detailWave.begin(start)
        val scale = h * 0.48f / 255f
        var x = start
        while (x < end) {
            val next = min(x + 1f, end)
            val i0 = floor(center + (x - playX) * perPx).toInt()
            val i1 = ceil(center + (next - playX) * perPx).toInt()
            val packed = WaveData.maxRange(d, i0, max(i0 + 1, i1))
            detailWave.point((x + next) / 2f, scale, (packed shr 16) and 0xff, (packed shr 8) and 0xff, packed and 0xff)
            x = next
        }
        detailWave.draw(c, end, top + h / 2f)
    }
}

/** Cached whole-recording strip. Rebuilt only when size/data change (cheap per frame). */
class OverviewRenderer {
    private var bmp: Bitmap? = null
    private var builtCount = -1; private var builtTotal = -1; private var builtData: WaveData? = null
    private var builtAt = 0L

    fun draw(c: Canvas, d: WaveData?, total: Int, x: Float, y: Float, w: Int, h: Int, minIntervalMs: Long, force: Boolean = false) {
        if (w <= 0 || h <= 0 || d == null || total <= 0) return
        val now = SystemClock.uptimeMillis()
        var b = bmp
        val sizeChanged = b == null || b.width != w || b.height != h
        val stale = d !== builtData || d.count != builtCount || total != builtTotal
        if (sizeChanged || force || (stale && now - builtAt >= minIntervalMs)) {
            if (sizeChanged) { b?.recycle(); b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp = b }
            val bc = Canvas(b!!)
            b.eraseColor(0)
            val cy = h / 2f; val half = h / 2f * 0.96f
            val cnt = d.count
            for (col in 0 until w) {
                val i0 = (col.toLong() * total / w).toInt()
                val i1 = max((((col + 1).toLong() * total) / w).toInt(), i0 + 1)
                if (i0 >= cnt) break
                WaveDraw.column(bc, col.toFloat(), col + 1f, cy, half, WaveData.maxRange(d, i0, i1))
            }
            builtCount = d.count; builtTotal = total; builtData = d; builtAt = now
        }
        bmp?.let { c.drawBitmap(it, x, y, null) }
    }
}

private fun markerPaint(ctx: Context) = Paint().apply { color = WaveColors.MARK; strokeWidth = max(1f, ctx.dp(1.5f)) }

/** Red dropout marker: a solid line at the start plus a translucent band as wide as the missing time. */
private class EventPaint(ctx: Context) {
    val line = Paint().apply { color = WaveColors.EVENT; strokeWidth = max(2f, ctx.dp(2.5f)) }
    val band = Paint().apply { color = WaveColors.EVENT; alpha = 90 }
    fun draw(c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float) {
        if (x1 - x0 > line.strokeWidth) c.drawRect(x0, top, x1, bottom, band)
        c.drawRect(x0 - line.strokeWidth / 2, top, x0 + line.strokeWidth / 2, bottom, line)
    }
}

/** One thin vertical tick over the full height of a view (markers, track changes). */
private fun tick(c: Canvas, x: Float, top: Float, bottom: Float, p: Paint) {
    c.drawRect(x - p.strokeWidth / 2, top, x + p.strokeWidth / 2, bottom, p)
}

/**
 * Record screen: two lanes (L on top, R below), the playhead in the middle and the history scrolling away to the left.
 * Each lane keeps the 3-band colouring (blue low, amber mid, white high) but is sized by that channel's own peak.
 */
class LiveWaveView(ctx: Context) : View(ctx) {
    /** Owned by RecordService (survives the activity); this view only draws it. */
    var data: WaveData = WaveData()
    var markers: List<Double> = emptyList()      // seconds, white ticks
    var events: List<DropEvent> = emptyList()    // red
    var tracks: List<TrackEntry> = emptyList()   // cyan ticks
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.RED; strokeWidth = ctx.dp(1.5f) }
    private val mk = Paint().apply { color = 0xFFF2F2F5.toInt(); strokeWidth = max(1f, ctx.dp(1.5f)) }
    private val tk = Paint().apply { color = C.CYAN; strokeWidth = max(1.5f, ctx.dp(2f)) }
    private val ev = EventPaint(ctx)
    private val axis = Paint().apply { color = C.LINE; strokeWidth = max(1f, ctx.dp(1f)) }
    private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.DIM; textSize = ctx.dp(12f); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private val leftWave = WaveEnvelope()
    private val rightWave = WaveEnvelope()
    private val tmp = IntArray(5)
    private var lastPush = 0L
    var active = false; set(v) { field = v; invalidate() }
    /** Seconds shown across the whole width (the left half holds the past). */
    var windowSec = 16f
    var minHeightDp = 120f

    /** Call after the service appended entries. */
    fun pushed() { lastPush = SystemClock.uptimeMillis(); invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val hh = MeasureSpec.getSize(h)

        setMeasuredDimension(MeasureSpec.getSize(w), if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) context.dp(minHeightDp).toInt() else hh)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val left = context.dp(24f)
        val gap = context.dp(8f)
        val laneH = (h - gap) / 2f
        val half = laneH / 2f * 0.94f
        val cyL = laneH / 2f; val cyR = laneH + gap + laneH / 2f
        val playX = (left + w) / 2f
        val span = w - left
        val perPx = windowSec * 100f / span
        // interpolate between 80 ms pushes so the scroll is smooth
        val frac = if (active && lastPush > 0) min(10f, (SystemClock.uptimeMillis() - lastPush) / 10f) else 0f
        val center = data.count + frac
        c.drawText("L", 0f, cyL + context.dp(4f), lab)
        c.drawText("R", 0f, cyR + context.dp(4f), lab)
        c.drawRect(left, cyL - 0.5f, playX, cyL + 0.5f, axis)
        c.drawRect(left, cyR - 0.5f, playX, cyR + 0.5f, axis)
        c.save(); c.clipRect(left, 0f, w, h)
        val start = max(left, playX - center / perPx)
        val end = min(playX, playX + (data.count - center) / perPx)
        if (start < end) {
            leftWave.begin(start)
            rightWave.begin(start)
            var x = start
            while (x < end) {
                val next = min(x + 1f, end)
                val i0 = floor(center + (x - playX) * perPx).toInt()
                val i1 = ceil(center + (next - playX) * perPx).toInt()
                WaveData.maxRangeLR(data, i0, max(i0 + 1, i1), tmp)
                val peak = max(1, max(tmp[0], max(tmp[1], tmp[2])))
                val scale = half / (255f * peak)
                val sampleX = (x + next) / 2f
                leftWave.point(sampleX, tmp[3] * scale, tmp[0], tmp[1], tmp[2])
                rightWave.point(sampleX, tmp[4] * scale, tmp[0], tmp[1], tmp[2])
                x = next
            }
            leftWave.draw(c, end, cyL)
            rightWave.draw(c, end, cyR)
        }
        for (m in markers) { val xx = playX + (m.toFloat() * 100f - center) / perPx; if (xx in left..playX) tick(c, xx, 0f, h, mk) }
        for (t in tracks) { val xx = playX + (t.sec.toFloat() * 100f - center) / perPx; if (xx in left..playX) tick(c, xx, 0f, h, tk) }
        for (e in events) {
            val x0 = playX + (e.sec.toFloat() * 100f - center) / perPx
            val x1 = playX + ((e.sec + e.ms / 1000.0).toFloat() * 100f - center) / perPx
            if (x1 >= left && x0 <= playX) ev.draw(c, x0, x1, 0f, h)
        }
        c.restore()
        // playhead with a small cap
        c.drawRect(playX - head.strokeWidth / 2, 0f, playX + head.strokeWidth / 2, h, head)
        c.drawCircle(playX, context.dp(2.5f), context.dp(2.5f), head)
        if (active) postInvalidateOnAnimation()
    }
}

/** Detail screen: whole-recording overview with trim handles, playhead, markers, tap/drag seek. */
class OverviewView(ctx: Context) : View(ctx) {
    var data: WaveData? = null; set(v) { field = v; invalidate() }
    var totalEntries = 0; set(v) { field = v; invalidate() }
    var durationSec = 0.0
    var markers: List<Double> = emptyList(); set(v) { field = v; invalidate() }
    var events: List<DropEvent> = emptyList(); set(v) { field = v; invalidate() }
    var tracks: List<Double> = emptyList(); set(v) { field = v; invalidate() }
    var playSec = 0.0; set(v) { field = v; invalidate() }
    var inSec = 0.0; set(v) { field = v; invalidate() }
    var outSec = 0.0; set(v) { field = v; invalidate() }
    var onSeek: ((Double) -> Unit)? = null
    var onTrim: ((Double, Double) -> Unit)? = null
    private val ov = OverviewRenderer()
    private val panel = Paint().apply { color = WaveColors.PANEL }
    private val shade = Paint().apply { color = 0xB00A0A0D.toInt() }
    private val head = Paint().apply { color = C.RED; strokeWidth = ctx.dp(2f) }
    private val handleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.GREEN }
    private val mk = markerPaint(ctx)
    private val evp = EventPaint(ctx)
    private val trk = Paint().apply { color = C.CYAN; strokeWidth = max(1.5f, ctx.dp(2f)) }
    private var drag = 0 // 0 none, 1 IN, 2 OUT, 3 seek

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), measureH(h, 72f))

    private fun xOf(sec: Double) = if (durationSec <= 0) 0f else (sec / durationSec * width).toFloat()
    private fun secOf(x: Float) = (x / max(1, width) * durationSec).coerceIn(0.0, durationSec)

    override fun onDraw(c: Canvas) {
        val w = width; val h = height
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), context.dp(8f), context.dp(8f), panel)
        ov.draw(c, data, totalEntries, 0f, 0f, w, h, 0)
        val xi = xOf(inSec); val xo = xOf(outSec)
        c.drawRect(0f, 0f, xi, h.toFloat(), shade); c.drawRect(xo, 0f, w.toFloat(), h.toFloat(), shade)
        for (m in markers) { val x = xOf(m); c.drawRect(x - mk.strokeWidth / 2, 0f, x + mk.strokeWidth / 2, h.toFloat(), mk) }
        for (e in events) evp.draw(c, xOf(e.sec), xOf(e.sec + e.ms / 1000.0), 0f, h.toFloat())
        for (t in tracks) { val x = xOf(t); c.drawRect(x - trk.strokeWidth / 2, 0f, x + trk.strokeWidth / 2, h.toFloat(), trk) }
        val px = xOf(playSec); c.drawRect(px - head.strokeWidth / 2, 0f, px + head.strokeWidth / 2, h.toFloat(), head)
        val hw = context.dp(10f)
        for ((x, isIn) in listOf(xi to true, xo to false)) {
            c.drawRect(x - 1.5f, 0f, x + 1.5f, h.toFloat(), handleP)
            val r = if (isIn) RectF(x, 0f, x + hw, hw * 1.6f) else RectF(x - hw, h - hw * 1.6f, x, h.toFloat())
            c.drawRoundRect(r, 4f, 4f, handleP)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (durationSec <= 0) return true
        val slop = context.dp(26f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val di = abs(e.x - xOf(inSec)); val dout = abs(e.x - xOf(outSec))
                drag = when {
                    di <= slop && di <= dout -> 1
                    dout <= slop -> 2
                    else -> 3
                }
                apply(e.x)
            }
            MotionEvent.ACTION_MOVE -> apply(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = 0; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    private fun apply(x: Float) {
        val s = secOf(x); val minGap = 0.5
        when (drag) {
            1 -> onTrim?.invoke(min(s, outSec - minGap).coerceAtLeast(0.0), outSec)
            2 -> onTrim?.invoke(inSec, max(s, inSec + minGap).coerceAtMost(durationSec))
            3 -> onSeek?.invoke(s)
        }
    }
}

/** Detail screen: zoomed waveform around the playhead (centre). Pinch to zoom, drag to scrub. */
class ZoomView(ctx: Context) : View(ctx) {
    var data: WaveData? = null; set(v) { field = v; invalidate() }
    var markers: List<Double> = emptyList(); set(v) { field = v; invalidate() }
    var events: List<DropEvent> = emptyList(); set(v) { field = v; invalidate() }
    var tracks: List<Double> = emptyList(); set(v) { field = v; invalidate() }
    var playSec = 0.0; set(v) { field = v; invalidate() }
    var inSec = 0.0; set(v) { field = v; invalidate() }
    var outSec = 0.0; set(v) { field = v; invalidate() }
    var windowSec = 10f; set(v) { field = v.coerceIn(2f, 120f); invalidate() }
    var onScrub: ((Double) -> Unit)? = null     // new absolute position in seconds
    var onWindow: ((Float) -> Unit)? = null
    private val panel = Paint().apply { color = WaveColors.PANEL }
    private val shade = Paint().apply { color = 0xB00A0A0D.toInt() }
    private val head = Paint().apply { color = C.RED; strokeWidth = ctx.dp(2f) }
    private val mk = markerPaint(ctx)
    private val edge = Paint().apply { color = C.GREEN; strokeWidth = ctx.dp(2f) }
    private val evp = EventPaint(ctx)
    private val trk = Paint().apply { color = C.CYAN; strokeWidth = max(1.5f, ctx.dp(2f)) }
    private var lastX = 0f; private var scrubbing = false
    private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            windowSec = windowSec / d.scaleFactor; onWindow?.invoke(windowSec); return true
        }
    })

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), measureH(h, 92f))

    override fun onDraw(c: Canvas) {
        val w = width; val h = height.toFloat()
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h), context.dp(8f), context.dp(8f), panel)
        val playX = w / 2f
        val perPx = windowSec * 100f / w
        val center = (playSec * 100).toFloat()
        c.save(); c.clipRect(0f, 0f, w.toFloat(), h)
        WaveDraw.detail(c, data, center, perPx, w, 0f, h, playX)
        val xi = playX + ((inSec * 100).toFloat() - center) / perPx
        val xo = playX + ((outSec * 100).toFloat() - center) / perPx
        if (xi > 0) c.drawRect(0f, 0f, min(xi, w.toFloat()), h, shade)
        if (xo < w) c.drawRect(max(xo, 0f), 0f, w.toFloat(), h, shade)
        if (xi in 0f..w.toFloat()) c.drawRect(xi - 1f, 0f, xi + 1f, h, edge)
        if (xo in 0f..w.toFloat()) c.drawRect(xo - 1f, 0f, xo + 1f, h, edge)
        for (m in markers) {
            val x = playX + ((m * 100).toFloat() - center) / perPx
            if (x in 0f..w.toFloat()) c.drawRect(x - mk.strokeWidth / 2, 0f, x + mk.strokeWidth / 2, h, mk)
        }
        for (t in tracks) {
            val x = playX + ((t * 100).toFloat() - center) / perPx
            if (x in 0f..w.toFloat()) c.drawRect(x - trk.strokeWidth / 2, 0f, x + trk.strokeWidth / 2, h, trk)
        }
        for (e in events) {
            val x0 = playX + ((e.sec * 100).toFloat() - center) / perPx
            val x1 = playX + (((e.sec + e.ms / 1000.0) * 100).toFloat() - center) / perPx
            if (x1 >= 0f && x0 <= w) evp.draw(c, x0, x1, 0f, h)
        }
        c.drawRect(playX - head.strokeWidth / 2, 0f, playX + head.strokeWidth / 2, h, head)
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); lastX = e.x; scrubbing = true }
            MotionEvent.ACTION_POINTER_DOWN -> scrubbing = false
            MotionEvent.ACTION_POINTER_UP -> { scrubbing = false; lastX = e.x }
            MotionEvent.ACTION_MOVE -> if (!scale.isInProgress && e.pointerCount == 1) {
                if (!scrubbing) { scrubbing = true; lastX = e.x }
                val dx = e.x - lastX; lastX = e.x
                val secPerPx = windowSec / max(1, width)
                onScrub?.invoke(playSec - dx * secPerPx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { scrubbing = false; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
}

/** Height from an exact spec, else [defDp]. */
private fun View.measureH(spec: Int, defDp: Float): Int =
    if (View.MeasureSpec.getMode(spec) == View.MeasureSpec.EXACTLY) View.MeasureSpec.getSize(spec) else context.dp(defDp).toInt()
