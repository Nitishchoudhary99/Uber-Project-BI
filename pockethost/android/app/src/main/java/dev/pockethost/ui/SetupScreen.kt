package dev.pockethost.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pockethost.MainViewModel
import dev.pockethost.UiState

/** First-run welcome. Everything is inside this app, so it only asks for two Android permissions. */
@SuppressLint("BatteryLife")
@Composable
fun SetupScreen(ui: UiState, vm: MainViewModel, padding: PaddingValues) {
    val ctx = LocalContext.current
    fun notifGranted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var notif by remember { mutableStateOf(notifGranted()) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = notifGranted() }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 12.dp)) {
                Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(8.dp))
                Text("Your phone is now an AI server", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ollama, a secure gateway and a Cloudflare tunnel are built into this app. " +
                        "No other apps, no PC, no root, no port forwarding.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!ui.engineBundled) {
            item {
                SectionCard(title = "This build has no AI engine", subtitle = "Install the APK from GitHub Actions: it includes Ollama and cloudflared. Local Android Studio builds need pockethost/native/build.sh first.") {}
            }
        }

        item {
            Step("Show server status", "A notification shows the live status and public URL, and lets you stop the server.", notif) {
                Button(onClick = { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Allow notifications")
                }
            }
        }

        item {
            Step("Keep running in the background", "Stops Android from putting the server to sleep when the screen is off.", ui.batteryUnrestricted) {
                FilledTonalButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                }) { Text("Allow") }
            }
        }

        item {
            Button(
                onClick = { vm.finishSetup(); vm.start() },
                enabled = ui.engineBundled,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Start my server") }
        }
        item {
            Text(
                "Tip: keep the phone on charge. First start takes ~10 seconds, then download a model from the Models tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(title: String, body: String, done: Boolean, content: @Composable () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (done) "Done" else "To do",
                tint = if (done) Brand.Green else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!done) content()
            }
        }
    }
}
