package dev.pockethost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pockethost.ChatState
import dev.pockethost.MainViewModel
import dev.pockethost.UiState

@Composable
fun ChatScreen(ui: UiState, chat: ChatState, vm: MainViewModel, padding: PaddingValues) {
    var input by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val model = chat.model ?: ui.models.firstOrNull()?.name

    LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.content?.length) {
        if (chat.messages.isNotEmpty()) list.scrollToItem(chat.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chat", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }, enabled = ui.models.isNotEmpty()) {
                    Text(model ?: "No model"); Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ui.models.forEach { m ->
                        DropdownMenuItem(text = { Text(m.name) }, onClick = { vm.selectModel(m.name); menu = false })
                    }
                }
            }
            IconButton(onClick = vm::clearChat, enabled = chat.messages.isNotEmpty()) { Icon(Icons.Rounded.DeleteSweep, "Clear chat") }
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (chat.messages.isEmpty()) {
                item {
                    Text(
                        when {
                            !ui.ollamaUp -> "Start the server to chat with your models."
                            ui.models.isEmpty() -> "Download a model in the Models tab first."
                            else -> "Everything runs on this phone. Nothing leaves it.\nAsk anything to test $model."
                        },
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            }
            itemsIndexed(chat.messages) { i, m ->
                val mine = m.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = 320.dp),
                    ) {
                        val typing = chat.streaming && i == chat.messages.lastIndex && m.content.isEmpty()
                        Text(if (typing) "…" else m.content, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
            chat.stats?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            chat.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Message") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            if (chat.streaming) {
                FilledIconButton(onClick = vm::stopChat) { Icon(Icons.Rounded.Stop, "Stop") }
            } else {
                FilledIconButton(enabled = input.isNotBlank() && ui.ollamaUp, onClick = { vm.send(input); input = "" }) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                }
            }
        }
    }
}
