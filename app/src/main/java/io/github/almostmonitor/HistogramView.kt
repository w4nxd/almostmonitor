package io.github.almostmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Luma or RGB histogram, drawn over the image. */
class HistogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var rgb = false
        set(v) { field = v; invalidate() }

    private var luma: IntArray? = null
    private var r: IntArray? = null
    private var g: IntArray? = null
    private var b: IntArray? = null

    private val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val lumaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 235, 235, 235) }
    private val rPaint = channelPaint(Color.argb(170, 255, 40, 40))
    private val gPaint = channelPaint(Color.argb(170, 40, 255, 40))
    private val bPaint = channelPaint(Color.argb(170, 60, 110, 255))
    private val grid = Paint().apply { color = Color.argb(90, 255, 255, 255); strokeWidth = 1f }
    private val clip = Paint().apply { color = Color.rgb(255, 59, 48) }
    private val path = Path()
    private val rect = RectF()

    private fun channelPaint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)   // overlaps become yellow / cyan / white
    }

    fun setData(d: ScopeData) {
        luma = d.luma
        r = d.r; g = d.g; b = d.b
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 12f, 12f, bg)
        val pad = 6f
        for (k in 1..3) {
            val x = pad + (w - 2 * pad) * k / 4f
            canvas.drawLine(x, pad, x, h - pad, grid)
        }
        val rr = r; val gg = g; val bb = b
        if (rgb && rr != null && gg != null && bb != null) {
            val save = canvas.saveLayer(null, null)
            drawBins(canvas, rr, rPaint, pad)
            drawBins(canvas, gg, gPaint, pad)
            drawBins(canvas, bb, bPaint, pad)
            canvas.restoreToCount(save)
            if (rr.last() > 0 || gg.last() > 0 || bb.last() > 0) drawClip(canvas, pad)
        } else {
            val l = luma ?: return
            drawBins(canvas, l, lumaPaint, pad)
            if (l.last() > 0) drawClip(canvas, pad)
        }
    }

    private fun drawBins(canvas: Canvas, bins: IntArray, paint: Paint, pad: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val iw = w - 2 * pad; val ih = h - 2 * pad
        // scale by the max excluding the ends, so crushed-black / clipped-white spikes don't flatten the rest
        var max = 1
        for (i in 1 until bins.size - 1) if (bins[i] > max) max = bins[i]
        val n = bins.size
        path.reset()
        path.moveTo(pad, h - pad)
        for (i in 0 until n) {
            val v = (bins[i].toFloat() / max).coerceAtMost(1f)
            val y = h - pad - v * ih
            path.lineTo(pad + iw * i / n, y)
            path.lineTo(pad + iw * (i + 1) / n, y)
        }
        path.lineTo(pad + iw, h - pad)
        path.close()
        canvas.drawPath(path, paint)
    }

    /** Red bar on the right: there are clipped pixels. */
    private fun drawClip(canvas: Canvas, pad: Float) {
        canvas.drawRect(width - pad - 4f, pad, width - pad, height - pad, clip)
    }
}
