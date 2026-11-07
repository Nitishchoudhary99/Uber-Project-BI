package dev.pockethost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pockethost.ui.ChatScreen
import dev.pockethost.ui.ConnectScreen
import dev.pockethost.ui.HomeScreen
import dev.pockethost.ui.ModelsScreen
import dev.pockethost.ui.PocketHostTheme
import dev.pockethost.ui.SettingsScreen
import dev.pockethost.ui.SetupScreen

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Home", Icons.Rounded.PowerSettingsNew),
    Tab("Models", Icons.Rounded.Memory),
    Tab("Chat", Icons.AutoMirrored.Rounded.Chat),
    Tab("Connect", Icons.Rounded.Hub),
    Tab("Settings", Icons.Rounded.Settings),
)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketHostTheme {
                val ui by vm.ui.collectAsStateWithLifecycle()
                val chat by vm.chat.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val snackbar = remember { SnackbarHostState() }

                LaunchedEffect(ui.message) {
                    ui.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        if (ui.setupDone) {
                            NavigationBar {
                                TABS.forEachIndexed { i, t ->
                                    NavigationBarItem(
                                        selected = tab == i, onClick = { tab = i },
                                        icon = { Icon(t.icon, contentDescription = null) },
                                        label = { Text(t.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    if (!ui.setupDone) {
                        SetupScreen(ui, vm, padding)
                    } else when (tab) {
                        0 -> HomeScreen(ui, vm, padding, onOpenSettings = { tab = 4 }, onOpenConnect = { tab = 3 })
                        1 -> ModelsScreen(ui, vm, padding)
                        2 -> ChatScreen(ui, chat, vm, padding)
                        3 -> ConnectScreen(ui, vm, padding)
                        else -> SettingsScreen(ui, vm, padding)
                    }
                }
            }
        }
    }
}
