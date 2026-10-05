package com.vibe.music.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import com.vibe.music.AppContainer
import com.vibe.music.remote.Sources
import com.vibe.music.remote.StreamInfo
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Malý HTTP server v telefonu, ze kterého si Chromecast stahuje hudbu.
 * Chromecast nemůže brát stream z YouTube napřímo (URL je vázaná na hlavičky a IP telefonu)
 * a ke staženým souborům se nedostane vůbec – telefon mu je proto „podává“ přes Wi-Fi.
 * URL: http://<ip-telefonu>:<port>/track/<id>
 */
class CastServer(private val context: Context, private val c: AppContainer) {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val streams = ConcurrentHashMap<String, StreamInfo>()

    fun urlFor(id: String): String {
        val socket = ensureStarted()
        return "http://${lanAddress() ?: "127.0.0.1"}:${socket.localPort}/track/${Uri.encode(id)}"
    }

    fun contentType(id: String) = if (Sources.isSoundCloud(id)) "audio/mpeg" else "audio/mp4"

    @Synchronized
    private fun ensureStarted(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        val socket = ServerSocket(0)
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(client) }.onFailure { Log.w(TAG, "Požadavek selhal", it) } }
            }
        }, "vibe-cast-server").apply { isDaemon = true }.start()
        return socket
    }

    private fun handle(client: Socket) = client.use { socket ->
        socket.soTimeout = 30_000
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val out = BufferedOutputStream(socket.getOutputStream())
        if (!path.startsWith("/track/")) return respond(out, "404 Not Found")
        val id = Uri.decode(path.removePrefix("/track/").substringBefore('?'))
        val range = headers["range"]
        val headOnly = method == "HEAD"

        // Stažený soubor (jen formáty, které Chromecast spolehlivě přehraje)
        localFile(id)?.let { return serveFile(out, it, range, headOnly, id) }
        proxyStream(out, id, range, headOnly)
    }

    private fun localFile(id: String): File? = runBlocking { c.music.tracks.get(id) }
        ?.localPath?.let(::File)
        ?.takeIf { it.exists() && it.extension.lowercase() in setOf("m4a", "mp4", "mp3") }

    private fun serveFile(out: OutputStream, file: File, range: String?, headOnly: Boolean, id: String) {
        val total = file.length()
        val (start, end) = parseRange(range, total)
        val length = end - start + 1
        val status = if (range != null) "206 Partial Content" else "200 OK"
        writeHead(
            out, status,
            mapOf(
                "Content-Type" to if (file.extension.equals("mp3", true)) "audio/mpeg" else contentType(id),
                "Content-Length" to length.toString(),
                "Accept-Ranges" to "bytes",
            ) + (if (range != null) mapOf("Content-Range" to "bytes $start-$end/$total") else emptyMap()),
        )
        if (!headOnly) file.inputStream().use { inp ->
            inp.skip(start)
            copy(inp, out, length)
        }
        out.flush()
    }

    private fun proxyStream(out: OutputStream, id: String, range: String?, headOnly: Boolean) {
        val stream = streams[id]?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?: runBlocking {
                // Pro Chromecast AAC/M4A (Opus/WebM neumí všechny přijímače)
                c.ageGate.run(id) { src -> c.ytDlp.stream(src, "bestaudio[ext=m4a]/bestaudio/best") }
            }.also { streams[id] = it }
        val request = Request.Builder()
            .url(stream.url)
            .apply { stream.headers.forEach { (k, v) -> header(k, v) } }
            .apply { if (range != null) header("Range", range) }
            .apply { if (headOnly) head() }
            .build()
        c.streamHttp.newCall(request).execute().use { r ->
            if (!r.isSuccessful) {
                streams.remove(id)
                return respond(out, "${r.code} Upstream")
            }
            val headers = buildMap {
                put("Content-Type", contentType(id))
                put("Accept-Ranges", "bytes")
                r.header("Content-Length")?.let { put("Content-Length", it) }
                r.header("Content-Range")?.let { put("Content-Range", it) }
            }
            writeHead(out, if (r.code == 206) "206 Partial Content" else "200 OK", headers)
            if (!headOnly) copy(r.body.byteStream(), out, Long.MAX_VALUE)
            out.flush()
        }
    }

    private fun parseRange(range: String?, total: Long): Pair<Long, Long> {
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) } ?: return 0L to total - 1
        val (s, e) = m.destructured
        return if (s.isEmpty()) {
            (total - (e.toLongOrNull() ?: total)).coerceAtLeast(0) to total - 1
        } else {
            s.toLong() to (e.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1))
        }
    }

    private fun writeHead(out: OutputStream, status: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun respond(out: OutputStream, status: String) {
        writeHead(out, status, mapOf("Content-Length" to "0"))
        out.flush()
    }

    private fun copy(input: InputStream, out: OutputStream, max: Long) {
        val buf = ByteArray(64 * 1024)
        var left = max
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    /** IPv4 adresa telefonu v domácí síti (Wi-Fi), na kterou se Chromecast připojí. */
    private fun lanAddress(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    companion object {
        private const val TAG = "CastServer"
    }
}
