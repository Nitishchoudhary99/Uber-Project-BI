package dev.pockethost.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.pockethost.MainViewModel
import dev.pockethost.Termux
import dev.pockethost.UiState

@Composable
fun SetupScreen(ui: UiState, vm: MainViewModel, padding: PaddingValues) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    var allowedDone by rememberSaveable { mutableStateOf(false) }
    var withN8n by rememberSaveable { mutableStateOf(true) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Termux.openAppSettings(ctx, ctx.packageName)
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 12.dp)) {
                Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(8.dp))
                Text("Turn this phone into an AI server", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ollama models, n8n automations and a secure public URL - running on your own phone. " +
                        "Four steps, about 15 minutes, no PC needed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Step(1, "Install Termux", "The Linux engine PocketHost runs on. Get it from F-Droid (the Play Store version is outdated).", ui.termuxInstalled) {
                if (!ui.termuxInstalled) {
                    Button(onClick = { uri.openUri(Termux.DOWNLOAD_URL) }) { Text("Get Termux") }
                    Text("After installing, open Termux once and wait for it to finish setting up.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Step(2, "Allow PocketHost to control Termux", "Android permission so this app can start and stop the server.", ui.hasPermission) {
                if (!ui.hasPermission) {
                    Button(enabled = ui.termuxInstalled, onClick = { permLauncher.launch(Termux.PERMISSION) }) { Text("Grant permission") }
                }
            }
        }

        item {
            Step(3, "Enable app control inside Termux", "Copy this line, paste it in Termux and press Enter.", allowedDone) {
                CopyField("Command", Termux.ALLOW_COMMAND) { vm.toast("Copied - now paste it in Termux") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = ui.termuxInstalled, onClick = { Termux.open(ctx) }) { Text("Open Termux") }
                    TextButton(onClick = { allowedDone = true }) { Text("I've done it") }
                }
            }
        }

        item {
            val ready = ui.termuxInstalled && ui.hasPermission && allowedDone
            Step(4, "Install the server", "Installs Ollama, n8n, Cloudflare tunnel and the PocketHost gateway. Keep the phone on charge.", ui.status != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withN8n, onCheckedChange = { withN8n = it })
                    Text("Include n8n (+1 GB, slower install)")
                }
                Button(enabled = ready, onClick = { vm.install(autostart = true, skipN8n = !withN8n) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Install & start")
                }
                if (!ready) Text("Finish steps 1-3 first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Termux opens and shows progress. Nothing happens? In Android settings give Termux \"Display over other apps\" and tap again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            TextButton(onClick = { vm.markSetupDone() }, modifier = Modifier.fillMaxWidth()) {
                Text("PocketHost is already installed - go to dashboard")
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, body: String, done: Boolean, content: @Composable () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (done) "Done" else "To do",
                tint = if (done) Brand.Green else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$n. $title", style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!done) content()
            }
        }
    }
}
