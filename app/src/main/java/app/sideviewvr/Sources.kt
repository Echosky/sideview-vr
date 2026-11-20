// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.EnumSet

/** An entry in a media browser. */
data class MediaEntry(val title: String, val isFolder: Boolean, val ref: String)

val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts", "wmv", "flv", "mpg", "mpeg", "vob", "iso", "3gp", "ogv", "rmvb"
)

val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "alac", "ape", "mka")
val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif")
val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup")

/** Anything the player can open: video, audio or a photo. */
fun isVideoName(name: String) = name.substringAfterLast('.', "").lowercase().let {
    it in VIDEO_EXTENSIONS || it in AUDIO_EXTENSIONS || it in IMAGE_EXTENSIONS
}
fun isImageName(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

// ---------------------------------------------------------------- SMB

/** A saved SMB share, e.g. host "nas.local" (or a VPN name / IP), share "media". */
data class SmbServer(val host: String, val share: String, val user: String, val password: String, val domain: String = "") {
    val label get() = "smb://$host/$share"
    fun toJson() = JSONObject().put("host", host).put("share", share).put("user", user).put("password", password).put("domain", domain)
    companion object {
        fun fromJson(o: JSONObject) = SmbServer(o.getString("host"), o.getString("share"), o.optString("user"),
            o.optString("password"), o.optString("domain"))
    }
}

object Smb {
    private val client by lazy { SMBClient() }
    private val sessions = HashMap<String, Session>()

    @Synchronized
    private fun share(s: SmbServer): DiskShare {
        val key = "${s.user}@${s.host}"
        var session = sessions[key]
        if (session == null || !session.connection.isConnected) {
            val conn = client.connect(s.host)
            val auth = if (s.user.isEmpty()) AuthenticationContext.guest()
                else AuthenticationContext(s.user, s.password.toCharArray(), s.domain.ifEmpty { null })
            session = conn.authenticate(auth)
            sessions[key] = session
        }
        return session!!.connectShare(s.share) as DiskShare
    }

    /** Lists folders and video files at [path] ("" = share root). Blocking. */
    fun list(s: SmbServer, path: String): List<MediaEntry> {
        val share = share(s)
        return share.list(path).filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }
            .mapNotNull {
                val dir = it.fileAttributes and 0x10L != 0L
                val p = if (path.isEmpty()) it.fileName else "$path\\${it.fileName}"
                if (dir) MediaEntry(it.fileName, true, p) else if (isVideoName(it.fileName)) MediaEntry(it.fileName, false, p) else null
            }
            .sortedWith(compareBy({ !it.isFolder }, { it.title.lowercase() }))
    }

    private val SUB_EXT = setOf("srt", "ass", "ssa", "vtt")

    /**
     * Subtitle files that belong to a video: same folder and starting with the video's
     * name, or inside a "Subs"/"Subtitles" folder next to it. Returns (stream URL, file name).
     */
    fun sidecars(s: SmbServer, videoPath: String): List<Pair<String, String>> =
        sidecarPaths(s, videoPath).map { streamFile(s, it) to it.substringAfterLast('\\') }

    /** Paths (within the share) of subtitle files that belong to [videoPath]. */
    fun sidecarPaths(s: SmbServer, videoPath: String): List<String> {
        val dir = videoPath.substringBeforeLast('\\', "")
        val stem = videoPath.substringAfterLast('\\').substringBeforeLast('.')
        val share = share(s)
        fun ls(d: String) = runCatching { share.list(d) }.getOrDefault(emptyList())
            .filter { it.fileName != "." && it.fileName != ".." }
        fun join(d: String, n: String) = if (d.isEmpty()) n else "$d\\$n"
        val out = ArrayList<String>()
        val entries = ls(dir)
        // A folder with a single video (a movie folder): every subtitle file in it belongs to that video.
        val onlyVideo = entries.count { it.fileAttributes and 0x10L == 0L && isVideoName(it.fileName) } == 1
        for (e in entries) {
            val n = e.fileName
            val isDir = e.fileAttributes and 0x10L != 0L
            if (!isDir && n.substringAfterLast('.').lowercase() in SUB_EXT && (onlyVideo || n.startsWith(stem, ignoreCase = true))) {
                out += join(dir, n)
            } else if (isDir && n.lowercase() in setOf("subs", "subtitles", "sub")) {
                val sd = join(dir, n)
                for (f in ls(sd)) {
                    val fn = f.fileName
                    if (f.fileAttributes and 0x10L != 0L) {
                        // Episode folders: Subs/<episode name>/2_English.srt
                        if (fn.equals(stem, ignoreCase = true)) for (g in ls(join(sd, fn)))
                            if (g.fileName.substringAfterLast('.').lowercase() in SUB_EXT) out += join(join(sd, fn), g.fileName)
                    } else if (fn.substringAfterLast('.').lowercase() in SUB_EXT) out += join(sd, fn)
                }
            }
        }
        return out.take(40)
    }

    /** Size of a file in the share. */
    fun size(s: SmbServer, path: String): Long = share(s).getFileInformation(path).standardInformation.endOfFile

    /** Reads [path] from [offset] into [out] (resumable copy); [progress] gets bytes done; return false from it to stop. */
    fun copy(s: SmbServer, path: String, offset: Long, out: java.io.OutputStream, progress: (Long) -> Boolean) {
        val f = share(s).openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        f.use {
            val buf = ByteArray(1 shl 20)
            var pos = offset
            while (true) {
                val n = it.read(buf, pos, 0, buf.size)
                if (n <= 0) break
                out.write(buf, 0, n); pos += n
                if (!progress(pos)) break
            }
        }
    }

    /** All playable files under [path], recursively (for downloading a whole folder or series). */
    fun walk(s: SmbServer, path: String, depth: Int = 0): List<String> {
        if (depth > 6) return emptyList()
        val out = ArrayList<String>()
        for (e in list(s, path)) if (e.isFolder) out += walk(s, e.ref, depth + 1) else out += e.ref
        return out
    }

    /** Returns a local http URL that streams the SMB file to libmpv. */
    fun streamUrl(s: SmbServer, path: String): String = streamFile(s, path)

    private fun streamFile(s: SmbServer, path: String): String =
        StreamServer.register(path.substringAfterLast('\\')) {
            val f = share(s).openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            object : RandomSource {
                override val length = f.fileInformation.standardInformation.endOfFile
                override fun read(offset: Long, buf: ByteArray, len: Int) = f.read(buf, offset, 0, len)
                override fun close() = f.close()
            }
        }
}

// ---------------------------------------------------------------- DLNA / UPnP

data class DlnaServer(val name: String, val descriptionUrl: String, var controlUrl: String? = null)

object Dlna {
    private const val CD = "urn:schemas-upnp-org:service:ContentDirectory:1"

    /** SSDP discovery on the local network (does not cross Tailscale; add those by URL). Blocking. */
    fun discover(context: Context, timeoutMs: Int = 3000): List<DlnaServer> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("sideview-ssdp").apply { setReferenceCounted(false); acquire() }
        val found = LinkedHashMap<String, DlnaServer>()
        try {
            MulticastSocket().use { sock ->
                sock.soTimeout = 500
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $CD\r\n\r\n".toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    val loc = Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: continue
                    if (loc !in found) found[loc] = DlnaServer(loc, loc)
                }
            }
        } finally {
            lock.release()
        }
        return found.values.mapNotNull { runCatching { resolve(it.descriptionUrl) }.getOrNull() }
    }

    /** Reads a device description; returns the server with its name and ContentDirectory control URL. Blocking. */
    fun resolve(descriptionUrl: String): DlnaServer {
        val xml = http(descriptionUrl, null, null)
        var name = descriptionUrl
        var base: String? = null
        var control: String? = null
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        var inService = false; var serviceType = ""; var serviceControl = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                "friendlyName" -> if (name == descriptionUrl) name = p.nextText()
                "URLBase" -> base = p.nextText()
                "service" -> { inService = true; serviceType = ""; serviceControl = "" }
                "serviceType" -> if (inService) serviceType = p.nextText()
                "controlURL" -> if (inService) serviceControl = p.nextText()
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "service") {
                if (serviceType.startsWith("urn:schemas-upnp-org:service:ContentDirectory")) control = serviceControl
                inService = false
            }
        }
        val c = control ?: error("No ContentDirectory service")
        return DlnaServer(name, descriptionUrl, URL(URL(base ?: descriptionUrl), c).toString())
    }

    /** Browses the children of [objectId] ("0" = root). ref of items = media URL, of folders = object id. */
    fun browse(server: DlnaServer, objectId: String): List<MediaEntry> {
        val control = server.controlUrl ?: resolve(server.descriptionUrl).controlUrl!!.also { server.controlUrl = it }
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:Browse xmlns:u="$CD"><ObjectID>${esc(objectId)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>
<Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>1000</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"""
        val resp = http(control, body, "\"$CD#Browse\"")
        // The DIDL-Lite document is XML-escaped inside <Result>.
        val p = Xml.newPullParser()
        p.setInput(StringReader(resp))
        var didl = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Result") { didl = p.nextText(); break }
        }
        return parseDidl(didl)
    }

    private fun parseDidl(didl: String): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(didl))
        var kind = ""; var id = ""; var title = ""; var res = ""; var resIsVideo = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "container", "item" -> { kind = p.name; id = p.getAttributeValue(null, "id") ?: ""; title = ""; res = ""; resIsVideo = false }
                    "dc:title" -> title = p.nextText()
                    "res" -> {
                        val info = p.getAttributeValue(null, "protocolInfo") ?: ""
                        val url = p.nextText().trim()
                        val media = info.contains("video") || info.contains("audio") || info.contains("image")
                        if (res.isEmpty() || (!resIsVideo && media)) { res = url; resIsVideo = media }
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "container" -> out += MediaEntry(title, true, id)
                    "item" -> if (res.isNotEmpty() && (resIsVideo || isVideoName(res.substringBefore('?')))) out += MediaEntry(title, false, res)
                }
            }
        }
        return out
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun http(url: String, body: String?, soapAction: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 10000
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", soapAction)
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

// ---------------------------------------------------------------- saved sources

class SavedSources(private val settings: Settings) {
    fun smbServers(): List<SmbServer> = arr("smbServers").map { SmbServer.fromJson(it as JSONObject) }
    fun addSmb(s: SmbServer) = put("smbServers", smbServers().filter { it.label != s.label } + s) { it.toJson() }
    fun removeSmb(s: SmbServer) = put("smbServers", smbServers().filter { it.label != s.label }) { it.toJson() }

    fun dlnaServers(): List<DlnaServer> = arr("dlnaServers").map {
        val o = it as JSONObject; DlnaServer(o.getString("name"), o.getString("url"))
    }
    fun addDlna(s: DlnaServer) = put("dlnaServers", dlnaServers().filter { it.descriptionUrl != s.descriptionUrl } + s) {
        JSONObject().put("name", it.name).put("url", it.descriptionUrl)
    }

    /** Recently played items: title + url (content://, http(s), smb-ref). */
    fun recents(): List<MediaEntry> = arr("recents").map {
        val o = it as JSONObject; MediaEntry(o.getString("t"), false, o.getString("u"))
    }
    fun addRecent(e: MediaEntry) = put("recents", (listOf(e) + recents().filter { it.ref != e.ref }).take(20)) {
        JSONObject().put("t", it.title).put("u", it.ref)
    }

    fun resumePosition(ref: String): Double = settings.string("pos:$ref")?.toDoubleOrNull() ?: 0.0
    fun saveResume(ref: String, pos: Double) = settings.putString("pos:$ref", if (pos > 0) pos.toString() else null)

    private fun arr(key: String) = runCatching { JSONArray(settings.string(key) ?: "[]") }.getOrDefault(JSONArray()).let { a ->
        (0 until a.length()).map { a.get(it) }
    }
    private fun <T> put(key: String, items: List<T>, f: (T) -> JSONObject) =
        settings.putString(key, JSONArray().apply { items.forEach { put(f(it)) } }.toString())
}
