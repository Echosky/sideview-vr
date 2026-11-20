// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Persistent user settings. Fields are read by the GL thread each frame. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inner class F(private val def: Float) : ReadWriteProperty<Any, Float> {
        @Volatile private var v: Float? = null
        override fun getValue(thisRef: Any, property: KProperty<*>): Float =
            v ?: prefs.getFloat(property.name, def).also { v = it }
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Float) {
            v = value; prefs.edit().putFloat(property.name, value).apply()
        }
    }

    private inner class I(private val def: Int) : ReadWriteProperty<Any, Int> {
        @Volatile private var v: Int? = null
        override fun getValue(thisRef: Any, property: KProperty<*>): Int =
            v ?: prefs.getInt(property.name, def).also { v = it }
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Int) {
            v = value; prefs.edit().putInt(property.name, value).apply()
        }
    }

    private inner class B(private val def: Boolean) : ReadWriteProperty<Any, Boolean> {
        @Volatile private var v: Boolean? = null
        override fun getValue(thisRef: Any, property: KProperty<*>): Boolean =
            v ?: prefs.getBoolean(property.name, def).also { v = it }
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) {
            v = value; prefs.edit().putBoolean(property.name, value).apply()
        }
    }

    // Viewer / headset
    var stereo by B(true)
    var viewerProfile by I(0)            // index into ViewerProfiles.all
    var customInterLensMm by F(63.9f)
    var customK1 by F(0.34f)
    var customK2 by F(0.55f)
    var customScreenToLensMm by F(39f)

    // Screen
    var screenWidth by F(11f)
    var screenDistance by F(6.5f)
    var curve by F(0f)                   // 0 = flat, 1 = wrapped around the viewer
    var tilt by F(0f)                    // degrees, positive = up
    var seat by I(1)                     // 0 front, 1 middle, 2 back

    // Room
    var roomLights by F(1f)
    var ambient by F(1f)                 // screen-colored light spill
    var dimLights by F(0.1f)             // light level while a video plays
    var autoDim by B(true)               // fade the lights down when playback starts
    var dimDelaySec by I(3)              // 0..15 s after playback starts
    var seats by B(true)                 // red theatre seats
    var curtains by B(true)              // curtains around the screen

    // Subtitles: SubtitlePicker.MODE_*
    var subMode by I(1)

    // Battery
    var batterySaver by B(false)         // lower render resolution, 50 Hz sensor, slower status updates
    var lockView by B(false)
    var smartView by B(true)             // smart view adjust: ease the screen to where the head rests             // freeze the view (no head tracking): the screen stays in front
    var zeroCopy by B(false)             // decode straight to the screen texture when no subtitles are shown
    var offlineWifiHint by B(true)       // offer to turn Wi-Fi off when playing a downloaded copy
    var prefLang: String
        get() = prefs.getString("prefLang", "en") ?: "en"
        set(v) { prefs.edit().putString("prefLang", v).apply() }

    // Start-up: true = go straight into the theatre, false = show the start screen
    var skipStart by B(false)

    // Picture (mpv, -100..100)
    var brightness by I(0)
    var contrast by I(0)
    var saturation by I(0)
    var gamma by I(0)
    var gpuNext by B(false)

    // Format
    var projection by I(0)               // Projection.*
    var stereoFormat by I(0)             // StereoFormat.*
    var swapEyes by B(false)
    var depthOffset by F(0f)             // texture-space shift per eye
    var autoDetectFormat by B(true)

    // Audio / playback
    var audioDelayMs by I(0)
    var speed by F(1f)

    // Pointer
    var gazeSelect by B(true)

    fun string(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
}

object Projection {
    const val FLAT = 0; const val EQUI_180 = 1; const val EQUI_360 = 2; const val FISHEYE_180 = 3; const val EAC_360 = 4
    val names = listOf("Screen", "180°", "360°", "Fisheye 180°", "EAC 360° (exp.)")
}

object StereoFormat {
    const val MONO = 0; const val SBS_HALF = 1; const val SBS_FULL = 2; const val TB_HALF = 3; const val TB_FULL = 4
    val names = listOf("2D", "3D SBS", "3D SBS full", "3D TB", "3D TB full")
}

object Seats {
    val names = listOf("Front", "Middle", "Back")
}

/** Physical viewer (headset) parameters, meters. */
data class ViewerParams(
    val name: String,
    val interLens: Float,
    val screenToLens: Float,
    val trayToLensCenter: Float,
    val k1: Float,
    val k2: Float,
    val tanMax: Float = 1.43f,
)

object ViewerProfiles {
    val all = listOf(
        ViewerParams("Cardboard v2", 0.0639f, 0.039f, 0.035f, 0.34f, 0.55f),
        // Approximate values; fine-tune with the Custom profile.
        ViewerParams("Daydream View", 0.0639f, 0.0393f, 0.035f, 0.385f, 0.593f, 1.6f),
        ViewerParams("Custom", 0.0639f, 0.039f, 0.035f, 0.34f, 0.55f),
    )

    fun current(s: Settings): ViewerParams {
        val p = all[s.viewerProfile.coerceIn(0, all.size - 1)]
        return if (p.name != "Custom") p else p.copy(
            interLens = s.customInterLensMm / 1000f,
            screenToLens = s.customScreenToLensMm / 1000f,
            k1 = s.customK1, k2 = s.customK2,
        )
    }
}

/** Guesses projection and 3D layout from common file name tags. Returns null if nothing found. */
object FormatDetect {
    fun detect(name: String): Pair<Int, Int>? {
        val n = name.lowercase()
        fun has(vararg tags: String) = tags.any { Regex("(^|[^a-z0-9])$it([^a-z0-9]|$)").containsMatchIn(n) }
        val stereo = when {
            has("sbs", "lr", "3dh", "hsbs", "half-sbs") -> StereoFormat.SBS_HALF
            has("fsbs", "full-sbs") -> StereoFormat.SBS_FULL
            has("tb", "ou", "3dv", "htb", "half-ou", "half-tb") -> StereoFormat.TB_HALF
            has("ftb", "full-ou", "full-tb") -> StereoFormat.TB_FULL
            else -> null
        }
        val proj = when {
            has("fisheye", "fisheye180", "fe") -> Projection.FISHEYE_180
            has("eac", "eac360") -> Projection.EAC_360
            has("360", "vr360", "360vr", "mono360") -> Projection.EQUI_360
            has("180", "vr180", "180vr", "180x180") -> Projection.EQUI_180
            else -> null
        }
        if (stereo == null && proj == null) return null
        val p = proj ?: Projection.FLAT
        // 180° content is almost always side-by-side, 360° stereo usually top-bottom.
        val s = stereo ?: StereoFormat.MONO
        return p to s
    }
}
