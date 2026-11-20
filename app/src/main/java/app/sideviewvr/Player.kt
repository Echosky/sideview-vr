// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.view.Surface

/** Playback control on top of [Mpv]. Call from the UI thread. */
class Player {
    private var initialized = false
    private var surfaceAttached = false
    var vo = "gpu"; private set

    companion object {
        /** libmpv's TLS (mbedTLS) has no CA store on Android: merge the system
         *  and user-added certificates into one PEM file. */
        fun buildCaBundle(dir: java.io.File): String? = try {
            val out = java.io.File(dir, "cacert.pem")
            val sb = StringBuilder()
            for (d in listOf("/system/etc/security/cacerts", "/data/misc/user/0/cacerts-added")) {
                java.io.File(d).listFiles()?.forEach { f ->
                    val t = runCatching { f.readText() }.getOrNull() ?: return@forEach
                    val s = t.indexOf("-----BEGIN CERTIFICATE-----")
                    val e = t.indexOf("-----END CERTIFICATE-----")
                    if (s >= 0 && e > s) sb.append(t, s, e + 25).append('\n')
                }
            }
            if (sb.isEmpty()) null else { out.writeText(sb.toString()); out.absolutePath }
        } catch (_: Exception) { null }
    }

    fun init(gpuNext: Boolean, caFile: String? = null): Boolean {
        if (initialized) return true
        if (!Mpv.create()) return false
        vo = if (gpuNext) "gpu-next" else "gpu"
        val opts = mutableListOf(
            "ytdl" to "no",
            "sub-auto" to "fuzzy",
            "config" to "no",
            "terminal" to "no",
            "idle" to "yes",
            "keep-open" to "yes",
            "image-display-duration" to "inf",   // photos stay on the screen
            "input-default-bindings" to "no",
            "vo" to "null",
            "gpu-context" to "android",
            "opengl-es" to "yes",
            "hwdec" to "mediacodec-copy",
            "ao" to "audiotrack,opensles",
            "cache" to "yes",
            // Read ahead in big bursts so the Wi-Fi radio can sleep between them.
            "demuxer-max-bytes" to "192MiB",
            "demuxer-max-back-bytes" to "32MiB",
            "demuxer-readahead-secs" to "120",
            "osd-level" to "0",
            // HDR sources are tone-mapped to SDR for the phone panel.
            "tone-mapping" to "auto",
            "hdr-compute-peak" to "auto",
            "sub-font-size" to "44",
        )
        if (caFile != null) { opts += "tls-ca-file" to caFile; opts += "tls-verify" to "yes" }
        for ((k, v) in opts) Mpv.setOption(k, v)
        initialized = Mpv.init() == 0
        return initialized
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (!initialized) return
        if (surfaceAttached) detachSurface()
        Mpv.attachSurface(surface)
        Mpv.setProperty("android-surface-size", "${width}x$height")
        Mpv.setOption("force-window", "yes")
        Mpv.setProperty("vo", vo)
        surfaceAttached = true
    }

    fun detachSurface() {
        if (!initialized || !surfaceAttached) return
        Mpv.setProperty("vo", "null")
        Mpv.setOption("force-window", "no")
        Mpv.detachSurface()
        surfaceAttached = false
    }

    /**
     * Zero-copy decoding: MediaCodec writes frames straight into our SurfaceTexture
     * (no GPU copy or scaling pass). mpv can't draw subtitles or picture adjustments then.
     */
    var zeroCopy = false; private set
    fun setZeroCopy(on: Boolean, gpuNext: Boolean) {
        if (on == zeroCopy) return
        zeroCopy = on
        Mpv.setProperty("hwdec", if (on) "mediacodec" else "mediacodec-copy")
        vo = if (on) "mediacodec_embed" else if (gpuNext) "gpu-next" else "gpu"
        if (surfaceAttached) Mpv.setProperty("vo", vo)
    }

    fun setVo(gpuNext: Boolean) {
        if (zeroCopy) return
        vo = if (gpuNext) "gpu-next" else "gpu"
        if (surfaceAttached) Mpv.setProperty("vo", vo)
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (surfaceAttached) Mpv.setProperty("android-surface-size", "${width}x$height")
    }

    /** Loads [url]; [start] in seconds (0 = beginning). Blu-ray: url "bd://" with [bluray] device path. */
    fun load(url: String, start: Double = 0.0, bluray: String? = null, subMode: Int = SubtitlePicker.MODE_FILE) {
        Mpv.setProperty("start", if (start > 1) start.toString() else "none")
        // We pick subtitles ourselves once the track list is known; keep mpv from flashing its own choice.
        Mpv.setProperty("sid", if (subMode == SubtitlePicker.MODE_FILE) "auto" else "no")
        if (bluray != null) Mpv.setProperty("bluray-device", bluray)
        Mpv.command(arrayOf("loadfile", url))
        Mpv.setProperty("pause", "no")
    }

    fun togglePause() { Mpv.command(arrayOf("cycle", "pause")) }
    fun setPaused(p: Boolean) { if (initialized) Mpv.setProperty("pause", if (p) "yes" else "no") }
    fun pause() = setPaused(true)
    fun seekRelative(seconds: Double) { Mpv.command(arrayOf("seek", seconds.toString(), "relative")) }
    fun seekAbsolute(seconds: Double) { Mpv.command(arrayOf("seek", seconds.toString(), "absolute")) }
    fun setSpeed(s: Double) { Mpv.setProperty("speed", s.toString()) }
    fun setAudioDelayMs(ms: Int) { Mpv.setProperty("audio-delay", (ms / 1000.0).toString()) }
    fun setPicture(name: String, value: Int) { Mpv.setProperty(name, value.toString()) }
    fun cycle(prop: String) { Mpv.command(arrayOf("cycle", prop)) }
    fun chapter(delta: Int) { Mpv.command(arrayOf("add", "chapter", delta.toString())) }

    /** Adds an external subtitle without selecting it. */
    fun addSubtitle(url: String, title: String) { Mpv.command(arrayOf("sub-add", url, "auto", title)) }

    /** The current file's tracks, read through mpv's track-list sub-properties. */
    fun tracks(): List<SubtitlePicker.Track> {
        val n = Mpv.getPropertyDouble("track-list/count").let { if (it.isNaN()) 0 else it.toInt() }
        return (0 until n).mapNotNull { i ->
            fun p(k: String) = Mpv.getPropertyString("track-list/$i/$k")
            fun b(k: String) = p(k) == "yes"
            val type = p("type") ?: return@mapNotNull null
            SubtitlePicker.Track(p("id")?.toIntOrNull() ?: return@mapNotNull null, type, p("lang"), p("title"), p("codec"),
                b("forced"), b("default"), b("hearing-impaired"), b("external"), p("external-filename"), b("selected"))
        }
    }

    /** Applies the subtitle choice; returns a short description. */
    fun applySubtitles(mode: Int, videoName: String?, lang: String): String {
        val t = tracks()
        val aid = SubtitlePicker.pickAudio(t, mode, videoName, lang)
        if (aid != null) Mpv.setProperty("aid", aid.toString())
        val id = SubtitlePicker.pick(t, mode, videoName, aid, lang) ?: return "file default"
        Mpv.setProperty("sid", if (id == 0) "no" else id.toString())
        val s = t.firstOrNull { it.type == "sub" && it.id == id } ?: return "off"
        return listOfNotNull(s.lang, s.title, if (SubtitlePicker.isForced(s)) "forced" else null).joinToString(" · ").ifEmpty { "track $id" }
    }

    val position: Double get() = Mpv.getPropertyDouble("time-pos")
    val duration: Double get() = Mpv.getPropertyDouble("duration")
    val paused: Boolean get() = Mpv.getPropertyString("pause") == "yes"
    val speed: Double get() = Mpv.getPropertyDouble("speed").let { if (it.isNaN()) 1.0 else it }
    val videoWidth: Double get() = Mpv.getPropertyDouble("video-params/dw")
    val videoHeight: Double get() = Mpv.getPropertyDouble("video-params/dh")
    val mediaTitle: String? get() = Mpv.getPropertyString("media-title")
    fun prop(name: String): String? = Mpv.getPropertyString(name)

    fun destroy() {
        if (!initialized) return
        detachSurface()
        Mpv.destroy()
        initialized = false
    }
}
