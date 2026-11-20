package io.github.almostmonitor

import java.io.InputStream

/**
 * 3D LUT in .cube format (Adobe/Resolve).
 * data = RGB triplets, with R varying fastest, then G, then B
 * (exactly the order glTexImage3D expects: width=R, height=G, depth=B).
 */
class CubeLut(
    val size: Int,
    val data: FloatArray,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val title: String?
) {
    companion object {
        fun parse(input: InputStream): CubeLut {
            var size = 0
            var title: String? = null
            val dMin = floatArrayOf(0f, 0f, 0f)
            val dMax = floatArrayOf(1f, 1f, 1f)
            var data: FloatArray? = null
            var idx = 0

            input.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) continue
                    val parts = line.split(Regex("\\s+"))
                    when (parts[0].uppercase()) {
                        "TITLE" -> title = line.substring(5).trim().trim('"')
                        "LUT_3D_SIZE" -> {
                            size = parts[1].toInt()
                            // 129 covers common LUTs (17/33/65); larger would use hundreds of MB
                            require(size in 2..129) { "Unsupported LUT_3D_SIZE: $size (max 129)" }
                            data = FloatArray(size * size * size * 3)
                        }
                        "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs aren't supported, only 3D")
                        "DOMAIN_MIN" -> for (i in 0..2) dMin[i] = parts[i + 1].toFloat()
                        "DOMAIN_MAX" -> for (i in 0..2) dMax[i] = parts[i + 1].toFloat()
                        "LUT_3D_INPUT_RANGE" -> {
                            val lo = parts[1].toFloat(); val hi = parts[2].toFloat()
                            for (i in 0..2) { dMin[i] = lo; dMax[i] = hi }
                        }
                        else -> {
                            val r = parts[0].toFloatOrNull() ?: continue // unknown keyword
                            val d = data ?: throw IllegalArgumentException("LUT_3D_SIZE missing before the data")
                            require(parts.size >= 3) { "Incomplete data line: $line" }
                            require(idx + 3 <= d.size) { "Too many data lines for size $size" }
                            d[idx++] = r
                            d[idx++] = parts[1].toFloat()
                            d[idx++] = parts[2].toFloat()
                        }
                    }
                }
            }
            val d = data ?: throw IllegalArgumentException("The file has no LUT_3D_SIZE")
            require(idx == d.size) { "Incomplete LUT: ${idx / 3} of ${d.size / 3} values" }
            for (i in 0..2) require(dMax[i] > dMin[i]) { "Invalid DOMAIN_MIN/MAX" }
            return CubeLut(size, d, dMin, dMax, title)
        }

        /** 2x2x2 identity LUT, used when no LUT is loaded. */
        fun identity(): CubeLut {
            val d = FloatArray(2 * 2 * 2 * 3)
            var i = 0
            for (b in 0..1) for (g in 0..1) for (r in 0..1) {
                d[i++] = r.toFloat(); d[i++] = g.toFloat(); d[i++] = b.toFloat()
            }
            return CubeLut(2, d, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), "identity")
        }
    }
}
