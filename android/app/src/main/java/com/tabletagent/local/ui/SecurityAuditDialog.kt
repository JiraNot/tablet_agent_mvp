package com.tabletagent.local.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

data class AuditFinding(
    val severity: String,
    val title: String,
    val description: String,
    val file: String,
    val line: Int,
    val codeSnippet: String,
    val suggestedFix: String
)

@Composable
fun SecurityAuditDialog(
    project: String,
    auditResult: JSONObject?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onAutoFixWithAi: (instruction: String) -> Unit
) {
    val findings = remember(auditResult) {
        val list = mutableListOf<AuditFinding>()
        val arr = auditResult?.optJSONArray("findings") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                AuditFinding(
                    severity = obj.optString("severity", "WARNING"),
                    title = obj.optString("title", "Finding"),
                    description = obj.optString("description", ""),
                    file = obj.optString("file", ""),
                    line = obj.optInt("line", 1),
                    codeSnippet = obj.optString("code", ""),
                    suggestedFix = obj.optString("suggested_fix", "")
                )
            )
        }
        list
    }

    val isClean = auditResult?.optBoolean("clean", false) == true
    val critCount = auditResult?.optInt("critical_count", 0) ?: 0
    val warnCount = auditResult?.optInt("warning_count", 0) ?: 0
    val tipCount = auditResult?.optInt("tip_count", 0) ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (isClean) Icons.Default.CheckCircle else Icons.Default.Security,
                null,
                tint = if (isClean) Color(0xFF10B981) else if (critCount > 0) MaterialTheme.colorScheme.error else Color(0xFFF59E0B)
            )
        },
        title = { Text(if (isClean) "Security Audit: Clean" else "AI Pre-Commit Audit") },
        text = {
            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Scanning code diffs for secrets & anti-patterns…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (isClean) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "✅ No security liabilities detected!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your uncommitted changes contain no hardcoded secrets, tokens, or obvious hygiene issues. Safe to commit and push.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Summary badge bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (critCount > 0) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "$critCount Critical",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        if (warnCount > 0) {
                            Surface(color = Color(0xFFFEF3C7), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "$warnCount Warning",
                                    color = Color(0xFF92400E),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        if (tipCount > 0) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "$tipCount Tip",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Findings list
                    findings.forEach { item ->
                        val (bgColor, txtColor) = when (item.severity) {
                            "CRITICAL" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                            "WARNING" -> Color(0xFFFFFBEB) to Color(0xFF78350F)
                            else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        Surface(
                            color = bgColor,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = txtColor)
                                    Spacer(Modifier.weight(1f))
                                    Text("${item.file}:${item.line}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(item.description, style = MaterialTheme.typography.bodySmall, color = txtColor.copy(alpha = 0.85f))
                                if (item.codeSnippet.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Surface(color = Color.Black.copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp)) {
                                        Text(
                                            item.codeSnippet,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isClean && !loading && findings.isNotEmpty()) {
                Button(
                    onClick = {
                        val fixPrompt = buildString {
                            append("Security & Code Audit Findings in project '$project':\n")
                            findings.forEachIndexed { idx, f ->
                                append("${idx + 1}. [${f.severity}] ${f.title} in ${f.file}:${f.line}\n")
                                append("   Code: ${f.codeSnippet}\n")
                                append("   Fix: ${f.suggestedFix}\n")
                            }
                            append("\nPlease refactor these files to resolve all critical security and hygiene issues (move keys to .env and clean debug statements).")
                        }
                        onDismiss()
                        onAutoFixWithAi(fixPrompt)
                    }
                ) {
                    Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Auto-Fix with AI")
                }
            } else {
                Button(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (!isClean && !loading) {
                TextButton(onClick = onDismiss) { Text("Ignore & Close") }
            }
        }
    )
}
