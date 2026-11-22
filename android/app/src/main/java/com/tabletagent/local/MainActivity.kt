package com.tabletagent.local

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
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
    var approvals by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var eventCursor by rememberSaveable { mutableIntStateOf(0) }
    var previewUrl by remember { mutableStateOf("") }
    var previewState by remember { mutableStateOf("stopped") }
    var files by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var fileDir by rememberSaveable { mutableStateOf(".") }
    var fileName by remember { mutableStateOf("") }
    var fileBody by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var createDialog by remember { mutableStateOf(false) }
    var gitReviewDialog by remember { mutableStateOf(false) }
    var pickerExpanded by remember { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("plan") }
    var availableAgents by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedAgent by rememberSaveable { mutableStateOf("codex") }
    var selectedModel by rememberSaveable { mutableStateOf("default") }
    var tokenEntry by remember { mutableStateOf(token) }
    val notify: (Throwable) -> Unit = { error = it.message ?: it.javaClass.simpleName }

    LaunchedEffect(selectedTask) {
        events = emptyList()
        approvals = emptyList()
        eventCursor = 0
    }
    // Reconnect/poll on-device: no remote server or external networking.
    LaunchedEffect(token, selectedProject, selectedTask) {
        while (isActive) {
            online = api.health()
            if (online && token.isNotBlank()) {
                try {
                    runCatching { availableAgents = api.agents().objects() }
                    projects = api.projects().objects()
                    if (selectedProject.isBlank() && projects.isNotEmpty()) {
                        selectedProject = projects.first().getString("name")
                    }
                    if (selectedProject.isNotBlank()) {
                        tasks = api.tasks(selectedProject).objects()
                        val activeTask = tasks.firstOrNull {
                            it.optString("status") in listOf("QUEUED", "PLANNING", "WAITING_APPROVAL", "EXECUTING", "VERIFYING", "REPAIRING")
                        }
                        if (activeTask != null) {
                            AgentForegroundService.update(
                                context,
                                activeTask.optString("id"),
                                activeTask.optString("status"),
                                activeTask.optString("prompt")
                            )
                        } else if (tasks.isNotEmpty() && tasks.all { it.optString("status") in listOf("PLANNED", "COMPLETED", "NEEDS_VERIFICATION", "FAILED", "CANCELLED", "BLOCKED_SETUP", "INTERRUPTED") }) {
                            AgentForegroundService.stop(context)
                        }
                        val p = api.previewStatus(selectedProject)
                        previewState = p.optString("status", "stopped")
                        previewUrl = p.optString("url", "")
                        if (selectedTask.isNotBlank()) {
                            val fresh = api.events(selectedTask, eventCursor).objects()
                            if (fresh.isNotEmpty()) {
                                eventCursor = fresh.last().optInt("seq", eventCursor)
                                events = (events + fresh).takeLast(220)
                            }
                            runCatching { approvals = api.approvals(selectedTask).objects() }
                        } else {
                            approvals = emptyList()
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
                    if (selectedProject.isNotBlank()) {
                        OutlinedButton(onClick = { gitReviewDialog = true }) {
                            Icon(Icons.Default.History, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Git")
                        }
                        Spacer(Modifier.width(6.dp))
                    }
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
                    agents = availableAgents, selectedAgent = selectedAgent, onSelectAgent = { selectedAgent = it },
                    selectedModel = selectedModel, onSelectModel = { selectedModel = it },
                    tasks = tasks, selectedTask = selectedTask, onSelectTask = { selectedTask = it }, events = events,
                    approvals = approvals,
                    canRun = online && token.isNotBlank() && selectedProject.isNotBlank(),
                    onSend = { scope.launch {
                        try {
                            val t = api.startTask(selectedProject, prompt, mode, selectedAgent, if (selectedModel == "default") "" else selectedModel)
                            val taskId = t.getString("id")
                            selectedTask = taskId
                            AgentForegroundService.start(context, taskId, selectedProject, mode.uppercase(), prompt)
                            prompt = ""; error = ""
                            tasks = api.tasks(selectedProject).objects()
                        } catch (e: Exception) { notify(e) }
                    } },
                    onCancel = { scope.launch { runCatching {
                        api.cancelTask(selectedTask)
                        AgentForegroundService.stop(context)
                        tasks = api.tasks(selectedProject).objects()
                    }.onFailure { notify(it) } } },
                    onApprove = { aid -> scope.launch { runCatching {
                        api.respondApproval(selectedTask, aid, true)
                        approvals = api.approvals(selectedTask).objects()
                    }.onFailure { notify(it) } } },
                    onDeny = { aid -> scope.launch { runCatching {
                        api.respondApproval(selectedTask, aid, false)
                        approvals = api.approvals(selectedTask).objects()
                    }.onFailure { notify(it) } } }
                            )
                        }
                        Box(Modifier.weight(.49f).fillMaxHeight()) { PreviewScreen(previewUrl, previewState,
                            onStart = { scope.launch { runCatching { val p = api.previewStart(selectedProject); previewUrl = p.optString("url", ""); previewState = p.optString("status", "starting") }.onFailure { notify(it) } } },
                            onStop = { scope.launch { runCatching { api.previewStop(selectedProject); previewState = "stopped"; previewUrl = "" }.onFailure { notify(it) } } },
                            enabled = online && selectedProject.isNotBlank()) }
                    } else AgentScreen(
                        prompt = prompt, onPrompt = { prompt = it }, mode = mode, onMode = { mode = it },
                        agents = availableAgents, selectedAgent = selectedAgent, onSelectAgent = { selectedAgent = it },
                        selectedModel = selectedModel, onSelectModel = { selectedModel = it },
                        tasks = tasks, selectedTask = selectedTask, onSelectTask = { selectedTask = it }, events = events,
                        approvals = approvals,
                        canRun = online && token.isNotBlank() && selectedProject.isNotBlank(),
                        onSend = { scope.launch {
                            try {
                                val t = api.startTask(selectedProject, prompt, mode, selectedAgent, if (selectedModel == "default") "" else selectedModel)
                                val taskId = t.getString("id")
                                selectedTask = taskId
                                AgentForegroundService.start(context, taskId, selectedProject, mode.uppercase(), prompt)
                                prompt = ""; error = ""
                                tasks = api.tasks(selectedProject).objects()
                            } catch (e: Exception) { notify(e) }
                        } },
                        onCancel = { scope.launch { runCatching {
                            api.cancelTask(selectedTask)
                            AgentForegroundService.stop(context)
                            tasks = api.tasks(selectedProject).objects()
                        }.onFailure { notify(it) } } },
                        onApprove = { aid -> scope.launch { runCatching {
                            api.respondApproval(selectedTask, aid, true)
                            approvals = api.approvals(selectedTask).objects()
                        }.onFailure { notify(it) } } },
                        onDeny = { aid -> scope.launch { runCatching {
                            api.respondApproval(selectedTask, aid, false)
                            approvals = api.approvals(selectedTask).objects()
                        }.onFailure { notify(it) } } }
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
    if (gitReviewDialog && selectedProject.isNotBlank()) {
        GitReviewDialog(
            project = selectedProject,
            api = api,
            onDismiss = { gitReviewDialog = false },
            onCommitted = { error = "Changes committed locally" },
            notify = notify
        )
    }
}

@Composable
private fun AgentScreen(prompt: String, onPrompt: (String) -> Unit, mode: String, onMode: (String) -> Unit,
                        agents: List<JSONObject> = emptyList(), selectedAgent: String = "codex", onSelectAgent: (String) -> Unit = {},
                        selectedModel: String = "default", onSelectModel: (String) -> Unit = {},
                        tasks: List<JSONObject>, selectedTask: String, onSelectTask: (String) -> Unit,
                        events: List<JSONObject>, approvals: List<JSONObject> = emptyList(),
                        canRun: Boolean, onSend: () -> Unit, onCancel: () -> Unit,
                        onApprove: (String) -> Unit = {}, onDeny: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        val pendingApproval = approvals.firstOrNull { it.optString("status") == "PENDING" }
        if (pendingApproval != null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Approval Required: ${pendingApproval.optString("type")}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        pendingApproval.optString("target"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { onDeny(pendingApproval.optString("id")) }) {
                            Text("Deny")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onApprove(pendingApproval.optString("id")) }) {
                            Text("Approve")
                        }
                    }
                }
            }
        }
        Text("Describe what to build", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val presets = listOf(
            Triple("✨ Feature", "build", "Build a feature that "),
            Triple("🔨 Fix Error", "build", "Fix the following issue or error: "),
            Triple("🎨 UI Polish", "build", "Polish the user interface, styling, and tablet responsiveness for "),
            Triple("⚡ Refactor", "plan", "Review and refactor code structure for "),
            Triple("🔒 Audit", "plan", "Audit workspace for security vulnerabilities and misconfigurations in "),
            Triple("🧪 Run Tests", "build", "Run test suites and verify build output with evidence")
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Presets:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            presets.forEach { (label, targetMode, template) ->
                SuggestionChip(
                    onClick = {
                        onMode(targetMode)
                        onPrompt(template)
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Agent:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            val fallback = listOf(
                Triple("codex", "Codex", true),
                Triple("claude", "Claude", true),
                Triple("opencode", "OpenCode", true)
            )
            val list = if (agents.isNotEmpty()) agents.map {
                Triple(it.optString("name"), it.optString("display_name"), it.optBoolean("available", true))
            } else fallback

            list.forEach { (name, label, avail) ->
                FilterChip(
                    selected = selectedAgent == name,
                    onClick = {
                        onSelectAgent(name)
                        onSelectModel("default")
                    },
                    label = { Text(if (avail) label else "$label (offline)") },
                    leadingIcon = if (selectedAgent == name) {
                        { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                    } else null
                )
            }
        }
        val currAgentObj = agents.firstOrNull { it.optString("name") == selectedAgent }
        val models = currAgentObj?.optJSONArray("models")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
        if (models.size > 1) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Model:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                models.forEach { m ->
                    FilterChip(
                        selected = selectedModel == m,
                        onClick = { onSelectModel(m) },
                        label = { Text(m) }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(prompt, onPrompt, modifier = Modifier.fillMaxWidth().heightIn(min = 115.dp),
            placeholder = { Text("Describe a feature, bug, or improvement…") }, maxLines = 5)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = mode == "plan", onClick = { onMode("plan") }, label = { Text("Plan only") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = mode == "build", onClick = { onMode("build") }, label = { Text("Build") })
            Spacer(Modifier.weight(1f))
            Button(onClick = onSend, enabled = canRun && prompt.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("Run ${selectedAgent.replaceFirstChar { it.uppercase() }}")
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
        LazyColumn(Modifier.weight(.55f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(events, key = { it.optInt("seq") }) { event ->
                val kind = event.optString("kind").lowercase()
                val msg = event.optString("message")
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when (kind) {
                        "message" -> MaterialTheme.colorScheme.secondaryContainer
                        "error" -> MaterialTheme.colorScheme.errorContainer
                        "verification" -> MaterialTheme.colorScheme.tertiaryContainer
                        "tool" -> MaterialTheme.colorScheme.surfaceVariant
                        "approval", "approval_resolved" -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surface
                    },
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (icon, color) = when (kind) {
                                "message" -> Pair(Icons.Default.SmartToy, MaterialTheme.colorScheme.primary)
                                "error" -> Pair(Icons.Default.Error, MaterialTheme.colorScheme.error)
                                "verification" -> Pair(Icons.Default.CheckCircle, MaterialTheme.colorScheme.tertiary)
                                "tool" -> Pair(Icons.Default.Build, MaterialTheme.colorScheme.secondary)
                                "approval", "approval_resolved" -> Pair(Icons.Default.Security, MaterialTheme.colorScheme.primary)
                                else -> Pair(Icons.Default.Info, MaterialTheme.colorScheme.outline)
                            }
                            Icon(icon, null, Modifier.size(14.dp), tint = color)
                            Spacer(Modifier.width(6.dp))
                            Text(kind.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewScreen(url: String, status: String, onStart: () -> Unit, onStop: () -> Unit, enabled: Boolean) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var viewportMode by remember { mutableStateOf("full") }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live preview", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onStart, enabled = enabled && status == "stopped") { Text("Start dev server") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = onStop, enabled = enabled && status != "stopped") { Text("Stop") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { refreshTick++ }, enabled = url.isNotBlank()) { Text("Reload") }
            Spacer(Modifier.width(10.dp))
            FilterChip(selected = viewportMode == "full", onClick = { viewportMode = "full" }, label = { Text("Full") })
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = viewportMode == "tablet", onClick = { viewportMode = "tablet" }, label = { Text("Tablet (768dp)") })
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = viewportMode == "mobile", onClick = { viewportMode = "mobile" }, label = { Text("Phone (375dp)") })
        }
        Spacer(Modifier.height(6.dp))
        Text(if (url.isNotBlank()) url else "Run npm dev to see your project", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        if (url.startsWith("http://127.0.0.1:")) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val previewMod = when (viewportMode) {
                    "tablet" -> Modifier.width(768.dp).fillMaxHeight()
                    "mobile" -> Modifier.width(375.dp).fillMaxHeight()
                    else -> Modifier.fillMaxSize()
                }
                key(url, refreshTick) { LocalPreview(url, previewMod) }
            }
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
    var maskSecrets by rememberSaveable { mutableStateOf(true) }
    val isEnv = selected.substringAfterLast('/').startsWith(".env")

    Column(Modifier.fillMaxSize()) {
        Text("Quick file editor", style = MaterialTheme.typography.titleMedium)
        Text("Project / $dir", style = MaterialTheme.typography.bodySmall)
        if (dir != ".") TextButton(onClick = { onFolder(dir.substringBeforeLast('/', ".").ifBlank { "." }) }) { Text("← Back") }
        LazyColumn(Modifier.weight(.4f)) {
            items(files, key = { it.optString("path") }) { f ->
                val isDir = f.optBoolean("directory"); val path = f.optString("path")
                Row(Modifier.fillMaxWidth().clickable { if (isDir) onFolder(path) else onFile(path) }.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (isDir) Icons.Default.Folder else Icons.Default.Description, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp)); Text(f.optString("name"), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
        if (selected.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (isEnv) {
                    TextButton(onClick = { maskSecrets = !maskSecrets }) {
                        Icon(if (maskSecrets) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (maskSecrets) "Reveal" else "Mask")
                    }
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onSave) { Text("Save") }
            }
            if (isEnv) {
                Text(
                    if (maskSecrets) "Sensitive .env file: secrets are masked for safety" else "Editing raw secrets: never paste keys into public prompts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            val displayValue = if (isEnv && maskSecrets) {
                body.lines().joinToString("\n") { line ->
                    if (line.contains('=') && !line.trimStart().startsWith('#')) {
                        line.substringBefore('=') + "=••••••••••••"
                    } else line
                }
            } else body

            OutlinedTextField(
                value = displayValue,
                onValueChange = {
                    if (!isEnv || !maskSecrets) onEdit(it)
                },
                readOnly = isEnv && maskSecrets,
                modifier = Modifier.fillMaxWidth().weight(.6f),
                textStyle = MaterialTheme.typography.bodySmall
            )
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
        Text("MVP capabilities & limitations", style = MaterialTheme.typography.titleMedium)
        Text("Codex, Claude Code, and OpenCode adapters are supported with capability flags. Git inspection and manual commit safety are active. Embedded native Linux runtime, streaming WebSocket, and integrated sandbox are later milestones.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun GitReviewDialog(
    project: String,
    api: LocalApi,
    onDismiss: () -> Unit,
    onCommitted: () -> Unit,
    notify: (Throwable) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var statusObj by remember { mutableStateOf<JSONObject?>(null) }
    var diffText by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("") }
    var committing by remember { mutableStateOf(false) }

    fun refresh() {
        loading = true
        scope.launch {
            try {
                val st = api.gitStatus(project)
                val df = api.gitDiff(project)
                statusObj = st
                diffText = df.optString("diff", "")
            } catch (e: Exception) {
                notify(e)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(project) {
        refresh()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Git Review & Commit", style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val clean = statusObj?.optBoolean("clean", true) ?: true
                val branch = statusObj?.optString("branch", "HEAD") ?: "HEAD"
                val uncommittedCount = statusObj?.optInt("uncommitted_count", 0) ?: 0

                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Branch: $branch", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (clean) "Clean working tree" else "$uncommittedCount uncommitted changes",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (clean) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    if (!clean) {
                        Text("Unified Diff:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                        ) {
                            Text(
                                text = diffText.ifBlank { "No text diff available (binary or untracked files)" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())
                            )
                        }

                        OutlinedTextField(
                            value = commitMessage,
                            onValueChange = { commitMessage = it },
                            label = { Text("Commit message") },
                            placeholder = { Text("feat: describe verified changes") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )

                        Text(
                            "Safety policy: Autonomous git pushes are prohibited. Commits are strictly local to this device.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        Text("Working directory is clean. No uncommitted changes.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            val isClean = statusObj?.optBoolean("clean", true) ?: true
            if (!isClean) {
                Button(
                    onClick = {
                        committing = true
                        scope.launch {
                            try {
                                api.gitCommit(project, commitMessage.trim())
                                refresh()
                                onCommitted()
                            } catch (e: Exception) {
                                notify(e)
                            } finally {
                                committing = false
                            }
                        }
                    },
                    enabled = !committing && commitMessage.isNotBlank()
                ) {
                    Text(if (committing) "Committing…" else "Commit changes")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
