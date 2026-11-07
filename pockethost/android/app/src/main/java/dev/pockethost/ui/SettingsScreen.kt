package dev.pockethost.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.pockethost.BuildConfig
import dev.pockethost.MainViewModel
import dev.pockethost.UiState
import dev.pockethost.server.TunnelMode

private val TUNNEL_MODES = listOf(TunnelMode.QUICK to "Quick (free)", TunnelMode.TOKEN to "Own domain", TunnelMode.NONE to "Off")
private val KEEP_ALIVE = listOf("1m", "5m", "30m", "-1")

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(ui: UiState, vm: MainViewModel, padding: PaddingValues) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val cfg = ui.settings
    var mode by rememberSaveable { mutableStateOf(cfg.tunnelMode) }
    var token by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf(cfg.publicUrl) }
    var confirmRotate by remember { mutableStateOf(false) }

    LaunchedEffect(cfg.tunnelMode, cfg.publicUrl) { mode = cfg.tunnelMode; domain = cfg.publicUrl }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            SectionCard(title = "Internet access", subtitle = "How the world reaches your phone. Works on Wi-Fi and mobile data, no port forwarding.") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TUNNEL_MODES.forEachIndexed { i, (m, label) ->
                        SegmentedButton(
                            selected = mode == m, onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(i, TUNNEL_MODES.size),
                        ) { Text(label, maxLines = 1) }
                    }
                }
                when (mode) {
                    TunnelMode.QUICK -> Text("Random *.trycloudflare.com address, ready in seconds, no account. Changes every restart.", style = MaterialTheme.typography.bodySmall)
                    TunnelMode.NONE -> Text("Only apps on this phone can reach the server.", style = MaterialTheme.typography.bodySmall)
                    TunnelMode.TOKEN -> {
                        Text(
                            "1. Cloudflare dashboard → Zero Trust → Networks → Tunnels → Create tunnel (Cloudflared)\n" +
                                "2. Public hostname, e.g. ai.yourdomain.com → service HTTP 127.0.0.1:8080\n" +
                                "3. Copy the token from the install command and paste it here",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { uri.openUri("https://one.dash.cloudflare.com/") }) { Text("Open Cloudflare") }
                        OutlinedTextField(
                            value = token, onValueChange = { token = it.trim() },
                            label = { Text(if (cfg.hasToken) "Tunnel token (saved - paste to replace)" else "Tunnel token") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = domain, onValueChange = { domain = it.trim() },
                            label = { Text("Public URL, e.g. https://ai.yourdomain.com") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val tokenOk = mode != TunnelMode.TOKEN || token.isNotEmpty() || cfg.hasToken
                val domainOk = mode != TunnelMode.TOKEN || domain.isEmpty() || Regex("^https://[A-Za-z0-9.-]+/?$").matches(domain)
                Button(enabled = tokenOk && domainOk, onClick = { vm.saveTunnel(mode, token, domain); token = "" }) { Text("Save & apply") }
            }
        }

        item {
            SectionCard(title = "Engine") {
                Text("Keep model in RAM after last request", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KEEP_ALIVE.forEach { v ->
                        FilterChip(selected = cfg.keepAlive == v, onClick = { vm.setKeepAlive(v) }, label = { Text(if (v == "-1") "Always" else v) })
                    }
                }
                Text("Shorter frees RAM for other apps; longer answers faster.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            SectionCard(title = "Keep it running 24/7") {
                ToggleRow("Start server when phone boots", cfg.autoStart) { vm.setAutoStart(it) }
                if (!ui.batteryUnrestricted) {
                    FilledTonalButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Allow running in background") }
                } else {
                    Text("✓ Background running allowed", color = Brand.Green, style = MaterialTheme.typography.bodyMedium)
                }
                Text("On some phones (Xiaomi, Oppo, Vivo, Samsung) also enable \"Autostart\" and lock the app in recent apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            SectionCard(title = "Security") {
                CopyField("API key", ui.apiKey, secret = true) { vm.toast("API key copied") }
                OutlinedButton(onClick = { confirmRotate = true }, modifier = Modifier.fillMaxWidth()) { Text("Rotate API key") }
            }
        }

        item {
            SectionCard(title = "Maintenance") {
                FilledTonalButton(onClick = vm::restart, modifier = Modifier.fillMaxWidth()) { Text("Restart server") }
                TextButton(onClick = { vm.finishSetup(false) }) { Text("Show welcome screen again") }
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Text("PocketHost ${BuildConfig.VERSION_NAME} · Ollama ${BuildConfig.OLLAMA_VERSION} · cloudflared ${BuildConfig.CLOUDFLARED_VERSION}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("Rotate API key?") },
            text = { Text("Every app, script and person using the old key loses access immediately.") },
            confirmButton = { TextButton(onClick = { vm.rotateKey(); confirmRotate = false }) { Text("Rotate") } },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
