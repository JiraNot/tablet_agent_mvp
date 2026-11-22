package com.tabletagent.local.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabletagent.local.runtime.AllCliAuthStatus
import kotlinx.coroutines.delay
import org.json.JSONObject

fun aionClaudeLightScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF0F172A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1F5F9),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFF059669),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECFDF5),
    onTertiaryContainer = Color(0xFF065F46),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEF2F2),
    onErrorContainer = Color(0xFF991B1B)
)

fun aionClaudeDarkScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFFE2E8F0),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1E2433),
    onSecondaryContainer = Color(0xFFF1F5F9),
    tertiary = Color(0xFF10B981),
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = Color(0xFF064E3B),
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = Color(0xFF13161F),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF171B26),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF0E1118),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF252D3D),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF3B1219),
    onErrorContainer = Color(0xFFFECACA)
)

private data class PresetAssistantCard(
    val title: String,
    val subtitle: String,
    val mode: String,
    val promptTemplate: String,
    val icon: ImageVector,
    val badgeColor: Color
)

private sealed interface ChatTurnItem {
    val key: String
    data class ToolBatch(val id: String, val events: List<JSONObject>) : ChatTurnItem {
        override val key: String = "tools-$id"
    }
    data class MessageTurn(val seq: Int, val text: String) : ChatTurnItem {
        override val key: String = "msg-$seq"
    }
    data class VerifyGroup(val id: String, val lines: List<String>) : ChatTurnItem {
        override val key: String = "verify-$id"
    }
    data class ErrorTurn(val seq: Int, val text: String) : ChatTurnItem {
        override val key: String = "err-$seq"
    }
}

private fun groupEventsIntoChatTurns(events: List<JSONObject>): List<ChatTurnItem> {
    val result = mutableListOf<ChatTurnItem>()
    val pendingTools = mutableListOf<JSONObject>()
    val pendingVerifies = mutableListOf<Pair<Int, String>>()

    fun flushTools() {
        if (pendingTools.isNotEmpty()) {
            val firstSeq = pendingTools.first().optInt("seq", result.size)
            result.add(ChatTurnItem.ToolBatch("$firstSeq", pendingTools.toList()))
            pendingTools.clear()
        }
    }

    fun flushVerifies() {
        if (pendingVerifies.isNotEmpty()) {
            val firstSeq = pendingVerifies.first().first
            result.add(ChatTurnItem.VerifyGroup("$firstSeq", pendingVerifies.map { it.second }))
            pendingVerifies.clear()
        }
    }

    for (ev in events) {
        val kind = ev.optString("kind").lowercase()
        val seq = ev.optInt("seq", 0)
        val msg = ev.optString("message", "").trim()
        when (kind) {
            "tool", "status", "log", "thread" -> {
                flushVerifies()
                pendingTools.add(ev)
            }
            "verification" -> {
                flushTools()
                if (msg.isNotEmpty()) pendingVerifies.add(seq to msg)
            }
            "message" -> {
                flushTools()
                flushVerifies()
                if (msg.isNotEmpty()) result.add(ChatTurnItem.MessageTurn(seq, msg))
            }
            "error" -> {
                flushTools()
                flushVerifies()
                if (msg.isNotEmpty()) result.add(ChatTurnItem.ErrorTurn(seq, msg))
            }
            else -> {
                flushVerifies()
                pendingTools.add(ev)
            }
        }
    }
    flushTools()
    flushVerifies()
    return result
}

@Composable
fun AionClaudeSidebar(
    projects: List<JSONObject>,
    selectedProject: String,
    onSelectProject: (String) -> Unit,
    onNewProject: () -> Unit,
    tasks: List<JSONObject>,
    selectedTask: String,
    isNewChatMode: Boolean,
    onNewChat: () -> Unit,
    onSelectTask: (String) -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxHeight().width(248.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // Brand Header (Claude Code UI + AionUI style)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Tablet Agent",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        "AI Coding Studio",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onNewProject, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = "New project",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // "+ New Session / New Chat" primary dark button (like Claude Code UI & AionUI)
            Button(
                onClick = onNewChat,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF111827),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New Session", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(14.dp))

            // Projects Section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                Text(
                    "Projects",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${projects.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                projects.take(6).forEach { proj ->
                    val name = proj.optString("name")
                    val isSelected = name == selectedProject
                    Surface(
                        onClick = { onSelectProject(name) },
                        color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                if (isSelected) Icons.Default.Folder else Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Conversations / Sessions Section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                Text(
                    "Conversations",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (tasks.isNotEmpty()) {
                    Text(
                        "${tasks.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(tasks, key = { it.optString("id") }) { task ->
                    val id = task.optString("id")
                    val isSelected = !isNewChatMode && id == selectedTask
                    val status = task.optString("status", "")
                    val agent = task.optString("agent", "codex")
                    val promptText = task.optString("prompt", "Session").replace("\n", " ").trim()

                    Surface(
                        onClick = { onSelectTask(id) },
                        color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    promptText.ifEmpty { "Untitled task" },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when (status) {
                                    "COMPLETED", "PLANNED" -> MaterialTheme.colorScheme.tertiary
                                    "FAILED", "CANCELLED" -> MaterialTheme.colorScheme.error
                                    "EXECUTING", "PLANNING", "VERIFYING" -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Box(
                                    Modifier.size(6.dp).clip(CircleShape).background(dotColor)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "$agent · $status",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        task.optString("mode", "build").uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))

            // Footer: Settings + Dark/Light Mode Switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    onClick = onOpenSettings,
                    color = Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Settings",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "Toggle theme",
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ClaudeCodeTopBar(
    sidebarOpen: Boolean,
    onToggleSidebar: () -> Unit,
    selectedProject: String,
    currentTask: JSONObject?,
    isNewChatMode: Boolean,
    online: Boolean,
    bridgePort: Int,
    workspaceView: Int,
    onSelectWorkspaceView: (Int) -> Unit,
    onOpenGit: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                IconButton(onClick = onToggleSidebar, modifier = Modifier.size(34.dp)) {
                    Icon(
                        if (sidebarOpen) Icons.Default.MenuOpen else Icons.Default.Menu,
                        contentDescription = "Toggle Sidebar",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    val title = if (isNewChatMode || currentTask == null) {
                        "New Session"
                    } else {
                        currentTask.optString("prompt", "Session").replace("\n", " ").trim()
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape).background(
                                if (online) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                            )
                        )
                        Spacer(Modifier.width(5.dp))
                        val sub = if (currentTask != null && !isNewChatMode) {
                            "${selectedProject.ifEmpty { "workspace" }} · ${currentTask.optString("agent", "codex")} · ${currentTask.optString("id").take(8)}"
                        } else {
                            "${selectedProject.ifEmpty { "workspace" }} · Embedded PRoot (:$bridgePort)"
                        }
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Claude Code UI top-right segmented tab bar: [ Chat | Split | Preview | Files | Git ]
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val tabs = listOf(
                            Triple(0, "Chat", Icons.Default.ChatBubbleOutline),
                            Triple(1, "Split", Icons.Default.VerticalSplit),
                            Triple(2, "Preview", Icons.Default.Visibility),
                            Triple(3, "Files", Icons.Default.FolderOpen)
                        )
                        tabs.forEach { (idx, label, icon) ->
                            val selected = workspaceView == idx
                            Surface(
                                onClick = { onSelectWorkspaceView(idx) },
                                color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                                shadowElevation = if (selected) 1.dp else 0.dp
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = label,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (selectedProject.isNotBlank()) {
                            Surface(
                                onClick = onOpenGit,
                                color = Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Commit,
                                        contentDescription = "Git",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Git",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun AionClaudeAgentPane(
    prompt: String,
    onPrompt: (String) -> Unit,
    mode: String,
    onMode: (String) -> Unit,
    agents: List<JSONObject>,
    selectedAgent: String,
    onSelectAgent: (String) -> Unit,
    selectedModel: String,
    onSelectModel: (String) -> Unit,
    cliAuthStatus: AllCliAuthStatus,
    isEmbeddedRuntime: Boolean,
    selectedProject: String,
    projects: List<JSONObject>,
    onSelectProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    tasks: List<JSONObject>,
    selectedTask: String,
    isNewChatMode: Boolean,
    onNewChat: () -> Unit,
    events: List<JSONObject>,
    approvals: List<JSONObject>,
    canRun: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onCommitClick: () -> Unit
) {
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

    val isSelectedVisible = visibleAgents.any { it.first == selectedAgent }
    val currAgentObj = if (isSelectedVisible) agents.firstOrNull { it.optString("name") == selectedAgent } else null
    val models = currAgentObj?.optJSONArray("models")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: listOf("default")
    val selectedAgentDisplay = visibleAgents.firstOrNull { it.first == selectedAgent }?.second ?: selectedAgent

    var attachment by rememberSaveable { mutableStateOf("") }
    var showAttachDialog by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var projectMenuExpanded by remember { mutableStateOf(false) }

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

    val currentTask = tasks.firstOrNull { it.optString("id") == selectedTask }

    if (isNewChatMode || selectedTask.isBlank() || currentTask == null) {
        // =========================================================================
        // STATE A: AionUI "Hi, what's your plan for today?" Home Composer View
        // =========================================================================
        val presets = remember {
            listOf(
                PresetAssistantCard("Feature Builder", "Scaffold new components, pages & routes", "build", "Build a feature that ", Icons.Default.Bolt, Color(0xFFF97316)),
                PresetAssistantCard("Fix & Debug", "Diagnose and repair build or runtime errors", "build", "Fix the following issue or error: ", Icons.Default.Build, Color(0xFFEF4444)),
                PresetAssistantCard("UI & UX Polish", "Improve styling, layout & responsiveness", "build", "Polish the user interface, styling, and tablet responsiveness for ", Icons.Default.Palette, Color(0xFF8B5CF6)),
                PresetAssistantCard("Architecture Plan", "Read-only implementation plan & test strategy", "plan", "Review and refactor code structure for ", Icons.Default.Map, Color(0xFF3B82F6)),
                PresetAssistantCard("Security Audit", "Inspect workspace for vulnerabilities & secrets", "plan", "Audit workspace for security vulnerabilities and misconfigurations in ", Icons.Default.Security, Color(0xFF10B981)),
                PresetAssistantCard("Verify & Run Tests", "Execute npm build/test and report evidence", "build", "Run test suites and verify build output with evidence", Icons.Default.Verified, Color(0xFF06B6D4))
            )
        }

        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 28.dp)
            ) {
                Text(
                    "Hi, what's your plan for today?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(14.dp))

                // AionUI Segmented Agent Pill Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(4.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (visibleAgents.isEmpty()) {
                            val label = if (isEmbeddedRuntime && !cliAuthStatus.loaded) {
                                "Checking signed-in agents..."
                            } else {
                                "No signed-in agent — Sign in via Settings"
                            }
                            Surface(
                                onClick = onOpenSettings,
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                )
                            }
                        } else {
                            visibleAgents.forEach { (name, label, _) ->
                                val selected = name == selectedAgent
                                val badgeColor = when (name) {
                                    "codex" -> Color(0xFF10B981)
                                    "antigravity" -> Color(0xFF3B82F6)
                                    "claude" -> Color(0xFFD97706)
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                Surface(
                                    onClick = {
                                        onSelectAgent(name)
                                        onSelectModel("default")
                                    },
                                    color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                                    shape = RoundedCornerShape(50),
                                    shadowElevation = if (selected) 2.dp else 0.dp
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    ) {
                                        Box(
                                            Modifier.size(8.dp).clip(CircleShape).background(badgeColor)
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // AionUI Unified Rounded Composer Card
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = onPrompt,
                            placeholder = {
                                Text(
                                    "$selectedAgentDisplay — Send a message, attach files, or build in ${selectedProject.ifEmpty { "project" }}...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                            maxLines = 6
                        )

                        if (attachment.isNotBlank()) {
                            InputChip(
                                selected = true,
                                onClick = { attachment = "" },
                                label = { Text("📎 $attachment", style = MaterialTheme.typography.labelSmall) },
                                trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) }
                            )
                            Spacer(Modifier.height(4.dp))
                        }

                        // Integrated Bottom Bar inside Composer Card (like AionUI homepage.png)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilledTonalIconButton(
                                onClick = { showAttachDialog = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Attach", modifier = Modifier.size(16.dp))
                            }

                            Spacer(Modifier.width(8.dp))

                            // Project selector pill inside composer footer
                            Box {
                                Surface(
                                    onClick = { projectMenuExpanded = true },
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            selectedProject.ifEmpty { "Select project" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = projectMenuExpanded,
                                    onDismissRequest = { projectMenuExpanded = false }
                                ) {
                                    projects.forEach { obj ->
                                        val name = obj.optString("name")
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = {
                                                onSelectProject(name)
                                                projectMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            // Model Dropdown Pill
                            Box {
                                Surface(
                                    onClick = { modelMenuExpanded = true },
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Memory,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            selectedModel,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = modelMenuExpanded,
                                    onDismissRequest = { modelMenuExpanded = false }
                                ) {
                                    models.forEach { m ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    m,
                                                    fontWeight = if (m == selectedModel) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                onSelectModel(m)
                                                modelMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.width(6.dp))

                            // Mode Toggle Pill (Build vs Plan)
                            Surface(
                                onClick = { onMode(if (mode == "build") "plan" else "build") },
                                color = if (mode == "build") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        if (mode == "build") Icons.Default.Build else Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = if (mode == "build") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (mode == "build") "Build" else "Plan",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (mode == "build") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            // Circular Send Button (AionUI style)
                            val sendEnabled = canRun && prompt.isNotBlank() && isSelectedVisible
                            IconButton(
                                onClick = {
                                    if (attachment.isNotBlank() && !prompt.contains("[Attachment: $attachment]")) {
                                        onPrompt("$prompt\n\n[Attachment: $attachment]")
                                    }
                                    onSend()
                                },
                                enabled = sendEnabled,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (sendEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = "Run",
                                    tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))

                Text(
                    "Select an assistant preset to start a task",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                // 2-column / 3-column AionUI Preset Assistant Cards
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    presets.chunked(2).forEach { rowItems ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowItems.forEach { item ->
                                Surface(
                                    onClick = {
                                        onMode(item.mode)
                                        onPrompt(item.promptTemplate)
                                    },
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Surface(
                                            color = item.badgeColor.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    item.icon,
                                                    contentDescription = null,
                                                    tint = item.badgeColor,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                item.title,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                item.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // =========================================================================
        // STATE B: Claude Code UI Conversational Chat & Structured Tool View
        // =========================================================================
        val currentTaskStatus = currentTask.optString("status", "")
        val taskPrompt = currentTask.optString("prompt", "")
        val taskAgent = currentTask.optString("agent", "codex")
        val taskMode = currentTask.optString("mode", "build")
        val isRunning = currentTaskStatus in listOf("QUEUED", "PLANNING", "WAITING_APPROVAL", "EXECUTING", "VERIFYING", "REPAIRING")
        val pendingApproval = approvals.firstOrNull { it.optString("status") == "PENDING" }

        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        ) {
            // Top Compact Workflow Stepper & Approval Banner
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                PhaseStepperBar(
                    state = currentTaskStatus,
                    onCommitClick = onCommitClick
                )

                if (pendingApproval != null) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
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
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { onDeny(pendingApproval.optString("id")) }) { Text("Deny") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { onApprove(pendingApproval.optString("id")) }) { Text("Approve") }
                            }
                        }
                    }
                }
            }

            // Conversational Chat & Accordion Tool Stream
            val groupedTurns = remember(events) { groupEventsIntoChatTurns(events) }
            val listState = rememberLazyListState()
            val lastEventSeq = events.lastOrNull()?.optInt("seq", 0) ?: 0
            LaunchedEffect(selectedTask, lastEventSeq, isRunning) {
                if (groupedTurns.isNotEmpty()) {
                    val isNearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                        it >= groupedTurns.size - 2
                    } ?: true
                    if (isNearBottom || events.size <= 4) {
                        listState.animateScrollToItem(groupedTurns.size)
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                // 1. Right-aligned Royal Blue User Message Bubble + "U" avatar
                item(key = "user-prompt-${currentTask.optString("id")}", contentType = "user_prompt") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                            modifier = Modifier.widthIn(max = 520.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(
                                    taskPrompt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${taskMode.uppercase()} · $taskAgent",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "U",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }

                // 2. Grouped Chat & Collapsible Accordion Tool Cards
                items(
                    items = groupedTurns,
                    key = { it.key },
                    contentType = { turn ->
                        when (turn) {
                            is ChatTurnItem.ToolBatch -> "tool_batch"
                            is ChatTurnItem.MessageTurn -> "message"
                            is ChatTurnItem.VerifyGroup -> "verify"
                            is ChatTurnItem.ErrorTurn -> "error"
                        }
                    }
                ) { turn ->
                    val agentInitial = taskAgent.firstOrNull()?.uppercase() ?: "A"
                    val agentTitle = taskAgent.replaceFirstChar { it.uppercase() }

                    when (turn) {
                        is ChatTurnItem.ToolBatch -> {
                            ToolBatchAccordionCard(
                                batch = turn,
                                isTaskRunning = isRunning && turn == groupedTurns.lastOrNull()
                            )
                        }

                        is ChatTurnItem.MessageTurn -> {
                            StreamingAssistantMessageCard(
                                seq = turn.seq,
                                agentInitial = agentInitial,
                                agentTitle = agentTitle,
                                fullText = turn.text,
                                isLatestAndRunning = isRunning && turn == groupedTurns.lastOrNull()
                            )
                        }

                        is ChatTurnItem.VerifyGroup -> {
                            VerificationAccordionCard(group = turn)
                        }

                        is ChatTurnItem.ErrorTurn -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth().padding(start = 36.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        turn.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Live Streaming Activity Pill when task is actively running
                if (isRunning) {
                    item(key = "live-streaming-indicator", contentType = "streaming_indicator") {
                        LiveStreamingIndicatorPill(
                            status = currentTaskStatus,
                            agentName = taskAgent.replaceFirstChar { it.uppercase() },
                            latestEvent = events.lastOrNull()?.optString("message", "") ?: ""
                        )
                    }
                }
            }

            // Bottom Floating Chat Dock (Claude Code UI mobile-chat.png & desktop-main.png style)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // Floating Status Pill Strip above the input box
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    ) {
                        // Mode Pill ("• Build Mode" / "• Plan Mode")
                        Surface(
                            onClick = { onMode(if (mode == "build") "plan" else "build") },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Box(
                                    Modifier.size(7.dp).clip(CircleShape).background(
                                        if (mode == "build") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                    )
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (mode == "build") "Build Mode" else "Plan Mode",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Model Pill
                        Box {
                            Surface(
                                onClick = { modelMenuExpanded = true },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Memory,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        selectedModel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp))
                                }
                            }
                            DropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false }
                            ) {
                                models.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m) },
                                        onClick = {
                                            onSelectModel(m)
                                            modelMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        if (isRunning) {
                            Surface(
                                onClick = onCancel,
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(50)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = "Cancel",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Cancel",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        } else {
                            Surface(
                                onClick = onNewChat,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(50)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("New Session", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Rounded Floating Input Box (like Claude Code UI bottom bar)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            IconButton(
                                onClick = { showAttachDialog = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.AttachFile,
                                    contentDescription = "Attach file",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            OutlinedTextField(
                                value = prompt,
                                onValueChange = onPrompt,
                                placeholder = {
                                    Text(
                                        "Ask $selectedAgentDisplay to help with your code... (@ to attach files)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                ),
                                modifier = Modifier.weight(1f),
                                maxLines = 3
                            )

                            val sendEnabled = canRun && prompt.isNotBlank() && isSelectedVisible
                            IconButton(
                                onClick = {
                                    if (attachment.isNotBlank() && !prompt.contains("[Attachment: $attachment]")) {
                                        onPrompt("$prompt\n\n[Attachment: $attachment]")
                                    }
                                    onSend()
                                },
                                enabled = sendEnabled,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (sendEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolBatchAccordionCard(
    batch: ChatTurnItem.ToolBatch,
    isTaskRunning: Boolean
) {
    var expanded by rememberSaveable(batch.id) { mutableStateOf(false) }
    val toolEvents = remember(batch.events) {
        batch.events.filter { it.optString("kind").equals("tool", ignoreCase = true) }
    }
    val logEvents = remember(batch.events) {
        batch.events.filter { !it.optString("kind").equals("tool", ignoreCase = true) }
    }
    val toolCount = toolEvents.size
    val latestEvent = batch.events.lastOrNull()
    val latestKind = latestEvent?.optString("kind", "")?.lowercase() ?: ""
    val latestMsg = latestEvent?.optString("message", "") ?: ""

    val summaryTitle = remember(toolEvents, logEvents) {
        when {
            toolCount == 1 -> {
                val msg = toolEvents.first().optString("message", "")
                val tName = msg.substringBefore(":").trim()
                "Used $tName"
            }
            toolCount > 1 -> {
                val names = toolEvents.map { it.optString("message", "").substringBefore(":").trim() }
                    .distinct()
                    .take(3)
                "Used $toolCount tools (${names.joinToString(", ")})"
            }
            else -> {
                "Session trace (${logEvents.size} steps)"
            }
        }
    }

    val latestStatusLabel = remember(latestKind, latestMsg, isTaskRunning) {
        if (latestKind == "tool") {
            val res = latestMsg.substringAfter(":", "done").trim()
            res
        } else if (isTaskRunning) {
            "running"
        } else {
            "completed"
        }
    }

    Surface(
        onClick = { expanded = !expanded },
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth().padding(start = 36.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (toolCount > 0) Icons.Default.Build else Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    summaryTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = if (latestStatusLabel.contains("progress", ignoreCase = true) || isTaskRunning)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            if (latestStatusLabel.contains("progress", ignoreCase = true) || isTaskRunning)
                                Icons.Default.Sync
                            else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (latestStatusLabel.contains("progress", ignoreCase = true) || isTaskRunning)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            latestStatusLabel.take(18),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (latestStatusLabel.contains("progress", ignoreCase = true) || isTaskRunning)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse tools" else "Expand tools",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(animationSpec = tween(120))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    Spacer(Modifier.height(2.dp))
                    batch.events.forEach { ev ->
                        val kind = ev.optString("kind").lowercase()
                        val msg = ev.optString("message", "")
                        if (kind == "tool") {
                            val tName = msg.substringBefore(":").trim()
                            val tRes = msg.substringAfter(":", "done").trim()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    Icons.Default.Code,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    tName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    tRes,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Box(
                                    Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${kind.uppercase()}: $msg",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationAccordionCard(group: ChatTurnItem.VerifyGroup) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val headline = group.lines.firstOrNull() ?: "Verification completed"
    val extraLines = group.lines.drop(1)

    Surface(
        onClick = { if (extraLines.isNotEmpty()) expanded = !expanded },
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().padding(start = 36.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Verified,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    headline,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (extraLines.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (expanded) "Hide log" else "+${extraLines.size} lines",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded && extraLines.isNotEmpty(),
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(150)) + fadeOut()
            ) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f))
                    extraLines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingAssistantMessageCard(
    seq: Int,
    agentInitial: String,
    agentTitle: String,
    fullText: String,
    isLatestAndRunning: Boolean
) {
    // Progressive typewriter reveal when this message is the latest turn of an active task
    var revealedLength by remember(seq, fullText) {
        mutableIntStateOf(if (isLatestAndRunning) 0 else fullText.length)
    }
    LaunchedEffect(seq, fullText, isLatestAndRunning) {
        if (!isLatestAndRunning) {
            revealedLength = fullText.length
        } else {
            val step = maxOf(6, fullText.length / 28)
            while (revealedLength < fullText.length) {
                delay(16L)
                revealedLength = minOf(fullText.length, revealedLength + step)
            }
        }
    }
    val displayText = if (revealedLength >= fullText.length) fullText else fullText.substring(0, revealedLength) + " ▍"
    val isLongText = fullText.length > 520 || fullText.lines().size > 10
    var expandedText by rememberSaveable(seq) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = Color(0xFF334155),
                shape = CircleShape,
                modifier = Modifier.size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        agentInitial,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                agentTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (isLatestAndRunning && revealedLength < fullText.length) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "streaming…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(start = 34.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (!isLongText || expandedText || isLatestAndRunning) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLongText && !isLatestAndRunning) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { expandedText = !expandedText }
                            .padding(vertical = 2.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            if (expandedText) "Show less" else "Show full response (${fullText.lines().size} lines)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            if (expandedText) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveStreamingIndicatorPill(
    status: String,
    agentName: String,
    latestEvent: String
) {
    val transition = rememberInfiniteTransition(label = "stream_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.padding(start = 36.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$agentName is ${status.lowercase()}…",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (latestEvent.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    latestEvent.replace("\n", " ").take(42),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
