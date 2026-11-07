package dev.pockethost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps a live status notification (with the public URL) while the server runs. */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        if (intent?.action == ACTION_STOP_SERVER) {
            Termux.cli(this, "stop")
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, build("Starting server...", null), type)
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        var misses = 0
        val nm = getSystemService(NotificationManager::class.java)
        while (scope.isActive) {
            val s = Api.status()
            if (s == null) {
                // Give a slow boot a few minutes before giving up.
                if (++misses >= 24) { stopSelf(); return }
                nm.notify(NOTIF_ID, build("Waiting for server...", null))
            } else {
                misses = 0
                val up = listOf("ollama", "n8n", "tunnel").filter { s.enabled(it) && s.running(it) }
                val text = if (up.isEmpty()) "Gateway online" else "Online · " + up.joinToString(" · ")
                nm.notify(NOTIF_ID, build(text, s.publicUrl))
            }
            delay(10_000)
        }
    }

    private fun build(title: String, url: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP_SERVER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("PocketHost · $title")
            .setContentText(url ?: "Serving on this phone")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop server", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Server status", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP_SERVER = "dev.pockethost.STOP_SERVER"

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}
