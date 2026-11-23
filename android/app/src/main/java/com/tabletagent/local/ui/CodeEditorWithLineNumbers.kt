package com.tabletagent.local.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern code editor with line number gutter and syntax highlighting.
 * Uses synchronized scrolling and monospace styling for precise line alignment.
 */
@Composable
fun CodeEditorWithLineNumbers(
    value: String,
    onValueChange: (String) -> Unit,
    filePath: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val lineCount = remember(value) { (value.count { it == '\n' } + 1).coerceAtLeast(1) }
    val visualTransform = remember(filePath) { SyntaxHighlightTransformation(filePath) }

    val editorBg = MaterialTheme.colorScheme.surface
    val gutterBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val gutterTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface
    )

    val gutterWidth = when {
        lineCount < 100 -> 38.dp
        lineCount < 1000 -> 48.dp
        else -> 58.dp
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(editorBg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScroll)
        ) {
            // Line Numbers Gutter
            Column(
                modifier = Modifier
                    .width(gutterWidth)
                    .background(gutterBg)
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.End
            ) {
                for (i in 1..lineCount) {
                    Text(
                        text = "$i",
                        style = textStyle.copy(
                            color = gutterTextColor,
                            textAlign = TextAlign.End
                        ),
                        maxLines = 1
                    )
                }
            }

            // Gutter Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )

            // Code Text Field with Horizontal Scroll
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScroll)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    textStyle = textStyle,
                    visualTransformation = visualTransform,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.defaultMinSize(minWidth = 800.dp)
                )
            }
        }
    }
}
