package com.tabletagent.local.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import org.json.JSONObject

data class InspectedElement(
    val tag: String,
    val id: String,
    val classes: String,
    val htmlSnippet: String,
    val computedText: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectElementBottomSheet(
    element: InspectedElement?,
    onDismiss: () -> Unit,
    onModifyWithAi: (instruction: String) -> Unit
) {
    if (element == null) return

    var promptText by remember(element) { mutableStateOf("") }
    val quickActions = listOf(
        "Make this button rounded with orange gradient" to "Change this element's color to an orange gradient with rounded-xl border radius",
        "Increase padding and text size" to "Increase padding and font size of this element to make it more prominent",
        "Add subtle shadow & hover animation" to "Add subtle drop shadow and a scale hover micro-interaction to this element",
        "Center align and add bottom margin" to "Center align this element and add 16px bottom margin"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Element Tag & ID/Class
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "<${element.tag.lowercase()}>",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.width(8.dp))

                if (element.classes.isNotBlank()) {
                    Text(
                        text = ".${element.classes.take(40)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, null)
                }
            }

            // HTML Snippet Preview
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "DOM ELEMENT SNIPPET",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = element.htmlSnippet.trim().take(300),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick suggestion chips
            Text(
                "Quick Actions",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickActions.forEach { (label, instruction) ->
                    SuggestionChip(
                        onClick = { promptText = instruction },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // Custom prompt instruction input
            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text("What would you like the AI to change?") },
                placeholder = { Text("e.g. Change this button to orange and add a shopping bag icon") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            // Submit Button
            Button(
                onClick = {
                    val fullInstruction = buildString {
                        append("Target Element: <${element.tag.lowercase()}>\n")
                        if (element.classes.isNotBlank()) append("Classes: ${element.classes}\n")
                        append("HTML:\n${element.htmlSnippet.trim()}\n\n")
                        append("Instruction: ${promptText.ifBlank { "Refine and style this element cleanly." }}")
                    }
                    onModifyWithAi(fullInstruction)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Modify Element with AI")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

object InspectorJsHelper {
    /**
     * JavaScript injected into the WebView to highlight elements on touch/click
     * and report their tag, classes, and HTML snippet back to Android.
     */
    val INJECT_INSPECTOR_SCRIPT = """
        (function() {
            if (window.__omniInspectorActive) return;
            window.__omniInspectorActive = true;

            var style = document.createElement('style');
            style.id = '__omni_inspector_css';
            style.innerHTML = `
                .__omni_inspect_highlight {
                    outline: 2px dashed #06b6d4 !important;
                    outline-offset: 2px !important;
                    background-color: rgba(6, 182, 212, 0.15) !important;
                    cursor: pointer !important;
                }
            `;
            document.head.appendChild(style);

            var lastEl = null;

            document.addEventListener('mouseover', function(e) {
                if (lastEl) lastEl.classList.remove('__omni_inspect_highlight');
                lastEl = e.target;
                if (lastEl) lastEl.classList.add('__omni_inspect_highlight');
            }, true);

            document.addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
                var el = e.target;
                if (!el) return;

                var snippet = el.outerHTML;
                if (snippet.length > 500) {
                    snippet = el.cloneNode(false).outerHTML;
                }

                var data = {
                    tag: el.tagName || '',
                    id: el.id || '',
                    classes: el.className ? el.className.toString().replace('__omni_inspect_highlight', '').trim() : '',
                    htmlSnippet: snippet,
                    computedText: (el.innerText || '').substring(0, 100)
                };

                console.log('__OMNI_INSPECT__:' + JSON.stringify(data));
            }, true);
        })();
    """.trimIndent()

    val REMOVE_INSPECTOR_SCRIPT = """
        (function() {
            window.__omniInspectorActive = false;
            var css = document.getElementById('__omni_inspector_css');
            if (css) css.remove();
            document.querySelectorAll('.__omni_inspect_highlight').forEach(function(el) {
                el.classList.remove('__omni_inspect_highlight');
            });
        })();
    """.trimIndent()
}
