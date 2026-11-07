package dev.pockethost.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pockethost.Api
import dev.pockethost.DeviceStats
import dev.pockethost.Health
import dev.pockethost.MainViewModel
import dev.pockethost.UiState

@Composable
fun HomeScreen(ui: UiState, vm: MainViewModel, padding: PaddingValues, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val health = ui.health
    val st = ui.status

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("PocketHost", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Pill(health.label(), health.color())
            }
        }

        if (!ui.hasPermission && ui.firstPollDone) {
            item {
                SectionCard(title = "Termux access missing", subtitle = "PocketHost cannot start or stop the server.") {
                    Button(onClick = { vm.markSetupDone(false) }) { Text("Open setup") }
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PowerButton(health) {
                        when (health) {
                            Health.ONLINE, Health.DEGRADED, Health.STARTING -> vm.stop()
                            Health.OFFLINE -> if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
                            else -> Unit
                        }
                    }
                    Text(
                        when (health) {
                            Health.ONLINE -> "Your phone is serving AI to the internet"
                            Health.DEGRADED -> "Running, but some services are down"
                            Health.STARTING -> "Starting services... n8n can take up to 2 minutes"
                            Health.STOPPING -> "Stopping..."
                            Health.INSTALLING -> "Installing in Termux - watch progress there"
                            Health.OFFLINE -> "Tap to start your server"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (st != null) Text("v${st.version} · up ${formatUptime(st.uptimeSec)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (st?.publicUrl != null) {
            item {
                val url = st.publicUrl
                SectionCard(title = "Public URL", subtitle = if (st.config["TUNNEL"] == "quick") "Free quick tunnel - changes on every restart" else "Your permanent domain") {
                    CopyField("Anyone with the API key can use it", url) { vm.toast("URL copied") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { shareText(ctx, "My PocketHost AI server:\n$url/ollama/v1\nAPI key: ${st.apiKey.orEmpty()}") }) {
                            Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Text("  Share")
                        }
                        if (st.running("n8n")) {
                            OutlinedButton(onClick = { uri.openUri("$url/?key=${st.apiKey.orEmpty()}") }) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Text("  Open n8n")
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Services") {
                ServiceRow("Ollama", "AI models · :11434", st?.enabled("ollama") != false, ui.ollamaUp) { vm.logs("ollama") }
                ServiceRow("n8n", "Automations · :5678", st?.enabled("n8n") != false, ui.n8nUp, starting = st?.running("n8n") == true && !ui.n8nUp) { vm.logs("n8n") }
                ServiceRow("Gateway", "API key auth · :8080", true, st != null) { vm.logs("gateway") }
                ServiceRow("Tunnel", "Cloudflare → internet", st?.enabled("tunnel") != false, st?.running("tunnel") == true) { vm.logs("tunnel") }
                Text("Tap a service to see its logs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (st == null && !ui.ollamaUp) {
                    TextButton(onClick = { uri.openUri(Api.GATEWAY + "/health") }) { Text("Local gateway: ${Api.GATEWAY}") }
                }
            }
        }

        item { DeviceCard(ui.device) }

        if (st?.config?.get("TUNNEL") == "quick" && health == Health.ONLINE) {
            item {
                SectionCard(title = "Want a permanent address?", subtitle = "Connect a free Cloudflare tunnel with your own domain, e.g. ai.yourname.com") {
                    TextButton(onClick = onOpenSettings) { Text("Set up in Settings") }
                }
            }
        }
    }
}

@Composable
private fun PowerButton(health: Health, onClick: () -> Unit) {
    val busy = health == Health.STARTING || health == Health.STOPPING || health == Health.INSTALLING
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    val color = health.color()
    Box(
        Modifier
            .size(132.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(3.dp, color.copy(alpha = if (busy) pulse else 1f), CircleShape)
            .clickable(enabled = health != Health.STOPPING && health != Health.INSTALLING, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.PowerSettingsNew,
            contentDescription = if (health == Health.OFFLINE) "Start server" else "Stop server",
            tint = color,
            modifier = Modifier.size(64.dp).alpha(if (busy) pulse else 1f),
        )
    }
}

@Composable
private fun ServiceRow(name: String, detail: String, enabled: Boolean, up: Boolean, starting: Boolean = false, onClick: () -> Unit) {
    val color = when {
        !enabled -> Brand.Grey
        up -> Brand.Green
        starting -> Brand.Amber
        else -> Brand.Red
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(color)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
        Text(
            when { !enabled -> "Off"; up -> "Running"; starting -> "Booting"; else -> "Stopped" },
            style = MaterialTheme.typography.labelLarge, color = color,
        )
    }
}

@Composable
private fun DeviceCard(d: DeviceStats) {
    SectionCard(title = "This phone") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(
                if (d.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                if (d.batteryPct >= 0) "${d.batteryPct}%" else "-",
                if (d.charging) "Charging" else "On battery",
                if (!d.charging && d.batteryPct in 0..20) Brand.Red else null,
                Modifier.weight(1f),
            )
            Stat(
                Icons.Rounded.Thermostat,
                d.batteryTempC?.let { "%.0f°C".format(it) } ?: "-",
                if (d.hot) "Hot - throttling" else "Temperature",
                if (d.hot) Brand.Amber else null,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(Icons.Rounded.Memory, "%.1f GB".format(d.ramAvailMb / 1024.0), "free of %.0f GB RAM".format(d.ramTotalGb), null, Modifier.weight(1f))
            Stat(Icons.Rounded.SdStorage, "%.0f GB".format(d.storageFreeGb), "storage free", if (d.storageFreeGb < 3) Brand.Amber else null, Modifier.weight(1f))
        }
        if (!d.charging) {
            Text("Tip: keep the phone plugged in while serving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, value: String, label: String, warn: Color?, modifier: Modifier) {
    Row(
        modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = warn ?: MaterialTheme.colorScheme.primary)
        Column {
            Text(value, style = MaterialTheme.typography.titleMedium, color = warn ?: Color.Unspecified)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private fun formatUptime(s: Long): String = when {
    s < 60 -> "${s}s"
    s < 3600 -> "${s / 60}m"
    s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
    else -> "${s / 86400}d ${(s % 86400) / 3600}h"
}
