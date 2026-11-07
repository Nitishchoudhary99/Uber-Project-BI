package dev.pockethost

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pockethost.server.AppConfig
import dev.pockethost.server.ServerService
import dev.pockethost.server.ServerSnapshot
import dev.pockethost.server.ServerState
import dev.pockethost.server.SvcStatus
import dev.pockethost.server.TunnelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Health { OFFLINE, STARTING, ONLINE, DEGRADED }

data class Settings(
    val tunnelMode: TunnelMode = TunnelMode.QUICK,
    val hasToken: Boolean = false,
    val publicUrl: String = "",
    val autoStart: Boolean = false,
    val keepAlive: String = "5m",
)

data class UiState(
    val setupDone: Boolean = false,
    val engineBundled: Boolean = true,
    val server: ServerSnapshot = ServerSnapshot(),
    val apiKey: String = "",
    val settings: Settings = Settings(),
    val models: List<LocalModel> = emptyList(),
    val loaded: Set<String> = emptySet(),
    val pulls: Map<String, PullProgress> = emptyMap(),
    val device: DeviceStats = DeviceStats(),
    val batteryUnrestricted: Boolean = false,
    val message: String? = null,
) {
    val ollamaUp: Boolean get() = server.status(ServerState.OLLAMA) == SvcStatus.RUNNING

    val health: Health
        get() {
            if (!server.active) return Health.OFFLINE
            val st = ServerState.SERVICES.map { server.status(it) }.filter { it != SvcStatus.DISABLED }
            return when {
                st.all { it == SvcStatus.RUNNING } -> Health.ONLINE
                st.any { it == SvcStatus.CRASHED } && ollamaUp -> Health.DEGRADED
                st.any { it == SvcStatus.CRASHED } && server.startedAt?.let { System.currentTimeMillis() - it > 60_000 } == true -> Health.DEGRADED
                else -> Health.STARTING
            }
        }

    /** Public URL if the tunnel is up, else the local gateway. */
    val baseUrl: String get() = server.publicUrl ?: Api.GATEWAY
}

data class ChatMessage(val role: String, val content: String)

data class ChatState(
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val stats: String? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val cfg = AppConfig(app)

    private val _ui = MutableStateFlow(UiState(setupDone = cfg.setupDone, engineBundled = ServerService.engineBundled(app)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private var chatJob: Job? = null
    private val pullJobs = mutableMapOf<String, Job>()

    init {
        loadSettings()
        viewModelScope.launch { ServerState.snapshot.collect { s -> _ui.update { it.copy(server = s) } } }
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(3000)
            }
        }
    }

    private fun loadSettings() = _ui.update {
        it.copy(
            apiKey = cfg.apiKey,
            settings = Settings(cfg.tunnelMode, cfg.tunnelToken.isNotBlank(), cfg.publicUrl, cfg.autoStart, cfg.keepAlive),
        )
    }

    suspend fun refresh() {
        val up = _ui.value.ollamaUp
        val models = if (up) Api.models() else emptyList()
        val loaded = if (up) Api.loaded() else emptySet()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        _ui.update {
            it.copy(
                models = models,
                loaded = loaded,
                device = DeviceStats.read(ctx),
                batteryUnrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            )
        }
    }

    // ---------- setup ----------

    fun finishSetup(done: Boolean = true) {
        cfg.setupDone = done
        _ui.update { it.copy(setupDone = done) }
    }

    // ---------- server ----------

    fun start() = ServerService.start(ctx)
    fun stop() = ServerService.stop(ctx)
    fun restart() = if (_ui.value.server.active) ServerService.restart(ctx) else ServerService.start(ctx)

    fun saveTunnel(mode: TunnelMode, token: String?, publicUrl: String) {
        cfg.tunnelMode = mode
        if (!token.isNullOrBlank()) cfg.tunnelToken = token.trim()
        cfg.publicUrl = publicUrl
        loadSettings()
        applyIfRunning()
    }

    fun setAutoStart(on: Boolean) { cfg.autoStart = on; loadSettings() }

    fun setKeepAlive(v: String) { cfg.keepAlive = v; loadSettings(); applyIfRunning() }

    fun rotateKey() {
        cfg.rotateApiKey()
        loadSettings()
        toast("New API key active. Update your apps.")
    }

    private fun applyIfRunning() {
        if (_ui.value.server.active) { ServerService.restart(ctx); toast("Saved. Restarting server...") } else toast("Saved")
    }

    fun logs(service: String): List<String> = ServerState.logs(service)

    // ---------- models ----------

    fun pull(name: String) {
        val model = name.trim()
        if (!MODEL_NAME.matches(model)) return toast("Invalid model name")
        if (!_ui.value.ollamaUp) return toast("Start the server first")
        if (pullJobs[model]?.isActive == true) return
        pullJobs[model] = viewModelScope.launch {
            setPull(model, PullProgress("starting", 0, 0))
            try {
                Api.pull(model).collect { setPull(model, it) }
                toast("$model is ready")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Download failed: ${e.message}")
            } finally {
                setPull(model, null)
                refresh()
            }
        }
    }

    fun cancelPull(name: String) { pullJobs.remove(name)?.cancel() }

    private fun setPull(name: String, p: PullProgress?) = _ui.update {
        it.copy(pulls = if (p == null) it.pulls - name else it.pulls + (name to p))
    }

    fun deleteModel(name: String) {
        viewModelScope.launch {
            toast(if (Api.deleteModel(name)) "Deleted $name" else "Could not delete $name")
            refresh()
        }
    }

    // ---------- chat ----------

    fun selectModel(name: String) = _chat.update { it.copy(model = name) }

    fun clearChat() { chatJob?.cancel(); _chat.update { ChatState(model = it.model) } }

    fun stopChat() { chatJob?.cancel() }

    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || _chat.value.streaming) return
        val model = _chat.value.model ?: _ui.value.models.firstOrNull()?.name
            ?: return _chat.update { it.copy(error = "Download a model first (Models tab)") }
        val history = _chat.value.messages + ChatMessage("user", msg)
        _chat.update { it.copy(model = model, messages = history + ChatMessage("assistant", ""), streaming = true, error = null, stats = null) }
        chatJob = viewModelScope.launch {
            try {
                Api.chat(model, history).collect { ev ->
                    when (ev) {
                        is ChatEvent.Token -> _chat.update { s ->
                            val last = s.messages.last()
                            s.copy(messages = s.messages.dropLast(1) + last.copy(content = last.content + ev.text))
                        }
                        is ChatEvent.Done -> _chat.update {
                            it.copy(stats = "${ev.tokens} tokens" + (ev.tokensPerSec?.let { t -> " · %.1f tok/s".format(t) } ?: ""))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _chat.update { it.copy(error = e.message ?: "Request failed") }
            } finally {
                _chat.update { s ->
                    val trimmed = if (s.messages.lastOrNull()?.let { it.role == "assistant" && it.content.isEmpty() } == true)
                        s.messages.dropLast(1) else s.messages
                    s.copy(streaming = false, messages = trimmed)
                }
            }
        }
    }

    // ---------- messages ----------

    fun toast(msg: String) = _ui.update { it.copy(message = msg) }
    fun consumeMessage() = _ui.update { it.copy(message = null) }

    companion object {
        val MODEL_NAME = Regex("^[A-Za-z0-9._:/-]+$")
    }
}
