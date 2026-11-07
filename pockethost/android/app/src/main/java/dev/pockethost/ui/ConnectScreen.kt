package dev.pockethost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pockethost.MainViewModel
import dev.pockethost.UiState

@Composable
fun ConnectScreen(ui: UiState, vm: MainViewModel, padding: PaddingValues) {
    val base = ui.baseUrl
    val key = ui.apiKey
    val model = ui.models.firstOrNull()?.name ?: "gemma3:1b"
    val copied: (String) -> Unit = { vm.toast("$it copied") }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Connect", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp)) }

        if (!ui.server.active) {
            item { SectionCard(title = "Server is offline", subtitle = "Start it on the Home tab to get your live public URL.") {} }
        } else if (ui.server.publicUrl == null) {
            item { SectionCard(title = "No public URL yet", subtitle = "The tunnel is starting or turned off (Settings). Until then these work only on this phone.") {} }
        }

        item {
            SectionCard(title = "OpenAI-compatible API", subtitle = "Works with any app or SDK that speaks OpenAI: n8n, LangChain, Flowise, Open WebUI, Cursor, Continue, your own code.") {
                CopyField("Base URL", "$base/v1", onCopied = copied)
                CopyField("API key", key, secret = true, onCopied = copied)
                CopyField("Model", model, onCopied = copied)
            }
        }

        item {
            SectionCard(title = "n8n (cloud, Docker or PC)", subtitle = "Use your phone as the AI brain for any n8n instance.") {
                Text(
                    "Credentials → New → OpenAI\n" +
                        "• API Key: your key above\n" +
                        "• Base URL: $base/v1\n" +
                        "Then use the OpenAI Chat Model / AI Agent nodes with model \"$model\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            SectionCard(title = "Test from any computer") {
                CodeBlock(
                    """
                    curl $base/v1/chat/completions \
                      -H "Authorization: Bearer $key" \
                      -H "Content-Type: application/json" \
                      -d '{"model":"$model","messages":[{"role":"user","content":"Hello from my phone server!"}]}'
                    """.trimIndent(),
                ) { vm.toast("Copied") }
            }
        }

        item {
            SectionCard(title = "Python") {
                CodeBlock(
                    """
                    from openai import OpenAI
                    client = OpenAI(base_url="$base/v1", api_key="$key")
                    r = client.chat.completions.create(
                        model="$model",
                        messages=[{"role": "user", "content": "Hi!"}],
                    )
                    print(r.choices[0].message.content)
                    """.trimIndent(),
                ) { vm.toast("Copied") }
            }
        }

        item {
            SectionCard(title = "Native Ollama API", subtitle = "For tools with an Ollama integration. Send the key as a Bearer token.") {
                CopyField("Ollama URL", base, onCopied = copied)
                Text("Apps on this phone can also use http://127.0.0.1:11434 directly, no key needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            SectionCard(title = "Security") {
                Text(
                    "• Every request from the internet needs your API key.\n" +
                        "• The AI engine only listens on 127.0.0.1 - the gateway is the only door.\n" +
                        "• HTTPS end-to-end through Cloudflare. Your IP address is never exposed.\n" +
                        "• Leaked the key? Settings → Rotate API key.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
