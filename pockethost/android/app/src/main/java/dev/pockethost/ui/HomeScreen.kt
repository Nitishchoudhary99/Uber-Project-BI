package dev.pockethost.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pockethost.DeviceStats
import dev.pockethost.Health
import dev.pockethost.MainViewModel
import dev.pockethost.UiState
import dev.pockethost.server.ServerState
import dev.pockethost.server.SvcInfo
import dev.pockethost.server.SvcStatus
import dev.pockethost.server.TunnelMode
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(ui: UiState, vm: MainViewModel, padding: PaddingValues, onOpenSettings: () -> Unit, onOpenConnect: () -> Unit) {
    val ctx = LocalContext.current
    val health = ui.health
    val s = ui.server
    var logsFor by remember { mutableStateOf<String?>(null) }

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

        if (!ui.engineBundled) {
            item { SectionCard(title = "AI engine missing", subtitle = "Install the APK built by GitHub Actions - it bundles Ollama and cloudflared.") {} }
        }

        item {
            SectionCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PowerButton(health) { if (s.active) vm.stop() else vm.start() }
                    Text(
                        when (health) {
                            Health.ONLINE -> "Your phone is serving AI to the internet"
                            Health.DEGRADED -> "Running, but something needs attention"
                            Health.STARTING -> "Starting the engine..."
                            Health.OFFLINE -> "Tap to start your server"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    s.startedAt?.let { Text("Up ${formatUptime((System.currentTimeMillis() - it) / 1000)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }

        s.publicUrl?.let { url ->
            item {
                SectionCard(
                    title = "Public URL",
                    subtitle = if (ui.settings.tunnelMode == TunnelMode.QUICK) "Free quick tunnel - changes on every restart" else "Your permanent domain",
                ) {
                    CopyField("Works from anywhere with your API key", url) { vm.toast("URL copied") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { shareText(ctx, "My PocketHost AI server (OpenAI-compatible):\n$url/v1\nAPI key: ${ui.apiKey}") }) {
                            Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Text("  Share")
                        }
                        TextButton(onClick = onOpenConnect) { Text("How to connect") }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Services") {
                ServiceRow("Ollama", "AI engine · 127.0.0.1:11434", s.services[ServerState.OLLAMA], s.active) { logsFor = ServerState.OLLAMA }
                ServiceRow("Gateway", "API key auth · 127.0.0.1:8080", s.services[ServerState.GATEWAY], s.active) { logsFor = ServerState.GATEWAY }
                ServiceRow("Tunnel", "Cloudflare → internet", s.services[ServerState.TUNNEL], s.active) { logsFor = ServerState.TUNNEL }
                Text("Tap a service to see its logs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { DeviceCard(ui.device) }

        if (ui.settings.tunnelMode == TunnelMode.QUICK && health == Health.ONLINE) {
            item {
                SectionCard(title = "Want a permanent address?", subtitle = "Connect a free Cloudflare tunnel to your own domain, e.g. ai.yourname.com") {
                    TextButton(onClick = onOpenSettings) { Text("Set up in Settings") }
                }
            }
        }
    }

    logsFor?.let { name -> LogsDialog(name, vm) { logsFor = null } }
}

@Composable
private fun LogsDialog(name: String, vm: MainViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf(vm.logs(name)) }
    val list = rememberLazyListState()
    LaunchedEffect(name) {
        while (true) {
            lines = vm.logs(name)
            if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
            delay(1000)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${name.replaceFirstChar { it.uppercase() }} logs") },
        text = {
            LazyColumn(state = list, modifier = Modifier.heightIn(max = 420.dp)) {
                if (lines.isEmpty()) item { Text("No output yet.") }
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = { shareText(ctx, lines.joinToString("\n")) }) { Text("Share") }
        },
    )
}

@Composable
private fun PowerButton(health: Health, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    val busy = health == Health.STARTING
    val color = health.color()
    Box(
        Modifier
            .size(132.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(3.dp, color.copy(alpha = if (busy) pulse else 1f), CircleShape)
            .clickable(onClick = onClick),
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
private fun ServiceRow(name: String, detail: String, info: SvcInfo?, active: Boolean, onClick: () -> Unit) {
    val st = if (active) info?.status ?: SvcStatus.STARTING else SvcStatus.STOPPED
    val (color, label) = when (st) {
        SvcStatus.RUNNING -> Brand.Green to "Running"
        SvcStatus.STARTING -> Brand.Amber to "Starting"
        SvcStatus.CRASHED -> Brand.Red to "Error"
        SvcStatus.DISABLED -> Brand.Grey to "Off"
        SvcStatus.STOPPED -> Brand.Grey to "Stopped"
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(color)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                info?.detail?.takeIf { active } ?: detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (st == SvcStatus.CRASHED) Brand.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
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
