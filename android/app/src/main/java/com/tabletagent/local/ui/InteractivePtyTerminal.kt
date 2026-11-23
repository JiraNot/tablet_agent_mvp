package com.tabletagent.local.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabletagent.local.LocalApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun parseAnsiToAnnotated(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var currentColor = Color(0xFFF8FAFC)
    val parts = text.split("\u001b[")
    if (parts.isNotEmpty()) {
        builder.append(parts[0])
    }
    for (i in 1 until parts.size) {
        val part = parts[i]
        val mIdx = part.indexOf('m')
        if (mIdx >= 0) {
            val codeStr = part.substring(0, mIdx)
            val rest = part.substring(mIdx + 1)
            val codes = codeStr.split(';').mapNotNull { it.toIntOrNull() }
            for (code in codes) {
                currentColor = when (code) {
                    0 -> Color(0xFFF8FAFC)
                    31, 91 -> Color(0xFFF87171) // red
                    32, 92 -> Color(0xFF4ADE80) // green
                    33, 93 -> Color(0xFFFBBF24) // yellow
                    34, 94 -> Color(0xFF60A5FA) // blue
                    35, 95 -> Color(0xFFC084FC) // purple
                    36, 96 -> Color(0xFF38BDF8) // cyan
                    37, 97 -> Color(0xFFFFFFFF) // white
                    else -> currentColor
                }
            }
            val start = builder.length
            builder.append(rest)
            builder.addStyle(SpanStyle(color = currentColor), start, builder.length)
        } else {
            // Strip other escape sequences like [?25h, [2J, [H, etc.
            val endIdx = part.indexOfFirst { it in 'A'..'Z' || it in 'a'..'z' }
            if (endIdx >= 0) {
                builder.append(part.substring(endIdx + 1))
            } else {
                builder.append(part)
            }
        }
    }
    return builder.toAnnotatedString()
}

@Composable
fun InteractivePtyTerminal(
    project: String,
    api: LocalApi,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(listOf("Initializing OmniDev PRoot Linux PTY Terminal...")) }
    var running by remember { mutableStateOf(false) }
    var inputBuffer by remember { mutableStateOf("") }
    var cursorSeq by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    // Start PTY session and start polling
    LaunchedEffect(project) {
        if (project.isBlank()) return@LaunchedEffect
        try {
            val startRes = api.terminalStart(project, cols = 100, rows = 30)
            running = startRes.optBoolean("running", false)
            lines = listOf("OmniDev PRoot Linux Terminal attached to project [$project]")
            cursorSeq = 0
            while (isActive) {
                try {
                    val poll = api.terminalPoll(project, cursorSeq)
                    val newSeq = poll.optInt("seq", cursorSeq)
                    val rawData = poll.optString("data", "")
                    running = poll.optBoolean("running", true)
                    if (newSeq > cursorSeq && rawData.isNotEmpty()) {
                        cursorSeq = newSeq
                        val cleanText = rawData.replace("\r\n", "\n").replace("\r", "\n")
                        val newLines = cleanText.split('\n')
                        lines = (lines + newLines).takeLast(600)
                        listState.scrollToItem(maxOf(0, lines.size - 1))
                    }
                } catch (_: Exception) {}
                delay(120)
            }
        } catch (e: Exception) {
            lines = listOf("Failed to start terminal: ${e.message}")
            running = false
        }
    }

    fun send(raw: String) {
        scope.launch {
            try {
                api.terminalInput(project, raw)
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
    ) {
        // Terminal Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF131A2A))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (running) Color(0xFF065F46) else Color(0xFF7F1D1D)
                ) {
                    Text(
                        text = if (running) "PTY LIVE" else "STOPPED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (running) Color(0xFF34D399) else Color(0xFFFCA5A5),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = "Linux /bin/sh ($project)",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(
                    onClick = { lines = emptyList() },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF1E293B), contentColor = Color(0xFFE2E8F0)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Clear", fontSize = 10.sp)
                }
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            api.terminalStop(project)
                            api.terminalStart(project, 100, 30)
                            cursorSeq = 0
                            lines = listOf("Terminal restarted.")
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF1E293B), contentColor = Color(0xFFE2E8F0)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Restart", modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Reset", fontSize = 10.sp)
                }
            }
        }

        // Terminal Screen (Scrollback Output)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(lines) { line ->
                    Text(
                        text = parseAnsiToAnnotated(line),
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // Quick Command Chips
        val quickCommands = listOf("git status", "ls -la", "npm test", "pytest", "pwd", "uname -a")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickCommands.forEach { cmd ->
                SuggestionChip(
                    onClick = { send("$cmd\n") },
                    label = { Text(cmd, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = Color(0xFF1E293B),
                        labelColor = Color(0xFF38BDF8)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier.height(24.dp)
                )
            }
        }

        // Tablet Extra Keys Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF131A2A))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TerminalKeyBtn("ESC", "\u001b", Color(0xFF334155), onSend = ::send)
            TerminalKeyBtn("TAB", "\t", Color(0xFF334155), onSend = ::send)
            TerminalKeyBtn("CTRL+C", "\u0003", Color(0xFF7F1D1D), onSend = ::send)
            TerminalKeyBtn("CTRL+D", "\u0004", Color(0xFF7F1D1D), onSend = ::send)
            TerminalKeyBtn("↑", "\u001b[A", onSend = ::send)
            TerminalKeyBtn("↓", "\u001b[B", onSend = ::send)
            TerminalKeyBtn("←", "\u001b[D", onSend = ::send)
            TerminalKeyBtn("→", "\u001b[C", onSend = ::send)
            TerminalKeyBtn("/", "/", onSend = ::send)
            TerminalKeyBtn("-", "-", onSend = ::send)
            TerminalKeyBtn("|", "|", onSend = ::send)
            TerminalKeyBtn("~", "~", onSend = ::send)
        }

        // Command Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("$", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            TextField(
                value = inputBuffer,
                onValueChange = { inputBuffer = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type Linux command...", fontSize = 12.sp, color = Color(0xFF64748B)) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1E293B),
                    unfocusedContainerColor = Color(0xFF1E293B),
                    focusedIndicatorColor = Color(0xFF38BDF8),
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (inputBuffer.isNotEmpty()) {
                        send(inputBuffer + "\n")
                        inputBuffer = ""
                    }
                })
            )
            IconButton(
                onClick = {
                    if (inputBuffer.isNotEmpty()) {
                        send(inputBuffer + "\n")
                        inputBuffer = ""
                    }
                },
                modifier = Modifier.size(36.dp).background(Color(0xFF2563EB), RoundedCornerShape(6.dp))
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun TerminalKeyBtn(
    label: String,
    payload: String,
    color: Color = Color(0xFF1E293B),
    onSend: (String) -> Unit
) {
    FilledTonalButton(
        onClick = { onSend(payload) },
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = color, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        modifier = Modifier.height(28.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}
