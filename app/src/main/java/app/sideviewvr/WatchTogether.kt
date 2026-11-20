// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * "Watch together": one phone hosts, others follow its playback clock over TCP
 * (LAN or Tailscale). Everyone opens the same video themselves.
 * Protocol (our own, line based): "S <position> <paused 0|1> <speed>\n" from host to followers.
 */
class WatchTogether(private val player: Player, private val onStatus: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private val followers = CopyOnWriteArrayList<PrintWriter>()
    private var client: Socket? = null
    var mode = "Off"; private set

    fun host(port: Int = PORT) {
        stop()
        val s = ServerSocket(port)
        server = s
        mode = "Hosting"
        status("Hosting on ${localAddresses().joinToString()} : $port")
        thread(name = "together-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                followers += PrintWriter(c.getOutputStream(), true)
                status("Hosting: ${followers.size} connected")
            }
        }
        main.post(broadcast)
    }

    private val broadcast = object : Runnable {
        override fun run() {
            if (server == null) return
            val pos = player.position
            if (!pos.isNaN()) {
                val line = "S $pos ${if (player.paused) 1 else 0} ${player.speed}"
                thread(isDaemon = true) {
                    for (w in followers) { w.println(line); if (w.checkError()) followers.remove(w) }
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    fun join(host: String, port: Int = PORT) {
        stop()
        mode = "Following"
        status("Connecting to $host…")
        thread(name = "together-follow", isDaemon = true) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                client = s
                status("Following $host")
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                while (true) {
                    val line = r.readLine() ?: break
                    val p = line.split(" ")
                    if (p.size >= 4 && p[0] == "S") main.post { follow(p[1].toDouble(), p[2] == "1", p[3].toDouble()) }
                }
                status("Host disconnected")
            } catch (e: Exception) {
                status("Connection failed: ${e.message}")
            }
            mode = "Off"
        }
    }

    private fun follow(pos: Double, paused: Boolean, speed: Double) {
        if (player.paused != paused) player.setPaused(paused)
        if (abs(player.speed - speed) > 0.01) player.setSpeed(speed)
        val local = player.position
        if (!local.isNaN() && abs(local - pos) > 0.75) player.seekAbsolute(pos + 0.15)
    }

    fun stop() {
        main.removeCallbacks(broadcast)
        try { server?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        server = null; client = null
        followers.clear()
        mode = "Off"
        status("Off")
    }

    private fun status(s: String) = main.post { onStatus(s) }

    companion object {
        const val PORT = 47630

        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { it.address.size == 4 }
                .map { it.hostAddress ?: "" }
        }.getOrDefault(emptyList())
    }
}
