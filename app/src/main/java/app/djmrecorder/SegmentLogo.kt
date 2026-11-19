package app.djmrecorder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot

class SegmentLogo(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segments = arrayOf(
        segment(3f, 2f, 13f, 2f),
        segment(14f, 3f, 14f, 10f),
        segment(14f, 12f, 14f, 19f),
        segment(3f, 20f, 13f, 20f),
        segment(2f, 12f, 2f, 19f),
        segment(2f, 3f, 2f, 10f),
        segment(3f, 11f, 13f, 11f),
        segment(8f, 3f, 8f, 10f),
        segment(8f, 12f, 8f, 19f),
        segment(8f, 12f, 13f, 19f),
        segment(8f, 11f, 13f, 11f)
    )
    private val getWord = word("02345a", "03456", "078")
    private val rectWord = word("014569", "03456", "0345", "078")
    private val white = Color.rgb(250, 246, 240)
    private val red = Color.rgb(255, 65, 79)

    init {
        contentDescription = "Get Rect"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val scale = minOf(width / 140f, height / 30f)
        val animate = ValueAnimator.areAnimatorsEnabled() && isShown && windowVisibility == VISIBLE
        val phase = (SystemClock.uptimeMillis() % 5400L) / 5400.0
        val glow = if (animate) ((1.0 - cos(phase * 2.0 * Math.PI)) / 2.0).toFloat() else 0.7f
        canvas.save()
        canvas.translate(4f * scale, (height - 20f * scale) / 2f)
        canvas.scale(scale, scale)
        paint.color = white
        paint.setShadowLayer(2.2f, 0f, 0f, 0x66fff4e8)
        canvas.drawPath(getWord, paint)
        canvas.translate(60f, 0f)
        paint.color = red
        paint.alpha = (218 + 37 * glow).toInt()
        paint.setShadowLayer(3.2f, 0f, 0f, Color.argb((95 + 120 * glow).toInt(), 255, 24, 52))
        canvas.drawPath(rectWord, paint)
        canvas.restore()
        if (animate) postInvalidateDelayed(50L)
    }

    private fun word(vararg letters: String): Path {
        val bounds = RectF()
        val cell = RectF(0f, 0f, 16f, 20f)
        val italic = Matrix().apply { setSkew(-0.16f, 0f) }
        val fit = Matrix()
        return Path().apply {
            letters.forEachIndexed { index, letter ->
                val glyph = Path()
                for (segment in letter) glyph.addPath(segments[segment.digitToInt(16)])
                glyph.transform(italic)
                glyph.computeBounds(bounds, true)
                fit.setRectToRect(bounds, cell, Matrix.ScaleToFit.FILL)
                glyph.transform(fit)
                addPath(glyph, index * 18f, 0f)
            }
        }
    }

    private fun segment(x0: Float, y0: Float, x1: Float, y1: Float): Path {
        val length = hypot(x1 - x0, y1 - y0)
        val dx = (x1 - x0) / length
        val dy = (y1 - y0) / length
        val bevel = 1.2f
        return Path().apply {
            moveTo(x0, y0)
            lineTo(x0 + (dx - dy) * bevel, y0 + (dy + dx) * bevel)
            lineTo(x1 - (dx + dy) * bevel, y1 - (dy - dx) * bevel)
            lineTo(x1, y1)
            lineTo(x1 - (dx - dy) * bevel, y1 - (dy + dx) * bevel)
            lineTo(x0 + (dx + dy) * bevel, y0 + (dy - dx) * bevel)
            close()
        }
    }
}
