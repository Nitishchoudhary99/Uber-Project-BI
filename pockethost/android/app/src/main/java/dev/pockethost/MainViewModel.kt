package dev.pockethost

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Health { OFFLINE, STARTING, ONLINE, DEGRADED, STOPPING, INSTALLING }

enum class PendingKind(val timeoutMs: Long) { START(240_000), STOP(30_000), INSTALL(45 * 60_000) }

data class Pending(val kind: PendingKind, val since: Long = System.currentTimeMillis())

data class UiState(
    val setupDone: Boolean = false,
    val termuxInstalled: Boolean = false,
    val bootInstalled: Boolean = false,
    val hasPermission: Boolean = false,
    val status: ServerStatus? = null,
    val ollamaUp: Boolean = false,
    val n8nUp: Boolean = false,
    val models: List<LocalModel> = emptyList(),
    val loaded: Set<String> = emptySet(),
    val pulls: Map<String, PullProgress> = emptyMap(),
    val device: DeviceStats = DeviceStats(),
    val pending: Pending? = null,
    val message: String? = null,
    val firstPollDone: Boolean = false,
) {
    val health: Health
        get() {
            val s = status
            pending?.let {
                return when (it.kind) {
                    PendingKind.INSTALL -> Health.INSTALLING
                    PendingKind.STOP -> Health.STOPPING
                    PendingKind.START -> Health.STARTING
                }
            }
            if (s == null) return Health.OFFLINE
            val wanted = listOf("ollama", "n8n", "tunnel").filter { s.enabled(it) }
            val n8nOk = !s.enabled("n8n") || n8nUp
            return if (wanted.all { s.running(it) } && n8nOk) Health.ONLINE else Health.DEGRADED
        }

    /** Public URL if there is a tunnel, else the local gateway. */
    val baseUrl: String get() = status?.publicUrl ?: Api.GATEWAY
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
    private val prefs = app.getSharedPreferences("pockethost", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(UiState(setupDone = prefs.getBoolean(KEY_SETUP, false)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private var chatJob: Job? = null
    private val pullJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(if (_ui.value.pending != null) 1500 else 3000)
            }
        }
    }

    suspend fun refresh() {
        val status = Api.status()
        val ollamaUp = status?.running("ollama") == true || Api.ollamaVersion() != null
        val n8nUp = status?.running("n8n") == true && n8nHealthy()
        val models = if (ollamaUp) Api.models() else emptyList()
        val loaded = if (ollamaUp) Api.loaded() else emptySet()
        _ui.update { s ->
            s.copy(
                termuxInstalled = Termux.isInstalled(ctx),
                bootInstalled = Termux.isInstalled(ctx, Termux.BOOT_PACKAGE),
                hasPermission = Termux.hasPermission(ctx),
                status = status,
                ollamaUp = ollamaUp,
                n8nUp = n8nUp,
                models = models,
                loaded = loaded,
                device = DeviceStats.read(ctx),
                pending = s.pending?.takeUnless { p -> p.isDone(status, n8nUp) },
                firstPollDone = true,
            )
        }
        // Once the gateway answers, the install obviously worked.
        if (status != null && !_ui.value.setupDone) markSetupDone()
    }

    private fun Pending.isDone(status: ServerStatus?, n8nUp: Boolean): Boolean {
        if (System.currentTimeMillis() - since > kind.timeoutMs) return true
        return when (kind) {
            PendingKind.STOP -> status == null
            PendingKind.INSTALL -> status != null
            PendingKind.START -> status != null &&
                listOf("ollama", "tunnel").filter { status.enabled(it) }.all { status.running(it) } &&
                (!status.enabled("n8n") || n8nUp)
        }
    }

    private suspend fun n8nHealthy(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val c = java.net.URL("${Api.N8N_LOCAL}/healthz").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 1000; c.readTimeout = 2000
            try { c.responseCode == 200 } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    // ---------- setup ----------

    fun markSetupDone(done: Boolean = true) {
        prefs.edit().putBoolean(KEY_SETUP, done).apply()
        _ui.update { it.copy(setupDone = done) }
    }

    fun install(autostart: Boolean, skipN8n: Boolean) {
        val r = Termux.run(ctx, Termux.installScript(ctx, autostart, skipN8n), background = false, label = "Install PocketHost")
        report(r) {
            _ui.update { it.copy(pending = Pending(PendingKind.INSTALL)) }
            viewModelScope.launch { delay(600); Termux.open(ctx) }
        }
    }

    // ---------- server control ----------

    fun start() = report(Termux.cli(ctx, "start")) {
        _ui.update { it.copy(pending = Pending(PendingKind.START)) }
        MonitorService.start(ctx)
    }

    fun stop() = report(Termux.cli(ctx, "stop")) {
        _ui.update { it.copy(pending = Pending(PendingKind.STOP)) }
        MonitorService.stop(ctx)
    }

    fun restart() = report(Termux.cli(ctx, "restart")) {
        _ui.update { it.copy(pending = Pending(PendingKind.START)) }
        MonitorService.start(ctx)
    }

    /** Applies config changes; restarts the server if it is running. */
    fun applyConfig(changes: Map<String, String>) {
        val cmds = changes.map { (k, v) -> "${Termux.CLI} config set ${Termux.shellQuote(k)} ${Termux.shellQuote(v)}" }
        val running = _ui.value.status != null
        val script = (cmds + if (running) listOf("${Termux.CLI} restart") else emptyList()).joinToString(" && ")
        report(Termux.run(ctx, script, true, "pockethost config")) {
            if (running) _ui.update { it.copy(pending = Pending(PendingKind.START)) }
            toast(if (running) "Saved. Restarting server..." else "Saved")
        }
    }

    fun setBoot(on: Boolean) = report(Termux.cli(ctx, "boot", if (on) "on" else "off")) {
        toast(if (on) "Will start on boot" else "Boot start disabled")
    }

    fun rotateKey() = report(Termux.cli(ctx, "rotate-key")) { toast("New API key generated. Update your clients.") }

    fun doctor() = report(Termux.cliVisible(ctx, "doctor"))

    fun logs(service: String) = report(Termux.cliVisible(ctx, "logs", service))

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
        if (!MODEL_NAME.matches(name)) return
        report(Termux.cli(ctx, "rm", name)) {
            toast("Deleting $name")
            viewModelScope.launch { delay(2500); refresh() }
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

    private inline fun report(r: Result<Unit>, onOk: () -> Unit = {}) {
        r.onSuccess { onOk() }.onFailure { toast(it.message ?: "Could not reach Termux") }
    }

    companion object {
        private const val KEY_SETUP = "setup_done"
        val MODEL_NAME = Regex("^[A-Za-z0-9._:/-]+$")
    }
}
