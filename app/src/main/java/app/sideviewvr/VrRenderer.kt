// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class VrRenderer(
    private val settings: Settings,
    private val tracker: HeadTracker,
    private val controller: DaydreamController,
    /** Called on the GL thread when a new video Surface exists (also after a GL context loss). */
    private val onVideoSurface: (Surface) -> Unit,
) : GLSurfaceView.Renderer {
    @Volatile var videoAspect = 16f / 9f
    @Volatile var hasVideo = false
    /** Display density in pixels per meter (from DisplayMetrics). */
    @Volatile var pxPerMeter = 18000f

    // In-VR control panel
    @Volatile var panelVisible = false
    val pendingPanel = AtomicReference<Bitmap?>(null)
    /** Pointer position on the panel (0..1, origin top-left), or NaN when not on it. */
    @Volatile var hitX = Float.NaN
    @Volatile var hitY = Float.NaN

    /** What the pointer is on outside the panel (for touchpad / wheel scrolling). */
    @Volatile var pointTarget = Target.NONE
    enum class Target { NONE, SCREEN, LIGHTS }

    /** House light level to fade to (the renderer eases toward it). */
    @Volatile var lightsTarget = 1f
    /** 0 = theatre visible, 1 = black void around the screen. */
    @Volatile var voidTarget = 0f
    /** Curtains: 1 = open (a video is on), 0 = closed. */
    @Volatile var curtainTarget = 0f
    private var curtainNow = 0f
    private var voidNow = 0f
    private var laser: LaserBeam? = null
    private var lightsNow = 1f
    private var lastFrameNs = 0L
    private var frameDt = 0f

    /** Mouse pointer (yaw/pitch in radians), used while [mouseActive]. */
    /** Asks the view for a frame (render-on-demand: frames are drawn only when something changed). */
    @Volatile var requestRender: () -> Unit = {}
    /** Battery saver: smaller eye buffers, less frequent room-light updates. */
    @Volatile var saver = false
    /** Lock the view: ignore head movement (the screen stays straight ahead). */
    @Volatile var lockView = false
    private val lockedHead = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var eyeScale = 0f
    fun recenterLock() { Matrix.setIdentityM(lockedHead, 0); adjYaw = 0f; adjPitch = 0f; requestRender() }

    // Smart view adjust: the room eases toward where the head rests when it is a little off.
    private var adjYaw = 0f
    private var adjPitch = 0f
    private var lastFwdX = 0f; private var lastFwdY = 0f; private var lastFwdZ = -1f
    private val adjM = FloatArray(16)
    private val adjTmp = FloatArray(16)

    /**
     * When the head rests within ~12 degrees of the screen, rotate the room toward it with a
     * slow exponential ease (time constant ~2.5 s): it only ever moves toward the head, so it
     * never overshoots or bounces. Pauses while the head is turning or looking further away.
     */
    private fun smartAdjust(dt: Float) {
        val fx = -head[2]; val fy = -head[6]; val fz = -head[10]
        val turn = kotlin.math.acos(((fx * lastFwdX + fy * lastFwdY + fz * lastFwdZ)).coerceIn(-1f, 1f))
        lastFwdX = fx; lastFwdY = fy; lastFwdZ = fz
        val speed = if (dt > 0) Math.toDegrees((turn / dt).toDouble()) else 0.0
        val yaw = atan2(fx, -fz)
        val pitch = kotlin.math.asin(fy.coerceIn(-1f, 1f))
        val dy = wrap(yaw - adjYaw); val dp = pitch - adjPitch
        val limit = Math.toRadians(12.0).toFloat()
        if (speed < 12.0 && kotlin.math.abs(dy) < limit && kotlin.math.abs(dp) < limit && dt > 0) {
            val k = 1f - kotlin.math.exp(-dt / 2.5f)
            adjYaw = wrap(adjYaw + dy * k); adjPitch += dp * k
            if (kotlin.math.abs(dy) > 1e-3f || kotlin.math.abs(dp) > 1e-3f) requestRender()
        }
    }

    private fun wrap(a: Float): Float {
        var x = a
        while (x > Math.PI) x -= (2 * Math.PI).toFloat()
        while (x < -Math.PI) x += (2 * Math.PI).toFloat()
        return x
    }

    /** Applies the adjustment to the head matrix: the room turns by (adjYaw, adjPitch). */
    private fun applyAdjust() {
        Matrix.setIdentityM(adjM, 0)
        Matrix.rotateM(adjM, 0, Math.toDegrees(-adjYaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(adjM, 0, Math.toDegrees(adjPitch.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.multiplyMM(adjTmp, 0, head, 0, adjM, 0)
        System.arraycopy(adjTmp, 0, head, 0, 16)
    }

    /** Pointer rays are in head/controller space; turn them into the adjusted room. */
    private fun unadjust(d: FloatArray) {
        if (adjYaw == 0f && adjPitch == 0f) return
        // Inverse of Ry(-yaw) * Rx(pitch) = Rx(-pitch) * Ry(yaw)
        val cy = cos(adjYaw); val sy = sin(adjYaw)
        val x1 = d[0] * cy + d[2] * sy; val z1 = -d[0] * sy + d[2] * cy
        val cp = cos(adjPitch); val sp = sin(adjPitch)
        val y2 = d[1] * cp + z1 * sp; val z2 = -d[1] * sp + z1 * cp
        d[0] = x1; d[1] = y2; d[2] = z2
    }

    /** Debug builds: ignore head tracking (for screenshots over adb). */
    @Volatile var fixedView = false
    @Volatile var fixedYaw = 0f
    @Volatile var fixedPitch = 0f
    @Volatile var mouseActive = false
    @Volatile var mouseYaw = 0f
    @Volatile var mousePitch = 0f

    private var room: CinemaRoom? = null
    private var seats: TheatreSeats? = null
    private var curtains: Curtains? = null
    private var screen: VideoScreen? = null
    private var immersive: ImmersiveView? = null
    private var lens: LensDistortion? = null
    private var glowCap: GlowCapture? = null
    private var panel: PanelQuad? = null
    private var eyes: Array<EyeTarget>? = null

    private var videoTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var frameAvailable = false
    @Volatile private var bufferW = 1920
    @Volatile private var bufferH = 1080
    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var frameCount = 0

    private var width = 1
    private var height = 1

    private val head = FloatArray(16)
    private val view = FloatArray(16)
    private val rotView = FloatArray(16)
    private val proj = FloatArray(16)
    private val vp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val tilt = FloatArray(16)
    private val eyeShift = FloatArray(16)
    private val camT = FloatArray(16)
    private val panelModel = FloatArray(16)
    private val dotModel = FloatArray(16)
    private val leftRect = FloatArray(4)
    private val rightRect = FloatArray(4)

    fun setVideoBufferSize(w: Int, h: Int) {
        bufferW = w; bufferH = h
        surfaceTexture?.setDefaultBufferSize(w, h)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        room = CinemaRoom()
        seats = TheatreSeats()
        curtains = Curtains()
        screen = VideoScreen()
        immersive = ImmersiveView()
        lens = LensDistortion()
        glowCap = GlowCapture()
        panel = PanelQuad()
        eyes = null

        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        videoTex = ids[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(videoTex)
        st.setDefaultBufferSize(bufferW, bufferH)
        st.setOnFrameAvailableListener { frameAvailable = true; requestRender() }
        val old = surfaceTexture
        surfaceTexture = st
        onVideoSurface(Surface(st))
        old?.release()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        makeEyes()
    }

    /** Eye buffer size relative to the display: supersampled for a sharper screen; less with the saver. */
    private fun targetEyeScale() = settings.renderScale.coerceIn(0.7f, 1.6f)

    private fun makeEyes() {
        eyeScale = targetEyeScale()
        val size = (minOf(width / 2, height) * eyeScale).toInt().coerceIn(384, 2048)
        eyes = arrayOf(EyeTarget(size), EyeTarget(size))
    }

    /** Fills the left/right eye sub-rectangles (u, v, w, h) of the video frame. */
    private fun computeEyeRects() {
        fun set(r: FloatArray, u: Float, v: Float, w: Float, h: Float) { r[0] = u; r[1] = v; r[2] = w; r[3] = h }
        when (settings.stereoFormat) {
            StereoFormat.SBS_HALF, StereoFormat.SBS_FULL -> { set(leftRect, 0f, 0f, .5f, 1f); set(rightRect, .5f, 0f, .5f, 1f) }
            StereoFormat.TB_HALF, StereoFormat.TB_FULL -> { set(leftRect, 0f, .5f, 1f, .5f); set(rightRect, 0f, 0f, 1f, .5f) }
            else -> { set(leftRect, 0f, 0f, 1f, 1f); set(rightRect, 0f, 0f, 1f, 1f) }
        }
        if (settings.swapEyes) {
            for (i in 0..3) { val t = leftRect[i]; leftRect[i] = rightRect[i]; rightRect[i] = t }
        }
    }

    /** Aspect ratio of one eye's picture. */
    private fun eyeAspect(): Float = when (settings.stereoFormat) {
        StereoFormat.SBS_FULL -> videoAspect / 2
        StereoFormat.TB_FULL -> videoAspect * 2
        else -> videoAspect
    }

    /** Eye position for the chosen seat: a real seat in a row, raised with its tier. */
    private fun seatPosition(dist: Float): FloatArray {
        val row = when (settings.seat) {
            0 -> -maxOf(0, kotlin.math.floor(minOf(dist * 0.4f, dist - 2.5f) / RoomBounds.ROW).toInt())
            2 -> minOf(5, kotlin.math.floor(dist * 0.6f / RoomBounds.ROW).toInt().coerceAtLeast(2))
            else -> 0
        }
        return floatArrayOf(0f, RoomBounds.RISE * row, RoomBounds.ROW * row)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        if (frameAvailable) {
            frameAvailable = false
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        }
        pendingPanel.getAndSet(null)?.let { panel!!.upload(it); it.recycle() }
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
        frameDt = dt
        lastFrameNs = now
        lightsNow += (lightsTarget - lightsNow) * minOf(1f, dt * 1.2f)
        voidNow += (voidTarget - voidNow) * minOf(1f, dt * 0.9f)
        // Curtains travel at a steady pace (about 3 s end to end).
        if (curtainNow != curtainTarget) {
            val step = dt / 3f
            curtainNow = if (curtainTarget > curtainNow) minOf(curtainTarget, curtainNow + step) else maxOf(curtainTarget, curtainNow - step)
            requestRender()
        }
        if (kotlin.math.abs(voidTarget - voidNow) > 0.002f) requestRender() else voidNow = voidTarget
        // Keep drawing while the lights fade or a pointing device moves the pointer.
        if (kotlin.math.abs(lightsTarget - lightsNow) > 0.002f || controller.connected) requestRender()
        if (lockView) System.arraycopy(lockedHead, 0, head, 0, 16)
        else { tracker.getViewMatrix(head); System.arraycopy(head, 0, lockedHead, 0, 16) }
        if (settings.smartView && !lockView && !fixedView) smartAdjust(frameDt) else { adjYaw = 0f; adjPitch = 0f }
        if (adjYaw != 0f || adjPitch != 0f) applyAdjust()
        if (settings.stereo && eyes != null && eyeScale != targetEyeScale()) makeEyes()
        if (fixedView) {
            Matrix.setIdentityM(head, 0)
            Matrix.rotateM(head, 0, -fixedPitch, 1f, 0f, 0f)
            Matrix.rotateM(head, 0, -fixedYaw, 0f, 1f, 0f)
        }
        computeEyeRects()

        val projection = settings.projection
        val geo = ScreenGeometry.of(settings.screenWidth, eyeAspect(), settings.screenDistance, settings.curve)
        val cam = seatPosition(geo.distance)
        curCam = cam
        curGeo = geo
        Matrix.setIdentityM(camT, 0)
        Matrix.translateM(camT, 0, -cam[0], -cam[1], -cam[2])
        Matrix.setIdentityM(tilt, 0)
        Matrix.translateM(tilt, 0, cam[0], cam[1], cam[2])
        Matrix.rotateM(tilt, 0, settings.tilt, 1f, 0f, 0f)
        Matrix.translateM(tilt, 0, -cam[0], -cam[1], -cam[2])

        if (hasVideo && projection == Projection.FLAT && (frameCount++ and (if (saver) 7 else 1)) == 0) {
            glowCap!!.update(videoTex, texMatrix, leftRect)
        }
        updatePointer()

        if (settings.stereo) {
            val v = ViewerProfiles.current(settings)
            val e = eyes ?: return
            for (i in 0..1) {
                val target = e[i]
                glBindFramebuffer(GL_FRAMEBUFFER, target.fbo)
                glViewport(0, 0, target.size, target.size)
                val near = 0.1f
                Matrix.frustumM(proj, 0, -near * v.tanMax, near * v.tanMax, -near * v.tanMax, near * v.tanMax, near, 100f)
                val shift = if (i == 0) v.interLens / 2 else -v.interLens / 2
                Matrix.setIdentityM(eyeShift, 0)
                Matrix.translateM(eyeShift, 0, shift, 0f, 0f)
                Matrix.multiplyMM(tmp, 0, eyeShift, 0, head, 0)
                Matrix.multiplyMM(view, 0, tmp, 0, camT, 0)
                drawScene(geo, i, projection)
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glDisable(GL_DEPTH_TEST)
            glViewport(0, 0, width, height)
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
            val ppm = pxPerMeter
            val lensY = (v.trayToLensCenter - BEZEL_M) * ppm
            val pxPerTan = ppm * v.screenToLens
            for (i in 0..1) {
                val half = width / 2
                glViewport(i * half, 0, half, height)
                val lensX = width / 2f + (if (i == 0) -1 else 1) * v.interLens / 2 * ppm
                lens!!.draw(e[i].tex, lensX, lensY, pxPerTan, v.k1, v.k2, v.k3, v.tanMax)
            }
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport(0, 0, width, height)
            Matrix.perspectiveM(proj, 0, 65f, width.toFloat() / height, 0.1f, 100f)
            Matrix.multiplyMM(view, 0, head, 0, camT, 0)
            drawScene(geo, 0, projection)
        }
    }

    private fun drawScene(geo: ScreenGeometry, eye: Int, projection: Int) {
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val rect = if (eye == 0) leftRect else rightRect
        if (projection == Projection.FLAT || !hasVideo) {
            glEnable(GL_DEPTH_TEST)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            val vis = 1f - voidNow
            if (vis > 0.004f) {
                val glow = (if (hasVideo) settings.ambient else 0f) * vis
                val light = lightsNow * vis
                room!!.draw(vp, geo, glow, light, glowCap!!.tex)
                val cam = curCam
                if (settings.seats) seats!!.draw(vp, cam, geo, glow, light, glowCap!!.tex)
                if (settings.curtains) curtains!!.draw(vp, cam, geo, glow, light, glowCap!!.tex, curtainNow)
            }
            val d = settings.depthOffset
            val shift = if (settings.stereoFormat == StereoFormat.MONO) 0f else if (eye == 0) d else -d
            screen!!.draw(vp, tilt, geo, texMatrix, videoTex, rect, shift, hasVideo)
        } else {
            glDisable(GL_DEPTH_TEST)
            // Rotation only: immersive video is at infinity.
            System.arraycopy(view, 0, rotView, 0, 16)
            rotView[12] = 0f; rotView[13] = 0f; rotView[14] = 0f
            Matrix.multiplyMM(vp, 0, proj, 0, rotView, 0)
            immersive!!.draw(vp, texMatrix, videoTex, rect, projection)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        }
        drawPanel()
    }

    // --- control panel and pointer ---

    private var curCam = floatArrayOf(0f, 0f, 0f)
    private var curGeo = ScreenGeometry(7f, 3.9f, 6f, 1f, 0f)

    private val panelCenter = floatArrayOf(0f, -0.45f, -1.3f)
    private val panelPitch = atan2(0.45f, 1.3f)
    private val panelWidth = 1.3f
    private val pointerDir = FloatArray(3)
    private val pointerOrigin = FloatArray(3)
    private val dotPos = FloatArray(3)
    private var dotOnPanel = false

    private fun panelHeight(): Float {
        val p = panel ?: return 0.5f
        return if (p.texWidth > 0) panelWidth * p.texHeight / p.texWidth else 0.5f
    }

    // Pointer dot fades out when the pointer rests (and the menu is closed), back in on movement.
    private val lastDir = FloatArray(3)
    private var lastMoveNs = 0L
    private var dotAlpha = 1f

    private fun updateDotAlpha(menuOpen: Boolean) {
        val dot = pointerDir[0] * lastDir[0] + pointerDir[1] * lastDir[1] + pointerDir[2] * lastDir[2]
        val now = System.nanoTime()
        if (menuOpen || dot < 0.99995f) {   // moved more than ~0.6 degrees
            lastMoveNs = now
            System.arraycopy(pointerDir, 0, lastDir, 0, 3)
        }
        val idle = (now - lastMoveNs) / 1e9f
        val target = if (idle < 1.5f) 1f else maxOf(0f, 1f - (idle - 1.5f) / 0.6f)
        dotAlpha = target
        if (target > 0f && target < 1f) requestRender()
        else if (target == 1f && !fadeWakePosted) {
            // Nothing else may draw while everything is still: wake up once to start the fade.
            fadeWakePosted = true
            fadeWake.postDelayed({ fadeWakePosted = false; requestRender() }, 1600)
        }
    }
    private val fadeWake = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var fadeWakePosted = false

    /**
     * Estimates where the hand holding the controller is from the controller's direction alone
     * (shoulder -> elbow -> wrist), so the laser starts from a hand that swings, rises and reaches
     * forward with the controller. Result in [pointerOrigin], relative to the eyes.
     */
    private fun armModel() {
        // Body faces where the head faces (yaw only).
        val hx = -head[2]; val hz = -head[10]
        val hl = sqrt(hx * hx + hz * hz).coerceAtLeast(1e-4f)
        val fx = hx / hl; val fz = hz / hl          // body forward
        val rx = -fz; val rz = fx                   // body right
        // Shoulder: right of and below the eyes.
        var px = rx * 0.19f; var py = -0.22f; var pz = rz * 0.19f
        // Elbow hangs below the shoulder, swinging forward/sideways a little with the pointing direction.
        val d0 = pointerDir[0]; val d1 = pointerDir[1]; val d2 = pointerDir[2]
        val lift = (d1 + 0.2f).coerceIn(-0.6f, 1f)  // raising the controller raises the elbow
        px += d0 * 0.12f; py += -0.26f + lift * 0.14f; pz += d2 * 0.12f
        // Forearm points along the controller; the beam starts at the controller tip.
        px += d0 * 0.34f; py += d1 * 0.34f; pz += d2 * 0.34f
        pointerOrigin[0] = px; pointerOrigin[1] = py; pointerOrigin[2] = pz
    }

    /** Computes the pointer ray (controller if connected, else gaze) and its hit on the panel. */
    private fun updatePointer() {
        val cam = seatPosition(ScreenGeometry.of(settings.screenWidth, eyeAspect(), settings.screenDistance, 0f).distance)
        if (controller.connected) {
            controller.pointerDirection(pointerDir)
        } else if (mouseActive) {
            val cp = cos(mousePitch)
            pointerDir[0] = sin(mouseYaw) * cp; pointerDir[1] = sin(mousePitch); pointerDir[2] = -cos(mouseYaw) * cp
            pointerOrigin.fill(0f)
        } else {
            // head = transpose(orientation): forward = -(row 2).
            pointerDir[0] = -head[2]; pointerDir[1] = -head[6]; pointerDir[2] = -head[10]
            pointerOrigin.fill(0f)
        }
        if (controller.connected || mouseActive) unadjust(pointerDir)
        if (controller.connected) armModel()
        pointTarget = classifyPointer(cam)
        updateDotAlpha(panelVisible && panel!!.hasTexture)
        if (!panelVisible || !panel!!.hasTexture) {
            hitX = Float.NaN; hitY = Float.NaN; dotOnPanel = false
            placeDotInRoom(cam)
            return
        }
        // Panel plane (relative to camera): normal n, axes right r and up u.
        val ny = sin(panelPitch); val nz = cos(panelPitch)
        val ux = 0f; val uy = cos(panelPitch); val uz = -sin(panelPitch)
        val dn = pointerDir[1] * ny + pointerDir[2] * nz
        val h = panelHeight()
        dotOnPanel = false
        if (dn < -1e-4f) {
            val cx = panelCenter[0] - pointerOrigin[0]; val cy = panelCenter[1] - pointerOrigin[1]; val cz = panelCenter[2] - pointerOrigin[2]
            val t = (cy * ny + cz * nz) / dn
            if (t > 0) {
                val lx = pointerOrigin[0] + pointerDir[0] * t - panelCenter[0]
                val ly = pointerOrigin[1] + pointerDir[1] * t - panelCenter[1]
                val lz = pointerOrigin[2] + pointerDir[2] * t - panelCenter[2]
                val u = lx / panelWidth + 0.5f
                val v = (lx * ux + ly * uy + lz * uz) / h + 0.5f
                if (u in 0f..1f && v in 0f..1f) {
                    hitX = u; hitY = 1f - v
                    dotPos[0] = panelCenter[0] + lx + ny * 0f
                    dotPos[1] = panelCenter[1] + ly + ny * 0.004f
                    dotPos[2] = panelCenter[2] + lz + nz * 0.004f
                    dotOnPanel = true
                }
            }
        }
        if (!dotOnPanel) {
            hitX = Float.NaN; hitY = Float.NaN
            placeDotInRoom(cam)
        } else {
            for (i in 0..2) dotPos[i] += cam[i]
        }
    }

    /** Screen if the ray hits it (with a margin), lights if it points above the screen or up at the ceiling. */
    private fun classifyPointer(cam: FloatArray): Target {
        val g = curGeo
        val oz = cam[2] + pointerOrigin[2]
        if (pointerDir[2] < -1e-3f) {
            val t = (-g.distance - oz) / pointerDir[2]
            val x = cam[0] + pointerOrigin[0] + pointerDir[0] * t
            val y = cam[1] + pointerOrigin[1] + pointerDir[1] * t
            if (kotlin.math.abs(x) < g.width / 2 + 0.3f && kotlin.math.abs(y - g.centerY) < g.height / 2 + 0.3f) return Target.SCREEN
            if (y > g.centerY + g.height / 2 + 0.3f) return Target.LIGHTS
        }
        return if (pointerDir[1] > 0.5f) Target.LIGHTS else Target.NONE
    }

    /** Puts the pointer dot on the screen when pointing at it, else 2 m along the ray. */
    private fun placeDotInRoom(cam: FloatArray) {
        var t = 2f
        if (pointTarget == Target.SCREEN && pointerDir[2] < -1e-3f) {
            t = (-curGeo.distance + 0.02f - (cam[2] + pointerOrigin[2])) / pointerDir[2]
        }
        for (i in 0..2) dotPos[i] = cam[i] + pointerOrigin[i] + pointerDir[i] * t
    }

    private fun drawPanel() {
        val p = panel ?: return
        val showPanel = panelVisible && p.hasTexture
        if (!showPanel && !controller.connected && !mouseActive) return
        val cam = seatPosition(ScreenGeometry.of(settings.screenWidth, eyeAspect(), settings.screenDistance, 0f).distance)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        if (showPanel) drawPanelQuad(p, cam)
        drawLaser(cam)
        drawDot(p, cam)
        glDisable(GL_BLEND)
    }

    private fun drawPanelQuad(p: PanelQuad, cam: FloatArray) {
        Matrix.setIdentityM(panelModel, 0)
        Matrix.translateM(panelModel, 0, cam[0] + panelCenter[0], cam[1] + panelCenter[1], cam[2] + panelCenter[2])
        Matrix.rotateM(panelModel, 0, -Math.toDegrees(panelPitch.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.scaleM(panelModel, 0, panelWidth, panelHeight(), 1f)
        Matrix.multiplyMM(tmp, 0, vp, 0, panelModel, 0)
        p.drawPanel(tmp)
    }

    private val laserFrom = FloatArray(3)

    private fun drawLaser(cam: FloatArray) {
        if (!controller.connected || dotAlpha <= 0f) return
        val l = laser ?: LaserBeam().also { laser = it }
        laserFrom[0] = cam[0] + pointerOrigin[0]; laserFrom[1] = cam[1] + pointerOrigin[1]; laserFrom[2] = cam[2] + pointerOrigin[2]
        when {
            dotOnPanel -> l.draw(vp, laserFrom, dotPos, cam, 0.45f, 0.75f, 1f, dotAlpha)
            pointTarget == Target.SCREEN -> l.draw(vp, laserFrom, dotPos, cam, 1f, 0.85f, 0.4f, dotAlpha)
            else -> l.draw(vp, laserFrom, dotPos, cam, 0.85f, 0.9f, 1f, dotAlpha)
        }
    }

    private fun drawDot(p: PanelQuad, cam: FloatArray) {
        if (dotAlpha <= 0f) return
        Matrix.setIdentityM(dotModel, 0)
        Matrix.translateM(dotModel, 0, dotPos[0], dotPos[1], dotPos[2])
        // Face the camera.
        val dx = cam[0] - dotPos[0]; val dy = cam[1] - dotPos[1]; val dz = cam[2] - dotPos[2]
        val yaw = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
        val pitch = Math.toDegrees(atan2(-dy.toDouble(), sqrt((dx * dx + dz * dz).toDouble()))).toFloat()
        Matrix.rotateM(dotModel, 0, yaw, 0f, 1f, 0f)
        Matrix.rotateM(dotModel, 0, pitch, 1f, 0f, 0f)
        val far = if (dotOnPanel) 1f else sqrt(dx * dx + dy * dy + dz * dz) / 2f
        val s = (if (dotOnPanel) 0.022f else 0.03f) * maxOf(1f, far)
        Matrix.scaleM(dotModel, 0, s, s, 1f)
        Matrix.multiplyMM(tmp, 0, vp, 0, dotModel, 0)
        when {
            dotOnPanel -> p.drawDot(tmp, 0.45f, 0.75f, 1f, dotAlpha)
            pointTarget == Target.SCREEN -> p.drawDot(tmp, 1f, 0.85f, 0.4f, dotAlpha)
            pointTarget == Target.LIGHTS -> p.drawDot(tmp, 1f, 0.95f, 0.7f, dotAlpha)
            else -> p.drawDot(tmp, 0.8f, 0.8f, 0.8f, dotAlpha)
        }
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }

    companion object {
        /** Gap between the bottom edge of the screen and the device edge resting on the tray. */
        const val BEZEL_M = 0.003f
    }
}
