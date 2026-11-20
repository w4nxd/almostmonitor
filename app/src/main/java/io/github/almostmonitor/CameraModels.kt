package io.github.almostmonitor

/**
 * Canon models for the welcome screen. The app uses PTP with the Canon EOS extensions,
 * common to these cameras; the reverse-engineered codes (AF area, video format, battery, AF block)
 * were verified only on the EOS R6 Mark II, so some features may differ on other models.
 */
object CameraModels {

    enum class Family { EOS_R, EOS_M, EOS_DSLR, OTHER }

    data class Model(val name: String, val family: Family, val tested: Boolean = false)

    val all = listOf(
        Model("EOS R6 Mark II", Family.EOS_R, tested = true),
        Model("EOS R1", Family.EOS_R),
        Model("EOS R3", Family.EOS_R),
        Model("EOS R5 Mark II", Family.EOS_R),
        Model("EOS R5", Family.EOS_R),
        Model("EOS R5 C", Family.EOS_R),
        Model("EOS R6 Mark III", Family.EOS_R),
        Model("EOS R6", Family.EOS_R),
        Model("EOS R7", Family.EOS_R),
        Model("EOS R8", Family.EOS_R),
        Model("EOS R10", Family.EOS_R),
        Model("EOS R50 V", Family.EOS_R),
        Model("EOS R50", Family.EOS_R),
        Model("EOS R100", Family.EOS_R),
        Model("EOS RP", Family.EOS_R),
        Model("EOS R", Family.EOS_R),
        Model("EOS M50 Mark II", Family.EOS_M),
        Model("EOS M50", Family.EOS_M),
        Model("EOS M6 Mark II", Family.EOS_M),
        Model("EOS M200", Family.EOS_M),
        Model("EOS-1D X Mark III", Family.EOS_DSLR),
        Model("EOS 5D Mark IV", Family.EOS_DSLR),
        Model("EOS 6D Mark II", Family.EOS_DSLR),
        Model("EOS 90D", Family.EOS_DSLR),
        Model("EOS 850D", Family.EOS_DSLR),
        Model("EOS 250D", Family.EOS_DSLR),
        Model("EOS 2000D", Family.EOS_DSLR),
        Model("Other Canon EOS camera", Family.OTHER)
    )

    fun byName(name: String?): Model = all.firstOrNull { it.name == name } ?: all.first()

    /** What works on the selected model, stated honestly. */
    fun compatibility(m: Model): String = when {
        m.tested ->
            "✓ Tested on this camera: all features have been verified."
        m.family == Family.EOS_R ->
            "Should work: live view, zebras, peaking, false color, LUTs, scopes and exposure settings " +
            "(shutter, aperture, ISO, exposure compensation, white balance).\n" +
            "May differ: AF frame on screen, AF area icons, video format, battery level and REC indicator " +
            "were mapped on the EOS R6 Mark II and might behave differently on the ${m.name}."
        m.family == Family.EOS_M ->
            "Should work: live view and most monitoring tools.\n" +
            "May not work: camera-specific features such as the AF frame, AF area, video format and battery level."
        m.family == Family.EOS_DSLR ->
            "Should work: live view (the camera must be in Live View / movie mode) and most monitoring tools.\n" +
            "May not work: AF frame, AF area, video format and battery level, which were mapped on mirrorless cameras."
        else ->
            "Most Canon EOS cameras with USB remote shooting (EOS Utility support) should show live view. " +
            "Camera-specific features may not work."
    }

    /** Connection steps for the selected model. */
    fun steps(m: Model): String {
        val s = ArrayList<String>()
        s += "On the camera: if the Set-up menu has \"Choose USB connection app\", select " +
             "\"Photo Import/Remote Control\" (not \"UVC/UAC streaming\" or \"Canon app(s) for iPhone\")."
        s += "Turn off the camera's wireless connection (Wi-Fi / Bluetooth): many Canon cameras disable USB " +
             "while a wireless connection is active."
        if (m.family == Family.EOS_DSLR) {
            s += "Enable Live View shooting on the camera (Shooting menu › Live View shooting: Enable)."
        }
        s += "On the phone: if there is an OTG setting (often under Settings › Additional settings), turn it on."
        s += "Connect the camera to the phone with a USB data cable. Charge-only cables won't work; " +
             "for cameras with a Micro-USB or Mini-USB port use a matching cable or an OTG adapter."
        s += "Turn the camera on. When Android asks whether Almost Monitor may access the camera, tap OK " +
             "and tick \"Always open\" so it connects automatically next time."
        s += "If Canon Camera Connect or another camera app opens instead, close it and clear its " +
             "\"Open by default\" setting."
        s += "While connected, the camera's own menu may be locked; disconnect the cable to use it normally."
        return s.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n\n")
    }
}
