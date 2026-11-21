// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Google Daydream controller over Bluetooth LE, implemented from the publicly
 * documented packet layout (20-byte notifications on service 0xFE55).
 */
@SuppressLint("MissingPermission")
class DaydreamController(private val context: Context) {
    interface Listener {
        fun onButton(button: Button)
        fun onSwipe(dx: Float, dy: Float)
        /** Continuous vertical touchpad motion while touching (pad heights; negative = finger moving up). */
        fun onScroll(dy: Float) {}
        fun onStatus(text: String)
    }

    enum class Button { CLICK, APP, HOME, VOL_UP, VOL_DOWN }

    var listener: Listener? = null
    /** Address of the last controller that connected; reused to reconnect automatically. */
    var savedAddress: String? = null
    var onAddress: (String) -> Unit = {}
    @Volatile var connected = false; private set
    private var wanted = false     // keep (re)connecting until disconnect() is called
    private var retries = 0
    var status = "Not connected"; private set

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    // Orientation as a quaternion (x, y, z, w), written from the Bluetooth thread.
    private val lock = Any()
    private val q = floatArrayOf(0f, 0f, 0f, 1f)
    @Volatile private var recenterPending = true
    private var yawOffset = 0f

    private var lastButtons = 0
    private var touchStartX = Float.NaN
    private var touchStartY = Float.NaN
    private var touchX = 0f
    private var touchY = 0f
    private var scrollY = Float.NaN
    private var scrolled = false

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /**
     * Connects to the remembered or paired controller (waiting in the background until it wakes up),
     * or scans for one. With [quiet], nothing is scanned: used for automatic reconnects.
     */
    fun connect(quiet: Boolean = false) {
        val a = adapter
        if (a == null || !a.isEnabled) { setStatus("Bluetooth is off"); return }
        if (connected) return
        wanted = true
        try {
            val known = savedAddress?.let { runCatching { a.getRemoteDevice(it) }.getOrNull() }
                ?: a.bondedDevices.firstOrNull { it.name?.contains("Daydream", true) == true }
            if (known != null) { connectTo(known, background = true); if (quiet) return }
            if (quiet) return
            if (!locationOn()) { setStatus("Turn on Location: Android needs it to find Bluetooth devices"); return }
            startScan()
        } catch (e: SecurityException) {
            setStatus("Bluetooth permission missing")
        }
    }

    private fun locationOn(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) return true   // BLUETOOTH_SCAN with neverForLocation
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    fun disconnect() {
        wanted = false
        stopScan()
        try { gatt?.close() } catch (_: SecurityException) {}
        gatt = null
        connected = false
        setStatus("Not connected")
    }

    fun recenter() { recenterPending = true }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { setStatus("No BLE scanner"); return }
        setStatus("Searching… hold the controller's home button")
        scanning = true
        scanner.startScan(scanCallback)
        main.postDelayed({ if (scanning) { stopScan(); if (!connected) setStatus("Controller not found") } }, 15000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids
            val name = try { result.device.name } catch (_: SecurityException) { null }
            if (uuids?.any { it.uuid == SERVICE } == true || name?.contains("Daydream", true) == true) {
                stopScan()
                connectTo(result.device, background = false)
            }
        }
    }

    /** [background]: let Android connect whenever the controller is awake (autoConnect). */
    private fun connectTo(device: BluetoothDevice, background: Boolean) {
        setStatus(if (background) "Waiting for the controller… press its home button" else "Connecting…")
        try { gatt?.close() } catch (_: SecurityException) {}
        gatt = device.connectGatt(context, background, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun retryLater(device: BluetoothDevice) {
        if (!wanted) return
        val delay = minOf(30_000L, 2_000L shl minOf(retries++, 4))
        main.postDelayed({ if (wanted && !connected) connectTo(device, background = true) }, delay)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val was = connected
                connected = false
                setStatus(if (was) "Controller disconnected, reconnecting…" else "Controller not reachable, retrying…")
                try { g.close() } catch (_: SecurityException) {}
                if (gatt === g) gatt = null
                retryLater(g.device)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE)?.getCharacteristic(CHARACTERISTIC)
            if (ch == null) { setStatus("Not a Daydream controller"); return }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            connected = true
            retries = 0
            recenterPending = true
            savedAddress = g.device.address
            main.post { onAddress(g.device.address) }
            setStatus("Controller connected")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            parse(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) ch.value?.let { parse(it) }
        }
    }

    private fun setStatus(s: String) {
        status = s
        main.post { listener?.onStatus(s) }
    }

    private fun parse(b: ByteArray) {
        if (b.size < 20) return
        fun u(i: Int) = b[i].toInt() and 0xFF
        fun signed13(v: Int) = (v shl 19) shr 19
        val scale = (2 * Math.PI / 4095.0).toFloat()
        val ox = signed13((u(1) and 0x03) shl 11 or (u(2) shl 3) or (u(3) and 0xE0 shr 5)) * scale
        val oy = signed13((u(3) and 0x1F) shl 8 or u(4)) * scale
        val oz = signed13((u(5) shl 5) or (u(6) and 0xF8 shr 3)) * scale
        // Axis-angle vector -> quaternion.
        val angle = sqrt(ox * ox + oy * oy + oz * oz)
        synchronized(lock) {
            if (angle < 1e-6f) { q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f } else {
                val s = sin(angle / 2) / angle
                q[0] = ox * s; q[1] = oy * s; q[2] = oz * s; q[3] = cos(angle / 2)
            }
        }

        val tx = ((u(16) and 0x1F) shl 3 or (u(17) and 0xE0 shr 5)) / 255f
        val ty = ((u(17) and 0x1F) shl 3 or (u(18) and 0xE0 shr 5)) / 255f
        val touching = tx != 0f || ty != 0f
        if (touching) {
            if (touchStartX.isNaN()) { touchStartX = tx; touchStartY = ty; scrollY = ty; scrolled = false }
            touchX = tx; touchY = ty
            // Vertical drags scroll continuously (screen size, lights, volume) in small steps.
            val d = ty - scrollY
            if (abs(d) >= 0.04f && abs(ty - touchStartY) > abs(tx - touchStartX)) {
                scrollY = ty; scrolled = true
                main.post { listener?.onScroll(d) }
            }
        } else if (!touchStartX.isNaN()) {
            val dx = touchX - touchStartX; val dy = touchY - touchStartY
            touchStartX = Float.NaN
            if (!scrolled && dx * dx + dy * dy > 0.04f) main.post { listener?.onSwipe(dx, dy) }
        }

        val buttons = u(18) and 0x1F
        val pressed = buttons and lastButtons.inv()
        lastButtons = buttons
        if (pressed != 0) main.post {
            if (pressed and 0x01 != 0) listener?.onButton(Button.CLICK)
            if (pressed and 0x02 != 0) listener?.onButton(Button.HOME)
            if (pressed and 0x04 != 0) listener?.onButton(Button.APP)
            if (pressed and 0x08 != 0) listener?.onButton(Button.VOL_DOWN)
            if (pressed and 0x10 != 0) listener?.onButton(Button.VOL_UP)
        }
    }

    /** Pointing direction in world space (+Y up, -Z forward after recenter). */
    fun pointerDirection(out: FloatArray) {
        val x: Float; val y: Float; val z: Float; val w: Float
        synchronized(lock) { x = q[0]; y = q[1]; z = q[2]; w = q[3] }
        // Rotate the controller's forward axis (0, 0, -1) by q.
        val fx = -(2 * (x * z + w * y))
        val fy = -(2 * (y * z - w * x))
        val fz = -(1 - 2 * (x * x + y * y))
        if (recenterPending) { yawOffset = atan2(-fx, -fz); recenterPending = false }
        val c = cos(-yawOffset); val s = sin(-yawOffset)
        out[0] = c * fx + s * fz
        out[1] = fy
        out[2] = -s * fx + c * fz
    }

    companion object {
        private const val TAG = "SideviewDaydream"
        val SERVICE: UUID = UUID.fromString("0000fe55-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC: UUID = UUID.fromString("00000001-1000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        init { Log.d(TAG, "Daydream controller support loaded") }
    }
}
