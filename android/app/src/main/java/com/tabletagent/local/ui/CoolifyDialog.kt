package com.tabletagent.local.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabletagent.local.LocalApi
import com.tabletagent.local.runtime.SecureSecrets
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun CoolifyDialog(
    api: LocalApi,
    selectedProject: String,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onSendDeployPrompt: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coolifyUrl = remember { SecureSecrets.getKey(context, SecureSecrets.KEY_COOLIFY_URL) }
    val coolifyToken = remember { SecureSecrets.getKey(context, SecureSecrets.KEY_COOLIFY_TOKEN) }
    val isConfigured = coolifyUrl.isNotBlank() && coolifyToken.isNotBlank()

    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var databases by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<JSONObject?>(null) }
    var deployments by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedDeployUuid by remember { mutableStateOf("") }
    var deployLogs by remember { mutableStateOf("") }
    var deployStatus by remember { mutableStateOf("") }
    var loadingLogs by remember { mutableStateOf(false) }
    var redeploying by remember { mutableStateOf(false) }
    var linkingBusy by remember { mutableStateOf(false) }
    var linkedUuid by remember { mutableStateOf("") }
    var actionBanner by remember { mutableStateOf("") }

    fun loadOverview() {
        if (!isConfigured) return
        loading = true
        errorMsg = ""
        scope.launch {
            runCatching { api.coolifyOverview(coolifyUrl, coolifyToken, selectedProject) }
                .onSuccess { res ->
                    val arr = res.optJSONArray("apps")
                    val dbArr = res.optJSONArray("databases")
                    val list = if (arr != null) (0 until arr.length()).map { arr.getJSONObject(it) } else emptyList()
                    apps = list
                    databases = if (dbArr != null) (0 until dbArr.length()).map { dbArr.getJSONObject(it) } else emptyList()
                    linkedUuid = res.optString("linked_uuid", "")
                    val preferred = list.firstOrNull { it.optBoolean("linked", false) }
                        ?: list.firstOrNull { it.optBoolean("matched", false) }
                        ?: list.firstOrNull()
                    if (preferred != null && selectedApp == null) {
                        selectedApp = preferred
                    }
                }
                .onFailure { errorMsg = it.message ?: "Failed to load Coolify overview" }
            loading = false
        }
    }

    fun loadDeployments(appUuid: String) {
        if (appUuid.isBlank()) return
        loadingLogs = true
        deployments = emptyList()
        deployLogs = ""
        selectedDeployUuid = ""
        scope.launch {
            runCatching { api.coolifyDeployments(coolifyUrl, coolifyToken, appUuid) }
                .onSuccess { res ->
                    val arr = res.optJSONArray("deployments")
                    val list = if (arr != null) (0 until arr.length()).map { arr.getJSONObject(it) } else emptyList()
                    deployments = list
                    val firstDep = list.firstOrNull()?.optString("deployment_uuid", "") ?: ""
                    if (firstDep.isNotBlank()) {
                        selectedDeployUuid = firstDep
                        runCatching { api.coolifyDeploymentLogs(coolifyUrl, coolifyToken, firstDep) }
                            .onSuccess { lres ->
                                deployStatus = lres.optString("status", "")
                                deployLogs = lres.optString("logs", "")
                            }
                            .onFailure { errorMsg = it.message ?: "Failed to load deploy logs" }
                    }
                }
                .onFailure { errorMsg = it.message ?: "Failed to load deployments" }
            loadingLogs = false
        }
    }

    LaunchedEffect(isConfigured, selectedProject) {
        if (isConfigured) loadOverview()
    }

    LaunchedEffect(selectedApp?.optString("uuid")) {
        val uid = selectedApp?.optString("uuid", "") ?: ""
        if (uid.isNotBlank()) loadDeployments(uid)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RocketLaunch, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Coolify Deploy & Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (coolifyUrl.isNotBlank()) {
                        Text(coolifyUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (isConfigured) {
                    IconButton(onClick = { loadOverview() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
        text = {
            if (!isConfigured) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Coolify is not configured yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Add your Coolify URL and API Token in Settings -> Coolify Integration so the agent can inspect deployments, read build errors, and link services.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { onDismiss(); onOpenSettings() }) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Settings")
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (errorMsg.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(errorMsg, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                    if (actionBanner.isNotBlank()) {
                        Surface(color = Color(0xFF065F46), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(actionBanner, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                        }
                    }

                    if (loading) {
                        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Left: Apps & Databases list
                            LazyColumn(
                                modifier = Modifier.weight(0.38f).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                item {
                                    Text("Applications (${apps.size})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                items(apps, key = { it.optString("uuid") }) { app ->
                                    val uid = app.optString("uuid")
                                    val isSel = selectedApp?.optString("uuid") == uid
                                    val isLinked = (linkedUuid.isNotBlank() && uid == linkedUuid) || app.optBoolean("linked", false)
                                    val matched = app.optBoolean("matched", false)
                                    val st = app.optString("status", "")
                                    Surface(
                                        color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp),
                                        border = when {
                                            isLinked -> BorderStroke(1.5.dp, Color(0xFF8B5CF6))
                                            matched -> BorderStroke(1.dp, Color(0xFF10B981))
                                            else -> null
                                        },
                                        modifier = Modifier.fillMaxWidth().clickable { selectedApp = app }
                                    ) {
                                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    app.optString("name", uid),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isLinked) {
                                                    Surface(color = Color(0xFF8B5CF6).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                                        Text("Linked", fontSize = 9.sp, color = Color(0xFF8B5CF6), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                    }
                                                } else if (matched) {
                                                    Surface(color = Color(0xFF10B981).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                                        Text("Auto", fontSize = 9.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                    }
                                                }
                                            }
                                            Text(
                                                "${app.optString("git_branch")} · $st",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                if (databases.isNotEmpty()) {
                                    item {
                                        Spacer(Modifier.height(4.dp))
                                        Text("Databases (${databases.size})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                    items(databases, key = { it.optString("uuid") }) { db ->
                                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(8.dp)) {
                                                Text(db.optString("name"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                                Text("${db.optString("type")} · ${db.optString("status")}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }

                            // Right: Selected App Deployments & Logs
                            Column(modifier = Modifier.weight(0.62f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val app = selectedApp
                                if (app == null) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Select an application to inspect deployments", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else {
                                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            val appUid = app.optString("uuid")
                                            val isThisLinked = (linkedUuid.isNotBlank() && appUid == linkedUuid) || app.optBoolean("linked", false)
                                            Column(Modifier.weight(1f)) {
                                                Text(app.optString("name"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                val fqdn = app.optString("fqdn", "")
                                                if (fqdn.isNotBlank()) {
                                                    Text(fqdn, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                if (isThisLinked && selectedProject.isNotBlank()) {
                                                    Text("Linked to $selectedProject · Agent knows this container", fontSize = 9.sp, color = Color(0xFF8B5CF6))
                                                }
                                            }
                                            if (selectedProject.isNotBlank()) {
                                                OutlinedButton(
                                                    onClick = {
                                                        linkingBusy = true
                                                        actionBanner = ""
                                                        scope.launch {
                                                            if (isThisLinked) {
                                                                runCatching { api.coolifyUnlink(selectedProject) }
                                                                    .onSuccess {
                                                                        linkedUuid = ""
                                                                        actionBanner = "Unlinked Coolify container from $selectedProject"
                                                                        loadOverview()
                                                                    }
                                                                    .onFailure { errorMsg = it.message ?: "Unlink failed" }
                                                            } else {
                                                                runCatching {
                                                                    api.coolifyLink(
                                                                        project = selectedProject,
                                                                        appUuid = appUid,
                                                                        appName = app.optString("name"),
                                                                        fqdn = app.optString("fqdn"),
                                                                        gitBranch = app.optString("git_branch"),
                                                                        buildPack = app.optString("build_pack"),
                                                                        status = app.optString("status")
                                                                    )
                                                                }
                                                                    .onSuccess {
                                                                        linkedUuid = appUid
                                                                        actionBanner = "🔗 Linked ${app.optString("name")} to $selectedProject"
                                                                        loadOverview()
                                                                    }
                                                                    .onFailure { errorMsg = it.message ?: "Link failed" }
                                                            }
                                                            linkingBusy = false
                                                        }
                                                    },
                                                    enabled = !linkingBusy,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Icon(
                                                        if (isThisLinked) Icons.Default.LinkOff else Icons.Default.Link,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(if (isThisLinked) "Unlink" else "Link", fontSize = 11.sp)
                                                }
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Button(
                                                onClick = {
                                                    redeploying = true
                                                    actionBanner = ""
                                                    scope.launch {
                                                        runCatching { api.coolifyDeploy(coolifyUrl, coolifyToken, app.optString("uuid")) }
                                                            .onSuccess {
                                                                actionBanner = "🚀 Redeploy triggered for ${app.optString("name")}"
                                                                loadDeployments(app.optString("uuid"))
                                                            }
                                                            .onFailure { errorMsg = it.message ?: "Redeploy failed" }
                                                        redeploying = false
                                                    }
                                                },
                                                enabled = !redeploying,
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Text(if (redeploying) "Deploying…" else "🚀 Redeploy", fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    // Recent deployment selector chips
                                    if (deployments.isNotEmpty()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                            deployments.take(4).forEach { dep ->
                                                val duid = dep.optString("deployment_uuid")
                                                val dStatus = dep.optString("status")
                                                val isSelDep = selectedDeployUuid == duid
                                                FilterChip(
                                                    selected = isSelDep,
                                                    onClick = {
                                                        selectedDeployUuid = duid
                                                        loadingLogs = true
                                                        scope.launch {
                                                            runCatching { api.coolifyDeploymentLogs(coolifyUrl, coolifyToken, duid) }
                                                                .onSuccess { lres ->
                                                                    deployStatus = lres.optString("status", dStatus)
                                                                    deployLogs = lres.optString("logs", "")
                                                                }
                                                                .onFailure { errorMsg = it.message ?: "Failed to load logs" }
                                                            loadingLogs = false
                                                        }
                                                    },
                                                    label = {
                                                        Text(
                                                            "${dStatus.take(8)} ${dep.optString("commit").take(6)}".trim(),
                                                            fontSize = 10.sp
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    Surface(
                                        color = Color(0xFF0F172A),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).fillMaxWidth()
                                    ) {
                                        if (loadingLogs) {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                            }
                                        } else {
                                            Column(Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())) {
                                                Text(
                                                    deployLogs.ifBlank { "No deployment logs available." },
                                                    color = Color(0xFFE2E8F0),
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    lineHeight = 14.sp
                                                )
                                            }
                                        }
                                    }

                                    if (deployLogs.isNotBlank()) {
                                        Button(
                                            onClick = {
                                                val appName = app.optString("name")
                                                val branch = app.optString("git_branch")
                                                val fqdn = app.optString("fqdn")
                                                val tailLogs = deployLogs.takeLast(3500)
                                                val promptMsg = "Analyze and fix this Coolify deployment issue for app '$appName' (branch: $branch, url: $fqdn, status: $deployStatus).\n\nCoolify Deploy Logs (redacted):\n```\n$tailLogs\n```"
                                                onDismiss()
                                                onSendDeployPrompt(promptMsg)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                            modifier = Modifier.fillMaxWidth().height(34.dp)
                                        ) {
                                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("✨ Send Deploy Log to AI Agent", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
