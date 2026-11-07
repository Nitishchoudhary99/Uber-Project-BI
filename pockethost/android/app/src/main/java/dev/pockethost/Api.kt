package dev.pockethost

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ServiceState(val running: Boolean, val pid: Int?)

data class ServerStatus(
    val version: String,
    val uptimeSec: Long,
    val publicUrl: String?,
    val apiKey: String?,
    val services: Map<String, ServiceState>,
    val config: Map<String, String>,
) {
    fun running(name: String) = services[name]?.running == true
    fun enabled(name: String): Boolean = when (name) {
        "ollama" -> config["ENABLE_OLLAMA"] != "0"
        "n8n" -> config["ENABLE_N8N"] != "0"
        "tunnel" -> config["TUNNEL"] != "none"
        else -> true
    }
}

data class LocalModel(
    val name: String,
    val sizeBytes: Long,
    val parameterSize: String?,
    val quantization: String?,
    val family: String?,
)

data class PullProgress(val status: String, val completed: Long, val total: Long) {
    val fraction: Float? get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null
}

sealed interface ChatEvent {
    data class Token(val text: String) : ChatEvent
    data class Done(val tokens: Int, val tokensPerSec: Double?) : ChatEvent
}

/** Plain HttpURLConnection + org.json: zero extra dependencies, all on-device. */
object Api {
    const val GATEWAY = "http://127.0.0.1:8080"
    const val OLLAMA = "http://127.0.0.1:11434"
    const val N8N_LOCAL = "http://127.0.0.1:5678"

    suspend fun status(): ServerStatus? = withContext(Dispatchers.IO) {
        runCatching { parseStatus(JSONObject(getText("$GATEWAY/pockethost/status"))) }.getOrNull()
    }

    suspend fun ollamaVersion(): String? = withContext(Dispatchers.IO) {
        runCatching { JSONObject(getText("$OLLAMA/api/version")).optString("version") }.getOrNull()
    }

    suspend fun models(): List<LocalModel> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONObject(getText("$OLLAMA/api/tags")).optJSONArray("models") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val d = m.optJSONObject("details")
                LocalModel(
                    name = m.optString("name"),
                    sizeBytes = m.optLong("size"),
                    parameterSize = d?.str("parameter_size"),
                    quantization = d?.str("quantization_level"),
                    family = d?.str("family"),
                )
            }.sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    /** Models currently loaded in RAM. */
    suspend fun loaded(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONObject(getText("$OLLAMA/api/ps")).optJSONArray("models") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.toSet()
        }.getOrDefault(emptySet())
    }

    fun pull(model: String): Flow<PullProgress> =
        streamLines("$OLLAMA/api/pull", JSONObject().put("model", model).put("stream", true)).map {
            PullProgress(it.optString("status"), it.optLong("completed"), it.optLong("total"))
        }

    fun chat(model: String, messages: List<ChatMessage>): Flow<ChatEvent> {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", model).put("messages", arr).put("stream", true)
        return streamLines("$OLLAMA/api/chat", body).map { o ->
            if (o.optBoolean("done")) {
                val n = o.optInt("eval_count")
                val ns = o.optLong("eval_duration")
                ChatEvent.Done(n, if (ns > 0) n / (ns / 1e9) else null)
            } else {
                ChatEvent.Token(o.optJSONObject("message")?.optString("content").orEmpty())
            }
        }
    }

    // ---------- plumbing ----------

    private fun open(url: String, method: String, connectMs: Int, readMs: Int) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectMs
            readTimeout = readMs
            useCaches = false
        }

    private fun getText(url: String): String {
        val c = open(url, "GET", 1500, 3000)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun streamLines(url: String, body: JSONObject): Flow<JSONObject> = flow {
        // Long read timeout: loading a model into RAM on a phone can take a while.
        val c = open(url, "POST", 3000, 300_000)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val msg = runCatching { JSONObject(err).optString("error") }.getOrNull()
                throw IOException(msg?.ifBlank { null } ?: "HTTP $code")
            }
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    val o = JSONObject(line)
                    o.str("error")?.let { throw IOException(it) }
                    emit(o)
                }
            }
        } finally {
            c.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseStatus(o: JSONObject): ServerStatus {
        val sv = o.optJSONObject("services") ?: JSONObject()
        val services = sv.keys().asSequence().associateWith { k ->
            val s = sv.getJSONObject(k)
            ServiceState(s.optBoolean("running"), s.optInt("pid").takeIf { it > 0 })
        }
        val cfg = o.optJSONObject("config") ?: JSONObject()
        val config = cfg.keys().asSequence().associateWith { cfg.optString(it) }
        return ServerStatus(
            version = o.optString("version"),
            uptimeSec = o.optLong("uptimeSec"),
            publicUrl = o.str("publicUrl"),
            apiKey = o.str("apiKey"),
            services = services,
            config = config,
        )
    }

    private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifBlank { null }
}
