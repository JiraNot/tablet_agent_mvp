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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabletagent.local.runtime.AllCliAuthStatus
import com.tabletagent.local.runtime.CliAuthManager
import com.tabletagent.local.runtime.GitHubRepoItem
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

private data class ToolCallRow(
    val name: String,
    val status: String
)

private data class TraceLineRow(
    val kind: String,
    val message: String
)

private sealed interface ChatTurnItem {
    val key: String
    data class ToolBatch(
        val id: String,
        val summaryTitle: String,
        val latestStatusLabel: String,
        val isToolBatch: Boolean,
        val toolRows: List<ToolCallRow>,
        val traceRows: List<TraceLineRow>
    ) : ChatTurnItem {
        override val key: String = "tools-$id"
    }
    data class UserTurn(val seq: Int, val text: String) : ChatTurnItem {
        override val key: String = "user-$seq"
    }
    data class MessageTurn(val seq: Int, val text: String, val lineCount: Int) : ChatTurnItem {
        override val key: String = "msg-$seq"
    }
    data class VerifyGroup(val id: String, val headline: String, val extraLines: List<String>) : ChatTurnItem {
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
            val toolRows = mutableListOf<ToolCallRow>()
            val traceRows = mutableListOf<TraceLineRow>()
            for (ev in pendingTools) {
                val k = ev.optString("kind").lowercase()
                val m = ev.optString("message", "")
                if (k == "tool") {
                    val tName = m.substringBefore(":").trim()
                    val tRes = m.substringAfter(":", "done").trim()
                    toolRows.add(ToolCallRow(tName, tRes))
                } else {
                    traceRows.add(TraceLineRow(k.uppercase(), m))
                }
            }
            val toolCount = toolRows.size
            val summaryTitle = when {
                toolCount == 1 -> "Used ${toolRows.first().name}"
                toolCount > 1 -> {
                    val names = toolRows.map { it.name }.distinct().take(3)
                    "Used $toolCount tools (${names.joinToString(", ")})"
                }
                else -> "Session trace (${traceRows.size} steps)"
            }
            val latestEv = pendingTools.last()
            val latestKind = latestEv.optString("kind", "").lowercase()
            val latestMsg = latestEv.optString("message", "")
            val latestStatus = if (latestKind == "tool") {
                latestMsg.substringAfter(":", "done").trim().take(18)
            } else {
                "completed"
            }
            result.add(
                ChatTurnItem.ToolBatch(
                    id = "$firstSeq",
                    summaryTitle = summaryTitle,
                    latestStatusLabel = latestStatus,
                    isToolBatch = toolCount > 0,
                    toolRows = toolRows,
                    traceRows = traceRows
                )
            )
            pendingTools.clear()
        }
    }

    fun flushVerifies() {
        if (pendingVerifies.isNotEmpty()) {
            val firstSeq = pendingVerifies.first().first
            val lines = pendingVerifies.map { it.second }
            result.add(
                ChatTurnItem.VerifyGroup(
                    id = "$firstSeq",
                    headline = lines.firstOrNull() ?: "Verification completed",
                    extraLines = lines.drop(1)
                )
            )
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
            "user" -> {
                flushTools()
                flushVerifies()
                if (msg.isNotEmpty()) result.add(ChatTurnItem.UserTurn(seq, msg))
            }
            "message" -> {
                flushTools()
                flushVerifies()
                if (msg.isNotEmpty()) {
                    result.add(ChatTurnItem.MessageTurn(seq, msg, msg.count { it == '\n' } + 1))
                }
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
        modifier = modifier.fillMaxHeight().width(232.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            // Brand Header (Claude Code UI + AionUI style)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Tablet Agent",
                        style = MaterialTheme.typography.titleSmall,
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
                IconButton(onClick = onNewProject, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = "New project",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // "+ New Session / New Chat" primary dark button (like Claude Code UI & AionUI)
            Button(
                onClick = onNewChat,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF111827),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("New Session", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))

            // Projects Section (tightened spacing)
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
            Spacer(Modifier.height(3.dp))

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                projects.take(10).forEach { proj ->
                    val name = proj.optString("name")
                    val isSelected = name == selectedProject
                    val bgMod = if (isSelected) {
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = bgMod
                            .clickable { onSelectProject(name) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            if (isSelected) Icons.Default.Folder else Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Conversations / Sessions Section (tightened spacing)
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
            Spacer(Modifier.height(3.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    items = tasks,
                    key = { it.optString("id") },
                    contentType = { "sidebar_task" }
                ) { task ->
                    val id = task.optString("id")
                    val isSelected = !isNewChatMode && id == selectedTask
                    val status = task.optString("status", "")
                    val agent = task.optString("agent", "codex")
                    val promptText = task.optString("prompt", "Session").replace("\n", " ").trim()
                    val rowMod = if (isSelected) {
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                    }

                    Column(
                        modifier = rowMod
                            .clickable { onSelectTask(id) }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
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
                        Spacer(Modifier.height(2.dp))
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
                            Text(
                                task.optString("mode", "build").uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
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

                // Compact Top-Right Controls: [ Single Cycling View Button (Chat ⇄ Split ⇄ Preview) | 📁 Files | 🌿 Git ]
                var viewMenuExpanded by remember { mutableStateOf(false) }
                val (currentViewLabel, currentViewIcon) = when (workspaceView) {
                    1 -> "Split" to Icons.Default.VerticalSplit
                    2 -> "Preview" to Icons.Default.Visibility
                    3 -> "Files" to Icons.Default.FolderOpen
                    4 -> "Settings" to Icons.Default.Settings
                    else -> "Chat" to Icons.Default.ChatBubbleOutline
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Single compact cycling View Pill (Tap to cycle Chat -> Split -> Preview, or tap arrow for menu)
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable {
                                        val next = when (workspaceView) {
                                            0 -> 1
                                            1 -> 2
                                            2 -> 0
                                            else -> 0
                                        }
                                        onSelectWorkspaceView(next)
                                    }
                                    .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp)
                            ) {
                                Icon(
                                    currentViewIcon,
                                    contentDescription = currentViewLabel,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    currentViewLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clickable { viewMenuExpanded = true }
                                    .padding(horizontal = 4.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Select view",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = viewMenuExpanded,
                            onDismissRequest = { viewMenuExpanded = false }
                        ) {
                            listOf(
                                Triple(0, "Chat View", Icons.Default.ChatBubbleOutline),
                                Triple(1, "Split (Chat + Preview)", Icons.Default.VerticalSplit),
                                Triple(2, "Full Preview", Icons.Default.Visibility),
                                Triple(3, "Quick Files", Icons.Default.FolderOpen)
                            ).forEach { (idx, label, icon) ->
                                DropdownMenuItem(
                                    leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
                                    text = { Text(label, style = MaterialTheme.typography.bodySmall) },
                                    onClick = {
                                        onSelectWorkspaceView(idx)
                                        viewMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Compact Icon Button for Quick Files
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (workspaceView == 3) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onSelectWorkspaceView(if (workspaceView == 3) 0 else 3) }
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = "Files",
                            modifier = Modifier.size(14.dp),
                            tint = if (workspaceView == 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Compact Icon Button for Git Review & Commit
                    if (selectedProject.isNotBlank()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onOpenGit() }
                        ) {
                            Icon(
                                Icons.Default.Commit,
                                contentDescription = "Git",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
    onCreateOrCloneProject: (name: String, gitUrl: String) -> Unit = { _, _ -> },
    onSignInGitHub: () -> Unit = {},
    cloningProject: Boolean = false,
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

                Spacer(Modifier.height(14.dp))

                // =========================================================================
                // Codex / ChatGPT Agent Workspace Selector Card (Local | GitHub Repos | Clone URL)
                // =========================================================================
                val context = androidx.compose.ui.platform.LocalContext.current
                var workspacePickerTab by rememberSaveable { mutableIntStateOf(0) }
                var workspacePickerExpanded by rememberSaveable { mutableStateOf(true) }
                var userRepos by remember { mutableStateOf<List<GitHubRepoItem>>(emptyList()) }
                var loadingRepos by remember { mutableStateOf(false) }
                var repoError by remember { mutableStateOf("") }
                var repoSearch by rememberSaveable { mutableStateOf("") }
                var cloneUrlEntry by rememberSaveable { mutableStateOf("") }
                var cloneFolderEntry by rememberSaveable { mutableStateOf("") }

                LaunchedEffect(workspacePickerTab, cliAuthStatus.github.signedIn) {
                    if (workspacePickerTab == 1 && cliAuthStatus.github.signedIn) {
                        loadingRepos = true
                        repoError = ""
                        CliAuthManager.fetchGitHubUserRepos(context)
                            .onSuccess { userRepos = it }
                            .onFailure { repoError = it.message ?: "Failed to load repositories" }
                        loadingRepos = false
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.FolderSpecial,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Choose a project or GitHub repository",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (selectedProject.isNotBlank()) "Active workspace: $selectedProject"
                                else "Select a local workspace or clone from GitHub to start coding",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 3 Mode Pills: [ Local | GitHub Repos | Clone URL ]
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                Triple(0, "Local (${projects.size})", Icons.Default.Folder),
                                Triple(1, "GitHub Repos", Icons.Default.CloudDownload),
                                Triple(2, "Clone URL / New", Icons.Default.Link)
                            ).forEach { (idx, label, icon) ->
                                val active = workspacePickerTab == idx && workspacePickerExpanded
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (active) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable {
                                            if (workspacePickerTab == idx) {
                                                workspacePickerExpanded = !workspacePickerExpanded
                                            } else {
                                                workspacePickerTab = idx
                                                workspacePickerExpanded = true
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (workspacePickerExpanded) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(10.dp))

                        when (workspacePickerTab) {
                            0 -> {
                                // Tab 0: Local Projects Grid + Quick New Folder
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    projects.forEach { proj ->
                                        val name = proj.optString("name")
                                        val isSelected = name == selectedProject
                                        val hasGit = proj.optBoolean("git", false)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .clickable { onSelectProject(name) }
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                if (isSelected) Icons.Default.CheckCircle else Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                name,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (hasGit) {
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    "git",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Button to switch to Clone URL / New Project
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                            .clickable { workspacePickerTab = 2 }
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(4.dp))
                                        Text("New / Clone", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }

                            1 -> {
                                // Tab 1: GitHub User Repositories (like ChatGPT Codex repo selector)
                                if (!cliAuthStatus.github.signedIn) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                            .padding(12.dp)
                                    ) {
                                        Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Connect your GitHub Account", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                            Text("Sign in once via GitHub Device OAuth to browse and clone your public & private repositories directly.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Button(
                                            onClick = onSignInGitHub,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.Login, null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text("Sign in to GitHub", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                contentAlignment = Alignment.CenterStart,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(32.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .padding(horizontal = 10.dp)
                                            ) {
                                                if (repoSearch.isEmpty()) {
                                                    Text("Search your GitHub repositories (${cliAuthStatus.github.label})…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                BasicTextField(
                                                    value = repoSearch,
                                                    onValueChange = { repoSearch = it },
                                                    singleLine = true,
                                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                            if (loadingRepos || cloningProject) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            }
                                        }

                                        if (repoError.isNotBlank()) {
                                            Text(repoError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }

                                        val filteredRepos = remember(userRepos, repoSearch) {
                                            if (repoSearch.isBlank()) userRepos
                                            else userRepos.filter {
                                                it.fullName.contains(repoSearch, ignoreCase = true) ||
                                                    it.description.contains(repoSearch, ignoreCase = true)
                                            }
                                        }

                                        if (!loadingRepos && filteredRepos.isEmpty()) {
                                            Text("No matching repositories found.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            Column(
                                                modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState()),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                filteredRepos.forEach { repo ->
                                                    val alreadyLocal = projects.any { it.optString("name").equals(repo.name, ignoreCase = true) }
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                                            .clickable(enabled = !cloningProject) {
                                                                if (alreadyLocal) {
                                                                    onSelectProject(repo.name)
                                                                } else {
                                                                    val safeName = repo.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                                                    onCreateOrCloneProject(safeName, repo.cloneUrl)
                                                                }
                                                            }
                                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                                    ) {
                                                        Icon(
                                                            if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Public,
                                                            contentDescription = null,
                                                            tint = if (repo.isPrivate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        Column(Modifier.weight(1f)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Text(repo.fullName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                Spacer(Modifier.width(6.dp))
                                                                Text(
                                                                    if (repo.isPrivate) "PRIVATE" else "PUBLIC",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    fontSize = 9.sp,
                                                                    color = if (repo.isPrivate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                            if (repo.description.isNotBlank()) {
                                                                Text(repo.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            }
                                                        }
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            if (alreadyLocal) "Open Local" else if (cloningProject) "Cloning…" else "Clone & Open",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (alreadyLocal) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            2 -> {
                                // Tab 2: Clone from any GitHub HTTPS URL or Create Empty Local Project
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // GitHub URL box
                                        Box(
                                            contentAlignment = Alignment.CenterStart,
                                            modifier = Modifier
                                                .weight(1.4f)
                                                .height(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                                .padding(horizontal = 10.dp)
                                        ) {
                                            if (cloneUrlEntry.isEmpty()) {
                                                Text(
                                                    "https://github.com/owner/repo.git (or leave blank for new project)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            BasicTextField(
                                                value = cloneUrlEntry,
                                                onValueChange = { url ->
                                                    cloneUrlEntry = url
                                                    val derived = url.trim().removeSuffix("/").removeSuffix(".git").substringAfterLast('/')
                                                    if (derived.isNotBlank() && !derived.contains("github.com")) {
                                                        cloneFolderEntry = derived.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                                    }
                                                },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        // Folder name box
                                        Box(
                                            contentAlignment = Alignment.CenterStart,
                                            modifier = Modifier
                                                .weight(0.75f)
                                                .height(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                                .padding(horizontal = 10.dp)
                                        ) {
                                            if (cloneFolderEntry.isEmpty()) {
                                                Text("Folder name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                            }
                                            BasicTextField(
                                                value = cloneFolderEntry,
                                                onValueChange = { cloneFolderEntry = it },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                val targetName = cloneFolderEntry.trim()
                                                val targetUrl = cloneUrlEntry.trim()
                                                if (targetName.isNotBlank()) {
                                                    onCreateOrCloneProject(targetName, targetUrl)
                                                    cloneUrlEntry = ""
                                                    cloneFolderEntry = ""
                                                    workspacePickerTab = 0
                                                }
                                            },
                                            enabled = cloneFolderEntry.isNotBlank() && !cloningProject,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text(
                                                when {
                                                    cloningProject -> "Cloning…"
                                                    cloneUrlEntry.isNotBlank() -> "Clone & Open"
                                                    else -> "Create Project"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

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
            LaunchedEffect(lastEventSeq) {
                if (isRunning && groupedTurns.isNotEmpty() && !listState.isScrollInProgress) {
                    val isNearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                        it >= groupedTurns.size - 2
                    } ?: true
                    if (isNearBottom) {
                        listState.scrollToItem(groupedTurns.size)
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
                            is ChatTurnItem.UserTurn -> "user_turn"
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

                        is ChatTurnItem.UserTurn -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Column(
                                    modifier = Modifier
                                        .widthIn(max = 520.dp)
                                        .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        turn.text,
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
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                ) {
                                    Text(
                                        "U",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }

                        is ChatTurnItem.MessageTurn -> {
                            StreamingAssistantMessageCard(
                                turn = turn,
                                agentInitial = agentInitial,
                                agentTitle = agentTitle,
                                isLatestAndRunning = isRunning && turn == groupedTurns.lastOrNull()
                            )
                        }

                        is ChatTurnItem.VerifyGroup -> {
                            VerificationAccordionCard(group = turn)
                        }

                        is ChatTurnItem.ErrorTurn -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
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

            // Compact Single-Row Bottom Chat Composer (Plan/Build + Model embedded inside input box, auto-expanding)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        // Attach File Icon
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { showAttachDialog = true }
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Attach file",
                                tint = if (attachment.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(Modifier.width(4.dp))

                        // Embedded Mode Chip (Plan / Build) inside chat box
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onMode(if (mode == "build") "plan" else "build") }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape).background(
                                    if (mode == "build") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                )
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (mode == "build") "Build" else "Plan",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(Modifier.width(4.dp))

                        // Embedded Model Selector Chip inside chat box
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { modelMenuExpanded = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Memory,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    selectedModel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 96.dp)
                                )
                                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(13.dp))
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

                        Spacer(Modifier.width(8.dp))

                        // Auto-expanding compact input field (starts 1 line high, expands up to 5 lines)
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.weight(1f).padding(vertical = 3.dp)
                        ) {
                            if (prompt.isEmpty()) {
                                Text(
                                    "Ask $selectedAgentDisplay…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = prompt,
                                onValueChange = onPrompt,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        if (isRunning) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .clickable { onCancel() }
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Cancel",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "Stop",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }

                        val sendEnabled = canRun && prompt.isNotBlank() && isSelectedVisible
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    if (sendEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable(enabled = sendEnabled) {
                                    if (attachment.isNotBlank() && !prompt.contains("[Attachment: $attachment]")) {
                                        onPrompt("$prompt\n\n[Attachment: $attachment]")
                                    }
                                    onSend()
                                }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
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
    val statusLabel = if (isTaskRunning) "running" else batch.latestStatusLabel
    val isBusy = isTaskRunning || statusLabel.contains("progress", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    if (batch.isToolBatch) Icons.Default.Build else Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(11.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                batch.summaryTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isBusy) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Icon(
                    if (isBusy) Icons.Default.Sync else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isBusy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isBusy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                batch.toolRows.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            row.name,
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
                            row.status,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1
                        )
                    }
                }
                batch.traceRows.forEach { trace ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Box(
                            Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${trace.kind}: ${trace.message}",
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

@Composable
private fun VerificationAccordionCard(group: ChatTurnItem.VerifyGroup) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val hasExtra = group.extraLines.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f))
            .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable(enabled = hasExtra) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Verified,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                group.headline,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = if (expanded) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hasExtra) {
                Spacer(Modifier.width(6.dp))
                Text(
                    if (expanded) "Hide" else "+${group.extraLines.size} lines",
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
        if (expanded && hasExtra) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f))
                group.extraLines.forEach { line ->
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

@Composable
private fun StreamingAssistantMessageCard(
    turn: ChatTurnItem.MessageTurn,
    agentInitial: String,
    agentTitle: String,
    isLatestAndRunning: Boolean
) {
    val fullText = turn.text
    var revealedLength by remember(turn.seq, fullText) {
        mutableIntStateOf(if (isLatestAndRunning) 0 else fullText.length)
    }
    LaunchedEffect(turn.seq, fullText, isLatestAndRunning) {
        if (!isLatestAndRunning) {
            revealedLength = fullText.length
        } else {
            val step = maxOf(8, fullText.length / 22)
            while (revealedLength < fullText.length) {
                delay(16L)
                revealedLength = minOf(fullText.length, revealedLength + step)
            }
        }
    }
    val displayText = if (revealedLength >= fullText.length) fullText else fullText.substring(0, revealedLength) + " ▍"
    val isLongText = fullText.length > 520 || turn.lineCount > 10
    var expandedText by rememberSaveable(turn.seq) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF334155))
            ) {
                Text(
                    agentInitial,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
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
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(11.dp)
        ) {
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
                        if (expandedText) "Show less" else "Show full response (${turn.lineCount} lines)",
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
