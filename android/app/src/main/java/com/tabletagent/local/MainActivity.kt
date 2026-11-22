package com.tabletagent.local

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme()) { TabletAgentApp() } }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletAgentApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(SecureDeviceToken.get(context)) }
    val api = remember(token) { LocalApi(token) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var online by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedProject by rememberSaveable { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedTask by rememberSaveable { mutableStateOf("") }
    var events by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var eventCursor by rememberSaveable { mutableIntStateOf(0) }
    var previewUrl by remember { mutableStateOf("") }
    var previewState by remember { mutableStateOf("stopped") }
    var files by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var fileDir by rememberSaveable { mutableStateOf(".") }
    var fileName by remember { mutableStateOf("") }
    var fileBody by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var createDialog by remember { mutableStateOf(false) }
    var pickerExpanded by remember { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("plan") }
    var tokenEntry by remember { mutableStateOf(token) }
    val notify: (Throwable) -> Unit = { error = it.message ?: it.javaClass.simpleName }

    LaunchedEffect(selectedTask) {
        events = emptyList()
        eventCursor = 0
    }
    // Reconnect/poll on-device: no remote server or external networking.
    LaunchedEffect(token, selectedProject, selectedTask) {
        while (isActive) {
            online = api.health()
            if (online && token.isNotBlank()) {
                try {
                    projects = api.projects().objects()
                    if (selectedProject.isBlank() && projects.isNotEmpty()) {
                        selectedProject = projects.first().getString("name")
                    }
                    if (selectedProject.isNotBlank()) {
                        tasks = api.tasks(selectedProject).objects()
                        val p = api.previewStatus(selectedProject)
                        previewState = p.optString("status", "stopped")
                        previewUrl = p.optString("url", "")
                        if (selectedTask.isNotBlank()) {
                            val fresh = api.events(selectedTask, eventCursor).objects()
                            if (fresh.isNotEmpty()) {
                                eventCursor = fresh.last().optInt("seq", eventCursor)
                                events = (events + fresh).takeLast(220)
                            }
                        }
                    }
                } catch (e: Exception) { error = e.message ?: "API error" }
            }
            delay(2400)
        }
    }
    LaunchedEffect(tab, selectedProject, fileDir) {
        if (tab == 2 && token.isNotBlank() && selectedProject.isNotBlank()) {
            runCatching { api.files(selectedProject, fileDir).objects() }
                .onSuccess { files = it }
                .onFailure { notify(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Column {
                Text("Tablet Agent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (online) "Local bridge connected" else "Local bridge offline", style = MaterialTheme.typography.labelSmall,
                    color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            } }, actions = { IconButton(onClick = { tab = 3 }) { Icon(Icons.Default.Settings, "Settings") } })
        },
        bottomBar = {
            NavigationBar {
                listOf(Triple("Agent", Icons.Default.SmartToy, 0), Triple("Preview", Icons.Default.Visibility, 1),
                    Triple("Files", Icons.Default.FolderOpen, 2), Triple("Settings", Icons.Default.Tune, 3)).forEach { (label, icon, index) ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }
    ) { inset ->
        Column(Modifier.padding(inset).fillMaxSize().padding(horizontal = 12.dp)) {
            if (error.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { error = "" }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, null) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (tab != 3) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { pickerExpanded = true }) {
                            Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(selectedProject.ifEmpty { "Select project" })
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = pickerExpanded, onDismissRequest = { pickerExpanded = false }) {
                            projects.forEach { obj ->
                                val name = obj.optString("name")
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    selectedProject = name; selectedTask = ""; events = emptyList(); eventCursor = 0; fileDir = "."; fileName = ""; pickerExpanded = false
                                })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { createDialog = true }) { Icon(Icons.Default.CreateNewFolder, "New project") }
                }
                Spacer(Modifier.height(6.dp))
            }
            when (tab) {
                0 -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val isWide = maxWidth >= 850.dp
                    if (isWide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(.51f).fillMaxHeight()) {
                            AgentScreen(
                    prompt = prompt, onPrompt = { prompt = it }, mode = mode, onMode = { mode = it },
                    tasks = tasks, selectedTask = selectedTask, onSelectTask = { selectedTask = it }, events = events,
                    canRun = online && token.isNotBlank() && selectedProject.isNotBlank(),
                    onSend = { scope.launch {
                        try {
                            val t = api.startTask(selectedProject, prompt, mode)
                            selectedTask = t.getString("id"); prompt = ""; error = ""
                            tasks = api.tasks(selectedProject).objects()
                        } catch (e: Exception) { notify(e) }
                    } },
                    onCancel = { scope.launch { runCatching { api.cancelTask(selectedTask); tasks = api.tasks(selectedProject).objects() }.onFailure { notify(it) } } }
                            )
                        }
                        Box(Modifier.weight(.49f).fillMaxHeight()) { PreviewScreen(previewUrl, previewState,
                            onStart = { scope.launch { runCatching { val p = api.previewStart(selectedProject); previewUrl = p.optString("url", ""); previewState = p.optString("status", "starting") }.onFailure { notify(it) } } },
                            onStop = { scope.launch { runCatching { api.previewStop(selectedProject); previewState = "stopped"; previewUrl = "" }.onFailure { notify(it) } } },
                            enabled = online && selectedProject.isNotBlank()) }
                    } else AgentScreen(
                        prompt = prompt, onPrompt = { prompt = it }, mode = mode, onMode = { mode = it },
                        tasks = tasks, selectedTask = selectedTask, onSelectTask = { selectedTask = it }, events = events,
                        canRun = online && token.isNotBlank() && selectedProject.isNotBlank(),
                        onSend = { scope.launch {
                            try { val t=api.startTask(selectedProject,prompt,mode); selectedTask=t.getString("id");prompt="";error="";tasks=api.tasks(selectedProject).objects() }
                            catch(e:Exception){notify(e)}
                        } },
                        onCancel = { scope.launch { runCatching { api.cancelTask(selectedTask); tasks=api.tasks(selectedProject).objects() }.onFailure { notify(it) } } }
                    )
                }
                1 -> PreviewScreen(previewUrl, previewState,
                    onStart = { scope.launch { runCatching { val p = api.previewStart(selectedProject); previewUrl = p.optString("url", ""); previewState = p.optString("status", "starting") }.onFailure { notify(it) } } },
                    onStop = { scope.launch { runCatching { api.previewStop(selectedProject); previewState = "stopped"; previewUrl = "" }.onFailure { notify(it) } } },
                    enabled = online && selectedProject.isNotBlank())
                2 -> FilesScreen(files, fileDir, fileName, fileBody,
                    onFolder = { fileDir = it; fileName = "" },
                    onFile = { path -> scope.launch { runCatching { api.file(selectedProject, path) }
                        .onSuccess { fileName = path; fileBody = it.optString("content", "") }
                        .onFailure { notify(it) } } },
                    onEdit = { fileBody = it },
                    onSave = { scope.launch { runCatching { api.saveFile(selectedProject, fileName, fileBody) }
                        .onSuccess { error = "Saved $fileName" }
                        .onFailure { notify(it) } } })
                3 -> SettingsScreen(tokenEntry, onToken = { tokenEntry = it },
                    onSave = {
                        try {
                            SecureDeviceToken.save(context, tokenEntry.trim())
                            token = tokenEntry.trim(); error = "Device token saved"
                        } catch (e: Exception) { notify(e) }
                    }, onStart = { error = TermuxLauncher.startBridge(context) }, online = online)
            }
        }
    }
    if (createDialog) {
        var name by remember { mutableStateOf("") }
        var gitUrl by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { createDialog = false },
            title = { Text("Create or clone project") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project folder name") }, singleLine = true)
                OutlinedTextField(value = gitUrl, onValueChange = { gitUrl = it }, label = { Text("GitHub HTTPS URL (optional)") }, maxLines = 2)
            } },
            confirmButton = { TextButton(onClick = { scope.launch {
                runCatching { api.createProject(name.trim(), gitUrl.trim()) }
                    .onSuccess { selectedProject = name.trim(); createDialog = false; projects = api.projects().objects() }
                    .onFailure { notify(it) }
            } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AgentScreen(prompt: String, onPrompt: (String) -> Unit, mode: String, onMode: (String) -> Unit,
                        tasks: List<JSONObject>, selectedTask: String, onSelectTask: (String) -> Unit,
                        events: List<JSONObject>, canRun: Boolean, onSend: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Describe what to build", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(prompt, onPrompt, modifier = Modifier.fillMaxWidth().heightIn(min = 115.dp),
            placeholder = { Text("Describe a feature, bug, or improvement…") }, maxLines = 5)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = mode == "plan", onClick = { onMode("plan") }, label = { Text("Plan only") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = mode == "build", onClick = { onMode("build") }, label = { Text("Build") })
            Spacer(Modifier.weight(1f))
            Button(onClick = onSend, enabled = canRun && prompt.isNotBlank()) {
                Icon(Icons.Default.Send, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp)); Text("Run Codex")
            }
        }
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tasks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${tasks.size} recent", style = MaterialTheme.typography.labelSmall)
        }
        // Separate task list from event stream, tuned for touch rather than an IDE editor.
        LazyColumn(Modifier.weight(.45f).fillMaxWidth()) {
            items(tasks, key = { it.optString("id") }) { task ->
                val id = task.optString("id")
                Surface(onClick = { onSelectTask(id) }, color = if (id == selectedTask) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(task.optString("status"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                color = if (task.optString("status") == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(task.optString("mode").uppercase(), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(task.optString("prompt"), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        if (task.optString("error").isNotBlank()) Text(task.optString("error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
                HorizontalDivider()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Agent activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (selectedTask.isNotEmpty()) TextButton(onClick = onCancel) { Text("Cancel") }
        }
        LazyColumn(Modifier.weight(.55f)) {
            items(events, key = { it.optInt("seq") }) { event ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(event.optString("kind").uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(event.optString("message"), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PreviewScreen(url: String, status: String, onStart: () -> Unit, onStop: () -> Unit, enabled: Boolean) {
    var refreshTick by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live preview", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.labelSmall)
        }
        Row {
            Button(onClick = onStart, enabled = enabled && status == "stopped") { Text("Start dev server") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onStop, enabled = enabled && status != "stopped") { Text("Stop") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { refreshTick++ }, enabled = url.isNotBlank()) { Text("Reload") }
        }
        Spacer(Modifier.height(6.dp))
        Text(if (url.isNotBlank()) url else "Run npm dev to see your project", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        if (url.startsWith("http://127.0.0.1:")) {
            key(url, refreshTick) { LocalPreview(url, Modifier.fillMaxSize()) }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No local preview yet") }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LocalPreview(url: String, modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val host = request?.url?.host ?: return true
                    return host != "127.0.0.1" && host != "localhost"
                }
            }
            loadUrl(url)
        }
    })
}

@Composable
private fun FilesScreen(files: List<JSONObject>, dir: String, selected: String, body: String,
                        onFolder: (String) -> Unit, onFile: (String) -> Unit, onEdit: (String) -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Quick file editor", style = MaterialTheme.typography.titleMedium)
        Text("Project / $dir", style = MaterialTheme.typography.bodySmall)
        if (dir != ".") TextButton(onClick = { onFolder(dir.substringBeforeLast('/', ".").ifBlank { "." }) }) { Text("← Back") }
        LazyColumn(Modifier.weight(.4f)) {
            items(files, key = { it.optString("path") }) { f ->
                val isDir=f.optBoolean("directory");val path=f.optString("path")
                Row(Modifier.fillMaxWidth().clickable { if (isDir) onFolder(path) else onFile(path) }.padding(9.dp),verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (isDir) Icons.Default.Folder else Icons.Default.Description, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp));Text(f.optString("name"), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
        if (selected.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Button(onClick = onSave) { Text("Save") }
            }
            if (selected.substringAfterLast('/').startsWith(".env"))
                Text("Sensitive file: do not paste secrets into Agent prompts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(value = body, onValueChange = onEdit, modifier = Modifier.fillMaxWidth().weight(.6f), textStyle = MaterialTheme.typography.bodySmall)
        } else {
            Box(Modifier.weight(.6f), contentAlignment = Alignment.Center) { Text("Select a file to edit") }
        }
    }
}

@Composable
private fun SettingsScreen(tokenEntry: String, onToken: (String) -> Unit, onSave: () -> Unit, onStart: () -> Unit, online: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Local runtime settings", style = MaterialTheme.typography.titleLarge)
        Text("On-device Termux bridge at 127.0.0.1:8765. No PC or VPS required.", style = MaterialTheme.typography.bodyMedium)
        Text("Status: ${if (online) "Bridge online" else "Bridge offline"}")
        OutlinedTextField(tokenEntry, onToken, label = { Text("Device token from Termux") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = tokenEntry.isNotBlank()) { Text("Save token") }
            OutlinedButton(onClick = onStart) { Text("Start bridge") }
        }
        HorizontalDivider()
        Text("Initial setup", style = MaterialTheme.typography.titleMedium)
        Text("1. Install Termux locally.\n2. Install python, git, nodejs and Codex CLI.\n3. Copy bridge files into ~/tablet-agent/bridge.\n4. Run python ~/tablet-agent/bridge/server.py.\n5. Copy the generated token above.")
        Text("To use Start bridge: enable allow-external-apps=true in Termux and grant this app RUN_COMMAND permission.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("MVP limitations", style = MaterialTheme.typography.titleMedium)
        Text("Codex is the only connected agent. Claude/OpenCode adapters, native bundled Linux, streaming WebSocket, full approval UI and background reliability are later phases.", style = MaterialTheme.typography.bodySmall)
    }
}
