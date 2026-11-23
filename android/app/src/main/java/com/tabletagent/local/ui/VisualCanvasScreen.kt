package com.tabletagent.local.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CanvasItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: String, // "navbar", "header", "card", "button", "input", "text", "badge"
    val label: String,
    val placeholder: String = "",
    val variant: String = "primary", // "primary", "secondary", "outline", "danger"
    val subText: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualCanvasScreen(
    project: String,
    onGenerateCode: (framework: String, styleNotes: String, schemaJson: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var items by remember {
        mutableStateOf(
            listOf(
                CanvasItem(type = "navbar", label = "OmniApp Studio", subText = "Home | Projects | Analytics"),
                CanvasItem(type = "header", label = "Welcome to Modern Workspace", subText = "Design and build on Android"),
                CanvasItem(type = "card", label = "Key Metrics", subText = "Revenue: $12,450 | Active Users: 890"),
                CanvasItem(type = "input", label = "Email Address", placeholder = "Enter your work email..."),
                CanvasItem(type = "button", label = "Get Started Now", variant = "primary")
            )
        )
    }

    var selectedItemId by remember { mutableStateOf<String?>(null) }
    var selectedFramework by rememberSaveable { mutableStateOf("React + Tailwind") }
    var stylePrompt by rememberSaveable { mutableStateOf("Modern glassmorphism dark theme with smooth gradients") }
    val frameworks = listOf("React + Tailwind", "Next.js 15", "Jetpack Compose", "HTML5 + CSS")

    fun addItem(type: String) {
        val newItem = when (type) {
            "navbar" -> CanvasItem(type = "navbar", label = "App Header", subText = "Dashboard | Settings")
            "header" -> CanvasItem(type = "header", label = "New Section Header", subText = "Subtitle description")
            "card" -> CanvasItem(type = "card", label = "Interactive Card", subText = "Card content and summary text")
            "input" -> CanvasItem(type = "input", label = "Input Field", placeholder = "Enter value...")
            "button" -> CanvasItem(type = "button", label = "Click Action", variant = "primary")
            "badge" -> CanvasItem(type = "badge", label = "FEATURED", variant = "secondary")
            else -> CanvasItem(type = "text", label = "Paragraph text content explaining feature details.")
        }
        items = items + newItem
        selectedItemId = newItem.id
    }

    fun moveItem(idx: Int, up: Boolean) {
        val targetIdx = if (up) idx - 1 else idx + 1
        if (targetIdx in items.indices) {
            val list = items.toMutableList()
            val temp = list[idx]
            list[idx] = list[targetIdx]
            list[targetIdx] = temp
            items = list
        }
    }

    fun removeItem(id: String) {
        items = items.filter { it.id != id }
        if (selectedItemId == id) selectedItemId = null
    }

    fun serializeSchema(): String {
        val root = JSONObject()
        root.put("project", project)
        root.put("framework", selectedFramework)
        root.put("stylePrompt", stylePrompt)
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("type", item.type)
            obj.put("label", item.label)
            obj.put("subText", item.subText)
            obj.put("placeholder", item.placeholder)
            obj.put("variant", item.variant)
            arr.put(obj)
        }
        root.put("components", arr)
        return root.toString(2)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(14.dp)
    ) {
        // Top Action Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF6366F1),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.DashboardCustomize, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Column {
                    Text("Visual Generative UI Canvas", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    Text("Touch Drag-and-Drop Layout Builder · Project [$project]", fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        val schema = serializeSchema()
                        onGenerateCode(selectedFramework, stylePrompt, schema)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White)
                ) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Generate Code with Agent", fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, null, tint = Color(0xFF94A3B8))
                }
            }
        }

        // Framework Selector & Style Prompt Bar
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF1E293B),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Target Code:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFE2E8F0))
                    frameworks.forEach { fw ->
                        FilterChip(
                            selected = selectedFramework == fw,
                            onClick = { selectedFramework = fw },
                            label = { Text(fw, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF6366F1),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
                OutlinedTextField(
                    value = stylePrompt,
                    onValueChange = { stylePrompt = it },
                    label = { Text("Style & Architecture Prompt", fontSize = 11.sp) },
                    placeholder = { Text("e.g. Modern glassmorphism with Tailwind CSS and dark mode support") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Main Layout: Palette Toolbar + Live Canvas Board
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Left Toolbar Palette
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1E293B),
                modifier = Modifier.width(170.dp).fillMaxHeight()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Component Palette", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF38BDF8))
                    HorizontalDivider(color = Color(0xFF334155))
                    listOf(
                        Pair("Navbar", "navbar"),
                        Pair("Heading", "header"),
                        Pair("Card Box", "card"),
                        Pair("Input Field", "input"),
                        Pair("Action Button", "button"),
                        Pair("Badge Pill", "badge"),
                        Pair("Text Block", "text")
                    ).forEach { (label, type) ->
                        FilledTonalButton(
                            onClick = { addItem(type) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF334155), contentColor = Color(0xFFF8FAFC))
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(label, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${items.size} components", fontSize = 10.sp, color = Color(0xFF64748B))
                }
            }

            // Center: Live Interactive Canvas
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF020617),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState())) {
                    Text("LIVE CANVAS PREVIEW", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF64748B))
                    Spacer(Modifier.height(10.dp))

                    items.forEachIndexed { idx, item ->
                        val isSelected = selectedItemId == item.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) Color(0xFF6366F1) else Color(0xFF1E293B)),
                                    RoundedCornerShape(8.dp)
                                )
                                .background(if (isSelected) Color(0xFF1E1B4B) else Color(0xFF0F172A))
                                .clickable { selectedItemId = item.id }
                                .padding(12.dp)
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF334155),
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    ) {
                                        Text(item.type.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                    }
                                    if (isSelected) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (idx > 0) {
                                                IconButton(onClick = { moveItem(idx, true) }, modifier = Modifier.size(24.dp)) {
                                                    Icon(Icons.Default.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                            if (idx < items.size - 1) {
                                                IconButton(onClick = { moveItem(idx, false) }, modifier = Modifier.size(24.dp)) {
                                                    Icon(Icons.Default.ArrowDownward, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                            IconButton(onClick = { removeItem(item.id) }, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                }

                                // Visual component representation
                                when (item.type) {
                                    "navbar" -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().background(Color(0xFF1E293B), RoundedCornerShape(6.dp)).padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(item.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                            Text(item.subText, fontSize = 11.sp, color = Color(0xFF94A3B8))
                                        }
                                    }
                                    "header" -> {
                                        Column {
                                            Text(item.label, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                                            if (item.subText.isNotBlank()) {
                                                Text(item.subText, fontSize = 12.sp, color = Color(0xFF94A3B8))
                                            }
                                        }
                                    }
                                    "card" -> {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF1E293B),
                                            border = BorderStroke(1.dp, Color(0xFF334155)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(Modifier.padding(12.dp)) {
                                                Text(item.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF38BDF8))
                                                Spacer(Modifier.height(4.dp))
                                                Text(item.subText, fontSize = 12.sp, color = Color(0xFFE2E8F0))
                                            }
                                        }
                                    }
                                    "input" -> {
                                        Column {
                                            Text(item.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCBD5E1))
                                            Spacer(Modifier.height(4.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color(0xFF0F172A),
                                                border = BorderStroke(1.dp, Color(0xFF334155)),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                            ) {
                                                Text(item.placeholder, fontSize = 12.sp, color = Color(0xFF64748B), modifier = Modifier.padding(10.dp))
                                            }
                                        }
                                    }
                                    "button" -> {
                                        Button(
                                            onClick = {},
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1), contentColor = Color.White),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        ) {
                                            Text(item.label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                    "badge" -> {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFF059669),
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        ) {
                                            Text(item.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                        }
                                    }
                                    else -> {
                                        Text(item.label, fontSize = 13.sp, color = Color(0xFFCBD5E1))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Right: Selected Component Inspector
            selectedItemId?.let { id ->
                val selected = items.firstOrNull { it.id == id }
                if (selected != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E293B),
                        modifier = Modifier.width(220.dp).fillMaxHeight()
                    ) {
                        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Properties Inspector", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF38BDF8))
                            HorizontalDivider(color = Color(0xFF334155))

                            OutlinedTextField(
                                value = selected.label,
                                onValueChange = { newVal ->
                                    items = items.map { if (it.id == id) it.copy(label = newVal) else it }
                                },
                                label = { Text("Label / Title", fontSize = 10.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall
                            )

                            OutlinedTextField(
                                value = selected.subText,
                                onValueChange = { newVal ->
                                    items = items.map { if (it.id == id) it.copy(subText = newVal) else it }
                                },
                                label = { Text("SubText / Content", fontSize = 10.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall
                            )

                            if (selected.type == "input") {
                                OutlinedTextField(
                                    value = selected.placeholder,
                                    onValueChange = { newVal ->
                                        items = items.map { if (it.id == id) it.copy(placeholder = newVal) else it }
                                    },
                                    label = { Text("Placeholder", fontSize = 10.sp) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
