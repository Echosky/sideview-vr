// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Head orientation from the phone's fused rotation sensor.
 * Output is an OpenGL view matrix (world: +Y up, -Z forward at recenter).
 */
class HeadTracker(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val lock = Any()
    private val rotVec = FloatArray(5)
    private var hasData = false
    @Volatile private var recenterRequested = true
    private var yawOffset = 0f

    /** Display rotation (Surface.ROTATION_*), set by the activity. */
    @Volatile var displayRotation = Surface.ROTATION_90

    val available: Boolean get() = sensor != null

    private val r = FloatArray(9)
    private val rr = FloatArray(9)
    private val h = FloatArray(9)

    /** Called (on the sensor thread) when the head turned noticeably since the last call. */
    @Volatile var onMoved: (() -> Unit)? = null
    private val lastQ = FloatArray(4)

    /** Battery saver: ~50 Hz instead of the sensor's fastest rate. */
    var lowRate = false

    fun start() {
        sensor?.let { sensors.registerListener(this, it, if (lowRate) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun restart() { stop(); start() }

    fun stop() = sensors.unregisterListener(this)

    fun recenter() { recenterRequested = true }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(lock) {
            val n = minOf(event.values.size, 5)
            System.arraycopy(event.values, 0, rotVec, 0, n)
            for (i in n until 5) rotVec[i] = 0f
            hasData = true
        }
        // Ask for a new frame only when the head actually moved (> ~0.05 degrees).
        val v = event.values
        val w = if (v.size > 3) v[3] else kotlin.math.sqrt(maxOf(0f, 1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]))
        // Quaternion components change by ~sin(angle/2): 4e-4 is about 0.05 degrees.
        val sign = if (lastQ[0] * v[0] + lastQ[1] * v[1] + lastQ[2] * v[2] + lastQ[3] * w < 0) -1f else 1f
        val d = maxOf(maxOf(kotlin.math.abs(lastQ[0] - sign * v[0]), kotlin.math.abs(lastQ[1] - sign * v[1])),
            maxOf(kotlin.math.abs(lastQ[2] - sign * v[2]), kotlin.math.abs(lastQ[3] - sign * w)))
        if (d > 4e-4f) {
            lastQ[0] = v[0]; lastQ[1] = v[1]; lastQ[2] = v[2]; lastQ[3] = w
            onMoved?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Writes the head view matrix (column-major 4x4) into [out]. Returns false without data. */
    fun getViewMatrix(out: FloatArray): Boolean {
        synchronized(lock) {
            if (!hasData) {
                android.opengl.Matrix.setIdentityM(out, 0)
                return false
            }
            SensorManager.getRotationMatrixFromVector(r, rotVec)
        }
        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, rr)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, rr)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, rr)
            else -> System.arraycopy(r, 0, rr, 0, 9)
        }
        // rr (row-major) maps device/screen axes to sensor world (Z up).
        // Convert to GL world (Y up): x=x, y=z, z=-y.
        for (c in 0..2) {
            h[c] = rr[c]
            h[3 + c] = rr[6 + c]
            h[6 + c] = -rr[3 + c]
        }
        if (recenterRequested) {
            // Forward (-Z of the head) projected onto the floor plane.
            val fx = -h[2]; val fz = -h[8]
            yawOffset = atan2(-fx, -fz)
            recenterRequested = false
        }
        // Rotate world by -yawOffset around Y: H' = Ry(-yaw) * H.
        val c = cos(-yawOffset); val s = sin(-yawOffset)
        for (col in 0..2) {
            val x = h[col]; val z = h[6 + col]
            h[col] = c * x + s * z
            h[6 + col] = -s * x + c * z
        }
        // View = transpose(H'): column-major out[col*4+row] = H'[col][row].
        for (col in 0..2) for (row in 0..2) out[col * 4 + row] = h[col * 3 + row]
        out[3] = 0f; out[7] = 0f; out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
        return true
    }
}
