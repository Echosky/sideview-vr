// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.InputDevice
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity(), DaydreamController.Listener {
    lateinit var settings: Settings; private set
    val player = Player()
    lateinit var controller: DaydreamController; private set
    lateinit var sources: SavedSources; private set
    lateinit var together: WatchTogether; private set

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var tracker: HeadTracker
    private lateinit var panel: ControlPanel
    private val ui = Handler(Looper.getMainLooper())

    private var playerReady = false
    private var surfaceAttached = false
    private var pending: (() -> Unit)? = null
    private var surfaceSize = Pair(1920, 1080)
    private var panelShown = true
    private var currentRef: String? = null
    private var lastResumeSave = 0L
    private var isoFd: ParcelFileDescriptor? = null
    private var currentTitle: String? = null
    private var startScreen: View? = null

    // Lights: fade down a few seconds after playback starts, back up on pause.
    private var playingSince = 0L
    private var dimmedNow = false

    // Subtitles are chosen once the file's tracks are known (and SMB sidecar files are added).
    private var subsPendingSince = 0L
    @Volatile private var sidecarsLoading = false
    var subtitleLabel: String? = null
    private var volumeAccum = 0f
    private var lastDlStatus = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        settings = Settings(this)
        sources = SavedSources(settings)
        tracker = HeadTracker(this)
        controller = DaydreamController(this).also {
            it.listener = this
            it.savedAddress = settings.string("controllerAddr")
            it.onAddress = { a -> settings.putString("controllerAddr", a) }
        }
        registerReceiver(btState, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        together = WatchTogether(player) { panel.setStatus("Together: $it") }
        renderer = VrRenderer(settings, tracker, controller) { surface -> ui.post { onVideoSurface(surface) } }

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            // Render on demand: a frame is drawn when the head moves, a video frame arrives,
            // or something on screen changes. Watching with a still head costs ~24 frames/s, not 60.
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        renderer.requestRender = { glView.requestRender() }
        tracker.onMoved = { glView.requestRender() }
        panel = ControlPanel(this, settings)
        val root = object : FrameLayout(this) {
            // In VR every touch is a "select" (the headset button taps the screen).
            override fun onInterceptTouchEvent(ev: MotionEvent) = settings.stereo && startScreen == null
            override fun onTouchEvent(ev: MotionEvent): Boolean = gestures.onTouchEvent(ev)
        }
        root.addView(glView)
        val dm = resources.displayMetrics
        val panelWidth = minOf(maxOf(dm.widthPixels, dm.heightPixels), panel.dp(760))
        root.addView(panel.root, FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        glView.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
        setupMouse()

        if (!tracker.available) toast(getString(R.string.no_sensor))
        playerReady = player.init(settings.gpuNext, Player.buildCaBundle(filesDir))
        if (!playerReady) toast("libmpv failed to start")
        applyPlayerSettings()
        applyBattery()
        panel.showTab(ControlPanel.LIBRARY)
        applyPanel()
        val viewIntent = (intent?.action == Intent.ACTION_VIEW && intent?.data != null) || intent?.hasExtra("ref") == true || intent?.hasExtra("dl") == true
        if (savedInstanceState == null && !viewIntent) {
            if (settings.skipStart) enterTheatre() else showStartScreen(root)
        }
        handleIntent(intent)
    }

    // ------------------------------------------------------------- start screen

    /** First screen: go into the theatre, or set things up in the flat phone menu. */
    private fun showStartScreen(root: FrameLayout) {
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BACKGROUND)
            isClickable = true
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.app_name); Ui.title(this, 36f); gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = "Watch in the theatre with your headset, or set things up here on the phone first."
            Ui.supporting(this); textSize = 15f; gravity = Gravity.CENTER; setPadding(px(32), px(8), px(32), px(28))
        })
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun big(label: String, sub: String, kind: Ui.Kind, action: () -> Unit) {
            val b = Button(this).apply {
                text = android.text.SpannableStringBuilder(label).apply {
                    append("\n")
                    val st = length
                    append(sub)
                    setSpan(android.text.style.RelativeSizeSpan(0.72f), st, length, 0)
                }
                textSize = 18f; minHeight = px(88); setPadding(px(28), px(12), px(28), px(12))
                Ui.styleButton(this, kind, 28f)
                setOnClickListener { action() }
            }
            buttons.addView(b, LinearLayout.LayoutParams(px(300), LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(px(10), 0, px(10), 0) })
        }
        big("Enter the theatre", "Put the phone in the headset, or press a controller button", Ui.Kind.FILLED) { closeStartScreen(); enterTheatre() }
        big("Menu", "Add servers, pick videos, downloads", Ui.Kind.TONAL) { closeStartScreen(); openMenu() }
        box.addView(buttons)
        box.addView(CheckBox(this).apply {
            text = "Always go straight into the theatre"; Ui.styleCheck(this); isChecked = settings.skipStart
            setPadding(px(4), px(20), px(4), px(4))
            setOnCheckedChangeListener { _, c -> settings.skipStart = c }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        startScreen = box
    }

    private fun closeStartScreen() {
        startScreen?.let { (it.parent as? ViewGroup)?.removeView(it) }
        startScreen = null
    }

    fun enterTheatre() {
        closeStartScreen()
        setStereo(true)
        panel.showTab(if (renderer.hasVideo) 0 else ControlPanel.LIBRARY)
        showPanel()
    }

    private fun openMenu() {
        setStereo(false)
        panel.showTab(ControlPanel.LIBRARY)
        showPanel()
        ui.removeCallbacks(autoHide)
    }

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { select(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { recenter(); return true }
            override fun onLongPress(e: MotionEvent) { player.togglePause() }
        })
    }

    private fun applyPlayerSettings() {
        if (!playerReady) return
        player.setSpeed(settings.speed.toDouble())
        player.setAudioDelayMs(settings.audioDelayMs)
        player.setPicture("brightness", settings.brightness)
        player.setPicture("contrast", settings.contrast)
        player.setPicture("saturation", settings.saturation)
        player.setPicture("gamma", settings.gamma)
    }

    // ------------------------------------------------------------- panel

    /** Tap / headset button / controller click. */
    private fun select() {
        if (!settings.stereo) { if (panelShown) hidePanel() else showPanel(); return }
        if (panelShown && !renderer.hitX.isNaN()) clickPanel(renderer.hitX, renderer.hitY)
        // With a pointing device, clicking the screen plays/pauses; head gaze keeps tap = menu.
        else if ((controller.connected || renderer.mouseActive) && renderer.pointTarget == VrRenderer.Target.SCREEN && renderer.hasVideo) player.togglePause()
        else if (panelShown) hidePanel() else showPanel()
    }

    /** In VR the panel is drawn at its full height (taller than the phone screen is fine there). */
    private fun fullHeightLayout(v: View) {
        val w = v.width
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(v.left, v.top, v.left + w, v.top + v.measuredHeight)
    }

    private fun clickPanel(fx: Float, fy: Float) {
        val v = panel.root
        fullHeightLayout(v)
        val x = fx * v.width; val y = fy * v.height
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        v.dispatchTouchEvent(down); down.recycle()
        ui.postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            v.dispatchTouchEvent(up); up.recycle()
            panelChanged()
        }, 60)
        scheduleHide()
    }

    fun showPanel() { panelShown = true; panel.refresh(); applyPanel(); scheduleHide() }
    fun hidePanel() { panelShown = false; applyPanel() }

    private fun applyPanel() {
        panel.setFlat(!settings.stereo, resources.displayMetrics.heightPixels)
        val v = panel.root
        if (settings.stereo) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            renderer.panelVisible = panelShown
            if (panelShown) panelChanged()
        } else {
            v.alpha = 1f
            v.visibility = if (panelShown) View.VISIBLE else View.GONE
            renderer.panelVisible = false
        }
    }

    private val renderPanel = Runnable {
        val v = panel.root
        if (!settings.stereo || !panelShown || v.width == 0 || v.height == 0) return@Runnable
        fullHeightLayout(v)
        val scale = minOf(1f, 1024f / v.width)
        val bmp = Bitmap.createBitmap((v.width * scale).toInt(), (v.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        v.draw(c)
        renderer.pendingPanel.getAndSet(bmp)?.recycle()
        glView.requestRender()
    }

    /** The panel's content changed: redraw its VR bitmap after layout. */
    fun panelChanged() {
        if (!settings.stereo) return
        ui.removeCallbacks(renderPanel)
        ui.postDelayed(renderPanel, 40)
    }

    private val autoHide = Runnable { if (renderer.hasVideo && !player.paused) hidePanel() }

    private fun scheduleHide() {
        ui.removeCallbacks(autoHide)
        ui.postDelayed(autoHide, if (settings.stereo) 15000 else 6000)
    }

    fun setStereo(on: Boolean) {
        settings.stereo = on; applyPanel()
        if (!on && Build.VERSION.SDK_INT >= 26) glView.releasePointerCapture()
        if (!on) renderer.mouseActive = false
    }

    fun recenter() {
        tracker.recenter(); controller.recenter(); renderer.recenterLock()
        renderer.mouseYaw = 0f; renderer.mousePitch = 0f
        toast(getString(R.string.recentered))
    }

    // ------------------------------------------------------------- mouse

    /** A mouse in VR: moving it points (pointer capture), click = select, right click = menu, wheel = scroll. */
    private fun setupMouse() {
        if (Build.VERSION.SDK_INT < 26) return
        glView.isFocusable = true
        glView.isFocusableInTouchMode = true
        glView.setOnCapturedPointerListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    renderer.mouseActive = true
                    renderer.mouseYaw += ev.x * 0.003f
                    renderer.mousePitch = (renderer.mousePitch - ev.y * 0.003f).coerceIn(-1.45f, 1.45f)
                    glView.requestRender()
                }
                MotionEvent.ACTION_DOWN -> {
                    renderer.mouseActive = true
                    if (ev.buttonState and MotionEvent.BUTTON_SECONDARY != 0) { if (panelShown) hidePanel() else showPanel() }
                    else select()
                }
                MotionEvent.ACTION_SCROLL -> onScroll(-ev.getAxisValue(MotionEvent.AXIS_VSCROLL) * 0.06f)
            }
            true
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (ev.actionMasked == MotionEvent.ACTION_SCROLL) { onScroll(-ev.getAxisValue(MotionEvent.AXIS_VSCROLL) * 0.06f); return true }
            if (settings.stereo && startScreen == null && Build.VERSION.SDK_INT >= 26 && !glView.hasPointerCapture()) {
                glView.requestFocus(); glView.requestPointerCapture()
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ------------------------------------------------------------- battery

    /** Applies the power settings: sensor rate, view lock, render resolution, zero-copy decoding. */
    fun applyBattery() {
        renderer.saver = settings.batterySaver
        renderer.lockView = settings.lockView
        tracker.lowRate = settings.batterySaver
        if (settings.lockView) tracker.stop() else tracker.restart()
        updateZeroCopy()
        glView.requestRender()
    }

    /** Zero-copy decoding only while nothing needs mpv's renderer (no subtitles, default picture). */
    fun updateZeroCopy() {
        if (!playerReady) return
        val picDefault = settings.brightness == 0 && settings.contrast == 0 && settings.saturation == 0 && settings.gamma == 0
        val subsOff = player.prop("sid").let { it == null || it == "no" }
        player.setZeroCopy(settings.zeroCopy && picDefault && subsOff, settings.gpuNext)
    }

    // ------------------------------------------------------------- lights

    fun lightsChanged() { updateLights(SystemClock.uptimeMillis()) }

    private fun updateLights(now: Long) {
        val playing = renderer.hasVideo && playerReady && !player.paused
        if (playing) { if (playingSince == 0L) playingSince = now } else playingSince = 0L
        dimmedNow = settings.autoDim && playing && now - playingSince >= settings.dimDelaySec * 1000L
        renderer.lightsTarget = if (dimmedNow) settings.dimLights else settings.roomLights
        renderer.voidTarget = if (settings.voidRoom || (settings.voidWhenDimmed && dimmedNow)) 1f else 0f
    }

    // ------------------------------------------------------------- subtitles

    fun applySubtitles() {
        if (!renderer.hasVideo) return
        subtitleLabel = player.applySubtitles(settings.subMode, currentTitle, settings.prefLang)
        updateZeroCopy()
        panel.refresh(); panelChanged()
    }

    fun subtitleLabel(): String = subtitleLabel ?: run {
        val sid = player.prop("sid")
        if (sid == null || sid == "no") "off" else listOfNotNull(player.prop("current-tracks/sub/lang"), player.prop("current-tracks/sub/title")).joinToString(" · ").ifEmpty { "track $sid" }
    }

    fun seekFraction(f: Double) {
        val d = player.duration
        if (!d.isNaN()) player.seekAbsolute(d * f)
    }

    // ------------------------------------------------------------- video surface / playback

    private fun onVideoSurface(surface: Surface) {
        if (!playerReady) return
        player.attachSurface(surface, surfaceSize.first, surfaceSize.second)
        surfaceAttached = true
        pending?.let { pending = null; it() }
    }

    /** Starts playback of [url]. [ref] identifies it for recents and resume. */
    private fun play(url: String, ref: String, title: String, bluray: String? = null) {
        if (!playerReady) return
        if (!surfaceAttached) { pending = { play(url, ref, title, bluray) }; return }
        saveResume()
        if (settings.autoDetectFormat) {
            val f = FormatDetect.detect(title)
            settings.projection = f?.first ?: Projection.FLAT
            settings.stereoFormat = f?.second ?: StereoFormat.MONO
        }
        closeStartScreen()   // a video opened from another app skips the start screen
        currentRef = ref
        currentTitle = title
        subtitleLabel = null
        subsPendingSince = SystemClock.uptimeMillis()
        player.load(url, sources.resumePosition(ref), bluray, settings.subMode)
        if (!ref.startsWith("fd:")) sources.addRecent(MediaEntry(title, false, ref))
        renderer.hasVideo = true
        panel.showTab(0)
        panel.updateTime(0.0, Double.NaN, title)
        scheduleHide()
    }

    private fun saveResume() {
        val ref = currentRef ?: return
        val pos = player.position; val dur = player.duration
        if (pos.isNaN()) return
        // Forget the position near the start and the end.
        sources.saveResume(ref, if (pos < 30 || (!dur.isNaN() && dur - pos < 60)) 0.0 else pos)
    }

    private fun handleIntent(intent: Intent?) {
        // Debug builds only: "am start --es ref 'smb://host/share|path'" plays a saved-server path (for testing).
        if (intent?.hasExtra("fixedView") == true && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            renderer.fixedView = intent.getBooleanExtra("fixedView", false)
        if (intent?.hasExtra("yaw") == true && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            renderer.fixedYaw = intent.getFloatExtra("yaw", 0f); renderer.fixedPitch = intent.getFloatExtra("pitch", 0f)
        }
        if (intent != null && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds only, for scripted screenshots: --ei tab N, --ez panel true|false, --ez play true|false
            if (intent.hasExtra("tab")) panel.showTab(intent.getIntExtra("tab", 0))
            if (intent.hasExtra("panel")) { if (intent.getBooleanExtra("panel", false)) showPanel() else hidePanel() }
            if (intent.hasExtra("play")) ui.postDelayed({ player.setPaused(!intent.getBooleanExtra("play", true)) }, 300)
        }
        // Debug builds only: "--es dl 'smb://host/share|path' --ez folder true" queues a download.
        val dl = intent?.getStringExtra("dl")
        if (dl != null && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val srv = sources.smbServers().firstOrNull { it.label == dl.substringBefore('|') }
            if (srv != null) Downloads.addSmb(this, srv, dl.substringAfter('|'), intent.getBooleanExtra("folder", false))
            return
        }
        val debugRef = intent?.getStringExtra("ref")
        if (debugRef != null && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            playRef(debugRef, debugRef.substringAfterLast('\\').substringAfterLast('/'))
            val start = intent.getFloatExtra("start", 0f)
            if (start > 0) ui.postDelayed({ player.seekAbsolute(start.toDouble()) }, 2500)
            return
        }
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        openUri(uri, persist = false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

    private var pendingFileUri: Uri? = null

    fun openUri(uri: Uri, persist: Boolean) {
        when (uri.scheme) {
            "content" -> {
                if (persist) runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val name = displayName(uri)
                try {
                    if (name.endsWith(".iso", true)) {
                        // libbluray opens a path: keep the descriptor open and use /proc/self/fd.
                        isoFd?.close()
                        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return
                        isoFd = pfd
                        play("bd://", uri.toString(), name, "/proc/self/fd/${pfd.fd}")
                    } else {
                        val fd = contentResolver.openFileDescriptor(uri, "r")?.detachFd() ?: return
                        play("fdclose://$fd", if (persist) uri.toString() else "fd:$uri", name)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "open $uri", e); toast(e.message ?: "Cannot open")
                }
            }
            "file" -> {
                val path = uri.path ?: return
                val perm = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_VIDEO"
                           else "android.permission.READ_EXTERNAL_STORAGE"
                if (!java.io.File(path).canRead() && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                    pendingFileUri = uri
                    requestPermissions(arrayOf(perm), REQ_FILE)
                    return
                }
                if (path.endsWith(".iso", true)) play("bd://", path, path.substringAfterLast('/'), path)
                else play(path, path, path.substringAfterLast('/'))
            }
            else -> {
                val s = uri.toString()
                if (s.substringBefore('?').endsWith(".iso", true)) toast("Blu-ray ISO needs a local file")
                play(s, s, Uri.decode(uri.lastPathSegment ?: s))
            }
        }
    }

    /** Plays a recents/DLNA reference. */
    fun playRef(ref: String, title: String) {
        if (ref.startsWith("smb://") && '|' in ref) {
            val label = ref.substringBefore('|')
            val srv = sources.smbServers().firstOrNull { it.label == label } ?: run { toast("SMB server not saved"); return }
            playSmb(srv, ref.substringAfter('|'), title)
        } else {
            openUri(Uri.parse(ref), persist = false)
        }
    }

    /** Plays a downloaded file (subtitle files next to it are found by mpv). */
    fun playLocal(f: java.io.File) {
        play(f.absolutePath, f.absolutePath, f.name)
        offlineHint()
    }

    /** Playing an offline copy: Wi-Fi isn't needed, so offer to turn it off (apps can't do it themselves on Android 10+). */
    private fun offlineHint() {
        if (!settings.offlineWifiHint) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifi.isWifiEnabled || together.mode != "Off" || Downloads.active) return
        panel.setStatus("Offline copy: Wi-Fi can be turned off to save battery")
        if (!settings.stereo && Build.VERSION.SDK_INT >= 29) {
            AlertDialog.Builder(this).setTitle("Playing an offline copy")
                .setMessage("Wi-Fi isn't needed for this video. Turn it off to save battery?")
                .setPositiveButton("Wi-Fi settings") { _, _ -> startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI)) }
                .setNegativeButton("Keep on", null).show()
        }
    }

    private var notifAsked = false
    /** Download progress shows in a notification (Android 13+ asks once). */
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            notifAsked = true; requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
        }
    }

    fun playSmb(srv: SmbServer, path: String, title: String) {
        // Prefer the offline copy: no network, no radio, and it works on the plane.
        Downloads.existing(this, srv, path)?.let { playLocal(it); currentRef = srv.label + "|" + path; return }
        if (title.endsWith(".iso", true)) { toast("Blu-ray ISO needs a local file"); return }
        val ref = srv.label + "|" + path
        play(Smb.streamUrl(srv, path), ref, title)
        if (settings.subMode == SubtitlePicker.MODE_OFF) return
        // Subtitle files next to the video: mpv can't see them through the stream URL, so add them.
        sidecarsLoading = true
        thread {
            try {
                val subs = runCatching { Smb.sidecars(srv, path) }.getOrDefault(emptyList())
                if (subs.isNotEmpty()) {
                    val until = SystemClock.uptimeMillis() + 20000
                    while (currentRef == ref && (player.prop("track-list/count")?.toIntOrNull() ?: 0) == 0 && SystemClock.uptimeMillis() < until) Thread.sleep(100)
                    if (currentRef == ref) subs.forEach { (url, name) -> player.addSubtitle(url, name) }
                }
            } finally { sidecarsLoading = false }
        }
    }

    // ------------------------------------------------------------- dialogs (set up outside the headset)

    fun openFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "application/x-iso9660-image", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_OPEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { openUri(it, persist = true) }
    }

    private fun field(hint: String, value: String = "", password: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value)
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun form(title: String, vararg fields: EditText, ok: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        fields.forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> ok() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    fun openUrlDialog() {
        val url = field("https://…", settings.string("lastUrl") ?: "")
        form(getString(R.string.url), url) {
            val u = url.text.toString().trim()
            if (u.isNotEmpty()) { settings.putString("lastUrl", u); openUri(Uri.parse(u), persist = false) }
        }
    }

    fun addSmbDialog() {
        val host = field("Host or IP (e.g. nas.local or 192.0.2.10)"); val share = field("Share (e.g. media)")
        val user = field("User (empty = guest)"); val pass = field("Password", password = true)
        form("Add SMB share", host, share, user, pass) {
            val h = host.text.toString().trim(); val s = share.text.toString().trim().trim('/', '\\')
            if (h.isEmpty() || s.isEmpty()) return@form
            sources.addSmb(SmbServer(h, s, user.text.toString().trim(), pass.text.toString()))
            panel.showTab(ControlPanel.LIBRARY)
        }
    }

    fun addDlnaDialog() {
        val url = field("Device description URL, e.g. http://nas.local:8200/rootDesc.xml")
        form("Add DLNA server", url) {
            val u = url.text.toString().trim()
            if (u.isEmpty()) return@form
            thread {
                val r = runCatching { Dlna.resolve(u) }
                runOnUiThread {
                    r.onSuccess { sources.addDlna(it); panel.showTab(ControlPanel.LIBRARY); toast("Added ${it.name}") }
                        .onFailure { toast("DLNA: ${it.message}") }
                }
            }
        }
    }

    fun joinDialog() {
        val host = field("Host IP or name", settings.string("lastHost") ?: "")
        form("Join watch party", host) {
            val h = host.text.toString().trim()
            if (h.isNotEmpty()) { settings.putString("lastHost", h); together.join(h) }
        }
    }

    // ------------------------------------------------------------- Daydream controller

    /** Reconnects quietly to a remembered or paired controller when Bluetooth is on. */
    private fun autoConnectController() {
        val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) controller.connect(quiet = true)
    }

    private val btState = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            val s = i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
            if (s == android.bluetooth.BluetoothAdapter.STATE_ON) autoConnectController()
        }
    }

    fun connectController() {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) controller.connect() else requestPermissions(missing.toTypedArray(), REQ_BT)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) controller.connect()
        else if (requestCode == REQ_BT) toast("Bluetooth permission is needed for the controller")
        if (requestCode == REQ_FILE) {
            val u = pendingFileUri; pendingFileUri = null
            if (u != null && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) openUri(u, false)
            else if (u != null) toast("Storage permission is needed to open file paths")
        }
    }

    override fun onButton(button: DaydreamController.Button) {
        // The start screen is flat (no pointer): any controller press means "I'm in the headset".
        if (startScreen != null && button != DaydreamController.Button.VOL_UP && button != DaydreamController.Button.VOL_DOWN) {
            enterTheatre(); return
        }
        when (button) {
            DaydreamController.Button.CLICK ->
                if (panelShown && !renderer.hitX.isNaN()) clickPanel(renderer.hitX, renderer.hitY) else player.togglePause()
            DaydreamController.Button.APP -> if (panelShown) hidePanel() else showPanel()
            DaydreamController.Button.HOME -> recenter()
            DaydreamController.Button.VOL_UP -> volume(AudioManager.ADJUST_RAISE)
            DaydreamController.Button.VOL_DOWN -> volume(AudioManager.ADJUST_LOWER)
        }
    }

    override fun onSwipe(dx: Float, dy: Float) {
        if (abs(dx) > abs(dy)) player.seekRelative(if (dx > 0) 10.0 else -10.0)
    }

    /** Touchpad drag / mouse wheel: resize the screen or dim the lights when pointing at them, else volume. */
    override fun onScroll(dy: Float) {
        if (!settings.stereo) return
        val up = -dy
        when (renderer.pointTarget) {
            VrRenderer.Target.SCREEN -> settings.screenWidth = (settings.screenWidth * (1f + up * 0.6f)).coerceIn(1f, 28f)
            VrRenderer.Target.LIGHTS -> {
                if (dimmedNow) settings.dimLights = (settings.dimLights + up * 0.6f).coerceIn(0f, 1.5f)
                else settings.roomLights = (settings.roomLights + up * 0.6f).coerceIn(0f, 1.5f)
                lightsChanged()
            }
            VrRenderer.Target.NONE -> {
                volumeAccum += up
                while (volumeAccum >= 0.12f) { volume(AudioManager.ADJUST_RAISE); volumeAccum -= 0.12f }
                while (volumeAccum <= -0.12f) { volume(AudioManager.ADJUST_LOWER); volumeAccum += 0.12f }
            }
        }
        if (panelShown) panel.refresh()
    }

    override fun onStatus(text: String) { panel.setStatus(text); panel.refresh() }

    private fun volume(dir: Int) {
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
    }

    // ------------------------------------------------------------- status polling

    private val poll = object : Runnable {
        override fun run() {
            if (playerReady) updateStatus()
            glView.requestRender()   // picks up settings changed from the menu
            ui.postDelayed(this, if (settings.batterySaver && !panelShown) 1000 else 300)
        }
    }

    private fun updateStatus() {
        val w = player.videoWidth; val h = player.videoHeight
        if (!w.isNaN() && !h.isNaN() && w > 0 && h > 0) {
            renderer.videoAspect = (w / h).toFloat()
            val scale = minOf(1.0, 3840.0 / w, 2160.0 / h)
            val sw = (w * scale).toInt(); val sh = (h * scale).toInt()
            if (sw to sh != surfaceSize) {
                surfaceSize = sw to sh
                renderer.setVideoBufferSize(sw, sh)
                player.setSurfaceSize(sw, sh)
                Log.i(TAG, "video ${w}x$h -> surface ${sw}x$sh")
            }
        }
        if (BuildDebug.on(this) && SystemClock.uptimeMillis() % 3000 < 300) Log.d(TAG, "osd=${player.prop("osd-dimensions/w")}x${player.prop("osd-dimensions/h")} vo=${player.prop("video-out-params/w")}x${player.prop("video-out-params/h")} surf=$surfaceSize")
        val pos = player.position
        if (!pos.isNaN()) panel.updateTime(pos, player.duration, player.mediaTitle)
        val now = SystemClock.uptimeMillis()
        if (now - lastResumeSave > 5000) { lastResumeSave = now; saveResume() }
        updateLights(now)
        if (Downloads.status != lastDlStatus) { lastDlStatus = Downloads.status; if (lastDlStatus.isNotEmpty()) panel.setStatus(lastDlStatus) }
        if (subsPendingSince != 0L && !sidecarsLoading) {
            val loaded = (player.prop("track-list/count")?.toIntOrNull() ?: 0) > 0 && !player.duration.isNaN()
            if (loaded || now - subsPendingSince > 20000) { subsPendingSince = 0L; if (loaded) applySubtitles() }
        }
        if (settings.stereo && panelShown) panelChanged()
    }

    // ------------------------------------------------------------- keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> player.togglePause()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekRelative(-10.0)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekRelative(10.0)
            KeyEvent.KEYCODE_DPAD_UP -> settings.screenDistance = (settings.screenDistance / 1.1f).coerceAtLeast(1.5f)
            KeyEvent.KEYCODE_DPAD_DOWN -> settings.screenDistance = (settings.screenDistance * 1.1f).coerceAtMost(25f)
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_BUTTON_R1 ->
                settings.screenWidth = (settings.screenWidth * 1.1f).coerceAtMost(28f)
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_BUTTON_L1 -> settings.screenWidth = (settings.screenWidth / 1.1f).coerceAtLeast(1f)
            KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_BUTTON_Y -> recenter()
            KeyEvent.KEYCODE_V -> setStereo(!settings.stereo)
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_X -> if (panelShown) hidePanel() else showPanel()
            else -> return super.onKeyDown(keyCode, event)
        }
        panel.refresh()
        return true
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        enterImmersive()
        updateDisplayParams()
        if (!settings.lockView) tracker.start()
        glView.onResume()
        ui.post(poll)
        autoConnectController()
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        saveResume()
        player.pause()
        tracker.stop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        together.stop()
        try { unregisterReceiver(btState) } catch (_: Exception) {}
        controller.disconnect()
        player.destroy()
        isoFd?.close()
        glView.queueEvent { renderer.release() }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    @Suppress("DEPRECATION")
    private fun updateDisplayParams() {
        val display = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        tracker.displayRotation = display?.rotation ?: Surface.ROTATION_90
        val dm = DisplayMetrics()
        display?.getRealMetrics(dm)
        val dpi = (dm.xdpi + dm.ydpi) / 2
        if (dpi > 50) renderer.pxPerMeter = dpi / 0.0254f
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "Sideview"
        private const val REQ_OPEN = 1
        private const val REQ_BT = 2
        private const val REQ_FILE = 3
        private const val REQ_NOTIF = 4
    }
}

private object BuildDebug {
    fun on(a: Activity) = a.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
}
