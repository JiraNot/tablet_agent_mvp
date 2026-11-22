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
import com.tabletagent.local.runtime.AllCliAuthStatus
import com.tabletagent.local.runtime.BootstrapManager
import com.tabletagent.local.runtime.BootstrapStatus
import com.tabletagent.local.runtime.CliAuthManager
import com.tabletagent.local.runtime.CliInstaller
import com.tabletagent.local.runtime.CliVersions
import com.tabletagent.local.runtime.EmbeddedBridgeManager
import com.tabletagent.local.runtime.EmbeddedBridgeStatus
import com.tabletagent.local.runtime.ExecutionResult
import com.tabletagent.local.runtime.ProviderKeySummary
import com.tabletagent.local.runtime.SecureSecrets
import com.tabletagent.local.ui.AionClaudeAgentPane
import com.tabletagent.local.ui.AionClaudeSidebar
import com.tabletagent.local.ui.ClaudeCodeTopBar
import com.tabletagent.local.ui.CliOAuthDialog
import com.tabletagent.local.ui.OfficialCliCard
import com.tabletagent.local.ui.PhaseStepperBar
import com.tabletagent.local.ui.ProviderApiKeysCard
import com.tabletagent.local.ui.aionClaudeDarkScheme
import com.tabletagent.local.ui.aionClaudeLightScheme
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
        val initToken = intent?.getStringExtra("SET_TOKEN")?.trim()
        if (!initToken.isNullOrBlank()) {
            SecureDeviceToken.save(this, initToken)
        }
        setContent { MaterialTheme(colorScheme = lightColorScheme()) { TabletAgentApp() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val t = intent.getStringExtra("SET_TOKEN")?.trim()
        if (!t.isNullOrBlank()) {
            SecureDeviceToken.save(this, t)
            recreate()
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletAgentApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(SecureDeviceToken.get(context)) }
    val bridgeStatus by EmbeddedBridgeManager.status.collectAsState()
    val bridgePort by EmbeddedBridgeManager.port.collectAsState()
    val api = remember(token, bridgePort) { LocalApi(token, bridgePort) }
    var bridgeRuntime by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var darkTheme by rememberSaveable { mutableStateOf(false) }
    var sidebarOpen by rememberSaveable { mutableStateOf(true) }
    var isNewChatMode by rememberSaveable { mutableStateOf(false) }
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
    val bootstrapStatus by BootstrapManager.status.collectAsState()
    val isRuntimeReady = remember(bootstrapStatus) { BootstrapManager.isReady(context) }
    var cliVersions by remember { mutableStateOf<CliVersions?>(null) }
    var installingCli by remember { mutableStateOf<String?>(null) }
    val oauthState by CliAuthManager.oauthState.collectAsState()
    var cliAuthStatus by remember { mutableStateOf(AllCliAuthStatus()) }
    var keySummary by remember { mutableStateOf(SecureSecrets.getSummary(context)) }
    var authBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!BootstrapManager.isReady(context)) {
            BootstrapManager.bootstrapIfNeeded(context)
        } else {
            BootstrapManager.ensureRuntimeDirs(context)
        }
        if (BootstrapManager.isReady(context)) {
            val ensured = EmbeddedBridgeManager.ensureDeviceToken(context)
            if (ensured.isNotBlank()) {
                token = ensured
                tokenEntry = ensured
            }
            EmbeddedBridgeManager.startIfNeeded(context)
            cliVersions = CliInstaller.getInstalledVersions(context)
            cliAuthStatus = CliAuthManager.getAuthStatus(context)
        }
    }

    LaunchedEffect(oauthState.completed) {
        if (oauthState.completed && BootstrapManager.isReady(context)) {
            cliAuthStatus = CliAuthManager.getAuthStatus(context)
        }
    }

    val notify: (Throwable) -> Unit = { 
        if (it !is kotlin.coroutines.cancellation.CancellationException) {
            error = it.message ?: it.javaClass.simpleName 
        }
    }

    LaunchedEffect(selectedTask) {
        events = emptyList()
        approvals = emptyList()
        eventCursor = 0
    }
    // Reconnect/poll on-device: throttled and non-mutating on identical snapshots to prevent scroll lag.
    var pollTick by remember { mutableIntStateOf(0) }
    var lastTasksRaw by remember { mutableStateOf("") }
    var lastProjectsRaw by remember { mutableStateOf("") }
    var lastApprovalsRaw by remember { mutableStateOf("") }
    LaunchedEffect(token, bridgePort, selectedProject, selectedTask) {
        pollTick = 0
        lastTasksRaw = ""
        lastApprovalsRaw = ""
        while (isActive) {
            val healthObj = api.healthInfo()
            val isOnline = healthObj != null
            if (online != isOnline) online = isOnline
            val rt = healthObj?.optString("runtime", "") ?: ""
            if (bridgeRuntime != rt) bridgeRuntime = rt
            if (isOnline && token.isNotBlank()) {
                try {
                    if (availableAgents.isEmpty() || pollTick % 10 == 0) {
                        runCatching {
                            val arr = api.agents()
                            if (availableAgents.size != arr.length()) availableAgents = arr.objects()
                        }
                    }
                    if (projects.isEmpty() || pollTick % 6 == 0) {
                        val projArr = api.projects()
                        val projRaw = projArr.toString()
                        if (projRaw != lastProjectsRaw) {
                            lastProjectsRaw = projRaw
                            projects = projArr.objects()
                        }
                        if (selectedProject.isBlank() && projects.isNotEmpty()) {
                            selectedProject = projects.first().getString("name")
                        }
                    }
                    if (selectedProject.isNotBlank()) {
                        val tasksArr = api.tasks(selectedProject)
                        val tasksRaw = tasksArr.toString()
                        if (tasksRaw != lastTasksRaw) {
                            lastTasksRaw = tasksRaw
                            tasks = tasksArr.objects()
                        }
                        val activeTask = tasks.firstOrNull {
                            it.optString("status") in listOf("QUEUED", "PLANNING", "WAITING_APPROVAL", "EXECUTING", "VERIFYING", "REPAIRING")
                        }
                        if (!isNewChatMode && selectedTask.isBlank() && tasks.isNotEmpty()) {
                            selectedTask = (activeTask ?: tasks.first()).optString("id")
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
                        if (pollTick % 3 == 0) {
                            val p = api.previewStatus(selectedProject)
                            val pState = p.optString("status", "stopped")
                            val pUrl = p.optString("url", "")
                            if (previewState != pState) previewState = pState
                            if (previewUrl != pUrl) previewUrl = pUrl
                        }
                        if (selectedTask.isNotBlank()) {
                            var keepPaging = true
                            while (keepPaging) {
                                val page = api.eventsPage(selectedTask, eventCursor)
                                val fresh = page.optJSONArray("events")?.objects() ?: emptyList()
                                val gap = page.optBoolean("gap_detected", false)
                                val prefix = if (gap) {
                                    listOf(JSONObject().put("seq", -1).put("kind", "log").put("message", "Event history gap detected after reconnect; showing retained events."))
                                } else emptyList()
                                if (fresh.isNotEmpty() || gap) {
                                    if (fresh.isNotEmpty()) {
                                        eventCursor = fresh.last().optInt("seq", eventCursor)
                                    }
                                    events = (events + prefix + fresh).takeLast(220)
                                }
                                keepPaging = page.optBoolean("has_more", false) && fresh.isNotEmpty()
                            }
                            if (activeTask != null || pollTick == 0) {
                                runCatching {
                                    val appArr = api.approvals(selectedTask)
                                    val appRaw = appArr.toString()
                                    if (appRaw != lastApprovalsRaw) {
                                        lastApprovalsRaw = appRaw
                                        approvals = appArr.objects()
                                    }
                                }
                            }
                        } else if (approvals.isNotEmpty()) {
                            approvals = emptyList()
                        }
                    }
                } catch (e: Exception) { 
                    if (e !is kotlin.coroutines.cancellation.CancellationException) {
                        error = e.message ?: "API error" 
                    }
                }
            }
            pollTick++
            val hasRunningTask = tasks.any {
                it.optString("status") in listOf("QUEUED", "PLANNING", "WAITING_APPROVAL", "EXECUTING", "VERIFYING", "REPAIRING")
            }
            delay(if (hasRunningTask) 1100L else 3200L)
        }
    }
    LaunchedEffect(tab, selectedProject, fileDir) {
        if (tab == 3 && token.isNotBlank() && selectedProject.isNotBlank()) {
            runCatching { api.files(selectedProject, fileDir).objects() }
                .onSuccess { files = it }
                .onFailure { notify(it) }
        }
    }

    MaterialTheme(colorScheme = if (darkTheme) aionClaudeDarkScheme() else aionClaudeLightScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                if (sidebarOpen) {
                    AionClaudeSidebar(
                        projects = projects,
                        selectedProject = selectedProject,
                        onSelectProject = { name ->
                            selectedProject = name; selectedTask = ""; isNewChatMode = false
                            events = emptyList(); eventCursor = 0; fileDir = "."; fileName = ""
                        },
                        onNewProject = { createDialog = true },
                        tasks = tasks,
                        selectedTask = selectedTask,
                        isNewChatMode = isNewChatMode,
                        onNewChat = {
                            selectedTask = ""; isNewChatMode = true; events = emptyList(); eventCursor = 0; tab = 0
                        },
                        onSelectTask = { tid ->
                            selectedTask = tid; isNewChatMode = false
                            if (tab > 1) tab = 0
                        },
                        darkTheme = darkTheme,
                        onToggleTheme = { darkTheme = !darkTheme },
                        onOpenSettings = { tab = 4 }
                    )
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val activeTaskObj = tasks.firstOrNull { it.optString("id") == selectedTask }
                    ClaudeCodeTopBar(
                        sidebarOpen = sidebarOpen,
                        onToggleSidebar = { sidebarOpen = !sidebarOpen },
                        selectedProject = selectedProject,
                        currentTask = activeTaskObj,
                        isNewChatMode = isNewChatMode,
                        online = online,
                        bridgePort = bridgePort,
                        workspaceView = tab,
                        onSelectWorkspaceView = { tab = it },
                        onOpenGit = { gitReviewDialog = true }
                    )
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (bootstrapStatus is BootstrapStatus.Extracting) {
                            val ext = bootstrapStatus as BootstrapStatus.Extracting
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CloudSync, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Setting up Standalone AI Runtime (offline ready)...", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("Extracting embedded PRoot + Alpine Linux aarch64 (Node 22, Git, Python 3)...", style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(progress = { ext.progress }, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(4.dp))
                                    Text(ext.currentFile, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        if (error.isNotBlank()) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    IconButton(onClick = { error = "" }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, null) }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        when (tab) {
                            0 -> AionClaudeAgentPane(
                                prompt = prompt, onPrompt = { prompt = it },
                                mode = mode, onMode = { mode = it },
                                agents = availableAgents, selectedAgent = selectedAgent, onSelectAgent = { selectedAgent = it },
                                selectedModel = selectedModel, onSelectModel = { selectedModel = it },
                                cliAuthStatus = cliAuthStatus, isEmbeddedRuntime = isRuntimeReady,
                                selectedProject = selectedProject, projects = projects,
                                onSelectProject = { name ->
                                    selectedProject = name; selectedTask = ""; events = emptyList(); eventCursor = 0; fileDir = "."; fileName = ""
                                },
                                onOpenSettings = { tab = 4 },
                                tasks = tasks, selectedTask = selectedTask,
                                isNewChatMode = isNewChatMode,
                                onNewChat = { selectedTask = ""; isNewChatMode = true; events = emptyList(); eventCursor = 0 },
                                events = events, approvals = approvals,
                                canRun = online && token.isNotBlank() && selectedProject.isNotBlank(),
                                onSend = {
                                    scope.launch {
                                        try {
                                            val t = api.startTask(selectedProject, prompt, mode, selectedAgent, if (selectedModel == "default") "" else selectedModel)
                                            val taskId = t.getString("id")
                                            selectedTask = taskId
                                            isNewChatMode = false
                                            AgentForegroundService.start(context, taskId, selectedProject, mode.uppercase(), prompt)
                                            prompt = ""; error = ""
                                            tasks = api.tasks(selectedProject).objects()
                                        } catch (e: Exception) { notify(e) }
                                    }
                                },
                                onCancel = {
                                    scope.launch {
                                        runCatching {
                                            api.cancelTask(selectedTask)
                                            AgentForegroundService.stop(context)
                                            tasks = api.tasks(selectedProject).objects()
                                        }.onFailure { notify(it) }
                                    }
                                },
                                onApprove = { aid ->
                                    scope.launch {
                                        runCatching {
                                            api.respondApproval(selectedTask, aid, true)
                                            approvals = api.approvals(selectedTask).objects()
                                        }.onFailure { notify(it) }
                                    }
                                },
                                onDeny = { aid ->
                                    scope.launch {
                                        runCatching {
                                            api.respondApproval(selectedTask, aid, false)
                                            approvals = api.approvals(selectedTask).objects()
                                        }.onFailure { notify(it) }
                                    }
                                },
                                onCommitClick = { gitReviewDialog = true }
                            )
                            1 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.weight(0.52f).fillMaxHeight()) {
                                    AionClaudeAgentPane(
                                        prompt = prompt, onPrompt = { prompt = it },
                                        mode = mode, onMode = { mode = it },
                                        agents = availableAgents, selectedAgent = selectedAgent, onSelectAgent = { selectedAgent = it },
                                        selectedModel = selectedModel, onSelectModel = { selectedModel = it },
                                        cliAuthStatus = cliAuthStatus, isEmbeddedRuntime = isRuntimeReady,
                                        selectedProject = selectedProject, projects = projects,
                                        onSelectProject = { name ->
                                            selectedProject = name; selectedTask = ""; events = emptyList(); eventCursor = 0; fileDir = "."; fileName = ""
                                        },
                                        onOpenSettings = { tab = 4 },
                                        tasks = tasks, selectedTask = selectedTask,
                                        isNewChatMode = isNewChatMode,
                                        onNewChat = { selectedTask = ""; isNewChatMode = true; events = emptyList(); eventCursor = 0 },
                                        events = events, approvals = approvals,
                                        canRun = online && token.isNotBlank() && selectedProject.isNotBlank(),
                                        onSend = {
                                            scope.launch {
                                                try {
                                                    val t = api.startTask(selectedProject, prompt, mode, selectedAgent, if (selectedModel == "default") "" else selectedModel)
                                                    val taskId = t.getString("id")
                                                    selectedTask = taskId
                                                    isNewChatMode = false
                                                    AgentForegroundService.start(context, taskId, selectedProject, mode.uppercase(), prompt)
                                                    prompt = ""; error = ""
                                                    tasks = api.tasks(selectedProject).objects()
                                                } catch (e: Exception) { notify(e) }
                                            }
                                        },
                                        onCancel = {
                                            scope.launch {
                                                runCatching {
                                                    api.cancelTask(selectedTask)
                                                    AgentForegroundService.stop(context)
                                                    tasks = api.tasks(selectedProject).objects()
                                                }.onFailure { notify(it) }
                                            }
                                        },
                                        onApprove = { aid ->
                                            scope.launch {
                                                runCatching {
                                                    api.respondApproval(selectedTask, aid, true)
                                                    approvals = api.approvals(selectedTask).objects()
                                                }.onFailure { notify(it) }
                                            }
                                        },
                                        onDeny = { aid ->
                                            scope.launch {
                                                runCatching {
                                                    api.respondApproval(selectedTask, aid, false)
                                                    approvals = api.approvals(selectedTask).objects()
                                                }.onFailure { notify(it) }
                                            }
                                        },
                                        onCommitClick = { gitReviewDialog = true }
                                    )
                                }
                                Box(Modifier.weight(0.48f).fillMaxHeight()) {
                                    PreviewScreen(
                                        previewUrl, previewState,
                                        onStart = { scope.launch { runCatching { val p = api.previewStart(selectedProject); previewUrl = p.optString("url", ""); previewState = p.optString("status", "starting") }.onFailure { notify(it) } } },
                                        onStop = { scope.launch { runCatching { api.previewStop(selectedProject); previewState = "stopped"; previewUrl = "" }.onFailure { notify(it) } } },
                                        enabled = online && selectedProject.isNotBlank()
                                    )
                                }
                            }
                            2 -> PreviewScreen(
                                previewUrl, previewState,
                                onStart = { scope.launch { runCatching { val p = api.previewStart(selectedProject); previewUrl = p.optString("url", ""); previewState = p.optString("status", "starting") }.onFailure { notify(it) } } },
                                onStop = { scope.launch { runCatching { api.previewStop(selectedProject); previewState = "stopped"; previewUrl = "" }.onFailure { notify(it) } } },
                                enabled = online && selectedProject.isNotBlank()
                            )
                            3 -> FilesScreen(files, fileDir, fileName, fileBody,
                                onFolder = { fileDir = it; fileName = "" },
                                onFile = { path -> scope.launch { runCatching { api.file(selectedProject, path) }
                                    .onSuccess { fileName = path; fileBody = it.optString("content", "") }
                                    .onFailure { notify(it) } } },
                                onEdit = { fileBody = it },
                                onSave = { scope.launch { runCatching { api.saveFile(selectedProject, fileName, fileBody) }
                                    .onSuccess { error = "Saved $fileName" }
                                    .onFailure { notify(it) } } })
                            4 -> SettingsScreen(
                    tokenEntry = tokenEntry,
                    onToken = { tokenEntry = it },
                    onSave = {
                        try {
                            val trimmed = tokenEntry.trim()
                            SecureDeviceToken.save(context, trimmed)
                            EmbeddedBridgeManager.syncTokenFile(context, trimmed)
                            token = trimmed
                            error = "Device token saved"
                            scope.launch {
                                if (BootstrapManager.isReady(context)) {
                                    EmbeddedBridgeManager.restart(context)
                                }
                            }
                        } catch (e: Exception) { notify(e) }
                    },
                    onStart = { error = TermuxLauncher.startBridge(context) },
                    onRestartEmbedded = {
                        scope.launch {
                            error = "Restarting embedded PRoot bridge..."
                            val ok = EmbeddedBridgeManager.restart(context)
                            error = if (ok) "Embedded PRoot bridge active on 127.0.0.1:${EmbeddedBridgeManager.port.value}"
                            else "Embedded bridge failed to start"
                        }
                    },
                    online = online,
                    bridgeRuntime = bridgeRuntime,
                    bridgePort = bridgePort,
                    bridgeStatus = bridgeStatus,
                    agents = availableAgents,
                    selectedProject = selectedProject,
                    api = api,
                    cliVersions = cliVersions,
                    installingCli = installingCli,
                    onInstallCli = { type ->
                        installingCli = type
                        scope.launch {
                            try {
                                val res = when (type) {
                                    "Codex" -> CliInstaller.installCodex(context)
                                    "AGY" -> CliInstaller.installAgy(context)
                                    "Claude" -> CliInstaller.installClaude(context)
                                    else -> ExecutionResult(-1, "", "Unknown CLI")
                                }
                                notify(Throwable(if (res.exitCode == 0) "$type CLI installed successfully!" else "Failed: ${res.stderr}"))
                            } catch (e: Exception) {
                                notify(e)
                            } finally {
                                cliVersions = CliInstaller.getInstalledVersions(context)
                                cliAuthStatus = CliAuthManager.getAuthStatus(context)
                                installingCli = null
                            }
                        }
                    },
                    cliAuthStatus = cliAuthStatus,
                    keySummary = keySummary,
                    authBusy = authBusy,
                    onSignInCli = { cliType -> CliAuthManager.startOAuthLogin(context, cliType) },
                    onSignOutCli = { cliType ->
                        scope.launch {
                            authBusy = true
                            CliAuthManager.logoutCli(context, cliType)
                                .onSuccess { error = it }
                                .onFailure { notify(it) }
                            cliAuthStatus = CliAuthManager.getAuthStatus(context)
                            authBusy = false
                        }
                    },
                    onSaveProviderKey = { envName, value ->
                        scope.launch {
                            authBusy = true
                            CliAuthManager.saveProviderApiKey(context, envName, value)
                                .onSuccess {
                                    error = it
                                    keySummary = SecureSecrets.getSummary(context)
                                }
                                .onFailure { notify(it) }
                            cliAuthStatus = CliAuthManager.getAuthStatus(context)
                            authBusy = false
                        }
                    },
                    notify = notify
                )
            }
                    }
                }
            }
        }
    }
    if (oauthState.cliType.isNotBlank()) {
        CliOAuthDialog(
            state = oauthState,
            onSubmitCode = { CliAuthManager.submitPastedCode(context, it) },
            onCancel = { CliAuthManager.cancelOAuth() },
            onDone = {
                CliAuthManager.cancelOAuth()
                scope.launch { cliAuthStatus = CliAuthManager.getAuthStatus(context) }
            }
        )
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
                        cliAuthStatus: AllCliAuthStatus = AllCliAuthStatus(),
                        isEmbeddedRuntime: Boolean = false,
                        onOpenSettings: () -> Unit = {},
                        tasks: List<JSONObject>, selectedTask: String, onSelectTask: (String) -> Unit,
                        events: List<JSONObject>, approvals: List<JSONObject> = emptyList(),
                        canRun: Boolean, onSend: () -> Unit, onCancel: () -> Unit,
                        onApprove: (String) -> Unit = {}, onDeny: (String) -> Unit = {},
                        onCommitClick: () -> Unit = {}) {
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
        val rawAgents = if (agents.isNotEmpty()) agents.map {
            Triple(it.optString("name"), it.optString("display_name"), it.optBoolean("available", true))
        } else listOf(
            Triple("codex", "Codex CLI", true),
            Triple("claude", "Claude Code", true),
            Triple("antigravity", "Antigravity CLI (agy)", true)
        )

        val visibleAgents = rawAgents.filter { (name, _, avail) ->
            val signedIn = when (name) {
                "codex" -> cliAuthStatus.codex.signedIn
                "claude" -> cliAuthStatus.claude.signedIn
                "antigravity" -> cliAuthStatus.agy.signedIn
                else -> false
            }
            avail && (!isEmbeddedRuntime || (cliAuthStatus.loaded && signedIn))
        }

        LaunchedEffect(visibleAgents, selectedAgent) {
            if (visibleAgents.isNotEmpty() && visibleAgents.none { it.first == selectedAgent }) {
                onSelectAgent(visibleAgents.first().first)
                onSelectModel("default")
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Agent:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            if (visibleAgents.isEmpty()) {
                if (isEmbeddedRuntime && !cliAuthStatus.loaded) {
                    Text("Checking signed-in agents...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    SuggestionChip(
                        onClick = onOpenSettings,
                        label = { Text("No signed-in agent — Sign in in Settings", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            } else {
                visibleAgents.forEach { (name, label, _) ->
                    FilterChip(
                        selected = selectedAgent == name,
                        onClick = {
                            onSelectAgent(name)
                            onSelectModel("default")
                        },
                        label = { Text(label) },
                        leadingIcon = if (selectedAgent == name) {
                            { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                        } else null
                    )
                }
            }
        }
        val isSelectedVisible = visibleAgents.any { it.first == selectedAgent }
        val currAgentObj = if (isSelectedVisible) agents.firstOrNull { it.optString("name") == selectedAgent } else null
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
        var attachment by rememberSaveable { mutableStateOf("") }
        var showAttachDialog by remember { mutableStateOf(false) }

        if (attachment.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            InputChip(
                selected = true,
                onClick = { attachment = "" },
                label = { Text("📎 $attachment", style = MaterialTheme.typography.labelSmall) },
                trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) }
            )
        }

        Spacer(Modifier.height(6.dp))
        OutlinedTextField(prompt, onPrompt, modifier = Modifier.fillMaxWidth().heightIn(min = 115.dp),
            placeholder = { Text("Describe a feature, bug, or improvement…") }, maxLines = 5)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = mode == "plan", onClick = { onMode("plan") }, label = { Text("Plan only") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = mode == "build", onClick = { onMode("build") }, label = { Text("Build") })
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { showAttachDialog = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (attachment.isBlank()) "Attach" else "Change", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (attachment.isNotBlank() && !prompt.contains("[Attachment: $attachment]")) {
                        onPrompt("$prompt\n\n[Attachment: $attachment]")
                    }
                    onSend()
                },
                enabled = canRun && prompt.isNotBlank() && isSelectedVisible
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("Run ${selectedAgent.replaceFirstChar { it.uppercase() }}")
            }
        }
        if (showAttachDialog) {
            var tempPath by remember { mutableStateOf(attachment) }
            AlertDialog(
                onDismissRequest = { showAttachDialog = false },
                title = { Text("Attach Workspace File") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Privacy Warning: File contents will be added to the agent context. Never attach files containing secrets, API keys, or private tokens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedTextField(
                            value = tempPath,
                            onValueChange = { tempPath = it },
                            label = { Text("Workspace relative path") },
                            placeholder = { Text("package.json or src/index.ts") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        attachment = tempPath.trim()
                        showAttachDialog = false
                    }) { Text("Confirm Attachment") }
                },
                dismissButton = {
                    TextButton(onClick = { showAttachDialog = false }) { Text("Cancel") }
                }
            )
        }
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        val currentTask = tasks.firstOrNull { it.optString("id") == selectedTask }
        val currentTaskStatus = currentTask?.optString("status") ?: ""
        if (selectedTask.isNotBlank() && currentTaskStatus.isNotBlank()) {
            PhaseStepperBar(
                state = currentTaskStatus,
                modifier = Modifier.padding(bottom = 8.dp),
                onCommitClick = onCommitClick
            )
        }
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
private fun SettingsScreen(
    tokenEntry: String,
    onToken: (String) -> Unit,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onRestartEmbedded: () -> Unit = {},
    online: Boolean,
    bridgeRuntime: String = "",
    bridgePort: Int = 8765,
    bridgeStatus: EmbeddedBridgeStatus = EmbeddedBridgeStatus.Stopped,
    agents: List<JSONObject> = emptyList(),
    selectedProject: String = "",
    api: LocalApi? = null,
    cliVersions: CliVersions? = null,
    installingCli: String? = null,
    onInstallCli: (String) -> Unit = {},
    cliAuthStatus: AllCliAuthStatus = AllCliAuthStatus(),
    keySummary: ProviderKeySummary = ProviderKeySummary(),
    authBusy: Boolean = false,
    onSignInCli: (String) -> Unit = {},
    onSignOutCli: (String) -> Unit = {},
    onSaveProviderKey: (String, String) -> Unit = { _, _ -> },
    notify: (Throwable) -> Unit = {}
) {
    val context = LocalContext.current
    var section by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var instructionContent by remember { mutableStateOf("") }
    var instructionFile by remember { mutableStateOf("AGENTS.md") }
    var loadingInstructions by remember { mutableStateOf(false) }
    var codexProotBypass by remember { mutableStateOf(false) }
    var savingSecurity by remember { mutableStateOf(false) }

    LaunchedEffect(selectedProject, section, online) {
        if (section == 2 && selectedProject.isNotBlank() && api != null) {
            loadingInstructions = true
            runCatching {
                val res = api.projectInstructions(selectedProject)
                instructionFile = res.optString("file", "AGENTS.md")
                instructionContent = res.optString("content", "")
            }.onFailure { notify(it) }
            loadingInstructions = false
        }
        if (section == 3 && online && api != null) {
            runCatching {
                val s = api.settings()
                codexProotBypass = s.optBoolean("codex_proot_sandbox_bypass", false)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings & Diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                Triple("Runtime", Icons.Default.Tune, 0),
                Triple("Agent CLIs", Icons.Default.SmartToy, 1),
                Triple("Instructions", Icons.Default.Description, 2),
                Triple("Security", Icons.Default.Security, 3),
                Triple("Walkthrough", Icons.Default.HelpOutline, 4)
            ).forEach { (label, icon, idx) ->
                FilterChip(
                    selected = section == idx,
                    onClick = { section = idx },
                    label = { Text(label) },
                    leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) }
                )
            }
        }

        HorizontalDivider()

        when (section) {
            0 -> {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Embedded Standalone Runtime (Zero-Termux)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.weight(1f))
                            Surface(
                                color = if (BootstrapManager.isReady(context)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    if (BootstrapManager.isReady(context)) "ACTIVE" else "NOT READY",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.surface,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            "Engine: PRoot 5.1 (Userland ARM64 with talloc & ashmem)\nRootfs: Alpine Linux v3.21 (Node.js 22, NPM 10, Git 2.47, Python 3.12)\nPath: ${BootstrapManager.getRootfsDir(context).absolutePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (cliVersions != null) {
                            Text(
                                "Packages: Node ${cliVersions.node ?: "v22.23.5"}, Git ${cliVersions.git ?: "2.47.3"}, Python ${cliVersions.python ?: "3.12.16"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        val bridgeLabel = when {
                            online && bridgeRuntime == "embedded" -> "Embedded Bridge Online (127.0.0.1:$bridgePort)"
                            bridgeStatus is EmbeddedBridgeStatus.Starting -> "Starting Embedded PRoot Bridge..."
                            bridgeStatus is EmbeddedBridgeStatus.Error -> "Embedded Bridge Error: ${bridgeStatus.message}"
                            else -> "Embedded Bridge Standby (127.0.0.1:$bridgePort)"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                bridgeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(onClick = onRestartEmbedded) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (online && bridgeRuntime == "embedded") "Restart Bridge" else "Start Embedded Bridge")
                            }
                        }
                    }
                }

                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (online) Icons.Default.CheckCircle else Icons.Default.Info, null,
                                tint = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    online && bridgeRuntime == "embedded" -> "Active Mode: Zero-Termux Embedded Bridge (:$bridgePort)"
                                    online -> "Active Mode: External Termux Bridge (:$bridgePort)"
                                    else -> "Loopback Bridge Offline"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text("Optional external Termux bridge fallback; primary workspace runs natively inside Embedded PRoot.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedTextField(
                    value = tokenEntry,
                    onValueChange = onToken,
                    label = { Text("Device Token (Auto-synced in KeyStore)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, enabled = tokenEntry.isNotBlank()) {
                        Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Token")
                    }
                    OutlinedButton(onClick = onStart) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Termux Fallback")
                    }
                }
            }

            1 -> {
                Text("Official Agent CLIs & Auth (Embedded PRoot)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                OfficialCliCard(
                    title = "OpenAI Codex CLI",
                    subtitle = cliVersions?.codex ?: "curl -fsSL https://chatgpt.com/codex/install.sh | sh",
                    authState = cliAuthStatus.codex,
                    isInstalled = cliVersions?.codex != null,
                    isInstalling = installingCli == "Codex",
                    anyInstalling = installingCli != null || authBusy,
                    onSignIn = { onSignInCli("Codex") },
                    onSignOut = { onSignOutCli("Codex") },
                    onInstallOrUpdate = { onInstallCli("Codex") }
                )

                OfficialCliCard(
                    title = "Google Antigravity CLI",
                    subtitle = cliVersions?.agy ?: "curl -fsSL https://antigravity.google/cli/install.sh | bash",
                    authState = cliAuthStatus.agy,
                    isInstalled = cliVersions?.agy != null,
                    isInstalling = installingCli == "AGY",
                    anyInstalling = installingCli != null || authBusy,
                    onSignIn = { onSignInCli("AGY") },
                    onSignOut = { onSignOutCli("AGY") },
                    onInstallOrUpdate = { onInstallCli("AGY") }
                )

                OfficialCliCard(
                    title = "Anthropic Claude Code",
                    subtitle = cliVersions?.claude ?: "curl -fsSL https://claude.ai/install.sh | bash",
                    authState = cliAuthStatus.claude,
                    isInstalled = cliVersions?.claude != null,
                    isInstalling = installingCli == "Claude",
                    anyInstalling = installingCli != null || authBusy,
                    onSignIn = { onSignInCli("Claude") },
                    onSignOut = { onSignOutCli("Claude") },
                    onInstallOrUpdate = { onInstallCli("Claude") }
                )

                ProviderApiKeysCard(
                    summary = keySummary,
                    busy = authBusy,
                    onSaveKey = onSaveProviderKey
                )

                Spacer(Modifier.height(8.dp))
                Text("Installed Agent Capabilities (Bridge):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                if (agents.isEmpty()) {
                    Text("No agent capability metadata from bridge. Embedded PRoot agents are active.", style = MaterialTheme.typography.bodySmall)
                } else {
                    agents.forEach { a ->
                        val display = a.optString("display_name")
                        val avail = a.optBoolean("available", false)
                        val canPlan = a.optBoolean("can_plan", false)
                        val canBuild = a.optBoolean("can_build", false)
                        val resume = a.optBoolean("supports_resume", false)

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(display, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text(if (avail) "ONLINE" else "OFFLINE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Text("Capabilities: ${listOfNotNull(if (canPlan) "Plan" else null, if (canBuild) "Build" else null, if (resume) "Session Resume" else null).joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            2 -> {
                Text("Per-Project Instructions (${instructionFile})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (selectedProject.isBlank()) {
                    Text("Select a project from the top bar to inspect or edit instructions.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Instructions in AGENTS.md or CLAUDE.md are automatically injected into the agent context.", style = MaterialTheme.typography.bodySmall)
                    if (loadingInstructions) {
                        CircularProgressIndicator(Modifier.padding(16.dp))
                    } else {
                        OutlinedTextField(
                            value = instructionContent,
                            onValueChange = { instructionContent = it },
                            label = { Text(instructionFile) },
                            placeholder = { Text("# Project Development Guidelines\n\n- Build using npm run build\n- Test with npm test") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                            maxLines = 10
                        )
                        Button(
                            onClick = {
                                if (api != null && selectedProject.isNotBlank()) {
                                    scope.launch {
                                        runCatching {
                                            api.saveProjectInstructions(selectedProject, instructionContent)
                                        }.onSuccess { notify(Throwable("Saved $instructionFile")) }
                                         .onFailure { notify(it) }
                                    }
                                }
                            },
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save Instructions")
                        }
                    }
                }
            }

            3 -> {
                Text("Security Policies & Sandboxing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(
                    color = if (codexProotBypass) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Codex Build in Embedded PRoot (Disable Linux Sandbox)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (codexProotBypass) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Opt-in (OFF by default): Android PRoot blocks Linux bubblewrap/Landlock namespaces needed by Codex workspace-write. Enabling this passes --sandbox danger-full-access ONLY for Codex Build tasks inside PRoot; Plan tasks remain read-only.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (codexProotBypass) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = codexProotBypass,
                                enabled = online && api != null && !savingSecurity,
                                onCheckedChange = { checked ->
                                    if (api == null) return@Switch
                                    savingSecurity = true
                                    scope.launch {
                                        runCatching { api.updateSettings(checked) }
                                            .onSuccess { res ->
                                                codexProotBypass = res.optBoolean("codex_proot_sandbox_bypass", checked)
                                                notify(Throwable(if (codexProotBypass) "Enabled Codex Build PRoot opt-in (--sandbox danger-full-access)" else "Restored default Codex workspace-write sandbox"))
                                            }
                                            .onFailure { notify(it) }
                                        savingSecurity = false
                                    }
                                }
                            )
                        }
                    }
                }
                listOf(
                    Pair("Loopback Only", "All bridge communication binds exclusively to 127.0.0.1:8765. Remote network listeners and 0.0.0.0 bindings are strictly rejected."),
                    Pair("Android KeyStore Encryption", "The device token is encrypted at rest using AES-256 GCM authenticated cryptography in the Android KeyStore hardware module."),
                    Pair("No Autonomous Git Pushes", "Agents are prevented by system policy from executing git push or deploying code. Commits require manual user initiation via Git Review."),
                    Pair("Secrets Masking", "Secrets in .env files and API keys are masked using regex redaction before appearing in previews, logs, or prompt contexts.")
                ).forEach { (title, desc) ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text(desc, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            4 -> {
                Text("Termux Setup Walkthrough", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "1. Install Termux locally on Android.\n" +
                    "2. Run pkg update && pkg install -y python git nodejs-lts.\n" +
                    "3. Copy bridge files to ~/tablet-agent/bridge/.\n" +
                    "4. Run python ~/tablet-agent/bridge/server.py.\n" +
                    "5. Paste the generated Device Token into the Runtime tab.\n\n" +
                    "For one-click Start Bridge: In ~/.termux/termux.properties set allow-external-apps=true and grant RUN_COMMAND permission to Tablet Agent.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            committing = true
                            scope.launch {
                                try {
                                    api.gitRollback(project)
                                    refresh()
                                    notify(Throwable("Rolled back uncommitted changes to HEAD checkpoint"))
                                } catch (e: Exception) {
                                    notify(e)
                                } finally {
                                    committing = false
                                }
                            }
                        },
                        enabled = !committing
                    ) {
                        Text("Rollback to HEAD")
                    }
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
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
