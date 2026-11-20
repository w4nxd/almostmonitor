package io.github.almostmonitor

import kotlin.math.abs
import kotlin.math.pow

/**
 * Camera state read from Canon EOS events (PropValueChanged).
 * Property and value codes come from libgphoto2; Canon doesn't publish them officially,
 * so some (recording, battery) were verified on the R6 II.
 */
data class CameraInfo(
    val mode: String? = null,
    val shutter: String? = null,
    val aperture: String? = null,
    val iso: String? = null,
    val expComp: String? = null,
    val wbCode: Int? = null,
    val kelvin: Int? = null,
    val style: String? = null,
    val af: String? = null,
    val battery: Int? = null,        // percent, if the camera reports it (the R6 II doesn't)
    val batteryBars: Int? = null,    // approximate level 0..4, like the bars on the camera screen
    val recording: Boolean = false,
    val cards: String? = null,       // free card space (+ estimated minutes)
    val focalMm: Int? = null,
    val codes: Map<Int, Int> = emptyMap(),          // current raw values (for the pickers)
    val allowed: Map<Int, List<Int>> = emptyMap(),  // values the camera allows in the current mode
    val cameraZoom: Int = 1,                        // live view magnification done by the camera
    val movie: List<Int>? = null                    // 0xD20D struct: [40][fps*100][resolution][...]
) {
    val movieText: String? get() = movie?.let { CanonProps.movieFormat(it[2], it[1]) }
    val whiteBalance: String? get() = CanonProps.whiteBalance(wbCode, kelvin)

    /** "🔋▮▮▮▯" from the approximate level (or the percentage, if available). */
    fun batteryText(): String? = battery?.let { "🔋$it%" }
        ?: batteryBars?.let { b -> "🔋" + "▮".repeat(b) + "▯".repeat(4 - b) }

    /** Text shown at the top, in the camera screen's order. */
    fun summary(): String = listOfNotNull(
        mode, shutter, aperture, iso, expComp, whiteBalance, style, af,
        focalMm?.let { "${it}mm" }, batteryText()
    ).joinToString("  ")
}

object CanonProps {
    const val EVENT_PROP_VALUE_CHANGED = 0xC189
    const val EVENT_ALLOWED_VALUES_CHANGED = 0xC18A   // [property][form][count][values...]; form 3 = list

    const val APERTURE = 0xD101
    const val SHUTTER = 0xD102
    const val ISO = 0xD103
    const val EXP_COMP = 0xD104
    const val AE_MODE = 0xD105
    const val FOCUS_MODE = 0xD108
    const val WHITE_BALANCE = 0xD109
    const val COLOR_TEMP = 0xD10A
    const val PICTURE_STYLE = 0xD110
    const val BATTERY_INFO = 0xD208        // battery struct; NOT the percentage (shows 100 even at 96%)
    const val BATTERY_LEVEL = 0xD111       // coarse level: 2 with a full battery, 1 with a half-used one

    /**
     * 0xD111 level mapped to bars (0..4). Only 2 = full and 1 = partly used have been seen;
     * the other values are guesses, to be adjusted as they show up in logs.
     */
    fun batteryBars(v: Int): Int? = when (v) {
        2 -> 4
        1 -> 2
        0 -> 1
        else -> null
    }
    const val D238_UNKNOWN = 0xD238        // drops ~1/min with live view, but is NOT the battery (checked: 47 vs 99%)
    const val EVF_RECORD_STATUS = 0xD1B8   // 4 = recording (per libgphoto2); 0 = stopped
    const val RECORDING = 4

    const val AF_AREA = 0xD1BA             // AF method / area in live view (LvAfSystem in libgphoto2)
    const val FOCUS_HALF_PRESS = 7         // R6 II: 0xD108 becomes 7 while half-pressed; not a real mode

    /** Properties you can change from the app. */
    val CONTROLLABLE = setOf(0xD101, 0xD102, 0xD103, 0xD104, 0xD108, 0xD109, 0xD10A, 0xD110, 0xD1BA)

    /** AF method (codes verified on the R6 II); shown as an icon in the UI, see AfAreaIcon. */
    fun afArea(code: Int): String = AfAreaIcon.name(code) ?: "Area $code"

    private val modes = mapOf(0 to "P", 1 to "Tv", 2 to "Av", 3 to "M", 4 to "B", 9 to "Auto", 22 to "A+", 55 to "Fv")
    private val focusModes = mapOf(0 to "One Shot", 1 to "Servo", 2 to "AI Focus", 3 to "MF")
    private val styles = mapOf(
        0x81 to "Standard", 0x82 to "Portrait", 0x83 to "Landscape", 0x84 to "Neutral", 0x85 to "Faithful",
        0x86 to "Mono", 0x87 to "Auto", 0x88 to "Fine Detail", 0x21 to "User 1", 0x22 to "User 2", 0x23 to "User 3"
    )
    private val whiteBalances = mapOf(
        0 to "AWB", 1 to "Daylight", 2 to "Cloudy", 3 to "Tungsten", 4 to "Fluorescent", 5 to "Flash",
        6 to "Custom", 8 to "Shade", 23 to "AWB-W"
    )

    fun mode(code: Int): String? = modes[code]
    fun focusMode(code: Int): String? = focusModes[code]
    fun style(code: Int): String? = styles[code]

    /** WB 9 = manually set color temperature, shown in Kelvin. */
    fun whiteBalance(code: Int?, kelvin: Int?): String? {
        if (code == null) return null
        if (code == 9) return kelvin?.let { "${it}K" } ?: "WB K"
        return whiteBalances[code]
    }

    /** Exposure compensation: signed byte, in eighths of a stop (3 = 1/3, 4 = 1/2, 5 = 2/3). */
    fun expComp(code: Int): String {
        val v = code.toByte().toInt()
        if (v == 0) return "±0"
        val sign = if (v < 0) "−" else "+"
        val a = kotlin.math.abs(v)
        val whole = a / 8
        val frac = when (a % 8) { 3 -> "⅓"; 4 -> "½"; 5 -> "⅔"; else -> "" }
        return sign + (if (whole > 0) whole.toString() else if (frac.isEmpty()) "0" else "") + frac
    }

    private val isoSteps = intArrayOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000,
        2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000,
        51200, 64000, 80000, 102400, 204800
    )
    private val apertureSteps = doubleArrayOf(
        1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1,
        8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0, 25.0, 29.0, 32.0
    )
    private val shutterDenoms = intArrayOf(
        8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250,
        200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3
    )

    /** ISO: 0x48 = 100, +8 = one stop. 0 = Auto. */
    fun iso(code: Int): String? {
        if (code == 0) return "ISO Auto"
        if (code < 0x28 || code > 0xB0) return null
        val v = 100.0 * 2.0.pow((code - 0x48) / 8.0)
        return "ISO " + isoSteps.minByOrNull { abs(it - v) / it }
    }

    /** Aperture: f = 2^((code - 8) / 16). */
    fun aperture(code: Int): String? {
        if (code < 0x08 || code > 0x70) return null
        val f = 2.0.pow((code - 0x08) / 16.0)
        val n = apertureSteps.minByOrNull { abs(it - f) / it } ?: return null
        return "f/" + if (n < 10) String.format(java.util.Locale.US, "%.1f", n) else n.toInt().toString()
    }

    /** Shutter speed: 0x38 = 1 s, +8 = one stop shorter. 0 = Auto, 0x0C = Bulb. */
    fun shutter(code: Int): String? {
        if (code == 0) return null
        if (code == 0x0C) return "Bulb"
        if (code < 0x10 || code > 0xA8) return null
        val t = 2.0.pow(-(code - 0x38) / 8.0)   // seconds
        if (t >= 0.3) {
            val s = if (t >= 1) Math.round(t).toString() else String.format(java.util.Locale.US, "%.1f", t)
            return "$s\""
        }
        val d = shutterDenoms.minByOrNull { abs(1.0 / it - t) / t } ?: return null
        return "1/$d"
    }

    // ---------------- Video format (0xD20D) ----------------
    // Struct (verified on the R6 II): [40][fps*100][resolution][compression][...] (10 x u32).
    // Resolution: 5 = 4K UHD, 0 = Full HD. Other fields are kept as the camera has them.
    const val MOVIE_FORMAT = 0xD20D
    const val SIZE_4K = 5
    const val SIZE_FHD = 0

    fun movieFormat(size: Int, fps100: Int): String {
        val res = when (size) { SIZE_4K -> "4K"; SIZE_FHD -> "FHD"; else -> "S$size" }
        val fps = if (fps100 % 100 == 0) (fps100 / 100).toString()
                  else String.format(java.util.Locale.US, "%.2f", fps100 / 100.0)
        return "$res $fps"
    }

    /**
     * Formats offered in the picker. The video system (NTSC / PAL) is inferred from the current fps;
     * combinations the camera doesn't accept are refused by it (a message appears).
     */
    fun movieChoices(currentFps100: Int): List<Pair<Int, Int>> {
        val ntsc = currentFps100 % 100 != 0
        val k4 = if (ntsc) listOf(2398, 2997, 5994) else listOf(2500, 5000)
        val fhd = if (ntsc) listOf(2398, 2997, 5994, 11988) else listOf(2500, 5000, 10000)
        return k4.map { SIZE_4K to it } + fhd.map { SIZE_FHD to it }
    }

    /** Kelvin values for the picker. */
    fun kelvin(v: Int) = "${v}K"

    /**
     * Allowed values sent by the camera (event 0xC18A). Only form 3 (list) is used.
     */
    inline fun forEachAllowed(buf: ByteArray, off: Int, len: Int, onList: (Int, IntArray) -> Unit) {
        val end = off + len
        var p = off
        while (p + 8 <= end) {
            val size = PtpUsb.getInt(buf, p)
            val type = PtpUsb.getInt(buf, p + 4)
            if (size < 8 || p + size > end) break
            if (type == 0 && size == 8) break
            if (type == EVENT_ALLOWED_VALUES_CHANGED && size >= 20) {
                val prop = PtpUsb.getInt(buf, p + 8)
                val form = PtpUsb.getInt(buf, p + 12)
                val count = PtpUsb.getInt(buf, p + 16)
                if (form == 3 && count in 0..512 && 20 + count * 4 <= size) {
                    onList(prop, IntArray(count) { PtpUsb.getInt(buf, p + 20 + it * 4) })
                }
            }
            p += size
        }
    }

    /**
     * Walks GetEvent data: records [length u32][type u32][...].
     * For PropValueChanged: [length][0xC189][property u32][value u32].
     */
    inline fun forEachProp(buf: ByteArray, off: Int, len: Int, onProp: (Int, Int) -> Unit) {
        val end = off + len
        var p = off
        while (p + 8 <= end) {
            val size = PtpUsb.getInt(buf, p)
            val type = PtpUsb.getInt(buf, p + 4)
            if (size < 8 || p + size > end) break
            if (type == 0 && size == 8) break
            if (type == EVENT_PROP_VALUE_CHANGED && size >= 16) {
                onProp(PtpUsb.getInt(buf, p + 8), PtpUsb.getInt(buf, p + 12))
            }
            p += size
        }
    }
}
