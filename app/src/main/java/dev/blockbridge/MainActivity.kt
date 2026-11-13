package dev.blockbridge

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val store = ServerStore(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(store) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(store: ServerStore) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var servers by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<Server?>(null) }
    var adding by remember { mutableStateOf(false) }
    val state by RelayService.state.collectAsStateWithLifecycle()

    fun update(list: List<Server>) { servers = list; store.save(list) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("BlockBridge") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add server") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            StatusCard(state) { RelayService.stop(ctx) }
            Spacer(Modifier.height(16.dp))
            Text("Servers", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap one to share it. Console and phone must be on same Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(servers, key = { it.id }) { s ->
                    val active = state.running && state.server?.id == s.id
                    ElevatedCard(
                        Modifier.fillMaxWidth().clickable { RelayService.start(ctx, s) },
                        colors = if (active) CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ) else CardDefaults.elevatedCardColors(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleMedium)
                                Text("${s.host}:${s.port}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { editing = s }) { Text("Edit") }
                            IconButton(onClick = { update(servers - s) }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
            if (state.log.isNotEmpty()) {
                Text("Log", style = MaterialTheme.typography.labelMedium)
                Text(
                    state.log.takeLast(5).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    if (adding || editing != null) {
        ServerDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { s ->
                update(if (servers.any { it.id == s.id }) servers.map { if (it.id == s.id) s else it } else servers + s)
                adding = false; editing = null
            },
        )
    }
}

@Composable
private fun StatusCard(state: RelayState, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (!state.running) {
                Text("Not sharing", style = MaterialTheme.typography.titleMedium)
                Text("Pick a server below.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text("Sharing ${state.server?.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.online) "Server online · ${state.players} console(s) connected"
                else "Waiting for server to answer…",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.motd?.split(';')?.getOrNull(3)?.let {
                Text("Server version $it", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "On console: Play → Friends → LAN Games",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onStop) { Text("Stop") }
        }
    }
}

@Composable
private fun ServerDialog(initial: Server?, onDismiss: () -> Unit, onSave: (Server) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 19132).toString()) }
    val portNum = port.toIntOrNull()
    val valid = host.isNotBlank() && portNum != null && portNum in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add server" else "Edit server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(host, { host = it.trim() }, label = { Text("Address") }, singleLine = true)
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val base = initial ?: Server(name = "", host = "")
                onSave(base.copy(name = name.ifBlank { host }, host = host, port = portNum!!))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
