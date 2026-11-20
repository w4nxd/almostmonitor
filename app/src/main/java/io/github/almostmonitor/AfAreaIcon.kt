package io.github.almostmonitor

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * AF area icons, drawn in the style of the pictograms on the camera's screen.
 * The codes (property 0xD1BA) were identified on the R6 Mark II.
 */
class AfAreaIcon(private val code: Int, color: Int, private val sizePx: Int) : Drawable() {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.075f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        when (code) {
            ONE_POINT -> rect(c, cx, cy, s * 0.36f, s * 0.36f)
            SPOT -> {
                rect(c, cx, cy, s * 0.26f, s * 0.26f)
                c.drawCircle(cx, cy, s * 0.05f, fill)
            }
            EXPAND -> {
                rect(c, cx, cy, s * 0.30f, s * 0.30f)
                val d = s * 0.30f
                for ((dx, dy) in listOf(0f to -d, 0f to d, -d to 0f, d to 0f)) dot(c, cx + dx, cy + dy, s)
            }
            EXPAND_AROUND -> {
                rect(c, cx, cy, s * 0.30f, s * 0.30f)
                val d = s * 0.30f
                for (dx in listOf(-d, 0f, d)) for (dy in listOf(-d, 0f, d)) {
                    if (dx != 0f || dy != 0f) dot(c, cx + dx, cy + dy, s)
                }
            }
            FLEX_ZONE_1 -> brackets(c, cx, cy, s * 0.62f, s * 0.62f, s)
            FLEX_ZONE_2 -> brackets(c, cx, cy, s * 0.42f, s * 0.78f, s)
            FLEX_ZONE_3 -> brackets(c, cx, cy, s * 0.86f, s * 0.44f, s)
            WHOLE_AREA -> brackets(c, cx, cy, s * 0.92f, s * 0.70f, s)
        }
    }

    private fun rect(c: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        c.drawRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, stroke)
    }

    private fun dot(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawCircle(x, y, s * 0.045f, fill)
    }

    /** Four L-shaped corners (the AF zone frame). */
    private fun brackets(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, s: Float) {
        val l = cx - w / 2; val r = cx + w / 2
        val t = cy - h / 2; val bt = cy + h / 2
        val k = s * 0.16f
        c.drawLine(l, t, l + k, t, stroke); c.drawLine(l, t, l, t + k, stroke)
        c.drawLine(r, t, r - k, t, stroke); c.drawLine(r, t, r, t + k, stroke)
        c.drawLine(l, bt, l + k, bt, stroke); c.drawLine(l, bt, l, bt - k, stroke)
        c.drawLine(r, bt, r - k, bt, stroke); c.drawLine(r, bt, r, bt - k, stroke)
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { stroke.colorFilter = cf; fill.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val ONE_POINT = 1
        const val EXPAND = 5
        const val EXPAND_AROUND = 6
        const val SPOT = 10
        const val FLEX_ZONE_1 = 11
        const val FLEX_ZONE_2 = 12
        const val FLEX_ZONE_3 = 13
        const val WHOLE_AREA = 14

        private val names = mapOf(
            ONE_POINT to "1-point AF", EXPAND to "Expand AF area", EXPAND_AROUND to "Expand AF area: Around",
            SPOT to "Spot AF", FLEX_ZONE_1 to "Flexible Zone AF 1", FLEX_ZONE_2 to "Flexible Zone AF 2",
            FLEX_ZONE_3 to "Flexible Zone AF 3", WHOLE_AREA to "Whole area AF"
        )

        fun name(code: Int): String? = names[code]

        /** Icon for a known code, or null (text is shown instead). */
        fun forCode(code: Int, color: Int, sizePx: Int): Drawable? =
            if (code in names) AfAreaIcon(code, color, sizePx).apply { setBounds(0, 0, sizePx, sizePx) } else null
    }
}
