package io.github.almostmonitor

import android.graphics.Bitmap

/**
 * Exposure scope data, computed from a live view frame
 * (the camera image, before the LUT). Null fields weren't requested.
 */
class ScopeData(
    val luma: IntArray?,                 // luma histogram, LEVELS bins
    val r: IntArray?, val g: IntArray?, val b: IntArray?,   // RGB histograms
    val wave: IntArray?,                 // waveform: LEVELS x COLS (row 0 = 100%)
    val paradeR: IntArray?, val paradeG: IntArray?, val paradeB: IntArray?,
    val vector: IntArray?                // vectorscope: VEC x VEC (Cb horizontal, Cr up)
) {
    companion object {
        const val COLS = 128
        const val LEVELS = 64
        const val VEC = 64

        const val HIST = 1
        const val HIST_RGB = 2
        const val WAVE = 4
        const val PARADE = 8
        const val VECTOR = 16

        private var rowBuf = IntArray(0)

        /** Samples one pixel in 4 in each direction. Called only from the camera thread. */
        fun compute(bm: Bitmap, flags: Int): ScopeData {
            val w = bm.width
            val h = bm.height
            val luma = if (flags and (HIST or HIST_RGB) != 0) IntArray(LEVELS) else null
            val rgb = flags and HIST_RGB != 0
            val hr = if (rgb) IntArray(LEVELS) else null
            val hg = if (rgb) IntArray(LEVELS) else null
            val hb = if (rgb) IntArray(LEVELS) else null
            val wave = if (flags and WAVE != 0) IntArray(COLS * LEVELS) else null
            val par = flags and PARADE != 0
            val pr = if (par) IntArray(COLS * LEVELS) else null
            val pg = if (par) IntArray(COLS * LEVELS) else null
            val pb = if (par) IntArray(COLS * LEVELS) else null
            val vec = if (flags and VECTOR != 0) IntArray(VEC * VEC) else null

            if (rowBuf.size < w) rowBuf = IntArray(w)
            val row = rowBuf
            var y = 0
            while (y < h) {
                bm.getPixels(row, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    val c = row[x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val l = (r * 54 + g * 183 + b * 19) shr 8          // Rec.709, 0..255
                    val col = x * COLS / w
                    if (luma != null) luma[l shr 2]++
                    if (hr != null) { hr[r shr 2]++; hg!![g shr 2]++; hb!![b shr 2]++ }
                    if (wave != null) wave[(LEVELS - 1 - (l shr 2)) * COLS + col]++
                    if (pr != null) {
                        pr[(LEVELS - 1 - (r shr 2)) * COLS + col]++
                        pg!![(LEVELS - 1 - (g shr 2)) * COLS + col]++
                        pb!![(LEVELS - 1 - (b shr 2)) * COLS + col]++
                    }
                    if (vec != null) {
                        // Rec.709: Cb, Cr in -128..127
                        val cb = (-29 * r - 99 * g + 128 * b) shr 8
                        val cr = (128 * r - 116 * g - 12 * b) shr 8
                        val vx = (((cb + 128) * VEC) shr 8).coerceIn(0, VEC - 1)
                        val vy = (((128 - cr) * VEC) shr 8).coerceIn(0, VEC - 1)
                        vec[vy * VEC + vx]++
                    }
                    x += 4
                }
                y += 4
            }
            return ScopeData(luma, hr, hg, hb, wave, pr, pg, pb, vec)
        }
    }
}
