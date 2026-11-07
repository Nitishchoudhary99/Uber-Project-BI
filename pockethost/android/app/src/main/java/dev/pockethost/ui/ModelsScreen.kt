package dev.pockethost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.pockethost.CatalogModel
import dev.pockethost.MODEL_CATALOG
import dev.pockethost.MainViewModel
import dev.pockethost.PullProgress
import dev.pockethost.UiState

@Composable
fun ModelsScreen(ui: UiState, vm: MainViewModel, padding: PaddingValues) {
    var custom by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val installed = ui.models.map { it.name }.toSet()
    val ramGb = ui.device.ramTotalGb

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Models", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp)) }

        if (!ui.ollamaUp) {
            item { SectionCard(title = "Ollama is not running", subtitle = "Start the server on the Home tab to download and manage models.") {} }
        }

        item {
            SectionCard(title = "On this phone", subtitle = if (ui.models.isEmpty()) "No models yet - pick one below." else "${ui.models.size} installed") {
                ui.models.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(formatBytes(m.sizeBytes), m.parameterSize, m.quantization, if (m.name in ui.loaded) "loaded in RAM" else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { confirmDelete = m.name }) { Icon(Icons.Rounded.Delete, "Delete ${m.name}") }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Any model from ollama.com", subtitle = "e.g. mistral, deepseek-r1:1.5b, llava") {
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it.trim() },
                    singleLine = true, label = { Text("Model name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.pull(custom); custom = "" }),
                    trailingIcon = {
                        IconButton(enabled = custom.isNotBlank(), onClick = { vm.pull(custom); custom = "" }) { Icon(Icons.Rounded.Download, "Download") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ui.pulls.filterKeys { k -> MODEL_CATALOG.none { it.name == k } }.forEach { (name, p) -> PullRow(name, p) { vm.cancelPull(name) } }
            }
        }

        item {
            Text(
                "Recommended for your %.0f GB phone".format(ramGb),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(MODEL_CATALOG.sortedBy { if (it.minRamGb <= ramGb + 0.5) 0 else 1 }, key = { it.name }) { m ->
            CatalogRow(m, ramGb, installed = m.name in installed || "${m.name}:latest" in installed, progress = ui.pulls[m.name],
                onPull = { vm.pull(m.name) }, onCancel = { vm.cancelPull(m.name) })
        }
    }

    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete $name?") },
            text = { Text("Frees its storage. You can download it again later.") },
            confirmButton = { TextButton(onClick = { vm.deleteModel(name); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CatalogRow(m: CatalogModel, ramGb: Double, installed: Boolean, progress: PullProgress?, onPull: () -> Unit, onCancel: () -> Unit) {
    val fits = m.minRamGb <= ramGb + 0.5
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium)
                Text(m.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${m.name} · ~%.1f GB · ".format(m.sizeGb) + if (fits) "fits your phone" else "needs ${m.minRamGb} GB RAM",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fits) Brand.Green else Brand.Amber,
                )
            }
            when {
                installed -> Text("Installed", style = MaterialTheme.typography.labelLarge, color = Brand.Green)
                progress == null -> FilledTonalIconButton(onClick = onPull) { Icon(Icons.Rounded.Download, "Download ${m.title}") }
            }
        }
        if (progress != null) PullRow(m.name, progress, onCancel)
    }
}

@Composable
private fun PullRow(name: String, p: PullProgress, onCancel: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$name · ${p.status}" + (p.fraction?.let { " · %.0f%%".format(it * 100) } ?: ""),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel download") }
        }
        val f = p.fraction
        if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
