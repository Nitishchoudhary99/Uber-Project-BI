package dev.pockethost.ui

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
import dev.pockethost.Termux
import dev.pockethost.UiState

private val TUNNEL_MODES = listOf("quick" to "Quick (free)", "token" to "Own domain", "none" to "Off")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(ui: UiState, vm: MainViewModel, padding: PaddingValues) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val cfg = ui.status?.config.orEmpty()
    var mode by rememberSaveable { mutableStateOf(cfg["TUNNEL"] ?: "quick") }
    var token by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf(cfg["PUBLIC_URL"].orEmpty()) }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmReinstall by remember { mutableStateOf(false) }

    // Sync with the server once it reports its config.
    LaunchedEffect(cfg["TUNNEL"], cfg["PUBLIC_URL"]) {
        cfg["TUNNEL"]?.let { mode = it }
        cfg["PUBLIC_URL"]?.let { domain = it }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            SectionCard(title = "Internet access", subtitle = "How the world reaches your phone. Mobile data works too - no port forwarding needed.") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TUNNEL_MODES.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = mode == key, onClick = { mode = key },
                            shape = SegmentedButtonDefaults.itemShape(i, TUNNEL_MODES.size),
                        ) { Text(label, maxLines = 1) }
                    }
                }
                when (mode) {
                    "quick" -> Text("Random *.trycloudflare.com address, ready in seconds. Changes every restart.", style = MaterialTheme.typography.bodySmall)
                    "none" -> Text("Only apps on this phone can reach the server.", style = MaterialTheme.typography.bodySmall)
                    "token" -> {
                        Text(
                            "1. Cloudflare dashboard → Zero Trust → Networks → Tunnels → Create tunnel\n" +
                                "2. Add a public hostname (e.g. ai.yourdomain.com) → service http://127.0.0.1:8080\n" +
                                "3. Copy the tunnel token and paste it here",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { uri.openUri("https://one.dash.cloudflare.com/") }) { Text("Open Cloudflare") }
                        OutlinedTextField(
                            value = token, onValueChange = { token = it.trim() },
                            label = { Text(if (cfg["HAS_TUNNEL_TOKEN"] == "1") "Tunnel token (saved - paste to replace)" else "Tunnel token") },
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
                val tokenOk = mode != "token" || token.isNotEmpty() || cfg["HAS_TUNNEL_TOKEN"] == "1"
                val domainOk = mode != "token" || domain.isEmpty() || Regex("^https://[A-Za-z0-9.-]+/?$").matches(domain)
                Button(enabled = tokenOk && domainOk, onClick = {
                    val changes = linkedMapOf("TUNNEL" to mode)
                    if (mode == "token") {
                        if (token.isNotEmpty()) changes["CF_TUNNEL_TOKEN"] = token
                        changes["PUBLIC_URL"] = domain.trimEnd('/')
                    }
                    vm.applyConfig(changes)
                    token = ""
                }) { Text("Save & apply") }
            }
        }

        item {
            SectionCard(title = "Services") {
                ToggleRow("Ollama (AI models)", cfg["ENABLE_OLLAMA"] != "0") { vm.applyConfig(mapOf("ENABLE_OLLAMA" to if (it) "1" else "0")) }
                ToggleRow("n8n (automations)", cfg["ENABLE_N8N"] != "0") { vm.applyConfig(mapOf("ENABLE_N8N" to if (it) "1" else "0")) }
                ToggleRow("Start when phone boots", cfg["BOOT"] == "1") { on ->
                    if (on && !ui.bootInstalled) uri.openUri(Termux.BOOT_DOWNLOAD_URL)
                    vm.setBoot(on)
                }
                if (!ui.bootInstalled) {
                    Text("Boot start needs the Termux:Boot app (from the same store as Termux). Open it once after installing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            SectionCard(title = "Keep it running 24/7", subtitle = "Android aggressively kills background apps. Do all three.") {
                FilledTonalButton(onClick = { Termux.openAppSettings(ctx, Termux.PACKAGE) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Termux → Battery → Unrestricted")
                }
                FilledTonalButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow PocketHost in background") }
                Text("Android 12+: if services die after a few minutes, run this once from a computer with USB debugging:", style = MaterialTheme.typography.bodySmall)
                CodeBlock("adb shell \"settings put global settings_enable_monitor_phantom_procs false\"") { vm.toast("Copied") }
            }
        }

        item {
            SectionCard(title = "Security") {
                OutlinedButton(onClick = { confirmRotate = true }, modifier = Modifier.fillMaxWidth()) { Text("Rotate API key") }
            }
        }

        item {
            SectionCard(title = "Maintenance") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = vm::restart, modifier = Modifier.weight(1f)) { Text("Restart") }
                    FilledTonalButton(onClick = vm::doctor, modifier = Modifier.weight(1f)) { Text("Diagnose") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Termux.open(ctx) }, modifier = Modifier.weight(1f)) { Text("Open Termux") }
                    OutlinedButton(onClick = { confirmReinstall = true }, modifier = Modifier.weight(1f)) { Text("Update / repair") }
                }
                TextButton(onClick = { vm.markSetupDone(false) }) { Text("Run setup wizard again") }
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Text("PocketHost app ${BuildConfig.VERSION_NAME}" + (ui.status?.version?.let { " · server $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (confirmReinstall) {
        AlertDialog(
            onDismissRequest = { confirmReinstall = false },
            title = { Text("Update / repair PocketHost?") },
            text = { Text("Re-installs the server scripts and missing packages in Termux. Your models, n8n workflows and API key are kept.") },
            confirmButton = { TextButton(onClick = { vm.install(autostart = ui.status != null, skipN8n = cfg["ENABLE_N8N"] == "0"); confirmReinstall = false }) { Text("Run") } },
            dismissButton = { TextButton(onClick = { confirmReinstall = false }) { Text("Cancel") } },
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
