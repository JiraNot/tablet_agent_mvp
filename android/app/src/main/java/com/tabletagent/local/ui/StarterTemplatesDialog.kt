package com.tabletagent.local.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class StarterTemplate(
    val id: String,
    val title: String,
    val description: String,
    val techStack: List<String>,
    val icon: ImageVector
)

@Composable
fun StarterTemplatesDialog(
    onDismiss: () -> Unit,
    onLaunchTemplate: (projectName: String, templateId: String) -> Unit,
    isCreating: Boolean = false
) {
    val templates = listOf(
        StarterTemplate(
            id = "react_vite",
            title = "React + Vite Studio",
            description = "Modern frontend with Tailwind CSS, Lucide Icons, and hot-reload development server.",
            techStack = listOf("React 18", "Vite 6", "TailwindCSS", "Lucide"),
            icon = Icons.Default.Bolt
        ),
        StarterTemplate(
            id = "pwa_notes",
            title = "Mobile PWA Notes App",
            description = "Offline-first Progressive Web App with service worker caching and localStorage sync.",
            techStack = listOf("Vanilla JS", "ServiceWorker", "PWA Manifest", "Local Storage"),
            icon = Icons.Default.Smartphone
        ),
        StarterTemplate(
            id = "data_dashboard",
            title = "Data Analytics Dashboard",
            description = "Telemetry & KPIs dashboard with interactive Chart.js line charts and summary metrics.",
            techStack = listOf("Chart.js", "Responsive Grid", "Tailwind", "Analytics"),
            icon = Icons.Default.BarChart
        ),
        StarterTemplate(
            id = "fastapi_sqlite",
            title = "FastAPI + SQLite Backend",
            description = "Lightweight Python REST API with automatic Swagger docs, Pydantic, and SQLite database.",
            techStack = listOf("Python 3", "FastAPI", "Uvicorn", "SQLite"),
            icon = Icons.Default.Storage
        )
    )

    var selectedTemplateId by remember { mutableStateOf("react_vite") }
    var projectName by remember { mutableStateOf("my-starter-app") }

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        icon = { Icon(Icons.Default.Layers, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("1-Click Starter Templates") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Choose a pre-built starter stack to bootstrap a complete project with git and dependencies in 10 seconds:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = projectName,
                    onValueChange = { input ->
                        projectName = input.replace(' ', '-').replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                templates.forEach { tmpl ->
                    val isSelected = selectedTemplateId == tmpl.id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTemplateId = tmpl.id },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = if (isSelected) 4.dp else 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        tmpl.icon,
                                        null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(Modifier.weight(1f)) {
                                Text(
                                    tmpl.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    tmpl.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    tmpl.techStack.forEach { tag ->
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surface
                                        ) {
                                            Text(
                                                tag,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedTemplateId = tmpl.id }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onLaunchTemplate(projectName.trim(), selectedTemplateId) },
                enabled = projectName.isNotBlank() && !isCreating
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Bootstrapping…")
                } else {
                    Icon(Icons.Default.Bolt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Launch Template")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) { Text("Cancel") }
        }
    )
}
