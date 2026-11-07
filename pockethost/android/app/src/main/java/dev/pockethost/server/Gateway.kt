package dev.pockethost.server

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The single front door of the phone server, written as a tiny streaming
 * HTTP/1.1 reverse proxy over raw sockets (no dependencies).
 *
 *   /health              200, no auth (readiness checks)
 *   /v1/...              OpenAI-compatible API  -> Ollama /v1/...
 *   /api/...             native Ollama API      -> Ollama /api/...
 *   /ollama/...          same, with a prefix    -> Ollama /...
 *
 * Requests from the internet (they carry Cloudflare's cf-connecting-ip header)
 * need the API key: `Authorization: Bearer <key>`, `X-Api-Key`, or `?key=`.
 * Ollama itself only listens on 127.0.0.1, so this is the only way in.
 */
class Gateway(
    private val port: Int,
    private val upstreamPort: Int,
    private val apiKey: () -> String,
) {
    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "ph-gw").apply { isDaemon = true } }

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 128)
        server = s
        thread(name = "ph-gw-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                pool.execute { handle(c) }
            }
        }
        ServerState.log(ServerState.GATEWAY, "listening on 127.0.0.1:$port")
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
        ServerState.log(ServerState.GATEWAY, "stopped")
    }

    private class Head(val first: String, val headers: List<Pair<String, String>>) {
        fun header(n: String) = headers.firstOrNull { it.first.equals(n, ignoreCase = true) }?.second
    }

    private fun handle(client: Socket) {
        try {
            client.use {
                client.soTimeout = 30_000
                val input = BufferedInputStream(client.getInputStream())
                val out = client.getOutputStream()
                val head = readHead(input) ?: return
                client.soTimeout = 0
                route(head, client, input, out)
            }
        } catch (_: Exception) {
            // client went away; nothing to do
        }
    }

    private fun route(head: Head, client: Socket, input: InputStream, out: OutputStream) {
        val parts = head.first.split(' ')
        if (parts.size < 3) return respond(out, 400, "text/plain", "bad request")
        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val external = isExternal(head, client)

        when {
            path == "/health" -> respond(out, 200, "application/json", """{"ok":true,"name":"pockethost"}""")
            method == "OPTIONS" -> respond(out, 204, null, "")
            path == "/" -> respond(out, 200, "text/html; charset=utf-8", LANDING)
            path.startsWith("/v1/") || path.startsWith("/api/") || path == "/ollama" || path.startsWith("/ollama/") -> {
                if (external && !authorized(head, query)) {
                    ServerState.log(ServerState.GATEWAY, "401 $method $path from ${head.header("cf-connecting-ip") ?: "?"}")
                    return respond(out, 401, "application/json", """{"error":"Missing or wrong API key. Send header: Authorization: Bearer <key>"}""")
                }
                var up = if (path == "/ollama" || path.startsWith("/ollama/")) target.removePrefix("/ollama") else target
                if (up.isEmpty() || up.startsWith("?")) up = "/$up"
                if (external) ServerState.log(ServerState.GATEWAY, "$method $path")
                proxy(method, up, head, input, out)
            }
            else -> respond(out, 404, "application/json", """{"error":"not found"}""")
        }
    }

    private fun proxy(method: String, target: String, head: Head, input: InputStream, out: OutputStream) {
        val up = Socket()
        try {
            up.connect(InetSocketAddress("127.0.0.1", upstreamPort), 3_000)
        } catch (_: IOException) {
            return respond(out, 502, "application/json", """{"error":"The AI engine is not running on this phone right now"}""")
        }
        up.use {
            val sb = StringBuilder("$method $target HTTP/1.1\r\n")
            for ((k, v) in head.headers) if (k.lowercase() !in DROP_REQUEST) sb.append(k).append(": ").append(v).append("\r\n")
            // Ollama rejects non-loopback Host/Origin when bound to loopback.
            sb.append("Host: 127.0.0.1:").append(upstreamPort).append("\r\nConnection: close\r\n\r\n")
            val upOut = up.getOutputStream()
            upOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            upOut.flush()

            // Request body: client -> engine, in the background.
            pool.execute {
                try { input.copyTo(upOut) } catch (_: IOException) { } finally { runCatching { up.shutdownOutput() } }
            }

            // Response: engine -> client, adding CORS so browser apps can call it.
            val upIn = BufferedInputStream(up.getInputStream())
            val resp = readHead(upIn) ?: return respond(out, 502, "application/json", """{"error":"bad response from engine"}""")
            val rb = StringBuilder(resp.first).append("\r\n")
            for ((k, v) in resp.headers) if (k.lowercase() !in DROP_RESPONSE) rb.append(k).append(": ").append(v).append("\r\n")
            CORS.forEach { (k, v) -> rb.append(k).append(": ").append(v).append("\r\n") }
            rb.append("Connection: close\r\n\r\n")
            out.write(rb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = upIn.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush() // stream tokens as they are generated
            }
        }
    }

    private fun isExternal(head: Head, client: Socket): Boolean =
        head.header("cf-connecting-ip") != null || head.header("x-forwarded-for") != null || !client.inetAddress.isLoopbackAddress

    private fun authorized(head: Head, query: String): Boolean {
        val auth = head.header("authorization").orEmpty()
        val presented = when {
            auth.startsWith("Bearer ", ignoreCase = true) -> auth.substring(7).trim()
            head.header("x-api-key") != null -> head.header("x-api-key")!!.trim()
            else -> query.split('&').firstOrNull { it.startsWith("key=") }
                ?.let { runCatching { URLDecoder.decode(it.substring(4), "UTF-8") }.getOrNull() }.orEmpty()
        }
        val key = apiKey()
        return key.isNotEmpty() && MessageDigest.isEqual(presented.toByteArray(), key.toByteArray())
    }

    private fun respond(out: OutputStream, code: Int, type: String?, body: String) {
        val bytes = body.toByteArray()
        val sb = StringBuilder("HTTP/1.1 $code ${REASONS[code] ?: "OK"}\r\n")
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n")
        CORS.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Cache-Control: no-store\r\nContent-Length: ").append(bytes.size).append("\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    /** Reads an HTTP head (request or status line + headers). Leaves the body in [input]. */
    private fun readHead(input: InputStream): Head? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (buf.size() > 64 * 1024) return null
        }
        val lines = buf.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }
        return Head(lines.first(), headers)
    }

    companion object {
        private val DROP_REQUEST = setOf(
            "host", "connection", "keep-alive", "proxy-connection", "upgrade", "expect",
            "origin", "referer", "authorization", "x-api-key", "cookie",
        )
        private val DROP_RESPONSE = setOf(
            "connection", "keep-alive",
            "access-control-allow-origin", "access-control-allow-headers", "access-control-allow-methods",
        )
        private val CORS = listOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Headers" to "authorization, content-type, x-api-key",
            "Access-Control-Allow-Methods" to "GET, POST, PUT, DELETE, OPTIONS",
        )
        private val REASONS = mapOf(200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 502 to "Bad Gateway")

        private const val LANDING = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>PocketHost</title>
<style>body{font-family:system-ui,sans-serif;background:#0b0f14;color:#e6edf3;max-width:560px;margin:0 auto;padding:32px 16px;line-height:1.5}
code{background:#1a212b;padding:2px 6px;border-radius:6px}h1{font-size:24px}</style></head><body>
<h1>&#9889; PocketHost</h1><p>This AI server is running on an Android phone.</p>
<p>OpenAI-compatible API: <code>/v1</code><br>Ollama API: <code>/api</code></p>
<p>Send your key as <code>Authorization: Bearer &lt;key&gt;</code>. Get it from the PocketHost app.</p></body></html>"""
    }
}
