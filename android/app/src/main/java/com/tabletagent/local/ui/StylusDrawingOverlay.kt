package com.tabletagent.local.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

data class DrawingPath(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
    val isEraser: Boolean = false
)

@Composable
fun StylusDrawingOverlay(
    modifier: Modifier = Modifier,
    targetView: View?,
    onCancel: () -> Unit,
    onApplySketch: (base64Image: String, promptInstruction: String) -> Unit
) {
    var paths by remember { mutableStateOf(listOf<DrawingPath>()) }
    var currentPoints by remember { mutableStateOf(listOf<Offset>()) }
    var currentColor by remember { mutableStateOf(Color(0xFFEF4444)) } // default bright red
    var strokeWidth by remember { mutableFloatStateOf(8f) }
    var isEraser by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var capturedB64 by remember { mutableStateOf("") }
    var promptInstruction by remember { mutableStateOf("Please update the UI code according to the sketch annotations on this screenshot.") }

    val colors = listOf(
        Color(0xFFEF4444), // Red
        Color(0xFF3B82F6), // Blue
        Color(0xFF10B981), // Green
        Color(0xFFF59E0B), // Amber/Yellow
        Color(0xFF8B5CF6), // Purple
        Color(0xFFFFFFFF)  // White
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Transparent Drawing Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isEraser, currentColor, strokeWidth) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPoints = listOf(offset)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentPoints = currentPoints + change.position
                        },
                        onDragEnd = {
                            if (currentPoints.isNotEmpty()) {
                                paths = paths + DrawingPath(
                                    points = currentPoints,
                                    color = if (isEraser) Color.Transparent else currentColor,
                                    strokeWidth = strokeWidth,
                                    isEraser = isEraser
                                )
                                currentPoints = emptyList()
                            }
                        }
                    )
                }
        ) {
            // Draw completed paths
            for (p in paths) {
                if (p.points.size > 1 && !p.isEraser) {
                    val composePath = Path().apply {
                        moveTo(p.points.first().x, p.points.first().y)
                        for (pt in p.points.drop(1)) {
                            lineTo(pt.x, pt.y)
                        }
                    }
                    drawPath(
                        path = composePath,
                        color = p.color,
                        style = Stroke(
                            width = p.strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            // Draw current active path
            if (currentPoints.size > 1 && !isEraser) {
                val currentPath = Path().apply {
                    moveTo(currentPoints.first().x, currentPoints.first().y)
                    for (pt in currentPoints.drop(1)) {
                        lineTo(pt.x, pt.y)
                    }
                }
                drawPath(
                    path = currentPath,
                    color = currentColor,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // Floating Stylus Toolbar at the top
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                VerticalDivider(modifier = Modifier.height(24.dp))

                // Color picker
                colors.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(c, CircleShape)
                            .border(
                                width = if (currentColor == c && !isEraser) 3.dp else 1.dp,
                                color = if (currentColor == c && !isEraser) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                            .clickable {
                                currentColor = c
                                isEraser = false
                            }
                    )
                }

                VerticalDivider(modifier = Modifier.height(24.dp))

                // Eraser button
                IconButton(
                    onClick = { isEraser = !isEraser },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.AutoFixNormal,
                        "Eraser",
                        tint = if (isEraser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Clear all button
                IconButton(
                    onClick = {
                        paths = emptyList()
                        currentPoints = emptyList()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                VerticalDivider(modifier = Modifier.height(24.dp))

                // Apply Sketch button
                Button(
                    onClick = {
                        val b64 = captureCompositeBitmap(targetView, paths)
                        capturedB64 = b64
                        showReviewDialog = true
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Apply Sketch", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Review Dialog before dispatching prompt
        if (showReviewDialog) {
            AlertDialog(
                onDismissRequest = { showReviewDialog = false },
                icon = { Icon(Icons.Default.Draw, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Annotate to Code") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Your sketch annotation will be sent to the Multimodal Agent along with this prompt instruction:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = promptInstruction,
                            onValueChange = { promptInstruction = it },
                            label = { Text("Agent Instruction") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 5
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showReviewDialog = false
                            onApplySketch(capturedB64, promptInstruction)
                        }
                    ) {
                        Text("Send to AI Agent")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showReviewDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

/**
 * Composites the underlying WebView with the user's stylus drawings and exports as Base64 JPEG.
 */
fun captureCompositeBitmap(view: View?, paths: List<DrawingPath>): String {
    if (view == null || view.width <= 0 || view.height <= 0) return ""
    val width = view.width
    val height = view.height

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // 1. Draw webview view content
    view.draw(canvas)

    // 2. Draw overlay paths on top
    val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    for (p in paths) {
        if (p.points.size > 1 && !p.isEraser) {
            paint.color = p.color.toArgb()
            paint.strokeWidth = p.strokeWidth
            val nativePath = android.graphics.Path().apply {
                moveTo(p.points.first().x, p.points.first().y)
                for (pt in p.points.drop(1)) {
                    lineTo(pt.x, pt.y)
                }
            }
            canvas.drawPath(nativePath, paint)
        }
    }

    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
    val byteArray = stream.toByteArray()
    return Base64.encodeToString(byteArray, Base64.NO_WRAP)
}
