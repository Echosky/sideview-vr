// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The control panel. On the phone it is shown flat; in VR it is drawn into a
 * bitmap and shown as a panel the user points at (gaze or controller).
 */
class ControlPanel(private val a: MainActivity, private val s: Settings) {
    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.shape(a, Ui.SURFACE, 28f)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    private val refreshers = ArrayList<() -> Unit>()
    private val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    /** Scrolls the tab content on the phone; in VR the panel is drawn at full height instead. */
    private class CapScroll(c: android.content.Context) : ScrollView(c) {
        var cap = Int.MAX_VALUE
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, if (cap < Int.MAX_VALUE) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST) else h)
        }
    }
    private val contentScroll = CapScroll(a).apply { isVerticalScrollBarEnabled = true }

    /** Flat (phone) mode: limit the content height so the menu scrolls; VR: no limit. */
    fun setFlat(flat: Boolean, screenHeight: Int) {
        contentScroll.cap = if (flat) (screenHeight * 0.58f).toInt() else Int.MAX_VALUE
        contentScroll.requestLayout()
    }
    private val tabButtons = ArrayList<Button>()
    private val statusText = text("")
    private var tab = 0
    private val io = Executors.newSingleThreadExecutor()

    val seek = SeekBar(a).apply { max = 1000; Ui.styleSlider(this) }
    private val timeText = text("0:00")
    private val titleText = text("No video").apply { Ui.title(this, 17f) }
    var userSeeking = false

    init {
        root.addView(titleText)
        val seekRow = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        seekRow.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        seekRow.addView(timeText)
        root.addView(seekRow)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) { userSeeking = false; a.seekFraction(sb.progress / 1000.0) }
        })
        val tabs = LinearLayout(a).apply { setPadding(0, dp(4), 0, dp(6)) }
        listOf("Play", "Screen", "Theatre", "Picture", "Format", "Audio", "Library", "Together", "Viewer", "Power").forEachIndexed { i, name ->
            tabButtons += button(name, tabs) { showTab(i) }.apply {
                textSize = 13f; minWidth = 0; minimumWidth = 0; setPadding(dp(11), 0, dp(11), 0)
                (layoutParams as LinearLayout.LayoutParams).setMargins(dp(2), dp(3), dp(2), dp(3))
            }
        }
        root.addView(tabs)
        contentScroll.addView(content)
        root.addView(contentScroll)
        root.addView(statusText)
        showTab(0)
    }

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun text(t: String) = TextView(a).apply {
        text = t; Ui.body(this); setPadding(dp(6), dp(4), dp(6), dp(4))
    }

    /** Small supporting text under a setting. */
    private fun hint(t: String) = TextView(a).apply {
        text = t; Ui.supporting(this); setPadding(dp(6), 0, dp(6), dp(8))
    }

    private fun button(label: String, parent: LinearLayout, weight: Float = 0f, action: () -> Unit) = Button(a).apply {
        text = label; textSize = 14f
        Ui.styleButton(this)
        minHeight = dp(40); minimumHeight = dp(40); minWidth = dp(48); minimumWidth = dp(48)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { action(); refresh(); a.panelChanged() }
        parent.addView(this, LinearLayout.LayoutParams(if (weight > 0) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) })
    }

    private fun row(): LinearLayout = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }.also { content.addView(it) }

    /** "Label: value [−] [+]" */
    private fun stepper(label: String, value: () -> String, minus: () -> Unit, plus: () -> Unit) {
        val r = row()
        val t = text("").apply { minWidth = dp(190) }
        r.addView(t)
        button("−", r) { minus() }
        button("+", r) { plus() }
        refreshers += { t.text = "$label: ${value()}" }
    }

    /** "Label: value" with a draggable bar from [min] to [max] in [steps] steps. */
    private fun slider(label: String, min: Float, max: Float, steps: Int, value: () -> Float, fmt: (Float) -> String, set: (Float) -> Unit) {
        val t = text("")
        content.addView(t)
        val bar = SeekBar(a).apply { this.max = steps; setPadding(dp(16), dp(4), dp(16), dp(10)); Ui.styleSlider(this) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + (max - min) * p / steps
                set(v); t.text = "$label: ${fmt(v)}"; a.panelChanged()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        content.addView(bar)
        refreshers += {
            val v = value()
            t.text = "$label: ${fmt(v)}"
            bar.progress = ((v - min) / (max - min) * steps).toInt().coerceIn(0, steps)
        }
    }

    private fun choice(label: String, value: () -> String, next: () -> Unit) {
        val r = row()
        val t = text("").apply { minWidth = dp(190) }
        r.addView(t)
        button("Change", r) { next() }
        refreshers += { t.text = "$label: ${value()}" }
    }

    private fun actions(vararg items: Pair<String, () -> Unit>) {
        val r = row()
        for ((l, f) in items) button(l, r, 1f) { f() }
    }

    fun setStatus(t: String) { statusText.text = t; a.panelChanged() }

    fun refresh() { refreshers.toList().forEach { it() } }

    fun updateTime(pos: Double, dur: Double, title: String?) {
        timeText.text = if (dur.isNaN()) fmt(pos) else "${fmt(pos)} / ${fmt(dur)}"
        if (!userSeeking && !dur.isNaN() && dur > 0) seek.progress = (pos / dur * 1000).toInt()
        if (title != null) titleText.text = title
    }

    private fun fmt(s: Double): String {
        if (s.isNaN()) return "--:--"
        val t = s.toLong()
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    fun showTab(i: Int) {
        tab = i
        tabButtons.forEachIndexed { j, b -> Ui.styleChip(b, j == i) }
        content.removeAllViews()
        refreshers.clear()
        when (i) {
            0 -> playTab()
            1 -> screenTab()
            2 -> theatreTab()
            3 -> pictureTab()
            4 -> formatTab()
            5 -> audioTab()
            LIBRARY -> libraryTab()
            7 -> togetherTab()
            VIEWER -> viewerTab()
            9 -> powerTab()
        }
        refresh()
        a.panelChanged()
    }

    private fun playTab() {
        actions("⏯ Play/Pause" to { a.player.togglePause() }, "−60s" to { a.player.seekRelative(-60.0) },
            "−10s" to { a.player.seekRelative(-10.0) }, "+10s" to { a.player.seekRelative(10.0) },
            "+60s" to { a.player.seekRelative(60.0) })
        actions("⏮ Chapter" to { a.player.chapter(-1) }, "Chapter ⏭" to { a.player.chapter(1) },
            "Recenter" to { a.recenter() }, "Hide panel" to { a.hidePanel() })
        stepper("Speed", { "%.2fx".format(s.speed) }, { setSpeed(s.speed - 0.1f) }, { setSpeed(s.speed + 0.1f) })
    }

    private fun setSpeed(v: Float) { s.speed = v.coerceIn(0.25f, 4f); a.player.setSpeed(s.speed.toDouble()) }

    private fun screenTab() {
        stepper("Size", { "%.1f m".format(s.screenWidth) }, { s.screenWidth = (s.screenWidth / 1.1f).coerceAtLeast(1f) },
            { s.screenWidth = (s.screenWidth * 1.1f).coerceAtMost(28f) })
        stepper("Distance", { "%.1f m".format(s.screenDistance) }, { s.screenDistance = (s.screenDistance / 1.1f).coerceAtLeast(1.5f) },
            { s.screenDistance = (s.screenDistance * 1.1f).coerceAtMost(25f) })
        stepper("Curve", { "${(s.curve * 100).toInt()}%" }, { s.curve = (s.curve - 0.1f).coerceAtLeast(0f) },
            { s.curve = (s.curve + 0.1f).coerceAtMost(1f) })
        stepper("Tilt", { "${s.tilt.toInt()}°" }, { s.tilt = (s.tilt - 5f).coerceAtLeast(-30f) }, { s.tilt = (s.tilt + 5f).coerceAtMost(75f) })
        choice("Seat", { Seats.names[s.seat] }) { s.seat = (s.seat + 1) % 3 }
        choice("Smart view adjust", { if (s.smartView) "On: the screen eases to where you rest your head" else "Off" }) { s.smartView = !s.smartView }
        actions("Recenter" to { a.recenter() }, "Reset screen" to {
            s.screenWidth = 11f; s.screenDistance = 6.5f; s.curve = 0f; s.tilt = 0f; s.seat = 1
        })
    }

    private fun pictureTab() {
        fun pic(label: String, prop: String, get: () -> Int, set: (Int) -> Unit) =
            stepper(label, { get().toString() }, { set((get() - 5).coerceAtLeast(-100)); a.player.setPicture(prop, get()); a.updateZeroCopy() },
                { set((get() + 5).coerceAtMost(100)); a.player.setPicture(prop, get()); a.updateZeroCopy() })
        pic("Brightness", "brightness", { s.brightness }, { s.brightness = it })
        pic("Contrast", "contrast", { s.contrast }, { s.contrast = it })
        pic("Saturation", "saturation", { s.saturation }, { s.saturation = it })
        pic("Gamma", "gamma", { s.gamma }, { s.gamma = it })
        choice("Renderer", { if (s.gpuNext) "gpu-next (HDR/Dolby Vision)" else "gpu (fast)" }) {
            s.gpuNext = !s.gpuNext; a.player.setVo(s.gpuNext)
        }
    }

    private fun theatreTab() {
        val pct = { v: Float -> "${(v * 100).toInt()}%" }
        slider("House lights", 0f, 1.5f, 30, { s.roomLights }, pct) { s.roomLights = it; a.lightsChanged() }
        slider("Lights during the movie", 0f, 1.5f, 30, { s.dimLights }, pct) { s.dimLights = it; a.lightsChanged() }
        choice("Dim lights when the movie starts", { if (s.autoDim) "On" else "Off" }) { s.autoDim = !s.autoDim; a.lightsChanged() }
        slider("Dim after", 0f, 15f, 15, { s.dimDelaySec.toFloat() }, { "${it.toInt()} s" }) { s.dimDelaySec = it.toInt() }
        slider("Screen glow on the room", 0f, 2.5f, 25, { s.ambient }, pct) { s.ambient = it }
        actions((if (s.seats) "Seats: on" else "Seats: off") to { s.seats = !s.seats; showTab(2) },
            (if (s.curtains) "Curtains: on" else "Curtains: off") to { s.curtains = !s.curtains; showTab(2) })
        content.addView(hint("Tip: point at the screen and drag up/down on the touchpad (or mouse wheel) to resize it; point above it at the lights to dim them."))
    }

    private fun formatTab() {
        choice("Projection", { Projection.names[s.projection] }) { s.projection = (s.projection + 1) % Projection.names.size }
        choice("3D", { StereoFormat.names[s.stereoFormat] }) { s.stereoFormat = (s.stereoFormat + 1) % StereoFormat.names.size }
        choice("Swap eyes", { if (s.swapEyes) "On" else "Off" }) { s.swapEyes = !s.swapEyes }
        stepper("3D depth offset", { "%+.1f%%".format(s.depthOffset * 100) }, { s.depthOffset = (s.depthOffset - 0.002f).coerceAtLeast(-0.05f) },
            { s.depthOffset = (s.depthOffset + 0.002f).coerceAtMost(0.05f) })
        choice("Detect from file name", { if (s.autoDetectFormat) "On" else "Off" }) { s.autoDetectFormat = !s.autoDetectFormat }
    }

    private fun audioTab() {
        stepper("Audio delay", { "${s.audioDelayMs} ms" }, { s.audioDelayMs -= 25; a.player.setAudioDelayMs(s.audioDelayMs) },
            { s.audioDelayMs += 25; a.player.setAudioDelayMs(s.audioDelayMs) })
        actions("Bluetooth preset (+200 ms)" to { s.audioDelayMs = 200; a.player.setAudioDelayMs(200) },
            "Reset delay" to { s.audioDelayMs = 0; a.player.setAudioDelayMs(0) })
        choice("Audio track", { a.player.prop("current-tracks/audio/lang") ?: a.player.prop("aid") ?: "-" }) { a.player.cycle("aid") }
        choice("Subtitle choice", { SubtitlePicker.modeNames[s.subMode] }) {
            s.subMode = (s.subMode + 1) % SubtitlePicker.modeNames.size; a.applySubtitles()
        }
        choice("Subtitles now", { a.subtitleLabel() }) { a.player.cycle("sid"); a.subtitleLabel = null }
        languageList()
        stepper("Speed", { "%.2fx".format(s.speed) }, { setSpeed(s.speed - 0.1f) }, { setSpeed(s.speed + 0.1f) })
    }

    /** Scrollable list of languages for auto-selecting audio and subtitles (drag on the phone, ▲/▼ in VR). */
    private fun languageList() {
        val label = text("")
        content.addView(label)
        refreshers += { label.text = "Preferred language: ${SubtitlePicker.langName(s.prefLang)}  (audio and subtitles are chosen for it)" }
        val r = row()
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (l in SubtitlePicker.LANGUAGES.filter { it.code.length == 2 }) {
            val b = Button(a).apply {
                text = l.name; isAllCaps = false; textSize = 13f; minHeight = dp(36); minimumHeight = dp(36)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { s.prefLang = l.code; a.applySubtitles(); refresh(); a.panelChanged() }
            }
            list.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            refreshers += { if (s.prefLang == l.code) Ui.styleChip(b, true) else Ui.styleButton(b, Ui.Kind.TEXT, 8f) }
        }
        scroll.addView(list)
        r.addView(scroll, LinearLayout.LayoutParams(0, dp(132), 1f))
        val arrows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        button("▲", arrows) { scroll.smoothScrollBy(0, -dp(108)); scroll.postDelayed({ a.panelChanged() }, 300) }
        button("▼", arrows) { scroll.smoothScrollBy(0, dp(108)); scroll.postDelayed({ a.panelChanged() }, 300) }
        r.addView(arrows)
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> a.panelChanged() }
        // Start with the chosen language in view.
        scroll.post {
            val i = SubtitlePicker.LANGUAGES.filter { it.code.length == 2 }.indexOfFirst { it.code == s.prefLang }
            if (i > 0) scroll.scrollTo(0, list.getChildAt(i).top - dp(36))
        }
    }

    // ------------------------------------------------------------- library browser

    /** A list in the browser; [download] (⬇) and [delete] (🗑) add a button to each row. */
    private class Level(val title: String, val load: () -> List<MediaEntry>, val pick: (MediaEntry) -> Unit,
                        val download: ((MediaEntry) -> Unit)? = null, val delete: ((MediaEntry) -> Unit)? = null)
    private val stack = ArrayList<Level>()
    private var items: List<MediaEntry> = emptyList()
    private var page = 0
    private var loading = false
    private val perPage = 6

    private fun libraryTab() {
        if (stack.isEmpty()) {
            if (!s.stereo) {
                val r = row()
                button("▶  Enter the theatre", r, 1f) { a.enterTheatre() }.apply { Ui.styleButton(this, Ui.Kind.FILLED); minHeight = dp(48) }
            }
            val top = row()
            button("Device files…", top, 1f) { a.openFile() }
            button("URL…", top, 1f) { a.openUrlDialog() }
            button("Add SMB…", top, 1f) { a.addSmbDialog() }
            button("Add DLNA URL…", top, 1f) { a.addDlnaDialog() }
            button("Scan DLNA", top, 1f) { scanDlna() }
            items = rootItems()
            if (Downloads.active || Downloads.status.isNotEmpty()) {
                val d = row()
                d.addView(text(Downloads.status.ifEmpty { "Downloads" }), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (Downloads.active) button("Cancel", d) { Downloads.cancelAll() }
            }
        } else {
            val top = row()
            button("◀ Back", top) { stack.removeAt(stack.size - 1); page = 0; if (stack.isNotEmpty()) load() else showTab(LIBRARY) }
            top.addView(text(stack.last().title), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val pages = maxOf(1, (items.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        if (loading) content.addView(text("Loading…"))
        else if (items.isEmpty()) content.addView(text("Nothing here"))
        for (e in items.drop(page * perPage).take(perPage)) {
            val r = row()
            button((if (e.isFolder) "📁 " else "▶ ") + e.title, r, 1f) { onPick(e) }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            val lv = stack.lastOrNull()
            lv?.download?.let { dl -> button("⬇", r) { dl(e); setStatus(if (e.isFolder) "Downloading folder ${e.title}…" else "Downloading ${e.title}…") } }
            lv?.delete?.let { del -> button("🗑", r) { del(e); load() } }
        }
        if (pages > 1) {
            val nav = row()
            button("◀ Prev", nav) { page--; showTab(LIBRARY) }
            nav.addView(text("Page ${page + 1}/$pages"))
            button("Next ▶", nav) { page++; showTab(LIBRARY) }
        }
    }

    private fun rootItems(): List<MediaEntry> {
        val list = ArrayList<MediaEntry>()
        list += MediaEntry("Recent", true, "recent:")
        list += MediaEntry("📥 Offline copies", true, "offline:")
        a.sources.smbServers().forEach { list += MediaEntry(it.label, true, "smb:" + it.label) }
        a.sources.dlnaServers().forEach { list += MediaEntry("DLNA: " + it.name, true, "dlna:" + it.descriptionUrl) }
        return list
    }

    private fun onPick(e: MediaEntry) {
        if (stack.isNotEmpty()) { stack.last().pick(e); return }
        when {
            e.ref == "recent:" -> push(Level("Recent", { a.sources.recents() }, { a.playRef(it.ref, it.title) }))
            e.ref == "offline:" -> pushLocal(Downloads.dir(a), "Offline copies")
            e.ref.startsWith("smb:") -> {
                val srv = a.sources.smbServers().firstOrNull { "smb:" + it.label == e.ref } ?: return
                pushSmb(srv, "")
            }
            e.ref.startsWith("dlna:") -> {
                val srv = a.sources.dlnaServers().firstOrNull { "dlna:" + it.descriptionUrl == e.ref } ?: return
                pushDlna(srv, "0", srv.name)
            }
        }
    }

    private fun pushSmb(srv: SmbServer, path: String) {
        push(Level(if (path.isEmpty()) srv.label else path.substringAfterLast('\\'), { Smb.list(srv, path) },
            { if (it.isFolder) pushSmb(srv, it.ref) else a.playSmb(srv, it.ref, it.title) },
            download = { a.requestNotifications(); Downloads.addSmb(a, srv, it.ref, it.isFolder) }))
    }

    private fun pushLocal(dir: java.io.File, title: String) {
        push(Level(title, { Downloads.list(dir) }, { if (it.isFolder) pushLocal(java.io.File(it.ref), it.title) else a.playLocal(java.io.File(it.ref)) },
            delete = { Downloads.delete(it.ref); setStatus("Deleted ${it.title}") }))
    }

    private fun pushDlna(srv: DlnaServer, id: String, title: String) {
        push(Level(title, { Dlna.browse(srv, id) },
            { if (it.isFolder) pushDlna(srv, it.ref, it.title) else a.playRef(it.ref, it.title) },
            download = { a.requestNotifications(); Downloads.addDlna(a, srv, it) }))
    }

    private fun push(l: Level) { stack += l; page = 0; load() }

    private fun load() {
        val l = stack.lastOrNull() ?: return
        loading = true; items = emptyList(); showTab(LIBRARY)
        io.execute {
            val r = runCatching { l.load() }
            a.runOnUiThread {
                loading = false
                items = r.getOrDefault(emptyList())
                r.exceptionOrNull()?.let { setStatus("Error: ${it.message}") }
                if (tab == LIBRARY) showTab(LIBRARY)
            }
        }
    }

    private fun scanDlna() {
        setStatus("Scanning for DLNA servers…")
        io.execute {
            val found = runCatching { Dlna.discover(a) }.getOrDefault(emptyList())
            a.runOnUiThread {
                found.forEach { a.sources.addDlna(it) }
                setStatus("Found ${found.size} DLNA server(s)")
                if (tab == LIBRARY && stack.isEmpty()) showTab(LIBRARY)
            }
        }
    }

    // ------------------------------------------------------------- together / viewer

    private fun togetherTab() {
        content.addView(hint("Everyone opens the same video; followers keep in sync with the host."))
        actions("Host" to { a.together.host() }, "Join…" to { a.joinDialog() }, "Stop" to { a.together.stop() })
        content.addView(text("This phone: " + WatchTogether.localAddresses().joinToString().ifEmpty { "no network" }))
        refreshers += { }
    }

    private fun viewerTab() {
        choice("Mode", { if (s.stereo) "VR headset" else "Flat (no headset)" }) { a.setStereo(!s.stereo) }
        choice("When the app opens", { if (s.skipStart) "Go straight into the theatre" else "Ask: theatre or menu" }) { s.skipStart = !s.skipStart }
        choice("Headset", { ViewerProfiles.all[s.viewerProfile].name }) { s.viewerProfile = (s.viewerProfile + 1) % ViewerProfiles.all.size }
        if (ViewerProfiles.all[s.viewerProfile].name == "Custom") {
            stepper("Lens separation", { "%.1f mm".format(s.customInterLensMm) }, { s.customInterLensMm -= 0.5f }, { s.customInterLensMm += 0.5f })
            stepper("Screen to lens", { "%.1f mm".format(s.customScreenToLensMm) }, { s.customScreenToLensMm -= 0.5f }, { s.customScreenToLensMm += 0.5f })
            stepper("Distortion k1", { "%.2f".format(s.customK1) }, { s.customK1 -= 0.02f }, { s.customK1 += 0.02f })
            stepper("Distortion k2", { "%.2f".format(s.customK2) }, { s.customK2 -= 0.02f }, { s.customK2 += 0.02f })
        }
        choice("Daydream controller", { a.controller.status }) { if (a.controller.connected) a.controller.disconnect() else a.connectController() }
        refreshers += { if (tab == VIEWER && content.childCount != expectedViewerRows()) showTab(VIEWER) }
    }

    private fun powerTab() {
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        choice("Battery saver", { onOff(s.batterySaver) }) { s.batterySaver = !s.batterySaver; a.applyBattery() }
        content.addView(hint("Saver: 20% fewer pixels per eye, 50 Hz head tracking, room light from the movie updated less often, slower menu updates."))
        choice("Lock the view (no head tracking)", { onOff(s.lockView) }) { s.lockView = !s.lockView; a.applyBattery() }
        content.addView(hint("Locks the screen in front of you and turns the motion sensor off; only new video frames are drawn. Recenter to re-aim."))
        choice("Zero-copy video (experimental)", { onOff(s.zeroCopy) }) { s.zeroCopy = !s.zeroCopy; a.applyBattery() }
        content.addView(hint("Decodes straight to the screen with no GPU copy, used only while no subtitles are on and the picture settings are default."))
        choice("Offer Wi-Fi off for downloaded videos", { onOff(s.offlineWifiHint) }) { s.offlineWifiHint = !s.offlineWifiHint }
        content.addView(hint("Always on: frames are drawn only when your head moves or a new video frame arrives; network video is buffered in big bursts so Wi-Fi can sleep."))
    }

    private fun expectedViewerRows() = if (ViewerProfiles.all[s.viewerProfile].name == "Custom") 8 else 4

    fun visible() = root.visibility == View.VISIBLE

    companion object {
        const val LIBRARY = 6
        const val VIEWER = 8
    }
}
