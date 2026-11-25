package com.tabletagent.local

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.tabletagent.local.ui.CodeEditorWithLineNumbers
import com.tabletagent.local.ui.CoolifyDialog
import com.tabletagent.local.ui.GitHubUnifiedAccountCard
import com.tabletagent.local.ui.HapticFeedbackHelper
import com.tabletagent.local.ui.HermesMemoryDialog
import com.tabletagent.local.ui.InteractivePtyTerminal
import com.tabletagent.local.ui.OfficialCliCard
import com.tabletagent.local.ui.PhaseStepperBar
import com.tabletagent.local.ui.ProviderApiKeysCard
import com.tabletagent.local.ui.aionClaudeDarkScheme
import com.tabletagent.local.ui.aionClaudeLightScheme
import com.tabletagent.local.runtime.NetworkMonitor
import com.tabletagent.local.ui.StylusDrawingOverlay
import com.tabletagent.local.ui.InspectElementBottomSheet
import com.tabletagent.local.ui.InspectorJsHelper
import com.tabletagent.local.ui.InspectedElement
import com.tabletagent.local.ui.KeyboardAccessoryBar
import com.tabletagent.local.ui.StarterTemplatesDialog
import com.tabletagent.local.ui.SecurityAuditDialog
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
        NetworkMonitor.start(this)
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
    var splitRatio by rememberSaveable { mutableFloatStateOf(0.50f) }
    var cloningProject by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedProject by rememberSaveable { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedTask by rememberSaveable { mutableStateOf("") }
    var events by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var liveStream by remember { mutableStateOf<JSONObject?>(null) }
    var approvals by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var eventCursor by rememberSaveable { mutableIntStateOf(0) }
    var previewUrl by remember { mutableStateOf("") }
    var previewState by remember { mutableStateOf("stopped") }
    var previewLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var files by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var fileDir by rememberSaveable { mutableStateOf(".") }
    var fileName by remember { mutableStateOf("") }
    var fileBody by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val notify: (Throwable) -> Unit = { 
        if (it !is kotlin.coroutines.cancellation.CancellationException) {
            error = it.message ?: it.javaClass.simpleName 
        }
    }
    var createDialog by remember { mutableStateOf(false) }
    var showTemplatesDialog by remember { mutableStateOf(false) }
    var isLaunchingTemplate by remember { mutableStateOf(false) }
    val isInternetConnected by NetworkMonitor.isOnline.collectAsState()
    var previousCloudAgent by rememberSaveable { mutableStateOf("codex") }
    var gitReviewDialog by remember { mutableStateOf(false) }
    var showCoolifyDialog by remember { mutableStateOf(false) }
    var showSlmManagerDialog by remember { mutableStateOf(false) }
    var pickerExpanded by remember { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("plan") }
    var availableAgents by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedAgent by rememberSaveable { mutableStateOf("codex") }
    var selectedModel by rememberSaveable { mutableStateOf("default") }

    LaunchedEffect(isInternetConnected) {
        if (!isInternetConnected) {
            if (selectedAgent != "local_slm") {
                previousCloudAgent = selectedAgent
                selectedAgent = "local_slm"
                selectedModel = "qwen2.5-coder-1.5b"
            }
        } else {
            if (selectedAgent == "local_slm" && previousCloudAgent != "local_slm") {
                selectedAgent = previousCloudAgent
                selectedModel = "default"
            }
        }
    }
    var tokenEntry by remember { mutableStateOf(token) }
    val bootstrapStatus by BootstrapManager.status.collectAsState()
    val isRuntimeReady = remember(bootstrapStatus) { BootstrapManager.isReady(context) }
    var cliVersions by remember { mutableStateOf<CliVersions?>(null) }
    var installingCli by remember { mutableStateOf<String?>(null) }
    val oauthState by CliAuthManager.oauthState.collectAsState()
    var cliAuthStatus by remember { mutableStateOf(AllCliAuthStatus()) }
    var keySummary by remember { mutableStateOf(SecureSecrets.getSummary(context)) }
    var authBusy by remember { mutableStateOf(false) }
    var detectedApks by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var hermesMemoryData by remember { mutableStateOf<JSONObject?>(null) }
    var showHermesMemoryDialog by remember { mutableStateOf(false) }

    val launchInstallApk: (String) -> Unit = { apkPath ->
        try {
            val rootfsWs = java.io.File(context.filesDir, "runtime/rootfs/root/tablet-agent/workspaces/$selectedProject")
            val candidate1 = java.io.File(rootfsWs, apkPath)
            val candidate2 = java.io.File(apkPath)
            val srcFile = if (candidate1.exists()) candidate1 else candidate2
            val cacheApk = java.io.File(context.cacheDir, "install_${srcFile.name}")
            if (srcFile.exists()) {
                srcFile.copyTo(cacheApk, overwrite = true)
            }
            val targetFile = if (cacheApk.exists()) cacheApk else srcFile
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            notify(e)
        }
    }

    val doExportProject: () -> Unit = {
        scope.launch {
            runCatching {
                val res = api.exportProject(selectedProject, false)
                val path = res.optString("zip_path")
                val bytes = res.optLong("size_bytes", 0)
                val mb = String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
                error = "Project exported: $path ($mb)"
            }.onFailure { notify(it) }
        }
    }

    val doViewMemory: () -> Unit = {
        scope.launch {
            runCatching {
                val mem = api.hermesMemory(selectedProject)
                hermesMemoryData = mem
                showHermesMemoryDialog = true
            }.onFailure { notify(it) }
        }
    }

    val uploadAttachmentHandler: suspend (String, String) -> String? = { fname, b64 ->
        try {
            val res = api.uploadAttachment(selectedProject, fname, b64)
            res.optString("path", ".agents/attachments/$fname")
        } catch (e: Exception) {
            notify(e)
            null
        }
    }

    val applyCodeHandler: (String, String) -> Unit = { path, code ->
        scope.launch {
            runCatching {
                api.gitCheckpoint(selectedProject, "apply-to-file")
                api.saveFile(selectedProject, path, code, createDirs = true)
            }.onSuccess {
                error = "Applied code to $path (Git checkpoint saved)"
            }.onFailure { notify(it) }
        }
    }

    val openFileInWorkspaceHandler: (String) -> Unit = { path ->
        scope.launch {
            runCatching { api.file(selectedProject, path) }
                .onSuccess {
                    fileName = path
                    fileBody = it.optString("content", "")
                    tab = 3
                }
                .onFailure { notify(it) }
        }
    }

    val sendPromptHandler: (String) -> Unit = { textToSend ->
        val cleanText = textToSend.trim()
        if (cleanText.isNotBlank() && online && token.isNotBlank() && selectedProject.isNotBlank()) {
            scope.launch {
                try {
                    val continueTid = if (isNewChatMode) "" else selectedTask
                    val t = api.startTask(
                        selectedProject,
                        cleanText,
                        mode,
                        selectedAgent,
                        if (selectedModel == "default") "" else selectedModel,
                        continueTid
                    )
                    val taskId = t.getString("id")
                    selectedTask = taskId
                    isNewChatMode = false
                    AgentForegroundService.start(context, taskId, selectedProject, mode.uppercase(), cleanText)
                    prompt = ""; error = ""
                    tasks = api.tasks(selectedProject).objects()
                } catch (e: Exception) { notify(e) }
            }
        }
    }

    val proceedPlanHandler: () -> Unit = {
        if (online && token.isNotBlank() && selectedProject.isNotBlank() && selectedTask.isNotBlank()) {
            mode = "build"
            val execPrompt = "Proceed with the approved plan and implement all steps."
            scope.launch {
                try {
                    val t = api.startTask(
                        selectedProject,
                        execPrompt,
                        "build",
                        selectedAgent,
                        if (selectedModel == "default") "" else selectedModel,
                        selectedTask
                    )
                    val taskId = t.getString("id")
                    selectedTask = taskId
                    isNewChatMode = false
                    AgentForegroundService.start(context, taskId, selectedProject, "BUILD", execPrompt)
                    error = ""
                    tasks = api.tasks(selectedProject).objects()
                } catch (e: Exception) { notify(e) }
            }
        }
    }

    val retryLastPromptHandler: () -> Unit = {
        val lastPrompt = events.lastOrNull {
            it.optString("kind") == "user"
        }?.optString("message", "")
            ?.ifBlank { null }
            ?: tasks.firstOrNull { it.optString("id") == selectedTask }?.optString("prompt", "")
            ?: ""
        if (lastPrompt.isNotBlank() && online && token.isNotBlank() && selectedProject.isNotBlank()) {
            scope.launch {
                try {
                    val continueTid = if (isNewChatMode) "" else selectedTask
                    val t = api.startTask(
                        selectedProject,
                        lastPrompt,
                        mode,
                        selectedAgent,
                        if (selectedModel == "default") "" else selectedModel,
                        continueTid
                    )
                    val taskId = t.getString("id")
                    selectedTask = taskId
                    isNewChatMode = false
                    AgentForegroundService.start(context, taskId, selectedProject, mode.uppercase(), lastPrompt)
                    error = ""
                    tasks = api.tasks(selectedProject).objects()
                } catch (e: Exception) { notify(e) }
            }
        }
    }

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
            keySummary = SecureSecrets.getSummary(context)
        }
    }

    LaunchedEffect(selectedTask) {
        events = emptyList()
        liveStream = null
        approvals = emptyList()
        eventCursor = 0
    }
    // Reconnect/poll on-device: throttled and non-mutating on identical snapshots to prevent scroll lag.
    var pollTick by remember { mutableIntStateOf(0) }
    var lastTasksRaw by remember { mutableStateOf("") }
    var lastProjectsRaw by remember { mutableStateOf("") }
    var lastApprovalsRaw by remember { mutableStateOf("") }
    val activeRunningTasks = remember { mutableSetOf<String>() }
    val notifiedCompletedTasks = remember { mutableSetOf<String>() }
    val notifiedApprovalIds = remember { mutableSetOf<String>() }
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
                        if (pollTick % 5 == 0) {
                            runCatching { api.listApks(selectedProject).objects() }
                                .onSuccess { detectedApks = it }
                        }
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
                            val atid = activeTask.optString("id")
                            if (atid.isNotBlank()) activeRunningTasks.add(atid)
                            AgentForegroundService.update(
                                context,
                                atid,
                                activeTask.optString("status"),
                                activeTask.optString("prompt")
                            )
                        } else if (tasks.isNotEmpty() && tasks.all { it.optString("status") in listOf("PLANNED", "COMPLETED", "NEEDS_VERIFICATION", "FAILED", "CANCELLED", "BLOCKED_SETUP", "INTERRUPTED") }) {
                            AgentForegroundService.stop(context)
                        }

                        // Check for task completion alerts and haptic feedback
                        for (t in tasks) {
                            val tid = t.optString("id")
                            val tStatus = t.optString("status")
                            if (tid in activeRunningTasks && tStatus in listOf("PLANNED", "COMPLETED", "NEEDS_VERIFICATION", "FAILED")) {
                                if (tid !in notifiedCompletedTasks) {
                                    notifiedCompletedTasks.add(tid)
                                    activeRunningTasks.remove(tid)
                                    AgentForegroundService.notifyTaskCompleted(context, selectedProject, tid, tStatus, t.optString("prompt"))
                                }
                            }
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
                                    val merged = events + prefix + fresh
                                    events = if (merged.size > 600) {
                                        val importantKinds = setOf("user", "message", "thought", "reasoning", "error")
                                        val important = merged.filter { it.optString("kind").lowercase() in importantKinds }
                                        val others = merged.filter { it.optString("kind").lowercase() !in importantKinds }.takeLast(250)
                                        (important + others).sortedBy { it.optInt("seq", 0) }
                                    } else {
                                        merged
                                    }
                                }
                                liveStream = page.optJSONObject("live_stream")
                                keepPaging = page.optBoolean("has_more", false) && fresh.isNotEmpty()
                            }
                            if (activeTask != null || pollTick == 0) {
                                runCatching {
                                    val appArr = api.approvals(selectedTask)
                                    val appRaw = appArr.toString()
                                    if (appRaw != lastApprovalsRaw) {
                                        lastApprovalsRaw = appRaw
                                        approvals = appArr.objects()
                                        for (app in approvals) {
                                            val aid = app.optString("id")
                                            if (aid.isNotBlank() && aid !in notifiedApprovalIds) {
                                                notifiedApprovalIds.add(aid)
                                                val actDesc = app.optString("action", app.optString("command", "Agent action confirmation required"))
                                                AgentForegroundService.notifyApprovalRequired(context, selectedProject, selectedTask, actDesc)
                                            }
                                        }
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
            if (!hasRunningTask && liveStream != null) liveStream = null
            delay(if (hasRunningTask) 700L else 3200L)
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
                        onOpenTemplates = { showTemplatesDialog = true },
                        onDeleteProject = { name ->
                            scope.launch {
                                runCatching {
                                    api.deleteProject(name)
                                    val projArr = api.projects()
                                    lastProjectsRaw = projArr.toString()
                                    projects = projArr.objects()
                                    if (selectedProject == name) {
                                        selectedProject = projects.firstOrNull()?.optString("name") ?: ""
                                        selectedTask = ""
                                        events = emptyList()
                                        liveStream = null
                                        eventCursor = 0
                                        fileDir = "."
                                        fileName = ""
                                    }
                                    error = "Deleted project $name"
                                }.onFailure { notify(it) }
                            }
                        },
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
                        onSelectWorkspaceView = { nextView ->
                            tab = nextView
                            if (nextView == 2) sidebarOpen = false
                        },
                        onOpenGit = { gitReviewDialog = true },
                        onOpenCoolify = { showCoolifyDialog = true },
                        onOpenSlm = { showSlmManagerDialog = true },
                        apks = detectedApks,
                        onInstallApk = launchInstallApk,
                        onExportProject = doExportProject,
                        onViewMemory = doViewMemory
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
                        val createOrCloneHandler: (String, String) -> Unit = { name, gitUrl ->
                            scope.launch {
                                cloningProject = true
                                error = if (gitUrl.isNotBlank()) "Cloning $gitUrl into $name…" else "Creating workspace $name…"
                                val ghToken = SecureSecrets.getKey(context, SecureSecrets.KEY_GITHUB)
                                runCatching { api.createProject(name.trim(), gitUrl.trim(), ghToken) }
                                    .onSuccess {
                                        val projArr = api.projects()
                                        lastProjectsRaw = projArr.toString()
                                        projects = projArr.objects()
                                        selectedProject = name.trim()
                                        selectedTask = ""
                                        events = emptyList()
                                        eventCursor = 0
                                        error = if (gitUrl.isNotBlank()) "Cloned $name successfully" else "Created project $name"
                                    }
                                    .onFailure { notify(it) }
                                cloningProject = false
                            }
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
                                onCreateOrCloneProject = createOrCloneHandler,
                                onSignInGitHub = { CliAuthManager.startOAuthLogin(context, "GitHub") },
                                cloningProject = cloningProject,
                                onOpenSettings = { tab = 4 },
                                tasks = tasks, selectedTask = selectedTask,
                                isNewChatMode = isNewChatMode,
                                onNewChat = { selectedTask = ""; isNewChatMode = true; events = emptyList(); eventCursor = 0 },
                                events = events, approvals = approvals,
                                liveStream = liveStream,
                                canRun = online && token.isNotBlank() && selectedProject.isNotBlank(),
                                onSend = {
                                    scope.launch {
                                        try {
                                            val continueTid = if (isNewChatMode) "" else selectedTask
                                            val t = api.startTask(selectedProject, prompt, mode, selectedAgent, if (selectedModel == "default") "" else selectedModel, continueTid)
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
                                onCommitClick = { gitReviewDialog = true },
                                apks = detectedApks,
                                onInstallApk = launchInstallApk,
                                onExportProject = doExportProject,
                                onViewMemory = doViewMemory,
                                onUploadAttachment = uploadAttachmentHandler,
                                onApplyCode = applyCodeHandler,
                                onRetryLastPrompt = retryLastPromptHandler,
                                onOpenFileInWorkspace = openFileInWorkspaceHandler,
                                onProceedPlan = proceedPlanHandler,
                                onSendPrompt = sendPromptHandler
                            )
                            1 -> BoxWithConstraints(Modifier.fillMaxSize()) {
                                val density = LocalDensity.current
                                val totalWidthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
                                Row(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(splitRatio).fillMaxHeight()) {
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
                                            onCreateOrCloneProject = createOrCloneHandler,
                                            onSignInGitHub = { CliAuthManager.startOAuthLogin(context, "GitHub") },
                                            cloningProject = cloningProject,
                                            onOpenSettings = { tab = 4 },
                                            tasks = tasks, selectedTask = selectedTask,
                                            isNewChatMode = isNewChatMode,
                                            onNewChat = { selectedTask = ""; isNewChatMode = true; events = emptyList(); eventCursor = 0 },
                                            events = events, approvals = approvals,
                                            liveStream = liveStream,
                                            canRun = online && token.isNotBlank() && selectedProject.isNotBlank(),
                                            onSend = {
                                                scope.launch {
                                                    try {
                                                        val continueTid = if (isNewChatMode) "" else selectedTask
                                                        val t = api.startTask(selectedProject, prompt, mode, selectedAgent, if (selectedModel == "default") "" else selectedModel, continueTid)
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
                                            onCommitClick = { gitReviewDialog = true },
                                            apks = detectedApks,
                                            onInstallApk = launchInstallApk,
                                            onExportProject = doExportProject,
                                            onViewMemory = doViewMemory,
                                            onUploadAttachment = uploadAttachmentHandler,
                                            onApplyCode = applyCodeHandler,
                                            onRetryLastPrompt = retryLastPromptHandler,
                                            onOpenFileInWorkspace = openFileInWorkspaceHandler,
                                            onProceedPlan = proceedPlanHandler,
                                            onSendPrompt = sendPromptHandler
                                        )
                                    }

                                    // Draggable Split Divider Handle
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(16.dp)
                                            .pointerInput(totalWidthPx) {
                                                detectHorizontalDragGestures { change, dragAmount ->
                                                    change.consume()
                                                    splitRatio = (splitRatio + dragAmount / totalWidthPx).coerceIn(0.26f, 0.74f)
                                                }
                                            }
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxHeight()
                                                .width(1.dp)
                                                .background(MaterialTheme.colorScheme.outlineVariant)
                                        )
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(50),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                            modifier = Modifier.width(6.dp).height(44.dp)
                                        ) {}
                                    }

                                    Box(Modifier.weight(1f - splitRatio).fillMaxHeight()) {
                                        PreviewScreen(
                                            url = previewUrl, status = previewState, logs = previewLogs,
                                            project = selectedProject, api = api,
                                            onSendPromptWithSketch = { instr, b64 ->
                                                tab = 0
                                                prompt = instr
                                                scope.launch {
                                                    runCatching {
                                                        val fn = "sketch_${System.currentTimeMillis()}.jpg"
                                                        api.saveAttachment(selectedProject, fn, b64)
                                                        prompt = "Attached sketch annotation ($fn):\n$instr"
                                                    }
                                                }
                                            },
                                            onSendPromptWithInspect = { instr ->
                                                tab = 0
                                                prompt = instr
                                            },
                                            onStart = { scope.launch { runCatching {
                                                val p = api.previewStart(selectedProject)
                                                previewUrl = p.optString("url", "")
                                                previewState = p.optString("status", "starting")
                                                val arr = p.optJSONArray("logs")
                                                if (arr != null) previewLogs = (0 until arr.length()).map { arr.getString(it) }
                                            }.onFailure { notify(it) } } },
                                            onStop = { scope.launch { runCatching { api.previewStop(selectedProject); previewState = "stopped"; previewUrl = ""; previewLogs = emptyList() }.onFailure { notify(it) } } },
                                            enabled = online && selectedProject.isNotBlank()
                                        )
                                    }
                                }
                            }
                            2 -> PreviewScreen(
                                url = previewUrl, status = previewState, logs = previewLogs,
                                project = selectedProject, api = api,
                                onSendPromptWithSketch = { instr, b64 ->
                                    tab = 0
                                    prompt = instr
                                    scope.launch {
                                        runCatching {
                                            val fn = "sketch_${System.currentTimeMillis()}.jpg"
                                            api.saveAttachment(selectedProject, fn, b64)
                                            prompt = "Attached sketch annotation ($fn):\n$instr"
                                        }
                                    }
                                },
                                onSendPromptWithInspect = { instr ->
                                    tab = 0
                                    prompt = instr
                                },
                                onStart = { scope.launch { runCatching {
                                    val p = api.previewStart(selectedProject)
                                    previewUrl = p.optString("url", "")
                                    previewState = p.optString("status", "starting")
                                    val arr = p.optJSONArray("logs")
                                    if (arr != null) previewLogs = (0 until arr.length()).map { arr.getString(it) }
                                }.onFailure { notify(it) } } },
                                onStop = { scope.launch { runCatching { api.previewStop(selectedProject); previewState = "stopped"; previewUrl = ""; previewLogs = emptyList() }.onFailure { notify(it) } } },
                                enabled = online && selectedProject.isNotBlank()
                            )
                            3 -> FilesScreen(files, fileDir, fileName, fileBody,
                                project = selectedProject, api = api,
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
                            keySummary = SecureSecrets.getSummary(context)
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
                            keySummary = SecureSecrets.getSummary(context)
                            authBusy = false
                        }
                    },
                    onRefreshKeySummary = { keySummary = SecureSecrets.getSummary(context) },
                    notify = notify
                )
                5 -> InteractivePtyTerminal(
                    project = selectedProject,
                    api = api
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
                scope.launch {
                    cliAuthStatus = CliAuthManager.getAuthStatus(context)
                    keySummary = SecureSecrets.getSummary(context)
                }
            }
        )
    }
    if (createDialog) {
        var dialogTab by remember { mutableIntStateOf(0) }
        var name by remember { mutableStateOf("") }
        var gitUrl by remember { mutableStateOf("") }
        var userRepos by remember { mutableStateOf<List<com.tabletagent.local.runtime.GitHubRepoItem>>(emptyList()) }
        var loadingRepos by remember { mutableStateOf(false) }
        var repoSearch by remember { mutableStateOf("") }
        var isCloning by remember { mutableStateOf(false) }
        var cloneError by remember { mutableStateOf("") }
        val ghSignedIn = cliAuthStatus.github.signedIn

        LaunchedEffect(dialogTab, ghSignedIn) {
            if (dialogTab == 0 && ghSignedIn) {
                loadingRepos = true
                cloneError = ""
                CliAuthManager.fetchGitHubUserRepos(context)
                    .onSuccess { userRepos = it }
                    .onFailure { cloneError = it.message ?: "Failed to fetch GitHub repos" }
                loadingRepos = false
            }
        }

        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text("Open or Clone Project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = dialogTab == 0,
                            onClick = { dialogTab = 0 },
                            label = { Text("Your GitHub Repos") },
                            leadingIcon = { Icon(Icons.Default.CloudDownload, null, Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = dialogTab == 1,
                            onClick = { dialogTab = 1 },
                            label = { Text("GitHub URL / New") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, Modifier.size(16.dp)) }
                        )
                    }

                    if (dialogTab == 0) {
                        if (!ghSignedIn) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Sign in to GitHub to browse & clone your repositories", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    Text("Authorize once via GitHub Device OAuth to see both your public and private repositories.", style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = { CliAuthManager.startOAuthLogin(context, "GitHub") }) {
                                        Icon(Icons.Default.Login, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Sign in to GitHub")
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = repoSearch,
                                onValueChange = { repoSearch = it },
                                label = { Text("Search ${cliAuthStatus.github.label} repos") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (loadingRepos) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            val filtered = userRepos.filter {
                                repoSearch.isBlank() || it.fullName.contains(repoSearch, ignoreCase = true)
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                filtered.forEach { repo ->
                                    Surface(
                                        onClick = {
                                            val safeName = repo.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                            name = safeName
                                            gitUrl = repo.cloneUrl
                                            dialogTab = 1
                                        },
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Public, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(repo.fullName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                                if (repo.description.isNotBlank()) {
                                                    Text(repo.description, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                            Text("Select", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        if (cloneError.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        cloneError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = gitUrl,
                            onValueChange = { url ->
                                gitUrl = url
                                cloneError = ""
                                val clean = url.trim().removeSuffix("/").removeSuffix(".git")
                                val derived = clean.substringAfterLast('/')
                                if (derived.isNotBlank() && !derived.contains("github.com") && !derived.contains(':')) {
                                    name = derived.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                }
                            },
                            label = { Text("GitHub Repository URL or owner/repo") },
                            placeholder = { Text("https://github.com/owner/repo or owner/repo") },
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { input ->
                                cloneError = ""
                                name = input.replace(' ', '-').replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            },
                            label = { Text("Project folder name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                if (dialogTab == 1) {
                    Button(
                        onClick = {
                            scope.launch {
                                isCloning = true
                                cloneError = ""
                                val ghToken = SecureSecrets.getKey(context, SecureSecrets.KEY_GITHUB)
                                runCatching { api.createProject(name.trim(), gitUrl.trim(), ghToken) }
                                    .onSuccess {
                                        selectedProject = name.trim()
                                        createDialog = false
                                        projects = api.projects().objects()
                                    }
                                    .onFailure { err ->
                                        cloneError = err.message ?: "Failed to create or clone project"
                                        notify(err)
                                    }
                                isCloning = false
                            }
                        },
                        enabled = name.isNotBlank() && !isCloning
                    ) {
                        if (isCloning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(if (gitUrl.isNotBlank()) "Cloning…" else "Creating…")
                        } else {
                            Text(if (gitUrl.isNotBlank()) "Clone & Open" else "Create Project")
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }, enabled = !isCloning) { Text("Cancel") } }
        )
    }
    if (showTemplatesDialog) {
        StarterTemplatesDialog(
            onDismiss = { showTemplatesDialog = false },
            isCreating = isLaunchingTemplate,
            onLaunchTemplate = { tName, tmplId ->
                scope.launch {
                    isLaunchingTemplate = true
                    runCatching {
                        api.initTemplate(tName, tmplId)
                    }.onSuccess {
                        showTemplatesDialog = false
                        val projArr = api.projects()
                        projects = projArr.objects()
                        selectedProject = tName
                        selectedTask = ""
                        events = emptyList()
                        runCatching { api.previewStart(tName) }
                        error = "Initialized template $tmplId for $tName"
                    }.onFailure { notify(it) }
                    isLaunchingTemplate = false
                }
            }
        )
    }
    if (gitReviewDialog && selectedProject.isNotBlank()) {
        GitReviewDialog(
            project = selectedProject,
            api = api,
            onDismiss = { gitReviewDialog = false },
            onCommitted = { error = "Changes committed locally" },
            notify = notify,
            onAutoFixPrompt = { fixPrompt ->
                tab = 0
                prompt = fixPrompt
            }
        )
    }
    if (showHermesMemoryDialog && selectedProject.isNotBlank()) {
        HermesMemoryDialog(
            project = selectedProject,
            memory = hermesMemoryData,
            onDismiss = { showHermesMemoryDialog = false }
        )
    }
    if (showSlmManagerDialog) {
        SlmManagerDialog(
            api = api,
            onDismiss = { showSlmManagerDialog = false },
            notify = notify
        )
    }
    if (showCoolifyDialog) {
        CoolifyDialog(
            api = api,
            selectedProject = selectedProject,
            onDismiss = { showCoolifyDialog = false },
            onOpenSettings = {
                showCoolifyDialog = false
                tab = 4
            },
            onSendDeployPrompt = { deployPrompt ->
                showCoolifyDialog = false
                tab = 0
                prompt = deployPrompt
            }
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
private fun PreviewScreen(
    url: String,
    status: String,
    logs: List<String> = emptyList(),
    project: String = "",
    api: LocalApi? = null,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSendPromptWithSketch: ((instruction: String, imageB64: String) -> Unit)? = null,
    onSendPromptWithInspect: ((instruction: String) -> Unit)? = null,
    enabled: Boolean
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var viewportMode by rememberSaveable { mutableStateOf("full") }
    var toolbarCollapsed by rememberSaveable { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Super Suite: Stylus, Inspector, and PWA Export states
    var isDrawingMode by remember { mutableStateOf(false) }
    var isInspectMode by remember { mutableStateOf(false) }
    var inspectedElement by remember { mutableStateOf<InspectedElement?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showPwaSuccessDialog by remember { mutableStateOf(false) }
    var isConfiguringPwa by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // Status Dot
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (status == "running") MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Spacer(Modifier.width(6.dp))

                // Start / Stop Icon Button
                if (status == "stopped") {
                    FilledTonalIconButton(
                        onClick = onStart,
                        enabled = enabled,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start dev server", modifier = Modifier.size(16.dp))
                    }
                } else {
                    FilledTonalIconButton(
                        onClick = onStop,
                        enabled = enabled,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop dev server", modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Reload Icon Button
                IconButton(
                    onClick = { refreshTick++ },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(16.dp))
                }

                // Super Suite 1: Stylus Drawing Mode Button
                FilledTonalIconButton(
                    onClick = {
                        isDrawingMode = !isDrawingMode
                        if (isDrawingMode) isInspectMode = false
                    },
                    enabled = url.isNotBlank(),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isDrawingMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        contentColor = if (isDrawingMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Default.Draw, contentDescription = "Draw on Preview", modifier = Modifier.size(16.dp))
                }

                Spacer(Modifier.width(2.dp))

                // Super Suite 2: Click-to-Inspect Mode Button
                FilledTonalIconButton(
                    onClick = {
                        isInspectMode = !isInspectMode
                        if (isInspectMode) isDrawingMode = false
                        webViewRef?.evaluateJavascript(
                            if (isInspectMode) InspectorJsHelper.INJECT_INSPECTOR_SCRIPT else InspectorJsHelper.REMOVE_INSPECTOR_SCRIPT,
                            null
                        )
                    },
                    enabled = url.isNotBlank(),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isInspectMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        contentColor = if (isInspectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Default.FindInPage, contentDescription = "Click to Inspect", modifier = Modifier.size(16.dp))
                }

                Spacer(Modifier.width(2.dp))

                // Super Suite 7: 1-Click PWA Fullscreen Exporter Button
                IconButton(
                    onClick = {
                        scope.launch {
                            isConfiguringPwa = true
                            runCatching {
                                api?.pwaConfigure(project)
                            }.onSuccess {
                                showPwaSuccessDialog = true
                            }
                            isConfiguringPwa = false
                        }
                    },
                    enabled = url.isNotBlank() && !isConfiguringPwa,
                    modifier = Modifier.size(30.dp)
                ) {
                    if (isConfiguringPwa) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.InstallMobile, contentDescription = "Export as PWA", modifier = Modifier.size(16.dp))
                    }
                }

                if (!toolbarCollapsed) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (url.isNotBlank()) url else "npm run dev ($status)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                // Responsive Viewport Icon Pill
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(2.dp)
                    ) {
                        listOf(
                            Triple("full", Icons.Default.DesktopWindows, "Full width"),
                            Triple("tablet", Icons.Default.TabletMac, "Tablet (768dp)"),
                            Triple("mobile", Icons.Default.PhoneAndroid, "Phone (375dp)")
                        ).forEach { (modeKey, icon, desc) ->
                            val selected = viewportMode == modeKey
                            Surface(
                                onClick = { viewportMode = modeKey },
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        icon,
                                        contentDescription = desc,
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Collapse / Expand toggle
                IconButton(
                    onClick = { toolbarCollapsed = !toolbarCollapsed },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (toolbarCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                        contentDescription = "Toggle preview toolbar",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        if (url.startsWith("http://127.0.0.1:")) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val previewMod = when (viewportMode) {
                    "tablet" -> Modifier.width(768.dp).fillMaxHeight()
                    "mobile" -> Modifier.width(375.dp).fillMaxHeight()
                    else -> Modifier.fillMaxSize()
                }
                key(url, refreshTick) {
                    LocalPreview(
                        url = url,
                        modifier = previewMod,
                        onWebViewCreated = { wv ->
                            webViewRef = wv
                            wv.webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                    val msg = consoleMessage?.message() ?: return false
                                    if (msg.startsWith("__OMNI_INSPECT__:")) {
                                        val jsonStr = msg.removePrefix("__OMNI_INSPECT__:")
                                        runCatching {
                                            val obj = JSONObject(jsonStr)
                                            inspectedElement = InspectedElement(
                                                tag = obj.optString("tag", ""),
                                                id = obj.optString("id", ""),
                                                classes = obj.optString("classes", ""),
                                                htmlSnippet = obj.optString("htmlSnippet", ""),
                                                computedText = obj.optString("computedText", "")
                                            )
                                        }
                                        return true
                                    }
                                    return super.onConsoleMessage(consoleMessage)
                                }
                            }
                        }
                    )
                }

                // Super Suite 1: Stylus Drawing Canvas Overlay
                if (isDrawingMode) {
                    StylusDrawingOverlay(
                        targetView = webViewRef,
                        onCancel = { isDrawingMode = false },
                        onApplySketch = { b64, instruction ->
                            isDrawingMode = false
                            onSendPromptWithSketch?.invoke(instruction, b64)
                        }
                    )
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("No local preview running", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap ▶ above to start npm run dev", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Super Suite 2: Bottom Sheet for Inspected Element
        if (inspectedElement != null) {
            InspectElementBottomSheet(
                element = inspectedElement,
                onDismiss = { inspectedElement = null },
                onModifyWithAi = { instruction ->
                    onSendPromptWithInspect?.invoke(instruction)
                }
            )
        }

        // Super Suite 7: PWA Configured Dialog
        if (showPwaSuccessDialog) {
            AlertDialog(
                onDismissRequest = { showPwaSuccessDialog = false },
                icon = { Icon(Icons.Default.InstallMobile, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("PWA Configured") },
                text = {
                    Text("manifest.json and service worker caching are installed in public/. You can launch and install this app as a standalone fullscreen PWA on your tablet.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPwaSuccessDialog = false
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    ) {
                        Text("Open Fullscreen")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPwaSuccessDialog = false }) { Text("Close") }
                }
            )
        }
        if (logs.isNotEmpty()) {
            Surface(
                color = Color(0xFF0F172A),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().clickable { showLogs = !showLogs }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Terminal, null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                            Text("Dev Server Logs (${logs.size} lines)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Text(if (showLogs) "Hide" else "Show", fontSize = 10.sp, color = Color(0xFF94A3B8))
                    }
                    if (showLogs) {
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                            items(logs.takeLast(60)) { logLine ->
                                Text(logLine, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Color(0xFFE2E8F0))
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LocalPreview(
    url: String,
    modifier: Modifier = Modifier,
    onWebViewCreated: ((WebView) -> Unit)? = null
) {
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
            onWebViewCreated?.invoke(this)
            loadUrl(url)
        }
    }, update = { wv ->
        onWebViewCreated?.invoke(wv)
    })
}

@Composable
private fun FilesScreen(
    files: List<JSONObject>,
    dir: String,
    selected: String,
    body: String,
    project: String = "",
    api: LocalApi? = null,
    onFolder: (String) -> Unit,
    onFile: (String) -> Unit,
    onEdit: (String) -> Unit,
    onSave: () -> Unit
) {
    var openTabs by rememberSaveable { mutableStateOf(if (selected.isNotBlank()) listOf(selected) else emptyList()) }
    var activeTab by rememberSaveable { mutableStateOf(selected) }
    var splitTab by rememberSaveable { mutableStateOf<String?>(null) }
    var isSplitView by rememberSaveable { mutableStateOf(false) }
    var showExplorer by rememberSaveable { mutableStateOf(true) }
    val tabBodies = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    var maskSecrets by rememberSaveable { mutableStateOf(true) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    // Sync incoming selected file with openTabs
    LaunchedEffect(selected, body) {
        if (selected.isNotBlank()) {
            if (!openTabs.contains(selected)) {
                openTabs = openTabs + selected
            }
            activeTab = selected
            tabBodies[selected] = body
        }
    }

    fun openFileInTab(path: String) {
        scope.launch {
            if (!openTabs.contains(path)) {
                openTabs = openTabs + path
            }
            activeTab = path
            if (!tabBodies.containsKey(path) && api != null && project.isNotBlank()) {
                runCatching {
                    val res = api.file(project, path)
                    tabBodies[path] = res.optString("content", "")
                }
            }
            onFile(path)
        }
    }

    fun closeTab(path: String) {
        val nextTabs = openTabs.filter { it != path }
        openTabs = nextTabs
        tabBodies.remove(path)
        if (activeTab == path) {
            activeTab = nextTabs.lastOrNull() ?: ""
            if (activeTab.isNotBlank()) onFile(activeTab)
        }
        if (splitTab == path) {
            splitTab = nextTabs.firstOrNull { it != activeTab }
        }
    }

    fun saveTabContent(path: String, content: String) {
        if (api != null && project.isNotBlank()) {
            scope.launch {
                runCatching {
                    api.saveFile(project, path, content)
                }
            }
        }
        if (path == selected) onSave()
    }

    Column(Modifier.fillMaxSize()) {
        // Multi-Tab Bar & Split-View Controls
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Explorer Toggle
                FilledTonalButton(
                    onClick = { showExplorer = !showExplorer },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(if (showExplorer) Icons.Default.FolderOpen else Icons.Default.Folder, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (showExplorer) "Hide Files" else "Files", fontSize = 11.sp)
                }

                Spacer(Modifier.width(6.dp))

                // Scrollable Open Tabs
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    openTabs.forEach { tabPath ->
                        val isCurrent = tabPath == activeTab
                        val fname = tabPath.substringAfterLast('/')
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.clickable {
                                activeTab = tabPath
                                onFile(tabPath)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    when {
                                        fname.endsWith(".json") -> Icons.Default.DataObject
                                        fname.startsWith(".env") -> Icons.Default.VpnKey
                                        else -> Icons.Default.Description
                                    },
                                    null,
                                    Modifier.size(13.dp),
                                    tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    fname,
                                    fontSize = 11.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Box(
                                    modifier = Modifier.size(16.dp).clickable { closeTab(tabPath) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Close, "Close tab", Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                // Split View Toggle Button
                FilledTonalButton(
                    onClick = {
                        isSplitView = !isSplitView
                        if (isSplitView && splitTab == null) {
                            splitTab = openTabs.firstOrNull { it != activeTab } ?: activeTab
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isSplitView) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        contentColor = if (isSplitView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(Icons.Default.VerticalSplit, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isSplitView) "Split ON" else "Split", fontSize = 11.sp)
                }
            }
        }

        // Main Editor Body
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Collapsible Left File Explorer Drawer
            if (showExplorer) {
                Column(Modifier.width(260.dp).fillMaxHeight().padding(end = 8.dp)) {
                    // Global Search (Grep)
                    if (api != null && project.isNotBlank()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { q ->
                                searchQuery = q
                                if (q.isNotBlank()) {
                                    scope.launch {
                                        runCatching {
                                            val res = api.searchFiles(project, q)
                                            val arr = res.optJSONArray("matches")
                                            searchResults = if (arr != null) (0 until arr.length()).map { arr.getJSONObject(it) } else emptyList()
                                        }
                                    }
                                } else {
                                    searchResults = emptyList()
                                }
                            },
                            placeholder = { Text("Search files...", fontSize = 11.sp) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(14.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = ""; searchResults = emptyList() }) {
                                        Icon(Icons.Default.Close, null, Modifier.size(12.dp))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        if (searchResults.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).padding(vertical = 4.dp)
                            ) {
                                LazyColumn(modifier = Modifier.padding(4.dp)) {
                                    items(searchResults) { match ->
                                        val f = match.optString("file")
                                        val line = match.optInt("line", 1)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable { openFileInTab(f) }.padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("$f:$line", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                            Text(match.optString("snippet"), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(dir, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1)
                        if (dir != ".") {
                            TextButton(
                                onClick = { onFolder(dir.substringBeforeLast('/', ".").ifBlank { "." }) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) { Text("← Up", fontSize = 10.sp) }
                        }
                    }

                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(files, key = { it.optString("path") }) { f ->
                            val isDir = f.optBoolean("directory")
                            val path = f.optString("path")
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { if (isDir) onFolder(path) else openFileInTab(path) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (isDir) Icons.Default.Folder else Icons.Default.Description, null, Modifier.size(15.dp),
                                    tint = if (isDir) Color(0xFFFBBF24) else MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(f.optString("name"), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.width(8.dp))
            }

            // Code Editors: Single View or Split View
            if (activeTab.isBlank() && openTabs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Code, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Select a file from the explorer or search to edit", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (!isSplitView) {
                // Single Full Width Editor Pane
                val currentContent = tabBodies[activeTab] ?: body
                val isEnv = activeTab.substringAfterLast('/').startsWith(".env")
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(activeTab, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isEnv) {
                                TextButton(onClick = { maskSecrets = !maskSecrets }) {
                                    Icon(if (maskSecrets) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (maskSecrets) "Reveal" else "Mask", fontSize = 11.sp)
                                }
                            }
                            Button(
                                onClick = { saveTabContent(activeTab, currentContent) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Save, null, Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Save", fontSize = 11.sp)
                            }
                        }
                    }
                    val displayValue = if (isEnv && maskSecrets) {
                        currentContent.lines().joinToString("\n") { line ->
                            if (line.contains('=') && !line.trimStart().startsWith('#')) {
                                line.substringBefore('=') + "=••••••••••••"
                            } else line
                        }
                    } else currentContent

                    CodeEditorWithLineNumbers(
                        value = displayValue,
                        onValueChange = { newVal ->
                            if (!isEnv || !maskSecrets) {
                                tabBodies[activeTab] = newVal
                                if (activeTab == selected) onEdit(newVal)
                            }
                        },
                        filePath = activeTab,
                        readOnly = isEnv && maskSecrets,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    KeyboardAccessoryBar(
                        onInsertText = { insertText ->
                            val current = tabBodies[activeTab] ?: body
                            val updated = current + insertText
                            tabBodies[activeTab] = updated
                            if (activeTab == selected) onEdit(updated)
                        }
                    )
                }
            } else {
                // 2-Column Split-View Side-by-Side Editors
                val pane1Content = tabBodies[activeTab] ?: body
                val pane2Path = splitTab ?: openTabs.firstOrNull { it != activeTab } ?: activeTab
                val pane2Content = tabBodies[pane2Path] ?: ""

                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Pane 1 (Left)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Pane 1: ${activeTab.substringAfterLast('/')}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Button(
                                onClick = { saveTabContent(activeTab, pane1Content) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) { Text("Save", fontSize = 10.sp) }
                        }
                        CodeEditorWithLineNumbers(
                            value = pane1Content,
                            onValueChange = { newVal ->
                                tabBodies[activeTab] = newVal
                                if (activeTab == selected) onEdit(newVal)
                            },
                            filePath = activeTab,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Pane 2 (Right)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Pane 2: ${pane2Path.substringAfterLast('/')}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF38BDF8))
                            Button(
                                onClick = { saveTabContent(pane2Path, pane2Content) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) { Text("Save", fontSize = 10.sp) }
                        }
                        CodeEditorWithLineNumbers(
                            value = pane2Content,
                            onValueChange = { newVal ->
                                tabBodies[pane2Path] = newVal
                            },
                            filePath = pane2Path,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
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
    onRefreshKeySummary: () -> Unit = {},
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Settings & Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp)
            )
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    Triple("Runtime", Icons.Default.Tune, 0),
                    Triple("Agents & Git", Icons.Default.SmartToy, 1),
                    Triple("Instructions", Icons.Default.Description, 2),
                    Triple("Security", Icons.Default.Security, 3),
                    Triple("Guide", Icons.AutoMirrored.Filled.HelpOutline, 4)
                ).forEach { (label, icon, idx) ->
                    FilterChip(
                        selected = section == idx,
                        onClick = { section = idx },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(icon, null, Modifier.size(14.dp)) },
                        modifier = Modifier.height(30.dp)
                    )
                }
            }
        }

        HorizontalDivider()

        when (section) {
            0 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Embedded PRoot Runtime (Zero-Termux)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    color = if (BootstrapManager.isReady(context)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        if (BootstrapManager.isReady(context)) "ACTIVE" else "NOT READY",
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.surface,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                "PRoot 5.1 ARM64 · Alpine v3.21 · Node ${cliVersions?.node ?: "v22.23.5"} · Git ${cliVersions?.git ?: "2.47.3"} · Python ${cliVersions?.python ?: "3.12.16"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            val bridgeLabel = when {
                                online && bridgeRuntime == "embedded" -> "Loopback Bridge Online (127.0.0.1:$bridgePort)"
                                bridgeStatus is EmbeddedBridgeStatus.Starting -> "Starting Embedded PRoot Bridge…"
                                bridgeStatus is EmbeddedBridgeStatus.Error -> "Bridge Error: ${bridgeStatus.message}"
                                else -> "Bridge Standby (127.0.0.1:$bridgePort)"
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    bridgeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    onClick = onRestartEmbedded,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Restart", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(0.9f)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VerifiedUser, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "KeyStore AES-256 Bridge Auth",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                "Device token & API keys are hardware-encrypted in Android KeyStore and synced automatically into PRoot. No external Termux app needed.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            1 -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OfficialCliCard(
                        title = "OpenAI Codex CLI",
                        subtitle = cliVersions?.codex ?: "Official Codex CLI",
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
                        subtitle = cliVersions?.agy ?: "Official Antigravity CLI (agy)",
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
                        subtitle = cliVersions?.claude ?: "Official Claude Code CLI",
                        authState = cliAuthStatus.claude,
                        isInstalled = cliVersions?.claude != null,
                        isInstalling = installingCli == "Claude",
                        anyInstalling = installingCli != null || authBusy,
                        onSignIn = { onSignInCli("Claude") },
                        onSignOut = { onSignOutCli("Claude") },
                        onInstallOrUpdate = { onInstallCli("Claude") }
                    )

                    GitHubUnifiedAccountCard(
                        authState = cliAuthStatus.github,
                        summary = keySummary,
                        gitVersion = cliVersions?.git ?: "git 2.47.3",
                        busy = installingCli != null || authBusy,
                        onSignIn = { onSignInCli("GitHub") },
                        onSignOut = { onSignOutCli("GitHub") }
                    )

                    ProviderApiKeysCard(
                        summary = keySummary,
                        busy = authBusy,
                        onSaveKey = onSaveProviderKey
                    )

                    // Coolify Integration Settings Card
                    var coolifyUrlInput by remember(keySummary.coolifyUrl) { mutableStateOf(keySummary.coolifyUrl) }
                    var coolifyTokenInput by remember { mutableStateOf("") }
                    var coolifyBusy by remember { mutableStateOf(false) }
                    var coolifyTestResult by remember { mutableStateOf("") }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.RocketLaunch, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Coolify Deployment Integration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Surface(
                                    color = if (keySummary.coolifyConfigured) Color(0xFF065F46) else MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        if (keySummary.coolifyConfigured) "Configured (${keySummary.coolifyTokenHint})" else "Not Configured",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (keySummary.coolifyConfigured) Color(0xFFD1FAE5) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Text(
                                "Connect your Coolify v4 instance so the agent can inspect project deployments & redacted error logs without exposing your API token.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = coolifyUrlInput,
                                onValueChange = { coolifyUrlInput = it },
                                label = { Text("Coolify URL (e.g. https://coolify.example.com)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = coolifyTokenInput,
                                onValueChange = { coolifyTokenInput = it },
                                label = { Text(if (keySummary.coolifyConfigured) "API Token (leave blank to keep ${keySummary.coolifyTokenHint})" else "Coolify API Token (Bearer)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    enabled = !coolifyBusy && coolifyUrlInput.isNotBlank(),
                                    onClick = {
                                        val u = coolifyUrlInput.trim().trimEnd('/')
                                        val t = coolifyTokenInput.trim()
                                        SecureSecrets.saveKey(context, SecureSecrets.KEY_COOLIFY_URL, u)
                                        if (t.isNotBlank()) {
                                            SecureSecrets.saveKey(context, SecureSecrets.KEY_COOLIFY_TOKEN, t)
                                            coolifyTokenInput = ""
                                        }
                                        onRefreshKeySummary()
                                        coolifyTestResult = "Saved to Android KeyStore"
                                    }
                                ) {
                                    Text("Save Coolify")
                                }
                                OutlinedButton(
                                    enabled = !coolifyBusy && api != null && online && coolifyUrlInput.isNotBlank(),
                                    onClick = {
                                        val u = coolifyUrlInput.trim().trimEnd('/')
                                        val t = coolifyTokenInput.trim().ifBlank {
                                            SecureSecrets.getKey(context, SecureSecrets.KEY_COOLIFY_TOKEN)
                                        }
                                        if (t.isBlank()) {
                                            coolifyTestResult = "Enter or save an API token first"
                                            return@OutlinedButton
                                        }
                                        coolifyBusy = true
                                        coolifyTestResult = "Testing connection..."
                                        scope.launch {
                                            runCatching { api!!.coolifyTest(u, t) }
                                                .onSuccess { res ->
                                                    val ver = res.optString("version", "ok")
                                                    coolifyTestResult = "Connected! Coolify $ver"
                                                }
                                                .onFailure { e ->
                                                    coolifyTestResult = "Error: ${e.message ?: e.javaClass.simpleName}"
                                                }
                                            coolifyBusy = false
                                        }
                                    }
                                ) {
                                    Text("Test Connection")
                                }
                            }
                            if (coolifyTestResult.isNotBlank()) {
                                Text(
                                    coolifyTestResult,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (coolifyTestResult.startsWith("Connected") || coolifyTestResult.startsWith("Saved"))
                                        MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    if (agents.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Bridge Adapters:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            agents.forEach { a ->
                                val display = a.optString("display_name")
                                val avail = a.optBoolean("available", false)
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Box(
                                            Modifier.size(6.dp).clip(CircleShape).background(
                                                if (avail) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                                            )
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(display, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Per-Project Instructions ($instructionFile)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (selectedProject.isNotBlank()) {
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
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Save, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (selectedProject.isBlank()) {
                    Text("Select a project from the sidebar to inspect or edit instructions.", style = MaterialTheme.typography.bodySmall)
                } else if (loadingInstructions) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp))
                } else {
                    OutlinedTextField(
                        value = instructionContent,
                        onValueChange = { instructionContent = it },
                        label = { Text("$selectedProject / $instructionFile (auto-injected into prompts)") },
                        placeholder = { Text("# Project Guidelines\n- Build: npm run build\n- Test: npm test") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 8
                    )
                }
            }

            3 -> {
                Surface(
                    color = if (codexProotBypass) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Codex Build in Embedded PRoot (Disable Linux Bubblewrap Sandbox)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (codexProotBypass) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Opt-in (OFF by default): Passes --sandbox danger-full-access ONLY for Codex Build inside PRoot; Plan remains read-only.",
                                style = MaterialTheme.typography.labelSmall,
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
                                            notify(Throwable(if (codexProotBypass) "Enabled Codex Build PRoot opt-in" else "Restored default Codex sandbox"))
                                        }
                                        .onFailure { notify(it) }
                                    savingSecurity = false
                                }
                            }
                        )
                    }
                }

                val policies = listOf(
                    Pair("Loopback 127.0.0.1 Only", "Bridge binds strictly to 127.0.0.1:8765; 0.0.0.0 and external listeners are rejected."),
                    Pair("Android KeyStore AES-256", "Tokens, API keys, and GitHub PATs are encrypted at rest in hardware KeyStore."),
                    Pair("Destructive Action Gate", "Destructive commands (rm -rf, git reset --hard, git push) require explicit user approval."),
                    Pair("Secrets & .env Masking", "Secrets in .env files and logs are automatically redacted before rendering or prompt injection.")
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    policies.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            rowItems.forEach { (title, desc) ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(Modifier.padding(9.dp)) {
                                        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(2.dp))
                                        Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            4 -> {
                val guideItems = listOf(
                    Pair("1. Zero-Termux Engine", "Bundled PRoot 5.1 + Alpine v3.21 (Node 22, Git 2.47, Python 3.12). Auto-starts on 127.0.0.1:8765."),
                    Pair("2. Official AI Agents", "Sign in via Settings → Agents & Git (Codex Device OAuth, Antigravity Google OAuth, Claude Code)."),
                    Pair("3. Private GitHub Clone", "Save a GitHub PAT (ghp_... / github_pat_...) in Agents & Git to clone private repositories."),
                    Pair("4. Draggable Split & Preview", "Drag the center divider in Split view or use compact Desktop / Tablet / Phone icons.")
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    guideItems.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            rowItems.forEach { (title, desc) ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                        Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
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
    notify: (Throwable) -> Unit,
    onAutoFixPrompt: ((String) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val dialogCtx = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) } // 0: Changes, 1: Branches & Sync, 2: History, 3: Worktrees
    var loading by remember { mutableStateOf(true) }
    var statusObj by remember { mutableStateOf<JSONObject?>(null) }
    var diffText by remember { mutableStateOf("") }
    var selectedFileDiff by remember { mutableStateOf<String?>(null) }
    var commitMessage by remember { mutableStateOf("") }
    var committing by remember { mutableStateOf(false) }
    var aiGenerating by remember { mutableStateOf(false) }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentBranch by remember { mutableStateOf("") }
    var showNewBranchDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var showAuditDialog by remember { mutableStateOf(false) }
    var auditLoading by remember { mutableStateOf(false) }
    var auditResult by remember { mutableStateOf<JSONObject?>(null) }
    var showPushDialog by remember { mutableStateOf(false) }
    var pushing by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var pullResult by remember { mutableStateOf<String?>(null) }
    var logCommits by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var worktrees by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var rollbackTargetSha by remember { mutableStateOf<String?>(null) }
    var showDiscardAllConfirm by remember { mutableStateOf(false) }
    var originUrl by remember { mutableStateOf("") }
    var hasOriginRemote by remember { mutableStateOf(false) }
    var newRepoName by remember(project) { mutableStateOf(project) }
    var isPrivateRepo by remember { mutableStateOf(true) }
    var newRepoDesc by remember(project) { mutableStateOf("Created with Tablet Agent Studio") }
    var createNewRepoMode by remember { mutableStateOf(false) }
    var hasConflicts by remember { mutableStateOf(false) }
    var conflictedFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var resolvingConflicts by remember { mutableStateOf(false) }

    var showPrDialog by remember { mutableStateOf(false) }
    var draftingPr by remember { mutableStateOf(false) }
    var creatingPr by remember { mutableStateOf(false) }
    var prHeadBranch by remember { mutableStateOf("") }
    var prBaseBranch by remember { mutableStateOf("main") }
    var prTitle by remember { mutableStateOf("") }
    var prBody by remember { mutableStateOf("") }
    var showPrSuccessDialog by remember { mutableStateOf(false) }
    var createdPrUrl by remember { mutableStateOf("") }
    var gitBanner by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            try {
                val st = api.gitStatus(project)
                val df = api.gitDiff(project)
                val br = api.gitBranches(project)
                val lg = api.gitLog(project, 40)
                val wt = api.listWorktrees(project)
                statusObj = st
                diffText = df.optString("diff", "")
                currentBranch = br.optString("current", st.optString("branch", "HEAD"))
                val arrBr = br.optJSONArray("branches")
                if (arrBr != null) branches = (0 until arrBr.length()).map { arrBr.getString(it) }
                val arrLg = lg.optJSONArray("commits")
                if (arrLg != null) logCommits = (0 until arrLg.length()).map { arrLg.getJSONObject(it) }
                worktrees = (0 until wt.length()).map { wt.getJSONObject(it) }
                val arrRem = br.optJSONArray("remotes")
                hasOriginRemote = arrRem != null && (0 until arrRem.length()).any { arrRem.getString(it) == "origin" }
                originUrl = br.optString("origin_url", "")
                if (!hasOriginRemote) {
                    createNewRepoMode = true
                }
                val hasConf = st.optBoolean("has_conflicts", false)
                val confArr = st.optJSONArray("conflicted_files")
                hasConflicts = hasConf
                conflictedFiles = if (confArr != null) (0 until confArr.length()).map { confArr.getString(it) } else emptyList()
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
        modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null, Modifier.size(24.dp), tint = Color(0xFF3B82F6))
                    Spacer(Modifier.width(8.dp))
                    Text("Git Control Center", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFF1E3A8A),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            currentBranch.ifBlank { "HEAD" },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFDBEAFE)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { refresh() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                    }
                }

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    val clean = statusObj?.optBoolean("clean", true) ?: true
                    val uncommittedCount = statusObj?.optInt("uncommitted_count", 0) ?: 0
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Changes", fontSize = 12.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal)
                                if (!clean && uncommittedCount > 0) {
                                    Badge(containerColor = Color(0xFFF59E0B), contentColor = Color.Black) { Text("$uncommittedCount") }
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Branches & Sync", fontSize = 12.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("History (${logCommits.size})", fontSize = 12.sp, fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Worktrees (${worktrees.size})", fontSize = 12.sp, fontWeight = if (selectedTab == 3) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    )
                }

                gitBanner?.let { (isSuccess, msg) ->
                    Surface(
                        color = if (isSuccess) Color(0xFF065F46) else Color(0xFF991B1B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                msg,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = { gitBanner = null }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val clean = statusObj?.optBoolean("clean", true) ?: true
                val filesArr = statusObj?.optJSONArray("files")
                val filesList = if (filesArr != null) (0 until filesArr.length()).map { filesArr.getJSONObject(it) } else emptyList()

                Box(Modifier.fillMaxSize()) {
                    when (selectedTab) {
                        0 -> {
                            // --- TAB 0: Changes & Staging ---
                            Column(
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Action Bar
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            aiGenerating = true
                                            scope.launch {
                                                try {
                                                    val res = api.gitAiCommit(project)
                                                    commitMessage = res.optString("message", "feat: update project files")
                                                } catch (e: Exception) {
                                                    gitBanner = false to (e.message ?: "AI Commit Msg failed")
                                                    notify(e)
                                                } finally {
                                                    aiGenerating = false
                                                }
                                            }
                                        },
                                        enabled = !aiGenerating && !clean,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(14.dp), tint = Color(0xFFA855F7))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (aiGenerating) "Generating…" else "✨ AI Auto-Commit Msg", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Spacer(Modifier.weight(1f))

                                    if (!clean) {
                                        OutlinedButton(
                                            onClick = { showDiscardAllConfirm = true },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.DeleteSweep, null, Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Discard All", fontSize = 11.sp)
                                        }
                                    }
                                }

                                if (filesList.isNotEmpty()) {
                                    Text("Modified Files (${filesList.size}):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        filesList.forEach { fObj ->
                                            val st = fObj.optString("status", "M")
                                            val fName = fObj.optString("file", "")
                                            Surface(
                                                color = if (selectedFileDiff == fName) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.fillMaxWidth().clickable {
                                                    if (selectedFileDiff == fName) {
                                                        selectedFileDiff = null
                                                    } else {
                                                        selectedFileDiff = fName
                                                        scope.launch {
                                                            try {
                                                                val fDiff = api.gitFileDiff(project, fName)
                                                                diffText = fDiff.optString("diff", "")
                                                            } catch (e: Exception) {
                                                                notify(e)
                                                            }
                                                        }
                                                    }
                                                }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    val badgeColor = when {
                                                        st.contains("M") -> Color(0xFFF59E0B)
                                                        st.contains("A") || st.contains("?") -> Color(0xFF10B981)
                                                        st.contains("D") -> Color(0xFFEF4444)
                                                        else -> Color(0xFF6B7280)
                                                    }
                                                    Surface(color = badgeColor.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                                        Text(st.trim().ifBlank { "?" }, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(fName, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                                    IconButton(
                                                        onClick = {
                                                            scope.launch {
                                                                try {
                                                                    api.gitDiscard(project, fName)
                                                                    refresh()
                                                                } catch (e: Exception) {
                                                                    notify(e)
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(Icons.Default.Close, contentDescription = "Discard file", modifier = Modifier.size(14.dp), tint = Color(0xFFEF4444))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Text(
                                    if (selectedFileDiff != null) "Diff for $selectedFileDiff:" else "Unified Project Diff:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp)
                                ) {
                                    val lines = diffText.ifBlank { "No changes detected in working tree." }.split("\n")
                                    Column(modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                                        lines.forEach { line ->
                                            val lineColor = when {
                                                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF22C55E)
                                                line.startsWith("-") && !line.startsWith("---") -> Color(0xFFEF4444)
                                                line.startsWith("@@") -> Color(0xFF06B6D4)
                                                else -> MaterialTheme.colorScheme.onSurface
                                            }
                                            Text(
                                                text = line,
                                                color = lineColor,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                }

                                FilledTonalButton(
                                    onClick = {
                                        showAuditDialog = true
                                        auditLoading = true
                                        scope.launch {
                                            runCatching { api.gitAudit(project) }
                                                .onSuccess { auditResult = it }
                                                .onFailure { notify(it) }
                                            auditLoading = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Security, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("🛡️ Run AI Pre-Commit & Security Audit")
                                }

                                if (!clean) {
                                    OutlinedTextField(
                                        value = commitMessage,
                                        onValueChange = { commitMessage = it },
                                        label = { Text("Commit message (Conventional Commits)") },
                                        placeholder = { Text("feat: describe verified changes") },
                                        modifier = Modifier.fillMaxWidth(),
                                        maxLines = 3
                                    )
                                }
                            }
                        }

                        1 -> {
                            // --- TAB 1: Branches & Remote Sync ---
                            Column(
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Active Branch & Sync Card
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CallSplit, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text("Current Branch: $currentBranch", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                Text("Remote tracking: origin/$currentBranch", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            val ctx = LocalContext.current
                                            FilledTonalButton(
                                                onClick = {
                                                    pulling = true
                                                    scope.launch {
                                                        try {
                                                            val ghToken = SecureSecrets.getKey(ctx, SecureSecrets.KEY_GITHUB)
                                                            val res = api.gitPull(project, "origin", currentBranch, ghToken)
                                                            val isConf = res.optBoolean("has_conflicts", false)
                                                            if (isConf) {
                                                                hasConflicts = true
                                                                val confArr = res.optJSONArray("conflicted_files")
                                                                if (confArr != null) conflictedFiles = (0 until confArr.length()).map { confArr.getString(it) }
                                                                pullResult = "⚠️ Merge conflicts detected in ${conflictedFiles.size} file(s)"
                                                                HapticFeedbackHelper.vibrateAlert(ctx)
                                                            } else {
                                                                pullResult = res.optString("output", "Pull complete")
                                                                HapticFeedbackHelper.vibrateSuccess(ctx)
                                                            }
                                                            refresh()
                                                        } catch (e: Exception) {
                                                            gitBanner = false to (e.message ?: "Pull failed")
                                                            notify(e)
                                                        } finally {
                                                            pulling = false
                                                        }
                                                    }
                                                },
                                                enabled = !pulling,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(if (pulling) "Pulling…" else "↓ Pull Remote")
                                            }

                                            Button(
                                                onClick = { showPushDialog = true },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(if (hasOriginRemote) Icons.Default.CloudUpload else Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(if (hasOriginRemote) "↑ Push Remote" else "🚀 Create & Push GitHub")
                                            }
                                        }

                                        // 1-Click Create PR Button
                                        Button(
                                            onClick = {
                                                draftingPr = true
                                                scope.launch {
                                                    try {
                                                        val draft = api.gitDraftPr(project, "main")
                                                        prHeadBranch = draft.optString("head", currentBranch)
                                                        prBaseBranch = draft.optString("base", "main")
                                                        prTitle = draft.optString("title", "")
                                                        prBody = draft.optString("body", "")
                                                        showPrDialog = true
                                                    } catch (e: Exception) {
                                                        notify(e)
                                                    } finally {
                                                        draftingPr = false
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                            modifier = Modifier.fillMaxWidth().height(36.dp),
                                            enabled = !draftingPr
                                        ) {
                                            Icon(Icons.Default.CallMerge, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (draftingPr) "Drafting PR with AI…" else "🔀 Create Pull Request", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }

                                        // AI Auto-Resolve Conflict Warning Card
                                        if (hasConflicts) {
                                            val ctx = LocalContext.current
                                            Surface(
                                                color = Color(0xFF450A0A),
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, Color(0xFFDC2626)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Warning, null, tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("Merge Conflicts Detected (${conflictedFiles.size} files)", color = Color(0xFFFEE2E2), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    }
                                                    if (conflictedFiles.isNotEmpty()) {
                                                        Text(conflictedFiles.joinToString(", "), color = Color(0xFFFCA5A5), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                                    }
                                                    Button(
                                                        onClick = {
                                                            resolvingConflicts = true
                                                            scope.launch {
                                                                try {
                                                                    val res = api.gitResolveConflicts(project)
                                                                    HapticFeedbackHelper.vibrateSuccess(ctx)
                                                                    pullResult = "✅ All conflicts resolved & auto-committed: ${res.optString("commit_sha")}"
                                                                    hasConflicts = false
                                                                    conflictedFiles = emptyList()
                                                                    refresh()
                                                                } catch (e: Exception) {
                                                                    HapticFeedbackHelper.vibrateError(ctx)
                                                                    notify(e)
                                                                } finally {
                                                                    resolvingConflicts = false
                                                                }
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(32.dp),
                                                        enabled = !resolvingConflicts
                                                    ) {
                                                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp))
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(if (resolvingConflicts) "Resolving Conflicts via AI…" else "✨ AI Auto-Resolve Conflicts", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }

                                        if (pullResult != null) {
                                            Surface(color = Color(0xFF064E3B), shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                                                Text(pullResult!!, color = Color(0xFF6EE7B7), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp))
                                            }
                                        }
                                    }
                                }

                                // Branch Switcher Section
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Local Branches (${branches.size}):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.weight(1f))
                                    Button(
                                        onClick = { showNewBranchDialog = true },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("New Branch", fontSize = 11.sp)
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    branches.forEach { bName ->
                                        val isCurrent = bName == currentBranch
                                        Surface(
                                            color = if (isCurrent) Color(0xFF1E3A8A).copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(8.dp),
                                            border = if (isCurrent) BorderStroke(1.dp, Color(0xFF3B82F6)) else null,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    if (isCurrent) Icons.Default.CheckCircle else Icons.Default.CallSplit,
                                                    null,
                                                    tint = if (isCurrent) Color(0xFF3B82F6) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(bName, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                                if (!isCurrent) {
                                                    FilledTonalButton(
                                                        onClick = {
                                                            scope.launch {
                                                                runCatching { api.gitCheckout(project, bName) }
                                                                    .onSuccess { refresh() }
                                                                    .onFailure { notify(it) }
                                                            }
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) {
                                                        Text("Switch", fontSize = 11.sp)
                                                    }
                                                } else {
                                                    Surface(color = Color(0xFF1D4ED8), shape = RoundedCornerShape(4.dp)) {
                                                        Text("Active", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            // --- TAB 2: Commit History Timeline ---
                            Column(
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (logCommits.isEmpty()) {
                                    Text("No commit history available.", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    logCommits.forEach { cObj ->
                                        val hash = cObj.optString("hash", "")
                                        val author = cObj.optString("author", "")
                                        val relTime = cObj.optString("relative_time", "")
                                        val msg = cObj.optString("message", "")

                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Surface(color = Color(0xFF374151), shape = RoundedCornerShape(4.dp)) {
                                                            Text(hash, color = Color(0xFF93C5FD), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                                        }
                                                        Text(author, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text("• $relTime", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                                    }
                                                    Text(msg, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                                                }

                                                OutlinedButton(
                                                    onClick = { rollbackTargetSha = hash },
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B)),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Icon(Icons.Default.Undo, null, Modifier.size(13.dp))
                                                    Spacer(Modifier.width(3.dp))
                                                    Text("Rollback", fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        3 -> {
                            // --- TAB 3: Agent Worktrees (Parallel Workspaces) ---
                            Column(
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Multi-Agent Parallel Worktrees:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.weight(1f))
                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    api.pruneWorktrees(project)
                                                    refresh()
                                                } catch (e: Exception) {
                                                    notify(e)
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Prune Stale", fontSize = 11.sp)
                                    }
                                }

                                Text(
                                    "Worktrees allow multiple AI agents to work on isolated git branches simultaneously without stepping on each other's files.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (worktrees.isEmpty()) {
                                    Text("No worktrees created. Tasks currently run in base project.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    worktrees.forEach { wtObj ->
                                        val path = wtObj.optString("path", "")
                                        val br = wtObj.optString("branch", "")
                                        val isBase = wtObj.optBoolean("is_base", false)

                                        Surface(
                                            color = if (isBase) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1E293B),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(if (isBase) Icons.Default.Folder else Icons.Default.AccountTree, null, tint = if (isBase) Color(0xFF3B82F6) else Color(0xFF10B981), modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(if (isBase) "Base Workspace" else "Agent Worktree: $br", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                        if (isBase) {
                                                            Surface(color = Color(0xFF2563EB).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                                                Text("PRIMARY", color = Color(0xFF60A5FA), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                            }
                                                        }
                                                    }
                                                    Text(path, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedTab == 0) {
                val isClean = statusObj?.optBoolean("clean", true) ?: true
                if (!isClean) {
                    Button(
                        onClick = {
                            committing = true
                            scope.launch {
                                try {
                                    val ghUser = SecureSecrets.getKey(dialogCtx, SecureSecrets.KEY_GITHUB_USER)
                                    val ghEmail = SecureSecrets.getKey(dialogCtx, SecureSecrets.KEY_GITHUB_EMAIL)
                                    val cRes = api.gitCommit(project, commitMessage.trim(), ghUser, ghEmail)
                                    val sha = cRes.optString("sha", "").take(7)
                                    HapticFeedbackHelper.vibrateSuccess(dialogCtx)
                                    gitBanner = true to if (sha.isNotBlank()) "✅ Committed ($sha): ${commitMessage.trim()}" else "✅ Committed changes successfully!"
                                    commitMessage = ""
                                    refresh()
                                    onCommitted()
                                } catch (e: Exception) {
                                    HapticFeedbackHelper.vibrateError(dialogCtx)
                                    gitBanner = false to (e.message ?: "Commit failed")
                                    notify(e)
                                } finally {
                                    committing = false
                                }
                            }
                        },
                        enabled = !committing && commitMessage.isNotBlank()
                    ) {
                        Text(if (committing) "Committing…" else "Commit Changes")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    if (showDiscardAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardAllConfirm = false },
            title = { Text("Discard All Changes?") },
            text = { Text("This will revert all modified files and remove untracked files in the working directory. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardAllConfirm = false
                        scope.launch {
                            try {
                                api.gitDiscard(project, all = true)
                                refresh()
                            } catch (e: Exception) {
                                notify(e)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) { Text("Discard Everything") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardAllConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (rollbackTargetSha != null) {
        val targetSha = rollbackTargetSha!!
        AlertDialog(
            onDismissRequest = { rollbackTargetSha = null },
            title = { Text("Rollback to Commit $targetSha?") },
            text = { Text("This will hard-reset your working tree and branch HEAD to commit $targetSha. Uncommitted changes will be discarded.") },
            confirmButton = {
                Button(
                    onClick = {
                        rollbackTargetSha = null
                        scope.launch {
                            try {
                                api.gitRollback(project, targetSha)
                                refresh()
                                notify(Throwable("Rolled back successfully to $targetSha"))
                            } catch (e: Exception) {
                                notify(e)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                ) { Text("Confirm Rollback") }
            },
            dismissButton = {
                TextButton(onClick = { rollbackTargetSha = null }) { Text("Cancel") }
            }
        )
    }

    if (showNewBranchDialog) {
        AlertDialog(
            onDismissRequest = { showNewBranchDialog = false },
            title = { Text("Create New Git Branch") },
            text = {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("Branch name") },
                    placeholder = { Text("feature/my-new-feature") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newBranchName.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                runCatching { api.gitCheckout(project, name, create = true) }
                                    .onSuccess {
                                        showNewBranchDialog = false
                                        newBranchName = ""
                                        refresh()
                                    }
                                    .onFailure { notify(it) }
                            }
                        }
                    },
                    enabled = newBranchName.isNotBlank()
                ) { Text("Create & Switch") }
            },
            dismissButton = {
                TextButton(onClick = { showNewBranchDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPushDialog) {
        val ctx = LocalContext.current
        val hasGh = SecureSecrets.hasKey(ctx, SecureSecrets.KEY_GITHUB)
        val ghUser = SecureSecrets.getKey(ctx, SecureSecrets.KEY_GITHUB_USER)
        var pushError by remember(showPushDialog) { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { if (!pushing) showPushDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (createNewRepoMode) Icons.Default.AddCircle else Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = Color(0xFF2563EB)
                    )
                    Text(if (createNewRepoMode) "Create & Push GitHub Repo" else "Push Branch to GitHub")
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasGh) {
                        Surface(
                            color = Color(0xFF10B981).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                                Text("GitHub Account: @${ghUser.ifBlank { "authorized" }}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                            }
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("Not signed in to GitHub. Please sign in via Settings → Agents & Git.", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }

                    if (createNewRepoMode) {
                        Text(
                            "Creates a new repository under your GitHub account (@$ghUser) and pushes all project files to it.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = newRepoName,
                            onValueChange = { newRepoName = it },
                            label = { Text("Repository Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = newRepoDesc,
                            onValueChange = { newRepoDesc = it },
                            label = { Text("Description (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { isPrivateRepo = !isPrivateRepo }
                        ) {
                            Checkbox(checked = isPrivateRepo, onCheckedChange = { isPrivateRepo = it })
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text("Private Repository (Recommended)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("Only you can access this repo.", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }

                        if (hasOriginRemote) {
                            TextButton(onClick = { createNewRepoMode = false }) {
                                Text("← Push to existing remote ($originUrl)", fontSize = 11.sp)
                            }
                        }
                    } else {
                        Text("Branch: ${currentBranch.ifBlank { "HEAD" }} -> origin", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (originUrl.isNotBlank()) {
                            Text("Remote: $originUrl", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("Pushes strictly over secure loopback authorization. Force-push is disabled.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        TextButton(
                            onClick = {
                                newRepoName = project
                                createNewRepoMode = true
                            }
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Create & Push to a New GitHub Repo instead", fontSize = 11.sp)
                        }
                    }

                    if (pushError.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                pushError,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pushing = true
                        pushError = ""
                        scope.launch {
                            try {
                                val ghToken = SecureSecrets.getKey(ctx, SecureSecrets.KEY_GITHUB)
                                if (createNewRepoMode) {
                                    val ghEmail = SecureSecrets.getKey(ctx, SecureSecrets.KEY_GITHUB_EMAIL)
                                    val res = api.gitCreateAndPush(project, newRepoName, isPrivateRepo, newRepoDesc, ghToken, ghUser, ghEmail)
                                    showPushDialog = false
                                    refresh()
                                    HapticFeedbackHelper.vibrateSuccess(ctx)
                                    val urlMsg = res.optString("html_url", res.optString("repo_name"))
                                    gitBanner = true to "✅ Created & Pushed: $urlMsg"
                                    notify(Throwable("Created & Pushed: $urlMsg"))
                                } else {
                                    val res = api.gitPush(project, "origin", currentBranch, token = ghToken)
                                    showPushDialog = false
                                    refresh()
                                    HapticFeedbackHelper.vibrateSuccess(ctx)
                                    val br = res.optString("branch", currentBranch)
                                    gitBanner = true to "✅ Pushed branch '$br' to origin successfully!"
                                    notify(Throwable("Pushed successfully: $br"))
                                    // If pushed branch is not main/master, prompt to create PR
                                    if (currentBranch != "main" && currentBranch != "master") {
                                        runCatching {
                                            val draft = api.gitDraftPr(project, "main")
                                            prHeadBranch = draft.optString("head", currentBranch)
                                            prBaseBranch = draft.optString("base", "main")
                                            prTitle = draft.optString("title", "")
                                            prBody = draft.optString("body", "")
                                            showPrDialog = true
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                HapticFeedbackHelper.vibrateError(ctx)
                                val errMsg = e.message ?: "Push failed"
                                pushError = errMsg
                                gitBanner = false to errMsg
                                notify(e)
                            } finally {
                                pushing = false
                            }
                        }
                    },
                    enabled = !pushing && hasGh && (!createNewRepoMode || newRepoName.isNotBlank()),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    if (pushing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(if (createNewRepoMode) "Creating & Pushing…" else "Pushing…")
                    } else {
                        Icon(if (createNewRepoMode) Icons.Default.CloudUpload else Icons.Default.Upload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (createNewRepoMode) "Create Repo & Push" else "Confirm Push")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPushDialog = false }, enabled = !pushing) { Text("Cancel") }
            }
        )
    }

    if (showPrDialog) {
        val ctx = LocalContext.current
        var prError by remember(showPrDialog) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!creatingPr) showPrDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CallMerge, contentDescription = null, tint = Color(0xFF8B5CF6))
                    Text("Create Pull Request", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(color = Color(0xFF7C3AED).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                            Text(prHeadBranch.ifBlank { currentBranch }, color = Color(0xFFA78BFA), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Text("➔", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                        Surface(color = Color(0xFF374151), shape = RoundedCornerShape(4.dp)) {
                            Text(prBaseBranch.ifBlank { "main" }, color = Color(0xFF93C5FD), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }

                    OutlinedTextField(
                        value = prTitle,
                        onValueChange = { prTitle = it },
                        label = { Text("PR Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = prBody,
                        onValueChange = { prBody = it },
                        label = { Text("PR Description (Markdown)") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )

                    if (prError.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                prError,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        creatingPr = true
                        prError = ""
                        scope.launch {
                            try {
                                val ghToken = SecureSecrets.getKey(ctx, SecureSecrets.KEY_GITHUB)
                                val res = api.gitCreatePr(project, prTitle, prBody, prHeadBranch, prBaseBranch, ghToken)
                                HapticFeedbackHelper.vibrateSuccess(ctx)
                                createdPrUrl = res.optString("pr_url", "")
                                showPrDialog = false
                                showPrSuccessDialog = true
                            } catch (e: Exception) {
                                HapticFeedbackHelper.vibrateError(ctx)
                                val errMsg = e.message ?: "PR creation failed"
                                prError = errMsg
                                gitBanner = false to errMsg
                                notify(e)
                            } finally {
                                creatingPr = false
                            }
                        }
                    },
                    enabled = !creatingPr && prTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                ) {
                    if (creatingPr) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (creatingPr) "Creating PR…" else "🚀 Submit Pull Request")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrDialog = false }, enabled = !creatingPr) { Text("Cancel") }
            }
        )
    }

    if (showPrSuccessDialog) {
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { showPrSuccessDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF10B981))
                    Text("Pull Request Created!")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your Pull Request has been opened successfully on GitHub.")
                    if (createdPrUrl.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(createdPrUrl, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPrSuccessDialog = false
                        if (createdPrUrl.isNotBlank()) {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(createdPrUrl)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                ctx.startActivity(intent)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View on GitHub")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrSuccessDialog = false }) { Text("Done") }
            }
        )
    }

    if (showAuditDialog) {
        SecurityAuditDialog(
            project = project,
            auditResult = auditResult,
            loading = auditLoading,
            onDismiss = { showAuditDialog = false },
            onAutoFixWithAi = { fixPrompt ->
                showAuditDialog = false
                onDismiss()
                onAutoFixPrompt?.invoke(fixPrompt)
            }
        )
    }
}

@Composable
fun SlmManagerDialog(
    api: LocalApi,
    onDismiss: () -> Unit,
    notify: (Throwable) -> Unit
) {
    val scope = rememberCoroutineScope()
    var slmData by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var downloadingKey by remember { mutableStateOf<String?>(null) }
    var testPrompt by remember { mutableStateOf("Write a Kotlin function to reverse a string") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var runningTest by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try {
            val res = api.slmStatus()
            slmData = res
            val dlProgress = res.optJSONObject("download_progress")
            if (dlProgress != null && dlProgress.optString("status") == "downloading") {
                downloadingKey = dlProgress.optString("model")
            } else if (dlProgress != null && (dlProgress.optString("status") == "completed" || dlProgress.optString("status") == "failed")) {
                downloadingKey = null
            }
        } catch (e: Exception) {
            notify(e)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(1500)
            if (downloadingKey != null) {
                runCatching { refresh() }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = Color(0xFF10B981))
                    Text("Offline Local AI (llama.cpp ARM64)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                val isInstalled = slmData?.optBoolean("installed", false) ?: false
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isInstalled) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (isInstalled) "● Engine Ready" else "● Engine Missing",
                        color = if (isInstalled) Color(0xFF10B981) else Color(0xFFEF4444),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            if (loading && slmData == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else {
                val data = slmData
                val installedModels = (0 until (data?.optJSONArray("models")?.length() ?: 0)).map {
                    data!!.getJSONArray("models").getString(it)
                }
                val availableList = data?.optJSONArray("available_to_download")?.objects() ?: emptyList()
                val dlProgress = data?.optJSONObject("download_progress")

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("100% Offline Device Intelligence", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(
                                "Runs locally on this tablet via llama.cpp ARM64 with glibc-compat in PRoot. Zero internet needed for inference, zero API fees, complete privacy.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text("Available GGUF Models (Qwen 2.5 Coder):", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    availableList.forEach { item ->
                        val key = item.optString("key")
                        val name = item.optString("name")
                        val size = item.optString("size")
                        val speed = item.optString("speed")
                        val isDownloaded = installedModels.any { it.contains(key.replace("qwen2.5-coder-", "")) || it.contains(key) }
                        val isCurrentDownloading = downloadingKey == key

                        Card(
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isDownloaded) Color(0xFF10B981).copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text("Size: $size • Speed: $speed", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isDownloaded) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFF10B981).copy(alpha = 0.15f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                                                Text("Downloaded", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                downloadingKey = key
                                                scope.launch {
                                                    runCatching { api.slmDownload(key) }
                                                        .onFailure { notify(it); downloadingKey = null }
                                                }
                                            },
                                            enabled = downloadingKey == null,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (isCurrentDownloading) "Downloading…" else "Download", fontSize = 11.sp)
                                        }
                                    }
                                }

                                if (isCurrentDownloading && dlProgress != null) {
                                    val pct = dlProgress.optInt("percent", 0)
                                    val dlMb = dlProgress.optDouble("downloaded_mb", 0.0)
                                    val totalMb = dlProgress.optDouble("total_mb", 0.0)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        LinearProgressIndicator(
                                            progress = { (pct / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                            color = Color(0xFF2563EB)
                                        )
                                        Text(
                                            "$pct% ($dlMb / $totalMb MB)",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("Instant On-Device Test:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    OutlinedTextField(
                        value = testPrompt,
                        onValueChange = { testPrompt = it },
                        label = { Text("Prompt for Local SLM") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                runningTest = true
                                testResult = null
                                scope.launch {
                                    try {
                                        val p = testPrompt.trim()
                                        val res = api.startTask("tablet_agent_mvp", p, "plan", agent = "slm")
                                        val taskId = res.optString("id")
                                        testResult = "Task started: $taskId. Model running locally on tablet CPU!"
                                    } catch (e: Exception) {
                                        testResult = "Error: ${e.message}"
                                    } finally {
                                        runningTest = false
                                    }
                                }
                            },
                            enabled = !runningTest && installedModels.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            if (runningTest) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (installedModels.isEmpty()) "Download a model first" else "Run Test Inference")
                        }
                    }

                    if (testResult != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = testResult ?: "",
                                modifier = Modifier.padding(10.dp),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        }
    )
}

