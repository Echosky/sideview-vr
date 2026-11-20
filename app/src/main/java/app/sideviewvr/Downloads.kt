// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread

/**
 * Offline copies for trips: single files, whole folders or whole series, from SMB or
 * DLNA/HTTP, into the app's own storage (no storage permission; removed with the app).
 * Partial files resume where they stopped.
 */
object Downloads {
    class Job(val title: String, val dest: File, val smb: SmbServer? = null, val path: String? = null, val url: String? = null)

    private val queue = LinkedBlockingDeque<Job>()
    @Volatile var status: String = ""; private set
    @Volatile var active = false; private set
    @Volatile private var cancel = false
    @Volatile private var done = 0
    @Volatile private var total = 0

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Offline").also { it.mkdirs() }

    /** Where an SMB file's offline copy lives. */
    fun localFor(ctx: Context, s: SmbServer, path: String) =
        File(dir(ctx), safe(s.host) + "/" + safe(s.share) + "/" + path.split('\\').joinToString("/") { safe(it) })

    /** The offline copy of an SMB file, if it is complete. */
    fun existing(ctx: Context, s: SmbServer, path: String): File? = localFor(ctx, s, path).takeIf { it.isFile && it.length() > 0 }

    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "_" }

    /** Queues an SMB file or folder (recursively, with subtitle files). Listing runs in the background. */
    fun addSmb(ctx: Context, s: SmbServer, path: String, isFolder: Boolean) {
        status = "Preparing download…"
        thread {
            try {
                val files = if (isFolder) Smb.walk(s, path) else listOf(path)
                val jobs = ArrayList<Job>()
                val seen = HashSet<String>()
                for (f in files) {
                    if (seen.add(f)) jobs += Job(f.substringAfterLast('\\'), localFor(ctx, s, f), smb = s, path = f)
                    if (!isImageName(f)) for (sub in runCatching { Smb.sidecarPaths(s, f) }.getOrDefault(emptyList()))
                        if (seen.add(sub)) jobs += Job(sub.substringAfterLast('\\'), localFor(ctx, s, sub), smb = s, path = sub)
                }
                enqueue(ctx, jobs)
            } catch (e: Exception) { status = "Download failed: ${e.message}" }
        }
    }

    /** Queues a DLNA item or folder (recursively). */
    fun addDlna(ctx: Context, srv: DlnaServer, e: MediaEntry) {
        status = "Preparing download…"
        thread {
            try {
                val jobs = ArrayList<Job>()
                fun walk(id: String, rel: String, depth: Int) {
                    if (depth > 6) return
                    for (c in Dlna.browse(srv, id)) {
                        if (c.isFolder) walk(c.ref, rel + "/" + safe(c.title), depth + 1)
                        else jobs += Job(c.title, File(dir(ctx), "DLNA/" + safe(srv.name) + rel + "/" + fileName(c)), url = c.ref)
                    }
                }
                if (e.isFolder) walk(e.ref, "/" + safe(e.title), 0)
                else jobs += Job(e.title, File(dir(ctx), "DLNA/" + safe(srv.name) + "/" + fileName(e)), url = e.ref)
                enqueue(ctx, jobs)
            } catch (x: Exception) { status = "Download failed: ${x.message}" }
        }
    }

    /** A file name with an extension (DLNA titles usually have none). */
    private fun fileName(e: MediaEntry): String {
        val ext = e.ref.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").takeIf { it.length in 2..5 } ?: "mkv"
        val t = safe(e.title)
        return if (isVideoName(t)) t else "$t.$ext"
    }

    private fun enqueue(ctx: Context, jobs: List<Job>) {
        if (jobs.isEmpty()) { status = "Nothing to download"; return }
        cancel = false
        if (!active) { done = 0; total = 0 }
        total += jobs.size
        queue.addAll(jobs)
        status = "Queued ${jobs.size} file(s)"
        val i = Intent(ctx, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun cancelAll() { cancel = true; queue.clear(); status = "Downloads cancelled" }

    /** Runs queued jobs until the queue is empty. Called by [DownloadService] on its own thread. */
    internal fun runQueue(onProgress: (String, Int) -> Unit) {
        active = true
        try {
            while (!cancel) {
                val job = queue.poll() ?: break
                runCatching { runJob(job, onProgress) }.onFailure {
                    Log.w("SideviewDl", "download ${job.title}", it); status = "Failed: ${job.title} (${it.message})"
                }
                done++
            }
            if (!cancel) status = "Downloads finished ($done file(s))"
        } finally { active = false }
    }

    private fun runJob(job: Job, onProgress: (String, Int) -> Unit) {
        if (job.dest.isFile && job.dest.length() > 0) return              // already complete
        job.dest.parentFile?.mkdirs()
        val part = File(job.dest.path + ".part")
        val have = if (part.isFile) part.length() else 0L
        val size = when {
            job.smb != null -> Smb.size(job.smb, job.path!!)
            else -> -1L
        }
        if (size > 0) {
            val free = StatFs(job.dest.parentFile!!.path).availableBytes
            if (size - have > free - 300L * 1024 * 1024) throw IllegalStateException("not enough free space")
        }
        var last = 0L
        fun report(pos: Long, len: Long): Boolean {
            val now = SystemClock.uptimeMillis()
            if (now - last > 1000) {
                last = now
                val pct = if (len > 0) (pos * 100 / len).toInt() else -1
                status = "Downloading ${done + 1}/$total: ${job.title}" + (if (pct >= 0) " $pct%" else " ${pos shr 20} MB")
                onProgress(status, pct)
            }
            return !cancel
        }
        FileOutputStream(part, true).use { out ->
            if (job.smb != null) {
                Smb.copy(job.smb, job.path!!, have, out) { report(it, size) }
            } else {
                val c = URL(job.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val resumed = c.responseCode == 206
                if (have > 0 && !resumed) out.channel.truncate(0)
                val len = c.contentLengthLong.let { if (it > 0) it + (if (resumed) have else 0) else -1 }
                c.inputStream.use { inp ->
                    val buf = ByteArray(1 shl 20)
                    var pos = if (resumed) have else 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); pos += n
                        if (!report(pos, len)) break
                    }
                }
            }
        }
        if (cancel) return
        if (size > 0 && part.length() < size) throw IllegalStateException("incomplete (${part.length()} of $size bytes)")
        if (!part.renameTo(job.dest)) throw IllegalStateException("could not finish ${job.dest.name}")
    }

    /** Files and folders in an offline folder. */
    fun list(folder: File): List<MediaEntry> = (folder.listFiles() ?: emptyArray())
        .filter { it.isDirectory || (isVideoName(it.name)) }
        .map { MediaEntry(it.name, it.isDirectory, it.absolutePath) }
        .sortedWith(compareBy({ !it.isFolder }, { it.title.lowercase() }))

    fun delete(path: String) { File(path).deleteRecursively() }

    fun usedBytes(ctx: Context): Long = dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/** Keeps downloads running with the screen off (foreground service, Wi-Fi and CPU locks only while busy). */
class DownloadService : Service() {
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = note("Starting downloads…", -1)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        if (worker?.isAlive != true) {
            worker = thread(name = "downloads") {
                val wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sideview:dl")
                val cpu = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sideview:dl")
                wifi.acquire(); cpu.acquire(12 * 60 * 60 * 1000L)
                try {
                    Downloads.runQueue { text, pct -> nm.notify(ID, note(text, pct)) }
                } finally {
                    if (wifi.isHeld) wifi.release(); if (cpu.isHeld) cpu.release()
                    stopForeground(true)
                    nm.notify(ID + 1, note(Downloads.status, 100, ongoing = false))
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun note(text: String, pct: Int, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        return b.setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle("Sideview VR").setContentText(text).setContentIntent(open).setOngoing(ongoing)
            .setOnlyAlertOnce(true).apply { if (ongoing) setProgress(100, maxOf(pct, 0), pct < 0) }.build()
    }

    companion object {
        private const val CH = "downloads"
        private const val ID = 4701
    }
}
