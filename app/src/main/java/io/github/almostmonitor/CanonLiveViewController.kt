package io.github.almostmonitor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Live view from a Canon EOS over USB/PTP (the camera's "Photo Import/Remote Control" mode).
 * Requests the viewfinder image (JPEG) in a loop, decodes it and hands it to the renderer.
 * The camera stays in its normal mode, so it can still record to the card.
 */
class CanonLiveViewController(
    private val context: Context,
    private val renderer: MonitorRenderer,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onStreaming(width: Int, height: Int)
        /** Requested exposure scopes; called from the camera thread. */
        fun onScopes(data: ScopeData)
        /** Camera state (ISO, shutter, aperture, REC); called from the camera thread. */
        fun onCameraInfo(info: CameraInfo)
        /** JPEG of the captured frame; called from the camera thread. */
        fun onCapture(jpeg: ByteArray)
        /** A command sent to the camera was refused; called from the camera thread. */
        fun onCommandFailed(message: String)
    }

    /** Which scopes to compute (ScopeData.HIST | WAVE | ...); 0 = none. */
    @Volatile var scopeFlags = 0
    /** Set by the UI: the next frame gets saved. */
    @Volatile var captureRequested = false
    private var frameNo = 0L
    private var jpegOff = 0
    private var jpegLen = 0
    private var info = CameraInfo()
    private var batterySupported = true
    private var storageSupported = true

    // ---------------- Commands to the camera (from the UI) ----------------
    private val commands = java.util.concurrent.ConcurrentLinkedQueue<(PtpUsb) -> Unit>()
    private val codes = HashMap<Int, Int>()
    private val allowed = HashMap<Int, List<Int>>()
    @Volatile private var lvRect = floatArrayOf(0f, 0f, 0f, 0f)   // sensor area shown: x, y, w, h
    private var afCancelAt = 0L
    private var halfPressAt = 0L      // when to release the half press

    private fun enqueue(c: (PtpUsb) -> Unit) { commands.add(c) }

    /** Changes a setting (ISO, shutter, aperture...). The camera's answer arrives via events. */
    fun setProperty(prop: Int, value: Int) = enqueue { ptp ->
        val r = setProp(ptp, prop, value)
        Log.i(TAG, "Set ${PtpUsb.hex(prop)} = ${PtpUsb.hex(value)}: ${PtpUsb.hex(r.code)}")
        if (!r.ok) listener.onCommandFailed("The camera refused the setting (${PtpUsb.hex(r.code)})")
    }

    /** Color temperature white balance: WB = 9 (Kelvin), then the value. */
    fun setKelvin(k: Int) = enqueue { ptp ->
        setProp(ptp, CanonProps.WHITE_BALANCE, 9)
        val r = setProp(ptp, CanonProps.COLOR_TEMP, k)
        if (!r.ok) listener.onCommandFailed("The camera refused the color temperature (${PtpUsb.hex(r.code)})")
    }

    /** Starts / stops recording (libgphoto2 method: EVFRecordStatus 4 = start, 0 = stop). */
    fun toggleRecording() = enqueue { ptp ->
        val start = !info.recording
        val r = setProp(ptp, CanonProps.EVF_RECORD_STATUS, if (start) 4 else 0)
        Log.i(TAG, "REC ${if (start) "start" else "stop"}: ${PtpUsb.hex(r.code)}")
        if (!r.ok) listener.onCommandFailed(
            "The camera didn't start recording (${PtpUsb.hex(r.code)}). Make sure it's in movie mode.")
    }

    /**
     * Autofocus: simulate a shutter half press (RemoteReleaseOn 1),
     * hold it ~1.2 s while the camera focuses, then release it (RemoteReleaseOff 1).
     * DoAf (0x9154) leaves the R6 II menu locked, so it's only a fallback if this is refused.
     */
    fun autofocus() = enqueue { ptp ->
        if (halfPressAt != 0L) return@enqueue          // already pressed
        val r = ptp.transaction(PtpUsb.OP_EOS_REMOTE_RELEASE_ON, 1, 0)
        Log.i(TAG, "RemoteReleaseOn(1): ${PtpUsb.hex(r.code)}")
        if (r.ok) {
            halfPressAt = SystemClock.uptimeMillis() + 1200
        } else {
            val d = ptp.transaction(PtpUsb.OP_EOS_DO_AF)
            Log.i(TAG, "DoAf: ${PtpUsb.hex(d.code)}")
            afCancelAt = SystemClock.uptimeMillis() + 1500
        }
    }

    /** Touch AF: moves the focus point to (u, v) in the image (0..1) and focuses there. */
    fun touchAf(u: Float, v: Float) = enqueue { ptp ->
        val lv = lvRect
        if (lv[2] <= 0f) return@enqueue
        val x = (lv[0] + u.coerceIn(0f, 1f) * lv[2]).toInt()
        val y = (lv[1] + v.coerceIn(0f, 1f) * lv[3]).toInt()
        // the exact type isn't documented; try known values until one returns OK
        for (type in intArrayOf(1, 0, 3, 2)) {
            val r = ptp.transaction(PtpUsb.OP_EOS_TOUCH_AF_POSITION, type, x, y)
            Log.i(TAG, "TouchAf type $type ($x, $y): ${PtpUsb.hex(r.code)}")
            if (r.ok) return@enqueue
        }
        listener.onCommandFailed("The camera doesn't accept touch AF over USB")
    }

    /** Live view magnification done by the camera (1, 5 or 10): real sensor detail. */
    fun setCameraZoom(level: Int) = enqueue { ptp ->
        val r = ptp.transaction(PtpUsb.OP_EOS_ZOOM, level)
        Log.i(TAG, "Zoom $level: ${PtpUsb.hex(r.code)}")
        if (r.ok) {
            info = info.copy(cameraZoom = level)
            listener.onCameraInfo(info)
        } else listener.onCommandFailed("The camera refused the magnification (${PtpUsb.hex(r.code)})")
    }

    /** Changes the video format: keep the current struct, replace resolution and fps. */
    fun setMovieFormat(size: Int, fps100: Int) = enqueue { ptp ->
        val cur = info.movie
        if (cur == null || cur.size < 3) {
            listener.onCommandFailed("The current video format isn't known yet")
            return@enqueue
        }
        val v = cur.toMutableList()
        v[1] = fps100
        v[2] = size
        val data = ByteArray(8 + v.size * 4)
        PtpUsb.putInt(data, 0, data.size)
        PtpUsb.putInt(data, 4, CanonProps.MOVIE_FORMAT)
        v.forEachIndexed { i, x -> PtpUsb.putInt(data, 8 + i * 4, x) }
        val r = ptp.transaction(PtpUsb.OP_EOS_SET_DEVICE_PROP_VALUE_EX, dataOut = data)
        Log.i(TAG, "Video format ${CanonProps.movieFormat(size, fps100)}: ${PtpUsb.hex(r.code)}")
        if (!r.ok) listener.onCommandFailed(
            "The camera doesn't accept ${CanonProps.movieFormat(size, fps100)} (${PtpUsb.hex(r.code)})")
    }

    /** Video bitrate measured during the last recording (bytes/s); saved by the UI between launches. */
    @Volatile var bitrate = 0L
    private var recStartFree = -1L
    private var recStartTime = 0L
    private var recCard = -1
    private var cardFree = LongArray(0)

    private val usb = context.getSystemService(UsbManager::class.java)
    private val actionPermission = context.packageName + ".USB_PERMISSION"
    private val sessionLock = Any()

    @Volatile private var running = false
    /** Camera released: leave remote mode so the camera's menu can be used. */
    @Volatile var paused = false
    @Volatile private var generation = 0
    @Volatile private var permissionPending = false
    private var lastStatus = ""
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != actionPermission) return
            permissionPending = false
            if (!i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                status("USB access was denied.\nUnplug and replug the cable to be asked again.")
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(actionPermission),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        thread(name = "canon-ptp") { loop(gen) }
    }

    fun stop() {
        running = false
        generation++
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    private fun alive(gen: Int) = running && gen == generation

    private fun loop(gen: Int) = synchronized(sessionLock) {
        while (alive(gen)) {
            if (paused) {
                status("Camera released: you can use its menu.")
                if (!sleep(300)) break
                continue
            }
            val dev = findCamera()
            if (dev == null) {
                val other = findOtherPtpDevice()
                status(
                    if (other != null)
                        "${other.productName ?: "This camera"} isn't a Canon camera.\n" +
                        "This app only works with Canon EOS cameras."
                    else
                        "Waiting for the camera…\n" +
                        "On the camera: Choose USB connection app › Photo Import/Remote Control (if available).\n" +
                        "On the phone: OTG enabled. Use a USB data cable."
                )
                if (!sleep(1000)) break
                continue
            }
            if (!usb.hasPermission(dev)) {
                if (!permissionPending) {
                    permissionPending = true
                    status("Found ${dev.productName ?: "camera"}. Requesting USB access…")
                    val intent = Intent(actionPermission).setPackage(context.packageName)
                    val pi = PendingIntent.getBroadcast(context, 0, intent,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    usb.requestPermission(dev, pi)
                }
                if (!sleep(500)) break
                continue
            }
            try {
                runSession(dev, gen)
            } catch (e: Exception) {
                Log.w(TAG, "Session ended", e)
                if (alive(gen)) status("Connection lost (${e.message}).\nRetrying…")
            }
            if (!sleep(1000)) break
        }
    }

    private fun isPtp(d: UsbDevice) =
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

    /** Canon cameras only: EOS commands make no sense on other brands. */
    private fun findCamera(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == CANON_VENDOR_ID && isPtp(it) }

    private fun findOtherPtpDevice(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId != CANON_VENDOR_ID && isPtp(it) }

    private fun runSession(dev: UsbDevice, gen: Int) {
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .first { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
        Log.i(TAG, "Device: ${dev.productName} vid=${PtpUsb.hex(dev.vendorId)} pid=${PtpUsb.hex(dev.productId)}, " +
            "interfaces=${dev.interfaceCount}, configurations=${dev.configurationCount}")
        val conn = usb.openDevice(dev) ?: throw IOException("can't open the USB device")
        PtpUsb(conn, intf).use { ptp ->
            status("Connected to ${dev.productName ?: "camera"}. Starting live view…")

            val open = ptp.transaction(PtpUsb.OP_OPEN_SESSION, 1)
            if (!open.ok && open.code != PtpUsb.RC_SESSION_ALREADY_OPEN)
                throw IOException("OpenSession ${PtpUsb.hex(open.code)}")
            check(ptp, ptp.transaction(PtpUsb.OP_EOS_SET_REMOTE_MODE, 1), "SetRemoteMode")
            check(ptp, ptp.transaction(PtpUsb.OP_EOS_SET_EVENT_MODE, 1), "SetEventMode")
            info = CameraInfo()
            propValues.clear(); eventTypesSeen.clear(); evfFramesLogged = 0
            eventDumps.clear(); afStateLogged = Int.MIN_VALUE; evfBlockLog.clear()
            quietUntil = SystemClock.uptimeMillis() + 3000
            propDumpDone = false
            storageSupported = true; recStartFree = -1L; recCard = -1
            commands.clear(); codes.clear(); allowed.clear(); afCancelAt = 0L; halfPressAt = 0L
            readEvents(ptp)          // the first GetEvent returns the current value of every property
            enableLiveView(ptp)
            requestLevel(ptp, true)
            var lastBattery = 0L
            var lastStorage = 0L

            var first = true
            var errors = 0
            var lastEvent = SystemClock.uptimeMillis()
            try {
                while (alive(gen) && !paused) {
                    val r = ptp.transaction(PtpUsb.OP_EOS_GET_VIEWFINDER_DATA, 0x00200000, 0, 0)
                    if (r.ok && r.dataLength > 0) {
                        if (DEBUG_EVF) logEvfBlocks(ptp.rx, r.dataOffset, r.dataLength)
                        parseAfFrame(ptp.rx, r.dataOffset, r.dataLength)
                        val bmp = decodeJpeg(ptp.rx, r.dataOffset, r.dataLength)
                        if (bmp != null) {
                            if (first) {
                                first = false
                                Log.i(TAG, "First live view frame: ${bmp.width}x${bmp.height}")
                                listener.onStreaming(bmp.width, bmp.height)
                            }
                            val flags = scopeFlags
                            if (flags != 0 && frameNo++ % 2 == 0L) {
                                listener.onScopes(scopes(bmp, flags, ptp.rx, r.dataOffset, r.dataLength))
                            }
                            if (captureRequested) {
                                captureRequested = false
                                listener.onCapture(ptp.rx.copyOfRange(jpegOff, jpegOff + jpegLen))
                            }
                            renderer.submitBitmap(bmp)
                            errors = 0
                        }
                    } else if (r.ok || r.code == PtpUsb.RC_CANON_NOT_READY || r.code == PtpUsb.RC_DEVICE_BUSY) {
                        sleep(15)   // live view has no new frame yet
                    } else {
                        errors++
                        if (errors == 1 || errors % 50 == 0) Log.w(TAG, "GetViewFinderData ${PtpUsb.hex(r.code)}")
                        if (errors % 25 == 0) enableLiveView(ptp)
                        if (errors > 300) throw IOException("live view won't start (${PtpUsb.hex(r.code)})")
                        sleep(30)
                    }
                    // Commands from the UI
                    while (true) {
                        val c = commands.poll() ?: break
                        c(ptp)
                    }
                    if (DIAG && !propDumpDone && SystemClock.uptimeMillis() > quietUntil + 2000) {
                        propDumpDone = true
                        dumpAllProps()
                    }
                    if (halfPressAt != 0L && SystemClock.uptimeMillis() > halfPressAt) {
                        halfPressAt = 0L
                        val r = ptp.transaction(PtpUsb.OP_EOS_REMOTE_RELEASE_OFF, 1)
                        Log.i(TAG, "RemoteReleaseOff(1): ${PtpUsb.hex(r.code)}")
                    }
                    if (afCancelAt != 0L && SystemClock.uptimeMillis() > afCancelAt) {
                        afCancelAt = 0L
                        ptp.transaction(PtpUsb.OP_EOS_AF_CANCEL)
                    }
                    // Canon cameras expect their events to be read periodically
                    val now = SystemClock.uptimeMillis()
                    if (now - lastEvent > 300) {
                        readEvents(ptp)
                        lastEvent = now
                    }
                    if (batterySupported && now - lastBattery > 10_000) {
                        readBattery(ptp)
                        lastBattery = now
                    }
                    // card space: every 10 s, every 4 s while recording (to measure the bitrate)
                    if (storageSupported && now - lastStorage > (if (info.recording) 4_000 else 10_000)) {
                        readStorage(ptp)
                        lastStorage = now
                    }
                }
            } finally {
                // Leave the camera as it was: image on its own screen only
                try {
                    if (halfPressAt != 0L) { halfPressAt = 0L; ptp.transaction(PtpUsb.OP_EOS_REMOTE_RELEASE_OFF, 1) }
                    if (afCancelAt != 0L) { afCancelAt = 0L; ptp.transaction(PtpUsb.OP_EOS_AF_CANCEL) }
                    requestLevel(ptp, false)
                    setProp(ptp, PtpUsb.PROP_EVF_OUTPUT_DEVICE, 1)
                    ptp.transaction(PtpUsb.OP_EOS_SET_REMOTE_MODE, 0)
                    ptp.transaction(PtpUsb.OP_CLOSE_SESSION)
                } catch (_: Exception) {}
            }
        }
    }

    private fun enableLiveView(ptp: PtpUsb) {
        setProp(ptp, PtpUsb.PROP_EVF_MODE, 1)   // may be refused on some models; that's fine
        // 3 = camera screen + PC, so the camera's screen stays on too
        val r = setProp(ptp, PtpUsb.PROP_EVF_OUTPUT_DEVICE, 3)
        if (!r.ok) {
            Log.w(TAG, "EVF output 3 refused (${PtpUsb.hex(r.code)}), trying 2")
            setProp(ptp, PtpUsb.PROP_EVF_OUTPUT_DEVICE, 2)
        }
        sleep(300)
        readEvents(ptp)
    }

    /** Reads camera events and updates ISO / shutter / aperture / recording. */
    private fun readEvents(ptp: PtpUsb) {
        val r = ptp.transaction(PtpUsb.OP_EOS_GET_EVENT)
        if (!r.ok || r.dataLength < 8) return
        logEventTypes(ptp.rx, r.dataOffset, r.dataLength)
        var next = info
        var listsChanged = false
        CanonProps.forEachAllowed(ptp.rx, r.dataOffset, r.dataLength) { prop, values ->
            val l = values.toList()
            if (allowed[prop] != l) { allowed[prop] = l; listsChanged = true }
        }
        if (listsChanged) next = next.copy(allowed = HashMap(allowed))
        CanonProps.forEachProp(ptp.rx, r.dataOffset, r.dataLength) { prop, value ->
            logProp(prop, value)
            val transient = prop == CanonProps.FOCUS_MODE && value == CanonProps.FOCUS_HALF_PRESS
            if (prop == CanonProps.FOCUS_MODE) renderer.afFocused = transient
            if (prop in CanonProps.CONTROLLABLE && !transient && codes[prop] != value) {
                codes[prop] = value
                next = next.copy(codes = HashMap(codes))
            }
            next = when (prop) {
                CanonProps.ISO -> next.copy(iso = CanonProps.iso(value))
                CanonProps.SHUTTER -> next.copy(shutter = CanonProps.shutter(value))
                CanonProps.APERTURE -> next.copy(aperture = CanonProps.aperture(value))
                CanonProps.EXP_COMP -> next.copy(expComp = CanonProps.expComp(value))
                CanonProps.AE_MODE -> next.copy(mode = CanonProps.mode(value))
                CanonProps.BATTERY_LEVEL -> {
                    Log.i(TAG, "Battery level (0xD111) = $value")
                    next.copy(batteryBars = CanonProps.batteryBars(value))
                }
                CanonProps.FOCUS_MODE -> if (transient) next else next.copy(af = CanonProps.focusMode(value))
                CanonProps.WHITE_BALANCE -> next.copy(wbCode = value)
                CanonProps.COLOR_TEMP -> next.copy(kelvin = value)
                CanonProps.PICTURE_STYLE -> next.copy(style = CanonProps.style(value))
                CanonProps.EVF_RECORD_STATUS -> {
                    Log.i(TAG, "EVFRecordStatus = $value")
                    val rec = value == CanonProps.RECORDING
                    if (rec && !next.recording) { recStartFree = -1L; recStartTime = SystemClock.uptimeMillis() }
                    next.copy(recording = rec)
                }
                else -> next
            }
        }
        // Battery: structured property 0xD208 = [length][3 slots][...][percent][state]...
        // (slot 0 = in-camera battery; the others = grip, FFFFFFFF/FFFFFFFE when absent)
        movieFromEvents(ptp.rx, r.dataOffset, r.dataLength)?.let { m ->
            if (m != next.movie) {
                Log.i(TAG, "Video format: ${CanonProps.movieFormat(m[2], m[1])}")
                next = next.copy(movie = m)
            }
        }
        if (next != info) {
            info = next
            listener.onCameraInfo(next)
        }
    }

    /** The 0xD20D struct from events: [length=40][fps*100][resolution]... as a list of u32. */
    private fun movieFromEvents(buf: ByteArray, off: Int, len: Int): List<Int>? {
        val end = off + len
        var p = off
        var result: List<Int>? = null
        while (p + 8 <= end) {
            val size = PtpUsb.getInt(buf, p)
            val type = PtpUsb.getInt(buf, p + 4)
            if (size < 8 || p + size > end || (type == 0 && size == 8)) break
            if (type == CanonProps.EVENT_PROP_VALUE_CHANGED && size >= 12 + 12 &&
                PtpUsb.getInt(buf, p + 8) == CanonProps.MOVIE_FORMAT) {
                val dataLen = PtpUsb.getInt(buf, p + 12)          // first field = struct length
                val n = (minOf(dataLen, size - 12) / 4).coerceIn(0, 32)
                if (n >= 3) result = List(n) { PtpUsb.getInt(buf, p + 12 + it * 4) }
            }
            p += size
        }
        return result
    }

    private fun batteryFromEvents(buf: ByteArray, off: Int, len: Int): Int? {
        val end = off + len
        var p = off
        var result: Int? = null
        while (p + 8 <= end) {
            val size = PtpUsb.getInt(buf, p)
            val type = PtpUsb.getInt(buf, p + 4)
            if (size < 8 || p + size > end || (type == 0 && size == 8)) break
            if (type == CanonProps.EVENT_PROP_VALUE_CHANGED && size >= 32 &&
                PtpUsb.getInt(buf, p + 8) == CanonProps.BATTERY_INFO) {
                val pct = PtpUsb.getInt(buf, p + 24)
                if (pct in 0..100) result = pct
            }
            p += size
        }
        return result
    }

    // ---- Diagnostics: what the camera sends (used to find the focus point position) ----
    private val propValues = HashMap<Int, Int>()
    private val eventTypesSeen = HashSet<Int>()
    private val eventDumps = HashMap<String, String>()
    private var dumpLines = 0

    /** Logs an event / structured property when its content changes. */
    private fun logChange(key: String, prefix: String, buf: ByteArray, o: Int, n: Int) {
        if (n <= 0 || dumpLines > 600) return
        val d = hexDump(buf, o, n)
        if (eventDumps[key] == d) return
        eventDumps[key] = d
        if (!DIAG || SystemClock.uptimeMillis() <= quietUntil) return
        dumpLines++
        Log.d(TAG, prefix + d)
    }
    private var evfFramesLogged = 0

    /** Logs each property on first appearance and on every change. */
    private fun logProp(prop: Int, value: Int) {
        if (propValues[prop] == value) return
        propValues[prop] = value
        if (DIAG && SystemClock.uptimeMillis() > quietUntil) Log.d(TAG, "Prop ${PtpUsb.hex(prop)} = ${PtpUsb.hex(value)} ($value)")
    }

    /** Logs each event type other than PropValueChanged once. */
    private fun logEventTypes(buf: ByteArray, off: Int, len: Int) {
        val end = off + len
        var p = off
        while (p + 8 <= end) {
            val size = PtpUsb.getInt(buf, p)
            val type = PtpUsb.getInt(buf, p + 4)
            if (size < 8 || p + size > end || (type == 0 && size == 8)) break
            if (type != CanonProps.EVENT_PROP_VALUE_CHANGED) {
                val sub = if (size >= 12) PtpUsb.getInt(buf, p + 8) else 0
                logChange("E${PtpUsb.hex(type)}/${PtpUsb.hex(sub)}", "Event ${PtpUsb.hex(type)}, $size bytes: ", buf, p + 8, minOf(size - 8, 96))
            } else if (size > 16) {
                // property with a structured value (e.g. detailed battery, video format)
                val prop = PtpUsb.getInt(buf, p + 8)
                logChange("P${PtpUsb.hex(prop)}", "Prop ${PtpUsb.hex(prop)} [${size - 12} B]: ", buf, p + 12, minOf(size - 12, 96))
            }
            p += size
        }
    }

    private var lastEvfHeader = ""
    private var lastEvfLog = 0L

    /**
     * R6 II live view data is a series of blocks [length u32][type u32][content]:
     * a header (type 0xFFFFFFFF), the JPEG (type 1), then other blocks (the camera histogram and
     * the AF info). Small blocks are logged in full whenever they change,
     * at most 4 lines per second.
     */
    private fun logEvfBlocks(buf: ByteArray, off: Int, len: Int) {
        val end = off + len
        var p = off
        val key = StringBuilder()
        val sb = StringBuilder("LV $len B:")
        var n = 0
        while (p + 8 <= end && n < 40) {
            val bl = PtpUsb.getInt(buf, p)
            val bt = PtpUsb.getInt(buf, p + 4)
            if (bl < 8 || p + bl > end) { sb.append(" [stop @${p - off}: ${hexDump(buf, p, minOf(16, end - p))}]"); break }
            sb.append("\n  type ${PtpUsb.hex(bt)} ${bl}B")
            when {
                bt == 1 || bt == 9 || bt == 0x11 -> sb.append(" (JPEG)")
                bt == -1 -> {}                                  // header: addresses we don't need
                bl <= 8 + 256 -> {
                    val d = hexDump(buf, p + 8, bl - 8)
                    sb.append(": ").append(d)
                    key.append(bt).append(':').append(d).append('|')
                }
                else -> sb.append(": ").append(hexDump(buf, p + 8, 32)).append(" …")
            }
            p += bl
            n++
        }
        val k = key.toString()
        val now = SystemClock.uptimeMillis()
        if (evfFramesLogged >= 3 && (k == lastEvfHeader || now - lastEvfLog < 250)) return
        lastEvfHeader = k
        lastEvfLog = now
        evfFramesLogged++
        Log.d(TAG, sb.toString())
    }

    /**
     * Focus point position, from the blocks after the JPEG (R6 Mark II, found from logs):
     *  - 0x12: sensor area shown in live view: [x, y, width, height]
     *  - 0x08: AF frames: [count], then 24 bytes each: [16, state, x, y, width, height]
     * Coordinates are in sensor pixels; they're converted to 0..1 of the displayed image.
     */
    private fun parseAfFrame(buf: ByteArray, off: Int, len: Int) {
        val end = off + len
        var p = off
        var lvX = 0f; var lvY = 0f; var lvW = 0f; var lvH = 0f
        var afStart = -1; var afLen = 0
        var n = 0
        while (p + 8 <= end && n < 60) {
            val bl = PtpUsb.getInt(buf, p)
            val bt = PtpUsb.getInt(buf, p + 4)
            if (bl < 8 || p + bl > end) break
            if (bt == 0x21 && bl >= 12) {
                val mm = PtpUsb.getInt(buf, p + 8)
                if (mm in 1..2000 && mm != info.focalMm) {
                    info = info.copy(focalMm = mm)
                    listener.onCameraInfo(info)
                }
            }
            if (DIAG && bl <= 8 + 96 && bt in DIAG_BLOCKS) {
                val d = hexDump(buf, p + 8, bl - 8)
                if (evfBlockLog[bt] != d) {
                    evfBlockLog[bt] = d
                    if (SystemClock.uptimeMillis() > quietUntil) Log.d(TAG, "LV block ${PtpUsb.hex(bt)}: $d")
                }
            }
            if (bt == 0x12 && bl >= 24) {
                lvX = PtpUsb.getInt(buf, p + 8).toFloat()
                lvY = PtpUsb.getInt(buf, p + 12).toFloat()
                lvW = PtpUsb.getInt(buf, p + 16).toFloat()
                lvH = PtpUsb.getInt(buf, p + 20).toFloat()
            } else if (bt == 0x08 && bl >= 36) {
                afStart = p + 8; afLen = bl - 8
            }
            p += bl
            n++
        }
        if (lvW > 0f && lvH > 0f) lvRect = floatArrayOf(lvX, lvY, lvW, lvH)
        if (afStart < 0 || lvW <= 0f || lvH <= 0f) {
            renderer.afRect = null
            return
        }
        // AF block (verified on the R6 II): [count] then, for each frame, 24 bytes:
        // [16][state][x][y][width][height]. State 3 = main frame, 1 = surrounding points
        // (Expand AF area / Around sends 1 + 4 or 1 + 8 frames).
        val count = PtpUsb.getInt(buf, afStart)
        val maxFit = (afLen - 4) / 24
        val frames = minOf(count, maxFit, MAX_AF_FRAMES)
        if (frames < 1) { renderer.afRect = null; return }
        logAfBlock(buf, afStart, afLen)
        val rects = FloatArray(frames * 4)
        var k = 0
        for (i in 0 until frames) {
            val e = afStart + 4 + i * 24
            val x = PtpUsb.getInt(buf, e + 8).toFloat()
            val y = PtpUsb.getInt(buf, e + 12).toFloat()
            val w = PtpUsb.getInt(buf, e + 16).toFloat()
            val h = PtpUsb.getInt(buf, e + 20).toFloat()
            if (w <= 0f || h <= 0f) continue
            rects[k++] = (x - lvX) / lvW
            rects[k++] = (y - lvY) / lvH
            rects[k++] = (x + w - lvX) / lvW
            rects[k++] = (y + h - lvY) / lvH
        }
        renderer.afRect = if (k == 0) null else rects.copyOf(k)
    }

    private var lastAfDump = ""
    private var lastAfLog = 0L
    private var afLogs = 0

    /** Logs the AF block when it changes (max 2 lines/s, 200 total): for debugging AF modes. */
    private fun logAfBlock(buf: ByteArray, o: Int, n: Int) {
        if (!DIAG || afLogs >= 200) return
        val d = hexDump(buf, o, minOf(n, 200))
        if (d == lastAfDump) return
        val now = SystemClock.uptimeMillis()
        if (now - lastAfLog < 500) return
        lastAfDump = d; lastAfLog = now; afLogs++
        Log.d(TAG, "AF block ($n B): $d")
    }

    // ---------------- Camera histogram ----------------

    /** Camera histograms (if requested and available) + the other scopes computed locally. */
    private fun scopes(bmp: Bitmap, flags: Int, buf: ByteArray, off: Int, len: Int): ScopeData {
        val histFlags = ScopeData.HIST or ScopeData.HIST_RGB
        val cam = if (flags and histFlags != 0) cameraHistogram(buf, off, len) else null
        if (cam == null) return ScopeData.compute(bmp, flags)
        val rest = flags and histFlags.inv()
        val d = if (rest != 0) ScopeData.compute(bmp, rest) else null
        return ScopeData(cam[0], cam[1], cam[2], cam[3], d?.wave, d?.paradeR, d?.paradeG, d?.paradeB, d?.vector)
    }

    private var histLogged = false
    private var afStateLogged = Int.MIN_VALUE
    private val evfBlockLog = HashMap<Int, String>()
    private var propDumpDone = false

    /**
     * Writes every received property to Logcat (simple value + structured content), 10 at a time
     * with pauses so Android doesn't drop lines. Used to find the battery property.
     */
    private fun dumpAllProps() {
        val keys = propValues.keys.sorted()
        Log.i(TAG, "=== Properties (${keys.size}) ===")
        keys.forEachIndexed { i, k ->
            val v = propValues[k] ?: 0
            val structured = eventDumps["P${PtpUsb.hex(k)}"]
            Log.i(TAG, "P ${PtpUsb.hex(k)} = $v (${PtpUsb.hex(v)})" + (structured?.let { " [$it]" } ?: ""))
            if (i % 10 == 9) sleep(60)
        }
        val lists = allowed.keys.sorted()
        Log.i(TAG, "=== Allowed values (${lists.size}) ===")
        lists.forEachIndexed { i, k ->
            Log.i(TAG, "L ${PtpUsb.hex(k)}: " + allowed[k].orEmpty().joinToString(" ") { PtpUsb.hex(it) })
            if (i % 10 == 9) sleep(60)
        }
        Log.i(TAG, "=== end ===")
    }
    private var quietUntil = 0L

    /**
     * The 4096-byte 0x11 block after the JPEG: histogram computed by the camera from the full image,
     * 4 channels x 256 bins x u32. Assumed order (as in EDSDK): Y, R, G, B.
     */
    private fun cameraHistogram(buf: ByteArray, off: Int, len: Int): Array<IntArray>? {
        val end = off + len
        var p = off
        var n = 0
        while (p + 8 <= end && n < 60) {
            val bl = PtpUsb.getInt(buf, p)
            val bt = PtpUsb.getInt(buf, p + 4)
            if (bl < 8 || p + bl > end) break
            if (bt == 0x11 && bl == 8 + 4096 && !(buf[p + 8] == 0xFF.toByte() && buf[p + 9] == 0xD8.toByte())) {
                val h = Array(4) { IntArray(256) }
                var q = p + 8
                for (c in 0 until 4) for (i in 0 until 256) { h[c][i] = PtpUsb.getInt(buf, q); q += 4 }
                if (!histLogged) {
                    histLogged = true
                    Log.i(TAG, "Camera histogram, channel sums: " + h.joinToString { ch -> ch.sumOf { it.toLong() }.toString() })
                }
                return h
            }
            p += bl
            n++
        }
        return null
    }

    // ---------------- Card space ----------------

    private fun readStorage(ptp: PtpUsb) {
        val r = ptp.transaction(PtpUsb.OP_GET_STORAGE_IDS)
        if (!r.ok || r.dataLength < 4) {
            Log.i(TAG, "GetStorageIDs: ${PtpUsb.hex(r.code)}; giving up on card space")
            storageSupported = false
            return
        }
        val count = PtpUsb.getInt(ptp.rx, r.dataOffset).coerceIn(0, 8)
        val ids = (0 until count).map { PtpUsb.getInt(ptp.rx, r.dataOffset + 4 + 4 * it) }
        val free = ArrayList<Long>()
        val names = ArrayList<String>()
        for ((k, id) in ids.withIndex()) {
            if (id and 0xFFFF == 0) continue          // empty slot
            val ri = ptp.transaction(PtpUsb.OP_GET_STORAGE_INFO, id)
            if (!ri.ok || ri.dataLength < 22) continue
            val max = getLong(ptp.rx, ri.dataOffset + 6)
            val fr = getLong(ptp.rx, ri.dataOffset + 14)
            if (max <= 0 || fr < 0) continue
            free.add(fr)
            names.add("SD${k + 1}")
        }
        if (free.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val freeArr = free.toLongArray()

        // While recording: the card whose free space drops is the one being recorded to
        if (info.recording) {
            if (recStartFree < 0 || cardFree.size != freeArr.size) {
                recStartFree = freeArr.sum(); recStartTime = now
            } else {
                for (i in freeArr.indices) if (freeArr[i] < cardFree[i]) recCard = i
                val dt = (now - recStartTime) / 1000.0
                val used = recStartFree - freeArr.sum()
                if (dt >= 8 && used > 0) bitrate = (used / dt).toLong()
            }
        }
        cardFree = freeArr

        val parts = names.indices.map { i -> "${names[i]} ${formatGb(freeArr[i])}" }
        val target = if (recCard in freeArr.indices) recCard else 0
        val minutes = if (bitrate > 0) (freeArr[target] / bitrate / 60).toInt() else null
        val text = parts.joinToString("\n") + (minutes?.let { "\n~$it min" } ?: "")
        if (text != info.cards) {
            info = info.copy(cards = text)
            listener.onCameraInfo(info)
        }
    }

    private fun formatGb(b: Long): String {
        val gb = b / 1_000_000_000.0
        return if (gb >= 10) "${gb.toInt()} GB" else String.format(java.util.Locale.US, "%.1f GB", gb)
    }

    private fun getLong(b: ByteArray, o: Int): Long =
        (PtpUsb.getInt(b, o).toLong() and 0xFFFFFFFFL) or (PtpUsb.getInt(b, o + 4).toLong() shl 32)

    // ---------------- Electronic level ----------------

    /** Asks the camera to send its tilt (SetRequestRollingPitchingLevel). The answer arrives via events. */
    private fun requestLevel(ptp: PtpUsb, on: Boolean) {
        val r = ptp.transaction(PtpUsb.OP_EOS_SET_REQUEST_ROLLING_PITCHING_LEVEL, if (on) 1 else 0)
        Log.i(TAG, "Level request ${if (on) "on" else "off"}: ${PtpUsb.hex(r.code)}")
    }

    private fun hexDump(b: ByteArray, o: Int, n: Int): String =
        (0 until n).joinToString(" ") { "%02X".format(b[o + it]) }

    /** Battery level via the standard PTP property 0x5001; give up if unsupported. */
    private fun readBattery(ptp: PtpUsb) {
        val r = ptp.transaction(PtpUsb.OP_GET_DEVICE_PROP_VALUE, 0x5001)
        if (!r.ok || r.dataLength < 1) {
            Log.i(TAG, "Battery (0x5001) not available: ${PtpUsb.hex(r.code)}")
            batterySupported = false
            return
        }
        val level = ptp.rx[r.dataOffset].toInt() and 0xFF
        if (level in 0..100 && level != info.battery) {
            info = info.copy(battery = level)
            listener.onCameraInfo(info)
        }
    }

    private fun setProp(ptp: PtpUsb, prop: Int, value: Int): PtpUsb.Result {
        val data = ByteArray(12)
        PtpUsb.putInt(data, 0, 12)
        PtpUsb.putInt(data, 4, prop)
        PtpUsb.putInt(data, 8, value)
        return ptp.transaction(PtpUsb.OP_EOS_SET_DEVICE_PROP_VALUE_EX, dataOut = data)
    }

    private fun check(ptp: PtpUsb, r: PtpUsb.Result, what: String) {
        if (!r.ok) Log.w(TAG, "$what returned ${PtpUsb.hex(r.code)}")
    }

    /**
     * Canon viewfinder data is a series of blocks [length u32][type u32][content].
     * Block type 1 (or 9 / 0x11 on newer models) is the JPEG. If the structure doesn't match,
     * search for the start of a JPEG (FF D8 FF) directly.
     */
    private fun decodeJpeg(buf: ByteArray, off: Int, len: Int): Bitmap? {
        val end = off + len
        var jOff = -1
        var jLen = 0
        var p = off
        while (p + 8 <= end) {
            val bl = PtpUsb.getInt(buf, p)
            val bt = PtpUsb.getInt(buf, p + 4)
            if (bl < 8 || p + bl > end) break
            if ((bt == 1 || bt == 9 || bt == 0x11) && bl > 10 &&
                buf[p + 8] == 0xFF.toByte() && buf[p + 9] == 0xD8.toByte()) {
                jOff = p + 8; jLen = bl - 8
                break
            }
            p += bl
        }
        if (jOff < 0) {
            for (i in off until end - 2) {
                if (buf[i] == 0xFF.toByte() && buf[i + 1] == 0xD8.toByte() && buf[i + 2] == 0xFF.toByte()) {
                    jOff = i; jLen = end - i
                    break
                }
            }
        }
        if (jOff < 0) return null
        jpegOff = jOff
        jpegLen = jLen

        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inBitmap = renderer.obtainBitmap()   // reuse the memory of old frames
        }
        return try {
            BitmapFactory.decodeByteArray(buf, jOff, jLen, opts)
        } catch (e: IllegalArgumentException) {
            // the reused bitmap has the wrong size
            BitmapFactory.decodeByteArray(buf, jOff, jLen,
                BitmapFactory.Options().apply { inMutable = true })
        }
    }

    private fun status(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        listener.onStatus(text)
    }

    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms); true
    } catch (e: InterruptedException) {
        false
    }

    companion object {
        private const val TAG = "CanonPTP"
        private const val CANON_VENDOR_ID = 0x04A9
        /** Logs the live view data structure (debugging only; very verbose). */
        private const val DEBUG_EVF = false
        /** Diagnostics: changes to properties and small live view blocks (in Logcat). */
        private const val DIAG = false
        /** How many AF frames to draw (Expand: Around = 1 + 8). */
        const val MAX_AF_FRAMES = 9
        /** Live view blocks to watch: candidates for the level and focus state. */
        private val DIAG_BLOCKS = setOf(0x04, 0x07, 0x10, 0x14, 0x15, 0x16, 0x18, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22)
    }
}
