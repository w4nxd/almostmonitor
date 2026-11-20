package io.github.almostmonitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** Waveform, RGB parade or vectorscope, drawn over the image. */
class ScopeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { WAVEFORM, PARADE, VECTOR }

    var mode = Mode.WAVEFORM
    /** The image is mirrored on screen: mirror the waveform too, so they match. */
    var flipX = false

    private var bmp: Bitmap? = null
    private var pixels = IntArray(0)
    private val bg = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val img = Paint(Paint.FILTER_BITMAP_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 170, 120); strokeWidth = 2f
    }
    private val target = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val rect = RectF()
    private val dst = Rect()

    fun setData(d: ScopeData) {
        when (mode) {
            Mode.WAVEFORM -> {
                val wv = d.wave ?: return
                render(ScopeData.COLS, ScopeData.LEVELS) { px ->
                    fillChannel(px, wv, 0, ScopeData.COLS, 190, 255, 190)
                }
            }
            Mode.PARADE -> {
                val pr = d.paradeR ?: return
                val pg = d.paradeG ?: return
                val pb = d.paradeB ?: return
                render(ScopeData.COLS * 3, ScopeData.LEVELS) { px ->
                    val stride = ScopeData.COLS * 3
                    fillChannel(px, pr, 0, stride, 255, 70, 70)
                    fillChannel(px, pg, ScopeData.COLS, stride, 70, 255, 70)
                    fillChannel(px, pb, ScopeData.COLS * 2, stride, 90, 140, 255)
                }
            }
            Mode.VECTOR -> {
                val vc = d.vector ?: return
                render(ScopeData.VEC, ScopeData.VEC) { px ->
                    val max = maxCount(vc)
                    for (i in vc.indices) px[i] = shade(vc[i], max, 230, 230, 230)
                }
            }
        }
        invalidate()
    }

    private inline fun render(w: Int, h: Int, fill: (IntArray) -> Unit) {
        var b = bmp
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp = b
            pixels = IntArray(w * h)
        }
        java.util.Arrays.fill(pixels, 0)
        fill(pixels)
        b.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun maxCount(a: IntArray): Int { var m = 1; for (v in a) if (v > m) m = v; return m }

    /** Copies a COLS x LEVELS channel into the final image at column `offset`. */
    private fun fillChannel(px: IntArray, src: IntArray, offset: Int, stride: Int,
                            r: Int, g: Int, b: Int) {
        val cols = ScopeData.COLS
        val max = maxCount(src)
        for (row in 0 until ScopeData.LEVELS) {
            for (c in 0 until cols) {
                val v = src[row * cols + c]
                if (v == 0) continue
                val dc = if (flipX) cols - 1 - c else c
                px[row * stride + offset + dc] = shade(v, max, r, g, b)
            }
        }
    }

    /** Log-scale intensity, so sparse areas stay visible. */
    private fun shade(v: Int, max: Int, r: Int, g: Int, b: Int): Int {
        if (v <= 0) return 0
        val a = (60 + 195 * ln(1.0 + v) / ln(1.0 + max)).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 12f, 12f, bg)
        val pad = 6
        dst.set(pad, pad, width - pad, height - pad)
        val iw = dst.width().toFloat()
        val ih = dst.height().toFloat()
        val l = dst.left.toFloat()
        val t = dst.top.toFloat()

        if (mode == Mode.VECTOR) {
            val cx = l + iw / 2; val cy = t + ih / 2
            val rad = minOf(iw, ih) / 2
            canvas.drawCircle(cx, cy, rad, grid)
            canvas.drawLine(cx - rad, cy, cx + rad, cy, grid)
            canvas.drawLine(cx, cy - rad, cx, cy + rad, grid)
            // skin tone line, at ~123 degrees
            val a = Math.toRadians(123.0)
            canvas.drawLine(cx, cy, cx + (rad * cos(a)).toFloat(), cy - (rad * sin(a)).toFloat(), skin)
            // 75% primary color targets
            drawTarget(canvas, cx, cy, rad, 0.75f, 0f, 0f, Color.RED)
            drawTarget(canvas, cx, cy, rad, 0.75f, 0.75f, 0f, Color.YELLOW)
            drawTarget(canvas, cx, cy, rad, 0f, 0.75f, 0f, Color.GREEN)
            drawTarget(canvas, cx, cy, rad, 0f, 0.75f, 0.75f, Color.CYAN)
            drawTarget(canvas, cx, cy, rad, 0f, 0f, 0.75f, Color.BLUE)
            drawTarget(canvas, cx, cy, rad, 0.75f, 0f, 0.75f, Color.MAGENTA)
        } else {
            // horizontal lines at 0 / 25 / 50 / 75 / 100 %
            for (k in 0..4) {
                val y = t + ih * k / 4f
                canvas.drawLine(l, y, l + iw, y, grid)
            }
            if (mode == Mode.PARADE) {
                canvas.drawLine(l + iw / 3, t, l + iw / 3, t + ih, grid)
                canvas.drawLine(l + 2 * iw / 3, t, l + 2 * iw / 3, t + ih, grid)
            }
        }
        bmp?.let { canvas.drawBitmap(it, null, dst, img) }
    }

    private fun drawTarget(c: Canvas, cx: Float, cy: Float, rad: Float, r: Float, g: Float, b: Float, color: Int) {
        val cb = -0.1146f * r - 0.3854f * g + 0.5f * b
        val cr = 0.5f * r - 0.4542f * g - 0.0458f * b
        val x = cx + cb * 2 * rad
        val y = cy - cr * 2 * rad
        target.color = color
        c.drawRect(x - 4, y - 4, x + 4, y + 4, target)
    }
}
