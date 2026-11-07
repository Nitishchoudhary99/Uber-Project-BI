package dev.pockethost.server

import android.annotation.SuppressLint
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.pockethost.Api
import dev.pockethost.MainActivity
import dev.pockethost.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs the whole server inside this app: the bundled Ollama engine, the
 * gateway and the Cloudflare tunnel, supervised and kept awake by a
 * foreground service.
 */
class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobs = mutableListOf<Job>()
    private var ollama: ManagedProcess? = null
    private var tunnel: ManagedProcess? = null
    private var gateway: Gateway? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        when (intent?.action) {
            ACTION_STOP -> {
                runCatching { goForeground() }
                AppConfig(this).wantRunning = false
                shutdown()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                goForeground()
                shutdown()
                launchAll()
            }
            else -> {
                goForeground()
                if (!running) launchAll()
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting...", null), type)
    }

    @SuppressLint("WakelockTimeout")
    private fun launchAll() {
        running = true
        val cfg = AppConfig(this)
        cfg.wantRunning = true
        ServerState.begin()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketHost::server").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PocketHost::wifi").apply { acquire() }

        val bin = applicationInfo.nativeLibraryDir
        val home = File(filesDir, "home").apply { mkdirs() }
        val models = modelsDir(this).apply { mkdirs() }
        val tmp = File(cacheDir, "tmp").apply { mkdirs() }
        // LD_LIBRARY_PATH: the engines load libc++_shared.so bundled next to them.
        val baseEnv = mapOf("HOME" to home.path, "TMPDIR" to tmp.path, "LD_LIBRARY_PATH" to bin)

        // 1. Gateway
        try {
            gateway = Gateway(GATEWAY_PORT, OLLAMA_PORT) { AppConfig(this).apiKey }.also { it.start() }
            ServerState.set(ServerState.GATEWAY, SvcStatus.RUNNING)
        } catch (e: Exception) {
            ServerState.log(ServerState.GATEWAY, "failed: ${e.message}")
            ServerState.set(ServerState.GATEWAY, SvcStatus.CRASHED, "port $GATEWAY_PORT busy")
        }

        // 2. Ollama
        val ollamaExe = "$bin/libollama.so"
        ManagedProcess.killOrphans(ollamaExe)
        ollama = ManagedProcess(
            name = ServerState.OLLAMA,
            command = listOf(ollamaExe, "serve"),
            env = baseEnv + mapOf(
                "OLLAMA_HOST" to "127.0.0.1:$OLLAMA_PORT",
                "OLLAMA_MODELS" to models.path,
                "OLLAMA_KEEP_ALIVE" to cfg.keepAlive,
                "OLLAMA_NUM_PARALLEL" to "1",
                "OLLAMA_MAX_LOADED_MODELS" to "1",
                "OLLAMA_NOPRUNE" to "1",
            ),
            workDir = home,
            onStart = { ServerState.set(ServerState.OLLAMA, SvcStatus.STARTING) },
        ).also { it.start() }
        jobs += scope.launch {
            while (isActive) {
                val st = ServerState.snapshot.value.status(ServerState.OLLAMA)
                val up = Api.ollamaVersion() != null
                if (up && st != SvcStatus.RUNNING) ServerState.set(ServerState.OLLAMA, SvcStatus.RUNNING)
                if (!up && st == SvcStatus.RUNNING) ServerState.set(ServerState.OLLAMA, SvcStatus.STARTING)
                delay(if (up) 5_000 else 1_000)
            }
        }

        // 3. Tunnel
        startTunnel(cfg, "$bin/libcloudflared.so", baseEnv, home)

        // Keep the notification in sync.
        jobs += scope.launch {
            ServerState.snapshot.collectLatest { s ->
                if (!s.active) return@collectLatest
                val up = ServerState.SERVICES.filter { s.status(it) == SvcStatus.RUNNING }
                val title = when {
                    s.status(ServerState.OLLAMA) == SvcStatus.RUNNING && up.size == ServerState.SERVICES.size -> "Online"
                    s.status(ServerState.OLLAMA) == SvcStatus.RUNNING -> "Running"
                    else -> "Starting..."
                }
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(title, s.publicUrl))
            }
        }
    }

    private fun startTunnel(cfg: AppConfig, exe: String, baseEnv: Map<String, String>, home: File) {
        when (cfg.tunnelMode) {
            TunnelMode.NONE -> {
                ServerState.set(ServerState.TUNNEL, SvcStatus.DISABLED, "local only")
                return
            }
            TunnelMode.TOKEN -> if (cfg.tunnelToken.isBlank()) {
                ServerState.set(ServerState.TUNNEL, SvcStatus.CRASHED, "no tunnel token set")
                return
            }
            TunnelMode.QUICK -> Unit
        }
        val quick = cfg.tunnelMode == TunnelMode.QUICK
        val args = mutableListOf(exe, "tunnel", "--no-autoupdate", "--protocol", "http2")
        if (quick) args += listOf("--url", "http://127.0.0.1:$GATEWAY_PORT") else args += "run"
        val env = baseEnv + if (quick) emptyMap() else mapOf("TUNNEL_TOKEN" to cfg.tunnelToken)
        val urlRe = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
        tunnel = ManagedProcess(
            name = ServerState.TUNNEL,
            command = args,
            env = env,
            workDir = home,
            onStart = {
                ServerState.set(ServerState.TUNNEL, SvcStatus.STARTING)
                if (quick) ServerState.setPublicUrl(null)
            },
            onLine = { line ->
                if (quick) {
                    urlRe.find(line)?.let { ServerState.setPublicUrl(it.value) }
                }
                if (line.contains("Registered tunnel connection")) {
                    ServerState.set(ServerState.TUNNEL, SvcStatus.RUNNING)
                    if (!quick) ServerState.setPublicUrl(cfg.publicUrl.ifBlank { null })
                }
            },
            onExit = { ServerState.setPublicUrl(null) },
        ).also { it.start() }
    }

    private fun shutdown() {
        if (!running) return
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        tunnel?.stop(); tunnel = null
        ollama?.stop(); ollama = null
        gateway?.stop(); gateway = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        ServerState.end()
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(title: String, url: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("PocketHost · $title")
            .setContentText(url ?: "AI server on this phone")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop server", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Server status", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val GATEWAY_PORT = 8080
        const val OLLAMA_PORT = 11434
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "dev.pockethost.STOP"
        private const val ACTION_RESTART = "dev.pockethost.RESTART"

        fun modelsDir(ctx: Context) = File(ctx.filesDir, "models")

        fun engineBundled(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libollama.so").exists()

        fun start(ctx: Context) = send(ctx, null)
        fun restart(ctx: Context) = send(ctx, ACTION_RESTART)
        fun stop(ctx: Context) {
            AppConfig(ctx).wantRunning = false
            if (ServerState.snapshot.value.active) send(ctx, ACTION_STOP)
        }

        private fun send(ctx: Context, action: String?) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ServerService::class.java).setAction(action))
            }.onFailure { ServerState.log(ServerState.GATEWAY, "could not start service: ${it.message}") }
        }
    }
}
