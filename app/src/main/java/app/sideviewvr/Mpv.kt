// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.view.Surface

/** JNI binding to libmpv (see jni/mpv_jni.cpp). One player per process. */
object Mpv {
    init {
        System.loadLibrary("sideview")
    }

    @JvmStatic external fun create(): Boolean
    @JvmStatic external fun init(): Int
    @JvmStatic external fun destroy()
    @JvmStatic external fun setOption(name: String, value: String): Int
    @JvmStatic external fun command(args: Array<String>): Int
    @JvmStatic external fun setProperty(name: String, value: String): Int
    @JvmStatic external fun getPropertyString(name: String): String?
    @JvmStatic external fun getPropertyDouble(name: String): Double
    @JvmStatic external fun attachSurface(surface: Surface)
    @JvmStatic external fun detachSurface()
}
