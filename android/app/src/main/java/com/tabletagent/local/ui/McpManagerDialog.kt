package com.tabletagent.local.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tabletagent.local.LocalApi
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun McpManagerDialog(
    api: LocalApi,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var presets by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var showAddForm by remember { mutableStateOf(false) }

    var mcpId by remember { mutableStateOf("") }
    var mcpName by remember { mutableStateOf("") }
    var mcpTransport by remember { mutableStateOf("stdio") }
    var mcpCommand by remember { mutableStateOf("npx") }
    var mcpArgs by remember { mutableStateOf("") }
    var mcpUrl by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun loadMcp() {
        loading = true
        errorMsg = ""
        scope.launch {
            runCatching { api.mcpServers() }
                .onSuccess { res ->
                    val sArr = res.optJSONArray("servers")
                    val pArr = res.optJSONArray("presets")
                    servers = if (sArr != null) (0 until sArr.length()).map { sArr.getJSONObject(it) } else emptyList()
                    presets = if (pArr != null) (0 until pArr.length()).map { pArr.getJSONObject(it) } else emptyList()
                }
                .onFailure { errorMsg = it.message ?: "Failed to load MCP servers" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadMcp()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.90f).fillMaxHeight(0.84f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MCP Servers Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Syncs Model Context Protocol tools to Codex (~/.codex/config.toml) & Claude Code (~/.claude.json)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showAddForm = !showAddForm },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(if (showAddForm) Icons.Default.List else Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showAddForm) "Servers" else "Add MCP", fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (errorMsg.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(errorMsg, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                    }
                }

                if (showAddForm) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = mcpTransport == "stdio", onClick = { mcpTransport = "stdio" }, label = { Text("stdio (npx / python)") })
                            FilterChip(selected = mcpTransport == "http", onClick = { mcpTransport = "http" }, label = { Text("HTTP / SSE URL") })
                        }
                        OutlinedTextField(value = mcpName, onValueChange = { mcpName = it }, label = { Text("Server Name (e.g. GitHub / Fetch)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (mcpTransport == "stdio") {
                            OutlinedTextField(value = mcpCommand, onValueChange = { mcpCommand = it }, label = { Text("Command (e.g. npx)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = mcpArgs, onValueChange = { mcpArgs = it }, label = { Text("Arguments (space-separated, e.g. -y @modelcontextprotocol/server-fetch)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        } else {
                            OutlinedTextField(value = mcpUrl, onValueChange = { mcpUrl = it }, label = { Text("MCP Endpoint URL (https://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { showAddForm = false }) { Text("Cancel") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = !saving && mcpName.isNotBlank() && ((mcpTransport == "stdio" && mcpCommand.isNotBlank()) || (mcpTransport == "http" && mcpUrl.isNotBlank())),
                                onClick = {
                                    saving = true
                                    errorMsg = ""
                                    val argsList = mcpArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                                    scope.launch {
                                        runCatching {
                                            api.upsertMcpServer(
                                                id = mcpId.ifBlank { mcpName },
                                                name = mcpName,
                                                transport = mcpTransport,
                                                command = mcpCommand,
                                                args = argsList,
                                                url = mcpUrl,
                                                enabled = true
                                            )
                                        }
                                            .onSuccess {
                                                mcpId = ""; mcpName = ""; mcpArgs = ""; mcpUrl = ""
                                                showAddForm = false
                                                loadMcp()
                                            }
                                            .onFailure { errorMsg = it.message ?: "Failed to save MCP server" }
                                        saving = false
                                    }
                                }
                            ) {
                                Text(if (saving) "Saving…" else "Save & Sync")
                            }
                        }
                    }
                } else {
                    if (presets.isNotEmpty()) {
                        Text("Quick Presets", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        ) {
                            presets.forEach { pr ->
                                val pid = pr.optString("id")
                                val pname = pr.optString("name", pid)
                                val installed = servers.any { it.optString("id") == pid }
                                AssistChip(
                                    onClick = {
                                        if (!installed) {
                                            val arr = pr.optJSONArray("args") ?: JSONArray()
                                            val argsList = (0 until arr.length()).map { arr.getString(it) }
                                            scope.launch {
                                                runCatching {
                                                    api.upsertMcpServer(
                                                        id = pid,
                                                        name = pname,
                                                        transport = pr.optString("transport", "stdio"),
                                                        command = pr.optString("command", "npx"),
                                                        args = argsList,
                                                        url = pr.optString("url", ""),
                                                        enabled = true
                                                    )
                                                }
                                                    .onSuccess { loadMcp() }
                                                    .onFailure { errorMsg = it.message ?: "Failed to add preset" }
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(if (installed) Icons.Default.Check else Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                                    },
                                    label = { Text(pname, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    if (loading) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (servers.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("No MCP servers configured yet. Tap a Quick Preset above or '+ Add MCP'.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(servers, key = { it.optString("id") }) { srv ->
                                val sid = srv.optString("id")
                                val sname = srv.optString("name", sid)
                                val tr = srv.optString("transport", "stdio")
                                val cmd = srv.optString("command", "")
                                val url = srv.optString("url", "")
                                val argsArr = srv.optJSONArray("args")
                                val argsStr = if (argsArr != null) (0 until argsArr.length()).joinToString(" ") { argsArr.getString(it) } else ""
                                val enabled = srv.optBoolean("enabled", true)

                                Surface(
                                    color = if (enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp),
                                    border = if (enabled) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(sname, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(4.dp)) {
                                                    Text(tr.uppercase(), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                                }
                                            }
                                            Text(
                                                if (tr == "http") url else "$cmd $argsStr".trim(),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    runCatching { api.deleteMcpServer(sid) }
                                                        .onSuccess { loadMcp() }
                                                        .onFailure { errorMsg = it.message ?: "Delete failed" }
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        }
                                        Switch(
                                            checked = enabled,
                                            onCheckedChange = { next ->
                                                scope.launch {
                                                    runCatching { api.toggleMcpServer(sid, next) }
                                                        .onSuccess { loadMcp() }
                                                        .onFailure { errorMsg = it.message ?: "Toggle failed" }
                                                }
                                            }
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
}
