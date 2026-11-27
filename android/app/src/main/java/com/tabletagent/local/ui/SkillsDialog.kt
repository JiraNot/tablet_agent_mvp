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
import org.json.JSONObject

@Composable
fun SkillsDialog(
    api: LocalApi,
    selectedProject: String,
    onDismiss: () -> Unit,
    onInsertSlashCommand: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var showCreateForm by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }
    var newInstr by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    fun loadSkills() {
        loading = true
        errorMsg = ""
        scope.launch {
            runCatching { api.skills(selectedProject) }
                .onSuccess { res ->
                    val arr = res.optJSONArray("skills")
                    skills = if (arr != null) (0 until arr.length()).map { arr.getJSONObject(it) } else emptyList()
                }
                .onFailure { errorMsg = it.message ?: "Failed to load skills" }
            loading = false
        }
    }

    LaunchedEffect(selectedProject) {
        loadSkills()
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
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Agent Skills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (selectedProject.isNotBlank()) "Toggle skills for '$selectedProject' or insert /slash in chat"
                            else "Select a project to toggle persistent skills, or insert /slash in chat",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selectedProject.isNotBlank()) {
                        OutlinedButton(
                            onClick = { showCreateForm = !showCreateForm },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(if (showCreateForm) Icons.Default.List else Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (showCreateForm) "Browse" else "New Skill", fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
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

                if (showCreateForm && selectedProject.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Text("Create Workspace Skill (.agents/skills/<slug>/SKILL.md)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Skill Name (e.g. api-reviewer)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newDesc,
                            onValueChange = { newDesc = it },
                            label = { Text("Short Description") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newInstr,
                            onValueChange = { newInstr = it },
                            label = { Text("Skill Instructions for the Agent") },
                            minLines = 5,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { showCreateForm = false }) { Text("Cancel") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = !creating && newName.isNotBlank() && newInstr.isNotBlank(),
                                onClick = {
                                    creating = true
                                    errorMsg = ""
                                    scope.launch {
                                        runCatching { api.createCustomSkill(selectedProject, newName, newDesc, newInstr) }
                                            .onSuccess {
                                                newName = ""; newDesc = ""; newInstr = ""
                                                showCreateForm = false
                                                loadSkills()
                                            }
                                            .onFailure { errorMsg = it.message ?: "Failed to create skill" }
                                        creating = false
                                    }
                                }
                            ) {
                                Text(if (creating) "Saving…" else "Save & Enable Skill")
                            }
                        }
                    }
                } else {
                    val categories = listOf("All", "Planning", "UI/UX", "Polish", "QA", "DevOps", "Security", "Custom")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    ) {
                        categories.forEach { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat },
                                label = { Text(cat, fontSize = 11.sp) }
                            )
                        }
                    }

                    val filtered = skills.filter {
                        selectedCategory == "All" || it.optString("category").equals(selectedCategory, ignoreCase = true)
                    }

                    if (loading) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filtered, key = { it.optString("id") }) { sk ->
                                val sid = sk.optString("id")
                                val sname = sk.optString("name", sid)
                                val scat = sk.optString("category", "General")
                                val sdesc = sk.optString("description", "")
                                val sslash = sk.optString("slash", "/$sid")
                                val enabled = sk.optBoolean("enabled", false)

                                Surface(
                                    color = if (enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp),
                                    border = if (enabled) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(sname, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(4.dp)) {
                                                    Text(sslash, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                                }
                                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(4.dp)) {
                                                    Text(scat, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                                }
                                            }
                                            Text(
                                                sdesc,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        OutlinedButton(
                                            onClick = {
                                                onInsertSlashCommand("$sslash ")
                                                onDismiss()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("Use", fontSize = 11.sp)
                                        }
                                        if (selectedProject.isNotBlank()) {
                                            Spacer(Modifier.width(8.dp))
                                            Switch(
                                                checked = enabled,
                                                onCheckedChange = { next ->
                                                    scope.launch {
                                                        runCatching { api.toggleSkill(selectedProject, sid, next) }
                                                            .onSuccess { loadSkills() }
                                                            .onFailure { errorMsg = it.message ?: "Failed to toggle skill" }
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
}
