// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** A seekable byte source (one instance per HTTP connection). */
interface RandomSource : AutoCloseable {
    val length: Long
    fun read(offset: Long, buf: ByteArray, len: Int): Int
}

/**
 * Minimal HTTP/1.1 server on 127.0.0.1 with Range support, so libmpv can
 * stream (and seek in) sources it cannot open itself, such as SMB files.
 */
object StreamServer {
    private const val TAG = "SideviewStream"
    private var server: ServerSocket? = null
    private val sources = ConcurrentHashMap<String, () -> RandomSource>()

    @Synchronized
    private fun ensureStarted(): Int {
        server?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "stream-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(name = "stream-conn", isDaemon = true) { serve(c) }
            }
        }
        return s.localPort
    }

    /** Registers a source and returns a local URL for it. */
    fun register(name: String, open: () -> RandomSource): String {
        val port = ensureStarted()
        val id = UUID.randomUUID().toString().substring(0, 8)
        sources[id] = open
        return "http://127.0.0.1:$port/$id/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val request = input.readLine() ?: return
                var rangeStart = 0L
                var rangeEnd = -1L
                var hasRange = false
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", true)) {
                        val m = Regex("bytes=(\\d*)-(\\d*)").find(line) ?: continue
                        hasRange = true
                        rangeStart = m.groupValues[1].toLongOrNull() ?: 0L
                        rangeEnd = m.groupValues[2].toLongOrNull() ?: -1L
                    }
                }
                val parts = request.split(" ")
                val id = parts.getOrNull(1)?.trimStart('/')?.substringBefore('/')
                val open = id?.let { sources[it] }
                val out = s.getOutputStream()
                if (open == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                open().use { src ->
                    val total = src.length
                    if (rangeEnd < 0 || rangeEnd >= total) rangeEnd = total - 1
                    if (rangeStart >= total) {
                        out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nConnection: close\r\n\r\n".toByteArray())
                        return
                    }
                    val len = rangeEnd - rangeStart + 1
                    val head = StringBuilder()
                    head.append(if (hasRange) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    head.append("Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n")
                    head.append("Content-Length: $len\r\n")
                    if (hasRange) head.append("Content-Range: bytes $rangeStart-$rangeEnd/$total\r\n")
                    head.append("Connection: close\r\n\r\n")
                    out.write(head.toString().toByteArray())
                    if (parts[0] == "HEAD") return
                    val buf = ByteArray(256 * 1024)
                    var pos = rangeStart
                    while (pos <= rangeEnd) {
                        val want = minOf(buf.size.toLong(), rangeEnd - pos + 1).toInt()
                        val n = src.read(pos, buf, want)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        pos += n
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                Log.d(TAG, "connection ended: ${e.message}")
            }
        }
    }
}
