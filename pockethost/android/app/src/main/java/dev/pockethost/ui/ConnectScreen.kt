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
    val st = ui.status
    val base = ui.baseUrl
    val key = st?.apiKey ?: "<your API key>"
    val model = ui.models.firstOrNull()?.name ?: "gemma3:1b"
    val copied: (String) -> Unit = { vm.toast("$it copied") }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Connect", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp)) }

        if (st == null) {
            item { SectionCard(title = "Server is offline", subtitle = "Start it on the Home tab to get your live endpoints.") {} }
        } else if (st.publicUrl == null) {
            item { SectionCard(title = "Local only", subtitle = "The tunnel is off, so these work only on this phone. Turn it on in Settings.") {} }
        }

        item {
            SectionCard(title = "OpenAI-compatible API", subtitle = "Works with any app or SDK that supports OpenAI: LangChain, Flowise, Open WebUI, Cursor, Continue, your own code.") {
                CopyField("Base URL", "$base/ollama/v1", onCopied = copied)
                CopyField("API key", key, secret = st?.apiKey != null, onCopied = copied)
                CopyField("Model", model, onCopied = copied)
            }
        }

        item {
            SectionCard(title = "Test from any computer") {
                CodeBlock(
                    """
                    curl $base/ollama/v1/chat/completions \
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
                    client = OpenAI(base_url="$base/ollama/v1", api_key="$key")
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
            SectionCard(title = "Native Ollama API", subtitle = "For tools with an Ollama integration (n8n Ollama node, Open WebUI, etc.). Send the key as a Bearer token.") {
                CopyField("Ollama URL", "$base/ollama", onCopied = copied)
                Text("Inside n8n on this phone use http://127.0.0.1:11434 - no key needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            SectionCard(title = "n8n", subtitle = "Open the editor once with the key link - it remembers you for 30 days. Webhooks are public so other services can call them.") {
                CopyField("Editor (with key)", "$base/?key=$key", secret = false, onCopied = copied)
                CopyField("Webhook base", "$base/webhook/", onCopied = copied)
                CopyField("Form base", "$base/form/", onCopied = copied)
            }
        }

        item {
            SectionCard(title = "Security") {
                Text(
                    "• Everything except n8n webhooks needs your API key.\n" +
                        "• Ollama and n8n only listen on 127.0.0.1 - the gateway is the only door.\n" +
                        "• Traffic is HTTPS end-to-end through Cloudflare. Your home IP is never exposed.\n" +
                        "• Leaked the key? Settings → Rotate API key.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
