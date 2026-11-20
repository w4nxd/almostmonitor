package io.github.almostmonitor

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Receives live view frames (Bitmaps decoded from JPEG), uploads them to the GPU and applies,
 * in a single shader: zoom, 3D LUT, false color, focus peaking, zebras, framing guides.
 */
class MonitorRenderer(private val view: GLSurfaceView) : GLSurfaceView.Renderer {

    // Settings read on the GL thread every frame
    @Volatile var zebraOn = false
    @Volatile var zebraThreshold = 0.95f
    @Volatile var zebraAfterLut = true
    @Volatile var peakingOn = false
    @Volatile var peakingThreshold = 2.6f
    @Volatile var peakingColor = floatArrayOf(1f, 0f, 0f)
    @Volatile var lutOn = false
    @Volatile var falseColorOn = false
    @Volatile var guideAspect = 0f          // 0 = no guide; otherwise width/height (e.g. 2.39)
    @Volatile var thirdsOn = false
    @Volatile var peakingMono = false       // black & white image while peaking is on
    @Volatile var squeeze = 1f              // anamorphic desqueeze (1 = off)
    @Volatile var mirror = false            // left-right mirror
    @Volatile var rotate180 = false
    @Volatile var safeAreas = false         // 90% / 80% frames
    @Volatile var referenceOn = false       // overlays the reference capture
    @Volatile var referenceAlpha = 0.5f
    @Volatile var afOn = true
    /** AF frames in the image (0..1), 4 values each: x0, y0, x1, y1; null = unknown. */
    @Volatile var afRect: FloatArray? = null
    @Volatile var afFocused = false

    // Zoom: factor and center of the visible area, in image coordinates 0..1 (y down)
    @Volatile var zoom = 1f
    @Volatile var zoomCx = 0.5f
    @Volatile var zoomCy = 0.5f

    // Screen area where the image is drawn (GL coordinates, y from the bottom), for mapping touches
    @Volatile var vpX = 0; @Volatile var vpY = 0
    @Volatile var vpW = 1; @Volatile var vpH = 1
    @Volatile var surfaceHeight = 1

    // Space taken by the side columns (pixels); the image fits between them
    @Volatile var insetLeft = 0
    @Volatile var insetRight = 0

    // Frames received (for fps display)
    @Volatile var framesReceived = 0L

    // --- frames: latest Bitmap received + pool of reusable Bitmaps ---
    private val lock = Any()
    private var pending: Bitmap? = null
    private val free = ArrayBlockingQueue<Bitmap>(4)

    private var texW = 0; private var texH = 0
    private var surfW = 1; private var surfH = 1

    private var program = 0
    private var imgTex = 0; private var lutTex = 0; private var refTex = 0
    private var reference: Bitmap? = null
    private var referenceDirty = false
    private var hasReference = false
    private var lut: CubeLut = CubeLut.identity()

    private var aPos = 0; private var aTex = 0
    private var uImg = 0; private var uLut = 0
    private var uTexel = 0; private var uLutOn = 0; private var uLutSize = 0
    private var uDomMin = 0; private var uDomMax = 0
    private var uZebraOn = 0; private var uZebraAfterLut = 0; private var uZebraThr = 0
    private var uPeakOn = 0; private var uPeakThr = 0; private var uPeakColor = 0
    private var uZoom = 0; private var uCenter = 0; private var uFalse = 0
    private var uGuide = 0; private var uFrameAspect = 0; private var uThirds = 0
    private var uPeakMono = 0; private var uMirror = 0; private var uRot = 0; private var uSafe = 0
    private var uRef = 0; private var uRefOn = 0; private var uRefAlpha = 0
    private var uAfOn = 0; private var uAf = 0; private var uAfOk = 0

    private val quad: FloatBuffer = floatBuffer(
        -1f, -1f, 0f, 1f,
         1f, -1f, 1f, 1f,
        -1f,  1f, 0f, 0f,
         1f,  1f, 1f, 0f
    )

    /** A free Bitmap for decoding (BitmapFactory inBitmap), or null. */
    fun obtainBitmap(): Bitmap? = free.poll()

    /** Called from the camera thread for every decoded frame. */
    fun submitBitmap(bmp: Bitmap) {
        val old: Bitmap?
        synchronized(lock) {
            old = pending
            pending = bmp
        }
        if (old != null) free.offer(old)   // a frame not drawn in time gets reused
        framesReceived++
        view.requestRender()
    }

    /** Called on the GL thread (via queueEvent). The renderer keeps the Bitmap. */
    fun setReference(b: Bitmap) {
        reference = b
        referenceDirty = true
    }

    /** Called on the GL thread (via queueEvent). */
    fun setLut(newLut: CubeLut) {
        lut = newLut
        if (lutTex != 0) uploadLut()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aTex = GLES30.glGetAttribLocation(program, "aTex")
        uImg = GLES30.glGetUniformLocation(program, "uImg")
        uLut = GLES30.glGetUniformLocation(program, "uLut")
        uTexel = GLES30.glGetUniformLocation(program, "uTexel")
        uLutOn = GLES30.glGetUniformLocation(program, "uLutOn")
        uLutSize = GLES30.glGetUniformLocation(program, "uLutSize")
        uDomMin = GLES30.glGetUniformLocation(program, "uDomMin")
        uDomMax = GLES30.glGetUniformLocation(program, "uDomMax")
        uZebraOn = GLES30.glGetUniformLocation(program, "uZebraOn")
        uZebraAfterLut = GLES30.glGetUniformLocation(program, "uZebraAfterLut")
        uZebraThr = GLES30.glGetUniformLocation(program, "uZebraThr")
        uPeakOn = GLES30.glGetUniformLocation(program, "uPeakOn")
        uPeakThr = GLES30.glGetUniformLocation(program, "uPeakThr")
        uPeakColor = GLES30.glGetUniformLocation(program, "uPeakColor")
        uZoom = GLES30.glGetUniformLocation(program, "uZoom")
        uCenter = GLES30.glGetUniformLocation(program, "uCenter")
        uFalse = GLES30.glGetUniformLocation(program, "uFalse")
        uGuide = GLES30.glGetUniformLocation(program, "uGuide")
        uFrameAspect = GLES30.glGetUniformLocation(program, "uFrameAspect")
        uThirds = GLES30.glGetUniformLocation(program, "uThirds")
        uPeakMono = GLES30.glGetUniformLocation(program, "uPeakMono")
        uMirror = GLES30.glGetUniformLocation(program, "uMirror")
        uRot = GLES30.glGetUniformLocation(program, "uRot")
        uSafe = GLES30.glGetUniformLocation(program, "uSafe")
        uRef = GLES30.glGetUniformLocation(program, "uRef")
        uRefOn = GLES30.glGetUniformLocation(program, "uRefOn")
        uRefAlpha = GLES30.glGetUniformLocation(program, "uRefAlpha")
        uAfOn = GLES30.glGetUniformLocation(program, "uAfOn")
        uAf = GLES30.glGetUniformLocation(program, "uAf")
        uAfOk = GLES30.glGetUniformLocation(program, "uAfOk")

        val ids = IntArray(3)
        GLES30.glGenTextures(3, ids, 0)
        imgTex = ids[0]; lutTex = ids[1]; refTex = ids[2]
        for (t in intArrayOf(imgTex, refTex)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        hasReference = false
        referenceDirty = reference != null
        texW = 0; texH = 0   // textures are (re)allocated on the first frame
        uploadLut()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfW = width; surfH = height
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // Upload the latest frame (if there's a new one)
        val bmp = synchronized(lock) { val b = pending; pending = null; b }
        if (bmp != null) {
            uploadBitmap(bmp)
            free.offer(bmp)   // GLUtils copied the pixels, the Bitmap can be reused
        }
        if (referenceDirty) {
            reference?.let {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refTex)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, it, 0)
                hasReference = true
            }
            referenceDirty = false
        }
        GLES30.glViewport(0, 0, surfW, surfH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (texW == 0) return
        val frontW = texW
        val frontH = texH

        // Letterbox
        val videoAspect = frontW.toFloat() / frontH * squeeze
        val availX = insetLeft
        val availW = (surfW - insetLeft - insetRight).coerceAtLeast(1)
        val availAspect = availW.toFloat() / surfH
        if (availAspect > videoAspect) {
            val w = (surfH * videoAspect).toInt()
            vpX = availX + (availW - w) / 2; vpY = 0; vpW = w; vpH = surfH
        } else {
            val h = (availW / videoAspect).toInt()
            vpX = availX; vpY = (surfH - h) / 2; vpW = availW; vpH = h
        }
        GLES30.glViewport(vpX, vpY, vpW, vpH)

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imgTex)
        GLES30.glUniform1i(uImg, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(uLut, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refTex)
        GLES30.glUniform1i(uRef, 2)

        GLES30.glUniform2f(uTexel, 1f / frontW, 1f / frontH)
        GLES30.glUniform1i(uLutOn, if (lutOn) 1 else 0)
        GLES30.glUniform1f(uLutSize, lut.size.toFloat())
        GLES30.glUniform3f(uDomMin, lut.domainMin[0], lut.domainMin[1], lut.domainMin[2])
        GLES30.glUniform3f(uDomMax, lut.domainMax[0], lut.domainMax[1], lut.domainMax[2])
        GLES30.glUniform1i(uZebraOn, if (zebraOn) 1 else 0)
        GLES30.glUniform1i(uZebraAfterLut, if (zebraAfterLut) 1 else 0)
        GLES30.glUniform1f(uZebraThr, zebraThreshold)
        GLES30.glUniform1i(uPeakOn, if (peakingOn) 1 else 0)
        GLES30.glUniform1f(uPeakThr, peakingThreshold)
        val c = peakingColor
        GLES30.glUniform3f(uPeakColor, c[0], c[1], c[2])
        GLES30.glUniform1f(uZoom, zoom)
        GLES30.glUniform2f(uCenter, zoomCx, zoomCy)
        GLES30.glUniform1i(uFalse, if (falseColorOn) 1 else 0)
        GLES30.glUniform1f(uGuide, guideAspect)
        GLES30.glUniform1f(uFrameAspect, videoAspect)
        GLES30.glUniform1i(uThirds, if (thirdsOn) 1 else 0)
        GLES30.glUniform1i(uPeakMono, if (peakingMono) 1 else 0)
        GLES30.glUniform1i(uMirror, if (mirror) 1 else 0)
        GLES30.glUniform1i(uRot, if (rotate180) 1 else 0)
        GLES30.glUniform1i(uSafe, if (safeAreas) 1 else 0)
        GLES30.glUniform1i(uRefOn, if (referenceOn && hasReference) 1 else 0)
        GLES30.glUniform1f(uRefAlpha, referenceAlpha)
        val af = afRect
        val afN = if (af == null) 0 else minOf(af.size / 4, CanonLiveViewController.MAX_AF_FRAMES)
        GLES30.glUniform1i(uAfOn, if (afOn && afN > 0) afN else 0)
        if (af != null && afN > 0) GLES30.glUniform4fv(uAf, afN, af, 0)
        GLES30.glUniform1i(uAfOk, if (afFocused) 1 else 0)

        quad.position(0)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(aTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uploadBitmap(b: Bitmap) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imgTex)
        if (b.width != texW || b.height != texH) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
            texW = b.width; texH = b.height
        } else {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, b)
        }
    }

    private fun uploadLut() {
        val l = lut
        val buf = floatBuffer(*l.data)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            l.size, l.size, l.size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buf
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) Log.e(TAG, "LUT upload error: 0x${err.toString(16)}")
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Link shader: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Shader compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val TAG = "MonitorRenderer"

        private fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(v); position(0) }

        // aTex.y = 0 at the top: row 0 of the frame is at the top of the screen
        private const val VERTEX_SHADER = """#version 300 es
in vec2 aPos;
in vec2 aTex;
out vec2 vTex;
void main() {
    vTex = aTex;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
precision highp sampler3D;

in vec2 vTex;
out vec4 fragColor;

uniform sampler2D uImg;
uniform sampler3D uLut;
uniform vec2 uTexel;
uniform int uLutOn;
uniform float uLutSize;
uniform vec3 uDomMin;
uniform vec3 uDomMax;
uniform int uZebraOn;
uniform int uZebraAfterLut;
uniform float uZebraThr;
uniform int uPeakOn;
uniform float uPeakThr;
uniform vec3 uPeakColor;
uniform float uZoom;
uniform vec2 uCenter;
uniform int uFalse;
uniform float uGuide;
uniform float uFrameAspect;
uniform int uThirds;
uniform int uPeakMono;
uniform int uMirror;
uniform int uRot;
uniform int uSafe;
uniform sampler2D uRef;
uniform int uRefOn;
uniform float uRefAlpha;
uniform int uAfOn;     // number of AF frames (0 = off)
uniform vec4 uAf[9];   // AF frames in image coordinates: x0, y0, x1, y1
uniform int uAfOk;     // 1 = focus confirmed (green)

const vec3 REC709 = vec3(0.2126, 0.7152, 0.0722);

vec3 rgbAt(vec2 uv) { return texture(uImg, uv).rgb; }

float lumaAt(vec2 uv) { return dot(texture(uImg, uv).rgb, REC709); }

vec3 applyLut(vec3 c) {
    vec3 n = clamp((c - uDomMin) / (uDomMax - uDomMin), 0.0, 1.0);
    vec3 coord = n * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
    return texture(uLut, coord).rgb;
}

// False color: exposure bands (percent of luma, roughly IRE)
vec3 falseColor(float y) {
    float ire = y * 100.0;
    if (ire < 2.5)                   return vec3(0.55, 0.0, 0.7);  // purple: black without detail
    if (ire < 10.0)                  return vec3(0.0, 0.35, 1.0);  // blue: deep shadows
    if (ire >= 38.0 && ire < 44.0)   return vec3(0.1, 0.85, 0.2);  // green: 18% middle gray
    if (ire >= 52.0 && ire < 58.0)   return vec3(1.0, 0.5, 0.75);  // pink: bright skin tone
    if (ire >= 94.0 && ire < 99.0)   return vec3(1.0, 0.85, 0.0);  // yellow: near clipping
    if (ire >= 99.0)                 return vec3(1.0, 0.0, 0.0);   // red: clipped
    return vec3(y);                                                // everything else: grayscale
}

void main() {
    // Mirror / rotate, then zoom: vTex = position on screen, uv = position in the image
    vec2 st = vTex;
    if (uMirror == 1) st.x = 1.0 - st.x;
    if (uRot == 1) st = 1.0 - st;
    vec2 uv = uCenter + (st - 0.5) / uZoom;
    vec3 src = rgbAt(uv);
    vec3 graded = (uLutOn == 1) ? applyLut(src) : src;
    vec3 outc = graded;

    if (uFalse == 1) {
        outc = falseColor(dot((uZebraAfterLut == 1) ? graded : src, REC709));
    }

    if (uRefOn == 1) {
        outc = mix(outc, texture(uRef, uv).rgb, uRefAlpha);
    }

    if (uPeakOn == 1) {
        if (uPeakMono == 1 && uFalse == 0) outc = vec3(dot(outc, REC709) * 0.85);
        // Focus peaking: how sharp the transition is (Sobel) relative to local contrast.
        // A sharp edge has a ratio of ~4; a blurry one ~1-2, regardless of its contrast.
        vec2 t = uTexel;
        float c  = lumaAt(uv);
        float tl = lumaAt(uv + vec2(-t.x, -t.y));
        float tc = lumaAt(uv + vec2( 0.0, -t.y));
        float tr = lumaAt(uv + vec2( t.x, -t.y));
        float ml = lumaAt(uv + vec2(-t.x,  0.0));
        float mr = lumaAt(uv + vec2( t.x,  0.0));
        float bl = lumaAt(uv + vec2(-t.x,  t.y));
        float bc = lumaAt(uv + vec2( 0.0,  t.y));
        float br = lumaAt(uv + vec2( t.x,  t.y));
        float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
        float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
        float sob = sqrt(gx * gx + gy * gy);

        // local contrast: min / max within a 3-pixel radius
        vec2 t3 = 3.0 * t;
        float r0 = lumaAt(uv + vec2(-t3.x, 0.0));
        float r1 = lumaAt(uv + vec2( t3.x, 0.0));
        float r2 = lumaAt(uv + vec2(0.0, -t3.y));
        float r3 = lumaAt(uv + vec2(0.0,  t3.y));
        float r4 = lumaAt(uv + vec2(-t3.x, -t3.y));
        float r5 = lumaAt(uv + vec2( t3.x, -t3.y));
        float r6 = lumaAt(uv + vec2(-t3.x,  t3.y));
        float r7 = lumaAt(uv + vec2( t3.x,  t3.y));
        float mx = max(max(max(max(c, tl), max(tc, tr)), max(max(ml, mr), max(bl, bc))),
                       max(max(max(br, r0), max(r1, r2)), max(max(r3, r4), max(max(r5, r6), r7))));
        float mn = min(min(min(min(c, tl), min(tc, tr)), min(min(ml, mr), min(bl, bc))),
                       min(min(min(br, r0), min(r1, r2)), min(min(r3, r4), min(min(r5, r6), r7))));
        float range = mx - mn;

        float ratio = sob / (range + 0.01);
        // ignore noise and JPEG artifacts in nearly flat areas
        float enough = smoothstep(0.04, 0.08, range) * smoothstep(0.05, 0.10, sob);
        float k = smoothstep(uPeakThr, uPeakThr + 0.4, ratio) * enough;
        outc = mix(outc, uPeakColor, k);
    }

    if (uZebraOn == 1) {
        float y = dot((uZebraAfterLut == 1) ? graded : src, REC709);
        if (y >= uZebraThr) {
            float s = mod(gl_FragCoord.x + gl_FragCoord.y, 12.0);
            if (s < 6.0) outc = mix(outc, vec3(0.0), 0.85);
        }
    }

    // Focus point frame, drawn in image coordinates (follows zoom and mirroring)
    if (uAfOn > 0) {
        vec2 lw = fwidth(uv) * 2.0;                    // ~2 screen pixels thick
        bool edge = false;
        for (int i = 0; i < 9; i++) {
            if (i >= uAfOn) break;
            vec4 a = uAf[i];
            bool inX = uv.x >= a.x - lw.x && uv.x <= a.z + lw.x;
            bool inY = uv.y >= a.y - lw.y && uv.y <= a.w + lw.y;
            bool eX = (abs(uv.x - a.x) < lw.x || abs(uv.x - a.z) < lw.x) && inY;
            bool eY = (abs(uv.y - a.y) < lw.y || abs(uv.y - a.w) < lw.y) && inX;
            if (eX || eY) edge = true;
        }
        if (edge) outc = (uAfOk == 1) ? vec3(0.2, 1.0, 0.3) : vec3(1.0);
    }

    // Framing guides, in screen coordinates (don't move with zoom)
    vec2 halfSize = vec2(0.5);
    if (uGuide > 0.0) {
        if (uGuide > uFrameAspect) halfSize = vec2(0.5, 0.5 * uFrameAspect / uGuide);
        else                       halfSize = vec2(0.5 * uGuide / uFrameAspect, 0.5);
    }
    vec2 d = abs(vTex - 0.5);
    vec2 px = fwidth(vTex) * 1.5;
    if (uGuide > 0.0) {
        if (d.x > halfSize.x || d.y > halfSize.y) outc *= 0.35;
        bool edgeX = abs(d.x - halfSize.x) < px.x && d.y <= halfSize.y;
        bool edgeY = abs(d.y - halfSize.y) < px.y && d.x <= halfSize.x;
        if (edgeX || edgeY) outc = (uAfOk == 1) ? vec3(0.2, 1.0, 0.3) : vec3(1.0);
    }
    if (uThirds == 1 && d.x <= halfSize.x && d.y <= halfSize.y) {
        vec2 lo = 0.5 - halfSize;
        vec2 t = (vTex - lo) / (2.0 * halfSize);           // 0..1 within the guide area
        vec2 tpx = px / (2.0 * halfSize);
        bool third = abs(t.x - 1.0/3.0) < tpx.x || abs(t.x - 2.0/3.0) < tpx.x ||
                     abs(t.y - 1.0/3.0) < tpx.y || abs(t.y - 2.0/3.0) < tpx.y;
        bool cross = (abs(t.x - 0.5) < tpx.x && abs(t.y - 0.5) < 0.03) ||
                     (abs(t.y - 0.5) < tpx.y && abs(t.x - 0.5) < 0.03 / uFrameAspect * (halfSize.y / halfSize.x));
        if (third || cross) outc = mix(outc, vec3(1.0), 0.6);
    }

    // Safe areas at 90% and 80% of the frame
    if (uSafe == 1) {
        bool s90 = (abs(d.x - 0.45) < px.x && d.y <= 0.45) || (abs(d.y - 0.45) < px.y && d.x <= 0.45);
        bool s80 = (abs(d.x - 0.40) < px.x && d.y <= 0.40) || (abs(d.y - 0.40) < px.y && d.x <= 0.40);
        if (s90 || s80) outc = mix(outc, vec3(1.0), 0.5);
    }

    fragColor = vec4(outc, 1.0);
}
"""
    }
}
