package io.github.almostmonitor

import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity(), CanonLiveViewController.Listener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MonitorRenderer
    private lateinit var camera: CanonLiveViewController
    private lateinit var prefs: SharedPreferences

    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var recLabel: TextView
    private lateinit var recFrame: View
    private lateinit var panel: View
    private lateinit var bar: LinearLayout  // right column: camera settings
    private lateinit var leftCol: View      // left column: REC + quick buttons
    private lateinit var quickCol: LinearLayout
    private lateinit var scopesOverlay: LinearLayout
    private val quick = ArrayList<Pair<TextView, CompoundButton>>()
    private lateinit var root: View
    private lateinit var lockBtn: View
    private lateinit var btnGuide: Button
    private lateinit var swLutOn: CompoundButton
    private lateinit var picker: ScrollView
    private lateinit var pickerList: LinearLayout
    private lateinit var histogram: HistogramView
    private lateinit var waveView: ScopeView
    private lateinit var paradeView: ScopeView
    private lateinit var vectorView: ScopeView
    private lateinit var zebraLabel: TextView
    private lateinit var peakLabel: TextView
    private lateinit var peakColorBtn: Button
    private lateinit var squeezeBtn: Button
    private lateinit var lutName: TextView
    private lateinit var swLock: CompoundButton

    private var resumed = false
    private var lutLoaded = false
    private var locked = false
    private var recIndicator = true
    private var touchAfOn = true
    private var pickerKey = ""

    /** A tile in the right column: small label + value. */
    private class Tile(val box: LinearLayout, val label: TextView, val value: TextView)

    /** A row in the vertical picker: text or an icon (drawn in the given color). */
    private class PickerItem(val text: String, val icon: ((Int) -> Drawable?)? = null, val action: () -> Unit)
    private val tiles = LinkedHashMap<String, Tile>()
    private var lastInfo = CameraInfo()
    private var streamInfo = ""
    private var streamAspect = 16f / 9f
    private var recStart = 0L          // SystemClock.elapsedRealtime() when recording started
    private var lastFrames = 0L
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val f = renderer.framesReceived
            val perSec = f - lastFrames
            lastFrames = f
            val z = renderer.zoom
            // Top bar: mode, focal length, battery, cards (+ app zoom, if active)
            val parts = listOfNotNull(
                lastInfo.mode,
                lastInfo.focalMm?.let { "${it}mm" },
                lastInfo.batteryText(),
                lastInfo.cards?.replace("\n", "  "),
                if (z > 1.01f) "zoom ${"%.1f".format(z)}×" else null
            )
            info.text = parts.joinToString("   ·   ")
            info.visibility = vis(parts.isNotEmpty())
            updateRecLabel()
            refreshQuick()
            ticker.postDelayed(this, 1000)
        }
    }

    private val peakColors = listOf(
        "Red" to floatArrayOf(1f, 0f, 0f),
        "Yellow" to floatArrayOf(1f, 0.9f, 0f),
        "Blue" to floatArrayOf(0.1f, 0.5f, 1f),
        "Green" to floatArrayOf(0f, 1f, 0.2f),
        "White" to floatArrayOf(1f, 1f, 1f)
    )

    private val guides = listOf(
        "GUIDE" to 0f, "2.39:1" to 2.39f, "1.85:1" to 1.85f, "16:9" to 16f / 9f,
        "4:5" to 0.8f, "9:16" to 9f / 16f, "1:1" to 1f
    )
    private var guideIdx = 0

    private val squeezes = floatArrayOf(1f, 1.33f, 1.5f, 1.8f, 2f)
    private var squeezeIdx = 0

    private val pickLut =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) { }
                loadLut(uri, enable = true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("monitor", MODE_PRIVATE)
        hideSystemBars()

        glView = findViewById(R.id.glView)
        status = findViewById(R.id.status)
        info = findViewById(R.id.info)
        recLabel = findViewById(R.id.recLabel)
        recFrame = findViewById(R.id.recFrame)
        panel = findViewById(R.id.panel)
        bar = findViewById(R.id.rightCol)
        leftCol = findViewById(R.id.leftCol)
        quickCol = findViewById(R.id.quickCol)
        scopesOverlay = findViewById(R.id.scopesOverlay)
        root = findViewById(R.id.root)
        lockBtn = findViewById(R.id.lockBtn)
        btnGuide = findViewById(R.id.btnGuide)
        swLutOn = findViewById(R.id.swLutOn)
        picker = findViewById(R.id.picker)
        pickerList = findViewById(R.id.pickerList)
        histogram = findViewById(R.id.histogram)
        waveView = findViewById(R.id.waveView)
        paradeView = findViewById(R.id.paradeView)
        vectorView = findViewById(R.id.vectorView)
        zebraLabel = findViewById(R.id.zebraLabel)
        peakLabel = findViewById(R.id.peakLabel)
        peakColorBtn = findViewById(R.id.peakColor)
        squeezeBtn = findViewById(R.id.btnSqueeze)
        lutName = findViewById(R.id.lutName)
        swLock = findViewById(R.id.swLock)

        waveView.mode = ScopeView.Mode.WAVEFORM
        paradeView.mode = ScopeView.Mode.PARADE
        vectorView.mode = ScopeView.Mode.VECTOR

        renderer = MonitorRenderer(glView)
        glView.setEGLContextClientVersion(3)
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY  // draw on every received frame

        camera = CanonLiveViewController(applicationContext, renderer, this)
        camera.bitrate = prefs.getLong("bitrate", 0L)

        buildTiles()
        setupMenu()
        buildQuickButtons()
        setupGestures()
        setupWelcome()
        loadReference()
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) root.post { updateSideColumns() }
        }

        // Reload the last LUT used
        prefs.getString("lutUri", null)?.let { loadLut(Uri.parse(it), enable = prefs.getBoolean("lutOn", false)) }
            ?: run { lutName.text = "LUT: none" }

        onStatus("Starting…")
    }

    // ---------------- Quick buttons (left) ----------------

    /** Each button toggles the matching menu switch, so the state stays in sync and saved. */
    private fun buildQuickButtons() {
        quickCol.removeAllViews()
        quick.clear()
        fun add(label: String, switchId: Int) {
            val sw = findViewById<CompoundButton>(switchId)
            val b = TextView(this).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER; maxLines = 1
                typeface = Typeface.DEFAULT_BOLD
                isClickable = true
                setOnClickListener {
                    if (locked) return@setOnClickListener
                    closePicker()
                    sw.isChecked = !sw.isChecked
                    root.post { refreshQuick(); updateSideColumns() }
                }
            }
            quickCol.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(0, dp(2), 0, dp(2))
            })
            quick += b to sw
        }
        add("ZEBRA", R.id.swZebra)
        add("PEAK", R.id.swPeak)
        add("FALSE", R.id.swFalse)
        add("LUT", R.id.swLutOn)
        add("HIST", R.id.swHist)
        add("WAVE", R.id.swWave)
        add("PARADE", R.id.swParade)
        add("VECTOR", R.id.swVector)
        refreshQuick()
    }

    private fun refreshQuick() {
        for ((b, sw) in quick) {
            val on = sw.isChecked
            b.setBackgroundColor(if (on) Color.argb(200, 255, 214, 0) else Color.argb(38, 255, 255, 255))
            b.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
    }

    // ---------------- Welcome screen ----------------

    private lateinit var welcome: View

    /** Camera choice + connection steps; shown at startup (can be turned off) and from the menu. */
    private fun setupWelcome() {
        welcome = findViewById(R.id.welcome)
        val spinner = findViewById<Spinner>(R.id.welcomeModel)
        val compat = findViewById<TextView>(R.id.welcomeCompat)
        val steps = findViewById<TextView>(R.id.welcomeSteps)
        val show = findViewById<CheckBox>(R.id.welcomeShow)

        val models = CameraModels.all
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models.map { it.name }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val saved = CameraModels.byName(prefs.getString("cameraModel", null))
        spinner.setSelection(models.indexOf(saved))
        fun apply(m: CameraModels.Model) {
            compat.text = CameraModels.compatibility(m)
            steps.text = CameraModels.steps(m)
        }
        apply(saved)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val m = models[pos]
                apply(m)
                save { putString("cameraModel", m.name) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        show.isChecked = prefs.getBoolean("showWelcome", true)
        show.setOnCheckedChangeListener { _, on -> save { putBoolean("showWelcome", on) } }
        findViewById<Button>(R.id.welcomeStart).setOnClickListener { welcome.visibility = View.GONE }
        findViewById<Button>(R.id.btnGuide2).setOnClickListener {
            panel.visibility = View.GONE
            closePicker()
            welcome.visibility = View.VISIBLE
        }
        welcome.visibility = if (show.isChecked) View.VISIBLE else View.GONE
    }

    // ---------------- Right column: camera settings ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Equal tiles on the right; tapping a setting opens the vertical picker. */
    private fun buildTiles() {
        bar.removeAllViews()
        fun add(key: String, label: String, onClick: () -> Unit) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(38, 255, 255, 255))
                isClickable = true
                setOnClickListener { if (!locked) onClick() }
            }
            val l = TextView(this).apply {
                text = label; textSize = 9f; gravity = Gravity.CENTER
                setTextColor(Color.argb(170, 255, 255, 255))
            }
            val v = TextView(this).apply {
                text = "–"; textSize = 16f; gravity = Gravity.CENTER; maxLines = 1
                typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            }
            l.visibility = View.GONE          // value only, no label
            box.addView(l)
            box.addView(v)
            bar.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(0, dp(2), 0, dp(2))
            })
            tiles[key] = Tile(box, l, v)
        }
        add("shutter", "SHUTTER") { openPropPicker("shutter", CanonProps.SHUTTER) }
        add("aperture", "APERTURE") { openPropPicker("aperture", CanonProps.APERTURE) }
        add("iso", "ISO") { openPropPicker("iso", CanonProps.ISO) }
        add("ev", "±EV") { openPropPicker("ev", CanonProps.EXP_COMP) }
        add("wb", "WB") { openWbPicker() }
        add("video", "VIDEO") { openMoviePicker() }
        add("afarea", "AF AREA") { openPropPicker("afarea", CanonProps.AF_AREA) }
        add("af", "FOCUS") { closePicker(); camera.autofocus() }
        add("rec", "RECORD") { closePicker(); camera.toggleRecording() }
        add("menu", "MENU") {
            closePicker()
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        tiles["af"]?.value?.text = "AF"
        tiles["menu"]?.value?.text = "☰"
        refreshTiles()
    }

    /** Display text for a raw camera code. */
    private fun format(prop: Int, code: Int): String = when (prop) {
        CanonProps.SHUTTER -> CanonProps.shutter(code) ?: "Auto"
        CanonProps.APERTURE -> CanonProps.aperture(code) ?: "–"
        CanonProps.ISO -> CanonProps.iso(code)?.removePrefix("ISO ") ?: "–"
        CanonProps.EXP_COMP -> CanonProps.expComp(code)
        CanonProps.PICTURE_STYLE -> CanonProps.style(code) ?: "Style ${PtpUsb.hex(code)}"
        CanonProps.WHITE_BALANCE -> CanonProps.whiteBalance(code, null) ?: "WB $code"
        CanonProps.FOCUS_MODE -> CanonProps.focusMode(code) ?: "Mode $code"
        CanonProps.AF_AREA -> CanonProps.afArea(code)
        else -> code.toString()
    }

    private fun refreshTiles() {
        val i = lastInfo
        fun set(key: String, text: String?, prop: Int? = null) {
            val t = tiles[key] ?: return
            t.value.text = text ?: "–"
            // settings the camera doesn't allow in the current mode are dimmed
            val enabled = prop == null || (i.allowed[prop]?.size ?: 0) > 1 || prop == CanonProps.FOCUS_MODE
            t.value.setTextColor(if (enabled) Color.WHITE else Color.argb(110, 255, 255, 255))
            t.box.setBackgroundColor(
                if (key == pickerKey) Color.argb(110, 255, 214, 0) else Color.argb(38, 255, 255, 255))
        }
        set("shutter", i.shutter, CanonProps.SHUTTER)
        set("aperture", i.aperture, CanonProps.APERTURE)
        set("iso", i.iso?.removePrefix("ISO "), CanonProps.ISO)
        set("ev", i.expComp, CanonProps.EXP_COMP)
        set("wb", i.whiteBalance, CanonProps.WHITE_BALANCE)
        set("video", i.movieText)
        set("afarea", i.codes[CanonProps.AF_AREA]?.let { format(CanonProps.AF_AREA, it) }, CanonProps.AF_AREA)
        tiles["afarea"]?.let { t ->
            val code = i.codes[CanonProps.AF_AREA]
            val icon = code?.let { AfAreaIcon.forCode(it, t.value.currentTextColor, dp(26)) }
            if (icon != null) {
                t.value.text = ""
                t.value.contentDescription = code?.let { AfAreaIcon.name(it) }
            }
            t.value.setCompoundDrawables(null, icon, null, null)
        }
        tiles["rec"]?.let { t ->
            t.value.text = if (i.recording) "■ STOP" else "● REC"
            t.value.setTextColor(if (i.recording) Color.WHITE else Color.rgb(255, 80, 70))
            t.box.setBackgroundColor(if (i.recording) Color.rgb(200, 40, 35) else Color.argb(38, 255, 255, 255))
        }
    }

    // ---------------- Vertical picker ----------------

    private fun openPropPicker(key: String, prop: Int, fallback: List<Int> = emptyList()) {
        val values = lastInfo.allowed[prop]?.takeIf { it.size > 1 } ?: fallback
        if (values.size < 2) {
            Toast.makeText(this, "The camera doesn't allow changing this setting in the current mode", Toast.LENGTH_SHORT).show()
            return
        }
        val current = lastInfo.codes[prop]
        showPicker(key, values.map { v ->
            val icon: ((Int) -> Drawable?)? =
                if (prop == CanonProps.AF_AREA) { col -> AfAreaIcon.forCode(v, col, dp(30)) } else null
            PickerItem(format(prop, v), icon) { camera.setProperty(prop, v) }
        }, values.indexOf(current))
    }

    private fun openWbPicker() {
        val presets = lastInfo.allowed[CanonProps.WHITE_BALANCE].orEmpty().filter { it != 9 }
        val kelvins = lastInfo.allowed[CanonProps.COLOR_TEMP].orEmpty()
        if (presets.size + kelvins.size < 2) {
            Toast.makeText(this, "The camera doesn't allow changing white balance right now", Toast.LENGTH_SHORT).show()
            return
        }
        val items = presets.map { v -> PickerItem(format(CanonProps.WHITE_BALANCE, v)) { camera.setProperty(CanonProps.WHITE_BALANCE, v) } } +
            kelvins.map { k -> PickerItem(CanonProps.kelvin(k)) { camera.setKelvin(k) } }
        val wb = lastInfo.codes[CanonProps.WHITE_BALANCE]
        val sel = if (wb == 9) presets.size + kelvins.indexOf(lastInfo.codes[CanonProps.COLOR_TEMP])
                  else presets.indexOf(wb)
        showPicker("wb", items, sel)
    }

    private fun openMoviePicker() {
        val cur = lastInfo.movie
        if (cur == null) {
            Toast.makeText(this, "The video format hasn't been received from the camera yet", Toast.LENGTH_SHORT).show()
            return
        }
        val choices = CanonProps.movieChoices(cur[1])
        val items = choices.map { (size, fps) ->
            PickerItem(CanonProps.movieFormat(size, fps)) { camera.setMovieFormat(size, fps) }
        }
        showPicker("video", items, choices.indexOf(cur[2] to cur[1]))
    }

    /** Scrolling vertical list next to the right column; the current value is highlighted and centered. */
    private fun showPicker(key: String, items: List<PickerItem>, selected: Int) {
        if (picker.visibility == View.VISIBLE && pickerKey == key) { closePicker(); return }
        panel.visibility = View.GONE
        pickerKey = key
        pickerList.removeAllViews()
        val itemH = dp(42)
        items.forEachIndexed { idx, item ->
            val sel = idx == selected
            val color = if (sel) Color.BLACK else Color.WHITE
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(color)
                val icon = item.icon?.invoke(color)
                if (icon != null) {
                    text = ""
                    contentDescription = item.text
                    setCompoundDrawables(null, icon, null, null)
                } else {
                    text = item.text
                }
                if (sel) setBackgroundColor(Color.rgb(255, 214, 0))
                setOnClickListener { item.action(); closePicker() }
            }
            pickerList.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemH))
        }
        picker.visibility = View.VISIBLE
        picker.post {
            if (selected >= 0) picker.scrollTo(0, (selected * itemH - picker.height / 2 + itemH / 2).coerceAtLeast(0))
        }
        refreshTiles()
    }

    private fun closePicker() {
        if (picker.visibility != View.VISIBLE && pickerKey.isEmpty()) return
        picker.visibility = View.GONE
        pickerKey = ""
        refreshTiles()
    }

    // ---------------- Hidden menu ----------------

    private fun setupMenu() {
        // Features
        bindSwitch(R.id.swZebra, "zebraOn", false) { on -> renderer.zebraOn = on }
        bindSwitch(R.id.swPeak, "peakOn", false) { on -> renderer.peakingOn = on }
        bindSwitch(R.id.swFalse, "falseOn", false) { on -> renderer.falseColorOn = on }
        bindSwitch(R.id.swHist, "histOn", false) { on -> histogram.visibility = vis(on); updateScopeFlags() }
        swLutOn.isChecked = false
        swLutOn.setOnCheckedChangeListener { _, on ->
            if (on && !lutLoaded) {
                swLutOn.isChecked = false
                pickLut.launch(arrayOf("*/*"))
                return@setOnCheckedChangeListener
            }
            renderer.lutOn = on
            save { putBoolean("lutOn", on) }
            glView.requestRender()
        }
        guideIdx = prefs.getInt("guide", 0).coerceIn(0, guides.lastIndex)
        fun applyGuide() {
            renderer.guideAspect = guides[guideIdx].second
            btnGuide.text = "Framing guide: " + if (guideIdx == 0) "off" else guides[guideIdx].first
            glView.requestRender()
        }
        applyGuide()
        btnGuide.setOnClickListener {
            guideIdx = (guideIdx + 1) % guides.size
            applyGuide(); save { putInt("guide", guideIdx) }
        }

        // Exposure
        bindSwitch(R.id.swWave, "wave", false) { on -> waveView.visibility = vis(on); updateScopeFlags() }
        bindSwitch(R.id.swParade, "parade", false) { on -> paradeView.visibility = vis(on); updateScopeFlags() }
        bindSwitch(R.id.swVector, "vector", false) { on -> vectorView.visibility = vis(on); updateScopeFlags() }
        bindSwitch(R.id.swHistRgb, "histRgb", false) { on -> histogram.rgb = on; updateScopeFlags() }

        val zebraSeek = findViewById<SeekBar>(R.id.zebraSeek)
        zebraSeek.progress = prefs.getInt("zebraPct", 95) - 70
        applyZebra(zebraSeek.progress)
        zebraSeek.setOnSeekBarChangeListener(seekListener { p ->
            applyZebra(p); save { putInt("zebraPct", p + 70) }
        })
        bindSwitch(R.id.zebraAfterLut, "zebraAfterLut", true) { on -> renderer.zebraAfterLut = on }

        // Focus
        bindSwitch(R.id.swPeakMono, "peakMono", false) { on -> renderer.peakingMono = on }
        bindSwitch(R.id.swAf, "afPoint", true) { on -> renderer.afOn = on }
        bindSwitch(R.id.swTouchAf, "touchAf", true) { on -> touchAfOn = on }
        val peakSeek = findViewById<SeekBar>(R.id.peakSeek)
        peakSeek.progress = prefs.getInt("peakSens", 50)
        applyPeak(peakSeek.progress)
        peakSeek.setOnSeekBarChangeListener(seekListener { p ->
            applyPeak(p); save { putInt("peakSens", p) }
        })
        var colorIdx = prefs.getInt("peakColor", 0).coerceIn(0, peakColors.lastIndex)
        fun applyColor() {
            renderer.peakingColor = peakColors[colorIdx].second
            peakColorBtn.text = "Peaking color: ${peakColors[colorIdx].first}"
            glView.requestRender()
        }
        applyColor()
        peakColorBtn.setOnClickListener {
            colorIdx = (colorIdx + 1) % peakColors.size
            applyColor(); save { putInt("peakColor", colorIdx) }
        }

        // Framing
        squeezeIdx = prefs.getInt("squeeze", 0).coerceIn(0, squeezes.lastIndex)
        fun applySqueeze() {
            val s = squeezes[squeezeIdx]
            renderer.squeeze = s
            squeezeBtn.text = if (s == 1f) "Anamorphic desqueeze: off" else "Anamorphic desqueeze: ${s}×"
            updateSideColumns()
        }
        applySqueeze()
        squeezeBtn.setOnClickListener {
            squeezeIdx = (squeezeIdx + 1) % squeezes.size
            applySqueeze(); save { putInt("squeeze", squeezeIdx) }
        }
        bindSwitch(R.id.swMirror, "mirror", false) { on -> renderer.mirror = on; updateFlip() }
        bindSwitch(R.id.swRotate, "rotate", false) { on -> renderer.rotate180 = on; updateFlip() }
        bindSwitch(R.id.swSafe, "safe", false) { on -> renderer.safeAreas = on }
        bindSwitch(R.id.thirds, "thirds", false) { on -> renderer.thirdsOn = on }

        // Camera
        bindSwitch(R.id.swRec, "recIndicator", true) { on -> recIndicator = on; showCameraInfo() }

        // Reference
        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            if (streamInfo.isEmpty()) {
                Toast.makeText(this, "No image from the camera", Toast.LENGTH_SHORT).show()
            } else {
                camera.captureRequested = true
            }
        }
        bindSwitch(R.id.swReference, "reference", false) { on -> renderer.referenceOn = on }

        // LUT
        findViewById<Button>(R.id.loadLut).setOnClickListener { pickLut.launch(arrayOf("*/*")) }

        // Screen
        swLock.isChecked = false
        swLock.setOnCheckedChangeListener { _, on -> if (on) setLocked(true) }
        lockBtn.setOnLongClickListener { setLocked(false); true }
    }

    /** Binds a switch to a preference and applies its state immediately. */
    private fun bindSwitch(id: Int, key: String, default: Boolean, apply: (Boolean) -> Unit) {
        val sw = findViewById<CompoundButton>(id)
        sw.isChecked = prefs.getBoolean(key, default)
        apply(sw.isChecked)
        sw.setOnCheckedChangeListener { _, on ->
            apply(on)
            save { putBoolean(key, on) }
            glView.requestRender()
        }
    }

    private fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE

    private fun updateScopeFlags() {
        var f = 0
        if (histogram.visibility == View.VISIBLE) f = f or if (histogram.rgb) ScopeData.HIST_RGB else ScopeData.HIST
        if (waveView.visibility == View.VISIBLE) f = f or ScopeData.WAVE
        if (paradeView.visibility == View.VISIBLE) f = f or ScopeData.PARADE
        if (vectorView.visibility == View.VISIBLE) f = f or ScopeData.VECTOR
        camera.scopeFlags = f
        root.post { updateSideColumns() }   // visible scopes share the height
    }

    private fun updateFlip() {
        val flip = renderer.mirror xor renderer.rotate180
        waveView.flipX = flip
        paradeView.flipX = flip
    }

    private fun setLocked(on: Boolean) {
        locked = on
        if (on) {
            panel.visibility = View.GONE
            closePicker()
            lockBtn.visibility = View.VISIBLE
        } else {
            lockBtn.visibility = View.GONE
            swLock.isChecked = false
        }
    }

    /**
     * The camera image (e.g. 16:9 or 3:2) is narrower than the phone screen (e.g. 20:9).
     * The leftover side bands become columns: quick buttons on the left, camera settings on the right.
     * If the bands are too narrow, the columns keep a minimum width and the image shrinks.
     */
    private fun updateSideColumns() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val density = resources.displayMetrics.density
        val minSide = (84 * density).toInt()
        val maxSide = (180 * density).toInt()
        val aspect = streamAspect * renderer.squeeze
        val natural = ((w - h * aspect) / 2).toInt()
        val side = natural.coerceIn(minSide, maxSide)

        leftCol.layoutParams = leftCol.layoutParams.apply { width = side }
        bar.layoutParams = bar.layoutParams.apply { width = side }
        lockBtn.layoutParams = lockBtn.layoutParams.apply { width = side }
        (panel.layoutParams as FrameLayout.LayoutParams).marginEnd = side
        panel.requestLayout()
        (picker.layoutParams as FrameLayout.LayoutParams).marginEnd = side
        picker.requestLayout()
        renderer.insetLeft = side
        renderer.insetRight = side
        // the REC frame and top info sit over the image
        val imgW = minOf(w - 2 * side, (h * aspect).toInt())
        val imgH = minOf(h, (imgW / aspect).toInt())
        val mx = (w - imgW) / 2
        val my = (h - imgH) / 2
        (recFrame.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = mx; rightMargin = mx; topMargin = my; bottomMargin = my
        }
        recFrame.requestLayout()
        (info.layoutParams as FrameLayout.LayoutParams).topMargin = my + (4 * density).toInt()
        info.maxWidth = imgW
        info.requestLayout()
        layoutScopes(mx, my, imgW, imgH)
        glView.requestRender()
    }

    /**
     * Scopes sit over the image, in the top-left corner, below the info bar.
     * Width is ~30% of the image; if they don't fit vertically, they all shrink.
     */
    private fun layoutScopes(mx: Int, my: Int, imgW: Int, imgH: Int) {
        val gap = dp(6)
        val top = my + dp(28)
        val avail = imgH - (top - my) - dp(6)
        // heights relative to width: histogram 0.45, waveform/parade 0.55, vectorscope 1 (square)
        val ratios = listOf(histogram to 0.45f, waveView to 0.55f, paradeView to 0.55f, vectorView to 1f)
            .filter { it.first.visibility == View.VISIBLE }
        var w = (imgW * 0.30f).toInt().coerceIn(dp(150), dp(300))
        if (ratios.isNotEmpty()) {
            val sum = ratios.sumOf { it.second.toDouble() }.toFloat()
            val maxW = ((avail - gap * ratios.size) / sum).toInt()
            if (w > maxW) w = maxW.coerceAtLeast(dp(80))
        }
        for ((v, r) in ratios) v.layoutParams = v.layoutParams.apply { height = (w * r).toInt() }
        (scopesOverlay.layoutParams as FrameLayout.LayoutParams).apply {
            width = w
            leftMargin = mx + dp(6)
            topMargin = top
        }
        scopesOverlay.requestLayout()
    }

    private fun applyZebra(progress: Int) {
        val pct = progress + 70
        renderer.zebraThreshold = pct / 100f
        zebraLabel.text = "Zebra threshold: $pct%"
        glView.requestRender()
    }

    private fun applyPeak(progress: Int) {
        // high sensitivity = low threshold
        // the threshold is the sharpness / local contrast ratio: ~3.6 = only very sharp, ~1.6 = lenient
        renderer.peakingThreshold = 3.6f - 2.0f * (progress / 100f)
        peakLabel.text = "Peaking sensitivity: $progress"
        glView.requestRender()
    }

    // ---------------- Gestures ----------------
    // tap = touch AF / close panels, double tap = zoom 1x/2x/4x, pinch = zoom, drag = pan

    private fun setupGestures() {
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // with the menu or picker open, a tap only closes them
                if (panel.visibility == View.VISIBLE || picker.visibility == View.VISIBLE) {
                    panel.visibility = View.GONE
                    closePicker()
                    return true
                }
                if (touchAfOn && streamInfo.isNotEmpty() && insideImage(e.x, e.y)) {
                    val (fx, fy) = screenToFrame(e.x, e.y)
                    val z = renderer.zoom
                    camera.touchAf(renderer.zoomCx + (fx - 0.5f) / z, renderer.zoomCy + (fy - 0.5f) / z)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val z = renderer.zoom
                val next = when {
                    z < 1.5f -> 2f
                    z < 3f -> 4f
                    else -> 1f
                }
                zoomAround(e.x, e.y, next, centerOnPoint = true)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val z = renderer.zoom
                if (z <= 1f) return false
                // on a mirrored / rotated screen, movement in the image is inverted
                val sx = if (renderer.mirror xor renderer.rotate180) -1f else 1f
                val sy = if (renderer.rotate180) -1f else 1f
                setCenter(
                    renderer.zoomCx + sx * dx / renderer.vpW / z,
                    renderer.zoomCy + sy * dy / renderer.vpH / z
                )
                return true
            }
        })
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomAround(d.focusX, d.focusY, renderer.zoom * d.scaleFactor, centerOnPoint = false)
                return true
            }
        })
        glView.setOnTouchListener { _, e ->
            if (locked) return@setOnTouchListener true
            scale.onTouchEvent(e)
            if (!scale.isInProgress) gestures.onTouchEvent(e)
            true
        }
    }

    private fun insideImage(x: Float, y: Float): Boolean {
        val top = renderer.surfaceHeight - renderer.vpY - renderer.vpH
        return x >= renderer.vpX && x <= renderer.vpX + renderer.vpW && y >= top && y <= top + renderer.vpH
    }

    /** Touch position in the displayed frame (0..1), accounting for letterboxing, mirroring and rotation. */
    private fun screenToFrame(x: Float, y: Float): Pair<Float, Float> {
        val top = renderer.surfaceHeight - renderer.vpY - renderer.vpH
        var fx = ((x - renderer.vpX) / renderer.vpW).coerceIn(0f, 1f)
        var fy = ((y - top) / renderer.vpH).coerceIn(0f, 1f)
        if (renderer.mirror) fx = 1f - fx
        if (renderer.rotate180) { fx = 1f - fx; fy = 1f - fy }
        return fx to fy
    }

    private fun zoomAround(x: Float, y: Float, newZoom: Float, centerOnPoint: Boolean) {
        val z = renderer.zoom
        val z2 = newZoom.coerceIn(1f, 8f)
        val (fx, fy) = screenToFrame(x, y)
        val ix = renderer.zoomCx + (fx - 0.5f) / z      // the image point under the finger
        val iy = renderer.zoomCy + (fy - 0.5f) / z
        renderer.zoom = z2
        if (centerOnPoint) setCenter(ix, iy)
        else setCenter(ix - (fx - 0.5f) / z2, iy - (fy - 0.5f) / z2)   // the point stays under the finger
    }

    private fun setCenter(cx: Float, cy: Float) {
        val half = 0.5f / renderer.zoom
        renderer.zoomCx = cx.coerceIn(half, 1f - half)
        renderer.zoomCy = cy.coerceIn(half, 1f - half)
        glView.requestRender()
    }

    // ---------------- LUT ----------------

    private fun loadLut(uri: Uri, enable: Boolean) {
        thread(name = "lut-loader") {
            try {
                val lut = contentResolver.openInputStream(uri)?.use { CubeLut.parse(it) }
                    ?: throw IllegalStateException("Can't open the file")
                glView.queueEvent { renderer.setLut(lut) }
                runOnUiThread {
                    lutLoaded = true
                    renderer.lutOn = enable
                    val name = lut.title ?: uri.lastPathSegment ?: "LUT"
                    lutName.text = "LUT: $name (${lut.size}³)"
                    save { putString("lutUri", uri.toString()); putBoolean("lutOn", enable) }
                    swLutOn.isChecked = enable
                    glView.requestRender()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Invalid LUT: ${e.message}", Toast.LENGTH_LONG).show()
                    lutName.text = "LUT: none"
                }
            }
        }
    }

    // ---------------- Reference capture ----------------

    private fun referenceFile() = File(filesDir, "reference.jpg")

    private fun loadReference() {
        val f = referenceFile()
        if (!f.exists()) return
        thread(name = "ref-loader") {
            BitmapFactory.decodeFile(f.path)?.let { b -> glView.queueEvent { renderer.setReference(b) } }
        }
    }

    override fun onCapture(jpeg: ByteArray) {
        thread(name = "capture") {
            try {
                referenceFile().writeBytes(jpeg)
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { b: Bitmap ->
                    glView.queueEvent { renderer.setReference(b) }
                }
                saveToGallery(jpeg)
                runOnUiThread { Toast.makeText(this, "Capture saved", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun saveToGallery(jpeg: ByteArray) {
        val name = "AlmostMonitor_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Almost Monitor")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore refused")
            contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            // Android 9: in the app's own folder, no permissions needed
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Almost Monitor")
            dir.mkdirs()
            File(dir, name).writeBytes(jpeg)
        }
    }

    // ---------------- Lifecycle ----------------

    private fun maybeStartCamera() {
        if (!resumed) return
        updateScopeFlags()
        camera.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        glView.onResume()
        maybeStartCamera()
        ticker.post(tick)
    }

    override fun onPause() {
        resumed = false
        ticker.removeCallbacks(tick)
        glView.onPause()
        super.onPause()
    }

    // The camera only stops in onStop: the USB permission dialog only triggers onPause
    override fun onStop() {
        camera.stop()
        super.onStop()
    }

    override fun onDestroy() {
        camera.stop()
        super.onDestroy()
    }

    // ---------------- CanonLiveViewController.Listener ----------------

    override fun onStatus(text: String) = runOnUiThread {
        status.text = text
        status.visibility = View.VISIBLE
        streamInfo = ""
        lastInfo = CameraInfo()
        showCameraInfo()
    }

    override fun onStreaming(width: Int, height: Int) = runOnUiThread {
        status.visibility = View.GONE
        streamInfo = "${width}×${height}"
        streamAspect = width.toFloat() / height
        updateSideColumns()
    }

    override fun onScopes(data: ScopeData) = runOnUiThread {
        if (histogram.visibility == View.VISIBLE) histogram.setData(data)
        if (waveView.visibility == View.VISIBLE) waveView.setData(data)
        if (paradeView.visibility == View.VISIBLE) paradeView.setData(data)
        if (vectorView.visibility == View.VISIBLE) vectorView.setData(data)
    }

    override fun onCameraInfo(info: CameraInfo) = runOnUiThread {
        if (info.recording && !lastInfo.recording) recStart = android.os.SystemClock.elapsedRealtime()
        lastInfo = info
        // the bitrate measured while recording is kept to estimate minutes next time
        val br = camera.bitrate
        if (br > 0 && br != prefs.getLong("bitrate", 0L)) save { putLong("bitrate", br) }
        showCameraInfo()
    }

    /** "● REC 01:23" while the camera is recording. */
    private fun updateRecLabel() {
        if (!lastInfo.recording) return
        val sec = (android.os.SystemClock.elapsedRealtime() - recStart) / 1000
        recLabel.text = String.format(Locale.US, "● REC %02d:%02d", sec / 60, sec % 60)
    }

    private fun showCameraInfo() {
        val i = lastInfo
        val rec = recIndicator && i.recording
        recLabel.visibility = vis(rec)
        recFrame.visibility = vis(rec)
        updateRecLabel()
        refreshTiles()
    }

    override fun onCommandFailed(message: String) = runOnUiThread {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------- Utilities ----------------

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private inline fun save(block: SharedPreferences.Editor.() -> Unit) =
        prefs.edit().apply(block).apply()

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    }
}
