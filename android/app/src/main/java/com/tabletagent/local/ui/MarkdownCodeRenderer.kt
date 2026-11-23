package com.tabletagent.local.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MarkdownNode {
    data class Paragraph(val text: String) : MarkdownNode
    data class Heading(val level: Int, val text: String) : MarkdownNode
    data class Alert(val type: String, val text: String) : MarkdownNode
    data class ChecklistItem(val checked: Boolean, val text: String) : MarkdownNode
    data class ListItem(val bullet: String, val text: String) : MarkdownNode
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownNode
    data class CodeBlock(val language: String, val filename: String, val code: String) : MarkdownNode
}

fun parseMarkdownNodes(markdown: String): List<MarkdownNode> {
    val nodes = mutableListOf<MarkdownNode>()
    val lines = markdown.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            val info = trimmed.removePrefix("```").trim()
            val parts = info.split(Regex("\\s+"))
            val lang = parts.firstOrNull() ?: ""
            var detectedFile = if (parts.size > 1) parts[1] else ""
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            val rawCode = codeLines.joinToString("\n")
            if (detectedFile.isBlank()) {
                val firstLine = codeLines.firstOrNull()?.trim() ?: ""
                val fileComment = Regex("""^(?://|#|<!--|/\*)\s*([a-zA-Z0-9_./\\-]+\.[a-zA-Z0-9]+)""")
                val m = fileComment.find(firstLine)
                if (m != null) {
                    detectedFile = m.groupValues[1]
                }
            }
            nodes.add(MarkdownNode.CodeBlock(lang, detectedFile, rawCode))
            i++
            continue
        }
        if (trimmed.startsWith("> [!")) {
            val alertHeader = Regex("""^>\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]""", RegexOption.IGNORE_CASE).find(trimmed)
            if (alertHeader != null) {
                val alertType = alertHeader.groupValues[1].uppercase()
                val bodyLines = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    bodyLines.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                nodes.add(MarkdownNode.Alert(alertType, bodyLines.joinToString("\n")))
                continue
            }
        }
        if (trimmed.startsWith("#")) {
            val m = Regex("""^(#{1,6})\s+(.*)$""").find(trimmed)
            if (m != null) {
                nodes.add(MarkdownNode.Heading(m.groupValues[1].length, m.groupValues[2]))
                i++
                continue
            }
        }
        if (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ")) {
            val checked = trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ")
            nodes.add(MarkdownNode.ChecklistItem(checked, trimmed.substring(6)))
            i++
            continue
        }
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            nodes.add(MarkdownNode.ListItem("•", trimmed.substring(2)))
            i++
            continue
        }
        val numberedMatch = Regex("""^(\d+\.)\s+(.*)$""").find(trimmed)
        if (numberedMatch != null) {
            nodes.add(MarkdownNode.ListItem(numberedMatch.groupValues[1], numberedMatch.groupValues[2]))
            i++
            continue
        }
        if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
            val tableLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                tableLines.add(lines[i].trim())
                i++
            }
            if (tableLines.size >= 2) {
                val headers = tableLines[0].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                val rows = mutableListOf<List<String>>()
                for (tIdx in 2 until tableLines.size) {
                    val cols = tableLines[tIdx].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    rows.add(cols)
                }
                nodes.add(MarkdownNode.Table(headers, rows))
                continue
            }
        }
        if (trimmed.isNotEmpty()) {
            nodes.add(MarkdownNode.Paragraph(trimmed))
        }
        i++
    }
    return nodes
}

fun highlightSyntax(code: String, language: String): AnnotatedString {
    val lang = language.lowercase()
    val isDark = true
    val keywordColor = Color(0xFFC792EA) // purple
    val stringColor = Color(0xFFC3E88D) // soft green
    val commentColor = Color(0xFF546E7A) // muted slate
    val numberColor = Color(0xFFF78C6C) // orange
    val functionColor = Color(0xFF82AAFF) // soft blue
    val defaultColor = Color(0xFFEEFFFF)

    val builder = AnnotatedString.Builder(code)
    builder.addStyle(SpanStyle(color = defaultColor), 0, code.length)

    // Comments
    val commentRegex = if (lang == "html" || lang == "xml") {
        Regex("""<!--[\s\S]*?-->""")
    } else {
        Regex("""(?://.*|#.*|/\*[\s\S]*?\*/)""")
    }
    for (m in commentRegex.findAll(code)) {
        builder.addStyle(SpanStyle(color = commentColor), m.range.first, m.range.last + 1)
    }

    // Strings
    val stringRegex = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`""")
    for (m in stringRegex.findAll(code)) {
        builder.addStyle(SpanStyle(color = stringColor), m.range.first, m.range.last + 1)
    }

    // Numbers
    val numberRegex = Regex("""\b\d+(?:\.\d+)?\b""")
    for (m in numberRegex.findAll(code)) {
        builder.addStyle(SpanStyle(color = numberColor), m.range.first, m.range.last + 1)
    }

    // Keywords
    val keywords = when (lang) {
        "python", "py" -> setOf("def", "class", "import", "from", "return", "if", "elif", "else", "for", "while", "try", "except", "with", "as", "async", "await", "lambda", "yield", "True", "False", "None")
        "kotlin", "kt" -> setOf("fun", "val", "var", "class", "interface", "object", "return", "if", "else", "for", "while", "when", "import", "package", "override", "suspend", "data", "sealed")
        "diff" -> setOf()
        else -> setOf("function", "const", "let", "var", "return", "if", "else", "for", "while", "import", "export", "from", "default", "class", "async", "await", "try", "catch", "new", "type", "interface")
    }
    if (keywords.isNotEmpty()) {
        val kwRegex = Regex("""\b(${keywords.joinToString("|")})\b""")
        for (m in kwRegex.findAll(code)) {
            builder.addStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
        }
    }

    // Functions
    val fnRegex = Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\s*(?=\()""")
    for (m in fnRegex.findAll(code)) {
        builder.addStyle(SpanStyle(color = functionColor), m.range.first, m.range.last + 1)
    }

    // Diff markers
    if (lang == "diff" || lang == "patch") {
        for (line in code.lines()) {
            if (line.startsWith("+")) {
                val idx = code.indexOf(line)
                if (idx >= 0) builder.addStyle(SpanStyle(color = Color(0xFF4ADE80)), idx, idx + line.length)
            } else if (line.startsWith("-")) {
                val idx = code.indexOf(line)
                if (idx >= 0) builder.addStyle(SpanStyle(color = Color(0xFFF87171)), idx, idx + line.length)
            }
        }
    }
    return builder.toAnnotatedString()
}

@Composable
fun MarkdownAssistantContent(
    markdown: String,
    modifier: Modifier = Modifier,
    onApplyCode: ((filename: String, code: String) -> Unit)? = null
) {
    val nodes = remember(markdown) { parseMarkdownNodes(markdown) }
    val context = LocalContext.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nodes.forEach { node ->
            when (node) {
                is MarkdownNode.Heading -> {
                    val fontSize = when (node.level) {
                        1 -> 18.sp
                        2 -> 16.sp
                        else -> 14.sp
                    }
                    Text(
                        text = node.text,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                is MarkdownNode.Alert -> {
                    val (badgeColor, bgColor, icon) = when (node.type) {
                        "TIP" -> Triple(Color(0xFF059669), Color(0xFFECFDF5), Icons.Default.Lightbulb)
                        "WARNING" -> Triple(Color(0xFFD97706), Color(0xFFFFFBEB), Icons.Default.Warning)
                        "CAUTION" -> Triple(Color(0xFFDC2626), Color(0xFFFEF2F2), Icons.Default.Error)
                        "IMPORTANT" -> Triple(Color(0xFF7C3AED), Color(0xFFF5F3FF), Icons.Default.PriorityHigh)
                        else -> Triple(Color(0xFF2563EB), Color(0xFFEFF6FF), Icons.Default.Info)
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = bgColor,
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(icon, contentDescription = node.type, tint = badgeColor, modifier = Modifier.size(18.dp))
                            Column {
                                Text(node.type, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = badgeColor)
                                Text(node.text, fontSize = 12.sp, color = Color(0xFF1E293B))
                            }
                        }
                    }
                }
                is MarkdownNode.ChecklistItem -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            if (node.checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = if (node.checked) "Checked" else "Unchecked",
                            tint = if (node.checked) Color(0xFF10B981) else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(node.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                is MarkdownNode.ListItem -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 6.dp)) {
                        Text(node.bullet, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        Text(node.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                is MarkdownNode.Table -> {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scrollState)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    ) {
                        Column {
                            // Header
                            Row(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp)) {
                                node.headers.forEach { h ->
                                    Text(
                                        text = h,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.width(130.dp).padding(horizontal = 4.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            // Rows
                            node.rows.forEach { r ->
                                Row(modifier = Modifier.padding(6.dp)) {
                                    r.forEach { cell ->
                                        Text(
                                            text = cell,
                                            fontSize = 11.sp,
                                            modifier = Modifier.width(130.dp).padding(horizontal = 4.dp),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
                is MarkdownNode.CodeBlock -> {
                    val annotatedCode = remember(node.code, node.language) { highlightSyntax(node.code, node.language) }
                    val scrollState = rememberScrollState()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F172A),
                        border = BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column {
                            // Code Action Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1E293B))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF334155),
                                        modifier = Modifier.padding(end = 4.dp)
                                    ) {
                                        Text(
                                            text = node.language.ifBlank { "text" }.uppercase(),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF38BDF8),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                    if (node.filename.isNotBlank()) {
                                        Text(
                                            text = node.filename,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFFE2E8F0)
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // Copy Button
                                    FilledTonalButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("code", node.code))
                                            Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF334155), contentColor = Color(0xFFF8FAFC)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(13.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copy", fontSize = 11.sp)
                                    }
                                    // Apply to File Button
                                    if (onApplyCode != null) {
                                        Button(
                                            onClick = { onApplyCode(node.filename, node.code) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB), contentColor = Color.White),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Icon(Icons.Default.Save, contentDescription = "Apply", modifier = Modifier.size(13.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Apply", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            // Code Body
                            Box(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState).padding(10.dp)) {
                                Text(
                                    text = annotatedCode,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
                is MarkdownNode.Paragraph -> {
                    Text(
                        text = node.text,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ApplyToFileConfirmDialog(
    initialFilename: String,
    code: String,
    onDismiss: () -> Unit,
    onConfirm: (targetFile: String) -> Unit
) {
    var targetFile by remember { mutableStateOf(initialFilename.ifBlank { "src/App.tsx" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Save, contentDescription = null, tint = Color(0xFF2563EB))
                Text("Apply Code to Project File", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Target file path in workspace:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = targetFile,
                    onValueChange = { targetFile = it },
                    placeholder = { Text("e.g. src/App.tsx") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Code snippet (${code.lines().size} lines, ${code.length} chars)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            code.take(240) + if (code.length > 240) "..." else "",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 4,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                    Text("A Git checkpoint will be created before saving.", fontSize = 11.sp, color = Color(0xFF059669))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (targetFile.isNotBlank()) {
                        onConfirm(targetFile.trim())
                    }
                },
                enabled = targetFile.isNotBlank()
            ) {
                Text("Confirm & Write")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
