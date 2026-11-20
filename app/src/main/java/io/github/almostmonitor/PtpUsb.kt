package io.github.almostmonitor

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.io.Closeable
import java.io.IOException

class PtpException(msg: String) : IOException(msg)

/**
 * PTP (Picture Transfer Protocol) transport over USB bulk, using Android's UsbManager API.
 * Little-endian containers: [length u32][type u16][code u16][transactionId u32][params / data].
 */
class PtpUsb(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface
) : Closeable {

    class Result(val code: Int, val dataOffset: Int, val dataLength: Int) {
        val ok get() = code == RC_OK
    }

    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    private var tid = 0
    private var current = 0

    /** Receive buffer; a Result's data is valid until the next transaction. */
    val rx = ByteArray(RX_SIZE)
    private val resp = ByteArray(1024)
    private val cmd = ByteArray(64)

    init {
        var i: UsbEndpoint? = null
        var o: UsbEndpoint? = null
        for (k in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(k)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.direction == UsbConstants.USB_DIR_IN) i = e else o = e
            }
        }
        epIn = i ?: throw PtpException("Missing bulk IN endpoint")
        epOut = o ?: throw PtpException("Missing bulk OUT endpoint")
        if (!conn.claimInterface(intf, true))
            throw PtpException("can't claim the USB interface – is another app holding it?")
        Log.i(TAG, "Interface ${intf.id}: IN ${hex(epIn.address)} (${epIn.maxPacketSize} B), " +
            "OUT ${hex(epOut.address)} (${epOut.maxPacketSize} B), endpoints ${intf.endpointCount}")
        reset()
    }

    /**
     * Brings the link back to its initial state: PTP "Device Reset" (class request)
     * and clears any STALL state left on the endpoints by a previous session.
     */
    fun reset() {
        val r = conn.controlTransfer(0x21, 0x66, 0, intf.id, null, 0, 1000)   // PTP Device Reset
        Log.i(TAG, "PTP Device Reset -> $r")
        resyncEndpoints()
        val status = ByteArray(16)
        val s = conn.controlTransfer(0xA1, 0x67, 0, intf.id, status, status.size, 1000) // Get Device Status
        if (s >= 4) Log.i(TAG, "PTP Device Status -> ${hex(getShort(status, 2))}")
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
    }

    /**
     * SET_INTERFACE resets the endpoint state (including the data toggle) both in the camera
     * and in the phone's kernel. A manual CLEAR_FEATURE would only reset the camera
     * and desynchronize the two sides.
     */
    private fun resyncEndpoints() {
        val ok = conn.setInterface(intf)
        Log.i(TAG, "setInterface(${intf.id}, alt ${intf.alternateSetting}) -> $ok")
    }

    @Synchronized
    fun transaction(code: Int, vararg params: Int, dataOut: ByteArray? = null, timeoutMs: Int = 3000): Result {
        tid++
        current = code
        // Command phase
        val n = 12 + 4 * params.size
        putHeader(cmd, n, TYPE_COMMAND, code, tid)
        params.forEachIndexed { k, p -> putInt(cmd, 12 + 4 * k, p) }
        write(cmd, n, timeoutMs, code)

        // Data phase to the camera (optional)
        if (dataOut != null) {
            val total = 12 + dataOut.size
            val buf = ByteArray(total)
            putHeader(buf, total, TYPE_DATA, code, tid)
            System.arraycopy(dataOut, 0, buf, 12, dataOut.size)
            write(buf, total, timeoutMs, code)
        }

        // First container from the camera: data or response
        var got = readSkippingZlp(rx, 0, CHUNK, timeoutMs)
        if (got < 12) throw PtpException("Container too short ($got)")
        val len = getInt(rx, 0)
        val type = getShort(rx, 4)

        return when (type) {
            TYPE_RESPONSE -> Result(getShort(rx, 6), 0, 0)
            TYPE_DATA -> {
                if (len > rx.size) throw PtpException("Data too large: $len bytes")
                var empty = 0
                while (got < len) {
                    val r = read(rx, got, minOf(len - got, CHUNK), timeoutMs)
                    if (r == 0) {
                        // repeated empty packets: the camera stopped sending, don't hang
                        if (++empty > 20) throw PtpException("Incomplete data from the camera ($got of $len bytes)")
                        continue
                    }
                    got += r
                }
                val rn = readSkippingZlp(resp, 0, resp.size, timeoutMs)
                if (rn < 12) throw PtpException("Missing response after data")
                Result(getShort(resp, 6), 12, len - 12)
            }
            else -> throw PtpException("Unexpected container, type $type")
        }
    }

    private fun write(buf: ByteArray, len: Int, timeoutMs: Int, code: Int) {
        var off = 0
        var retries = 0
        while (off < len) {
            val t0 = android.os.SystemClock.uptimeMillis()
            val r = conn.bulkTransfer(epOut, buf, off, minOf(len - off, CHUNK), timeoutMs)
            if (r < 0) {
                val dt = android.os.SystemClock.uptimeMillis() - t0
                Log.w(TAG, "bulkTransfer OUT failed on ${hex(code)} after $dt ms, attempt ${retries + 1}")
                if (off > 0 || ++retries > 3)
                    throw PtpException("USB write error, command ${hex(code)}")
                resyncEndpoints()
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                continue
            }
            off += r
        }
    }

    private fun read(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        val t0 = android.os.SystemClock.uptimeMillis()
        val r = conn.bulkTransfer(epIn, buf, off, len, timeoutMs)
        if (r < 0) {
            val dt = android.os.SystemClock.uptimeMillis() - t0
            Log.w(TAG, "bulkTransfer IN failed on ${hex(current)} after $dt ms (requested $len B, offset $off)")
            throw PtpException("USB read error, command ${hex(current)}, after $dt ms")
        }
        return r
    }

    /** Zero-length packets (ZLP) sometimes end the previous container: skip them. */
    private fun readSkippingZlp(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        repeat(4) {
            val r = read(buf, off, len, timeoutMs)
            if (r > 0) return r
        }
        return 0
    }

    override fun close() {
        try { conn.releaseInterface(intf) } catch (_: Exception) {}
        conn.close()
    }

    companion object {
        private const val TAG = "CanonPTP"
        const val RX_SIZE = 8 * 1024 * 1024
        // 16 KB: some Android kernels reject larger USB transfers in a single call
        const val CHUNK = 16 * 1024

        const val TYPE_COMMAND = 1
        const val TYPE_DATA = 2
        const val TYPE_RESPONSE = 3

        // Response codes
        const val RC_OK = 0x2001
        const val RC_DEVICE_BUSY = 0x2019
        const val RC_SESSION_ALREADY_OPEN = 0x201E
        const val RC_CANON_NOT_READY = 0xA102

        // Standard operations
        const val OP_OPEN_SESSION = 0x1002
        const val OP_CLOSE_SESSION = 0x1003
        const val OP_GET_DEVICE_PROP_VALUE = 0x1015
        const val OP_GET_STORAGE_IDS = 0x1004
        const val OP_GET_STORAGE_INFO = 0x1005

        // Canon EOS operations (documented in libgphoto2)
        const val OP_EOS_SET_DEVICE_PROP_VALUE_EX = 0x9110
        const val OP_EOS_SET_REMOTE_MODE = 0x9114
        const val OP_EOS_SET_EVENT_MODE = 0x9115
        const val OP_EOS_GET_EVENT = 0x9116
        const val OP_EOS_GET_VIEWFINDER_DATA = 0x9153
        const val OP_EOS_SET_REQUEST_ROLLING_PITCHING_LEVEL = 0x913E
        const val OP_EOS_REMOTE_RELEASE_ON = 0x9128   // (1 = half press / 2 = full press, 0)
        const val OP_EOS_REMOTE_RELEASE_OFF = 0x9129  // (1 / 2)
        const val OP_EOS_DO_AF = 0x9154
        const val OP_EOS_ZOOM = 0x9158                 // 1 param: 1 / 5 / 10
        const val OP_EOS_TOUCH_AF_POSITION = 0x915B    // 3 params: type, x, y (sensor coordinates)
        const val OP_EOS_AF_CANCEL = 0x9160

        // Canon EOS properties
        const val PROP_EVF_OUTPUT_DEVICE = 0xD1B0   // 1 = camera screen, 2 = PC, 3 = both
        const val PROP_EVF_MODE = 0xD1B1          // (0xD1B3 is live view sharpness, not the mode)

        fun getInt(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        fun getShort(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        fun putInt(b: ByteArray, o: Int, v: Int) {
            b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
            b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
        }

        private fun putHeader(b: ByteArray, len: Int, type: Int, code: Int, tid: Int) {
            putInt(b, 0, len)
            b[4] = type.toByte(); b[5] = (type shr 8).toByte()
            b[6] = code.toByte(); b[7] = (code shr 8).toByte()
            putInt(b, 8, tid)
        }

        fun hex(v: Int) = "0x" + Integer.toHexString(v).uppercase()
    }
}
