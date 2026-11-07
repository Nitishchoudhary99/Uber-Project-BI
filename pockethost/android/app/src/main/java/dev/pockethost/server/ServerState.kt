package dev.pockethost.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class SvcStatus { STOPPED, STARTING, RUNNING, CRASHED, DISABLED }

data class SvcInfo(val status: SvcStatus = SvcStatus.STOPPED, val detail: String? = null)

data class ServerSnapshot(
    val active: Boolean = false,
    val startedAt: Long? = null,
    val services: Map<String, SvcInfo> = emptyMap(),
    val publicUrl: String? = null,
) {
    fun status(name: String) = services[name]?.status ?: SvcStatus.STOPPED
}

/** In-process state shared by the server service and the UI. */
object ServerState {
    const val OLLAMA = "ollama"
    const val GATEWAY = "gateway"
    const val TUNNEL = "tunnel"
    val SERVICES = listOf(OLLAMA, GATEWAY, TUNNEL)

    private val _snapshot = MutableStateFlow(ServerSnapshot())
    val snapshot: StateFlow<ServerSnapshot> = _snapshot.asStateFlow()

    private val logs = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun begin() = _snapshot.update {
        ServerSnapshot(active = true, startedAt = System.currentTimeMillis(), services = SERVICES.associateWith { SvcInfo(SvcStatus.STARTING) })
    }

    fun end() = _snapshot.update { ServerSnapshot() }

    fun set(name: String, status: SvcStatus, detail: String? = null) = _snapshot.update {
        if (!it.active) it else it.copy(services = it.services + (name to SvcInfo(status, detail)))
    }

    fun setPublicUrl(url: String?) = _snapshot.update { if (!it.active) it else it.copy(publicUrl = url) }

    fun log(name: String, line: String) {
        val q = logs.getOrPut(name) { ArrayDeque() }
        synchronized(q) {
            q.addLast("${time.format(Date())}  $line")
            while (q.size > 500) q.removeFirst()
        }
    }

    fun logs(name: String): List<String> = logs[name]?.let { synchronized(it) { it.toList() } } ?: emptyList()
}
